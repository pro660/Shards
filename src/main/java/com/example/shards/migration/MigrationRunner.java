package com.example.shards.migration;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.example.shards.meta.BucketMapping;
import com.example.shards.meta.BucketMappingRepository;
import com.example.shards.meta.MigrationJob;
import com.example.shards.meta.MigrationJobRepository;
import com.example.shards.meta.OperationLocks;
import com.example.shards.meta.ShardRepository;
import com.example.shards.order.BucketOrderRepository;
import com.example.shards.order.Order;
import com.example.shards.sharding.ShardConnectionRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MigrationRunner {

    private static final int BATCH_SIZE = 100;
    private final MigrationJobRepository jobs;
    private final BucketMappingRepository mappings;
    private final ShardRepository shards;
    private final ShardConnectionRegistry connections;
    private final BucketOrderRepository orders;
    private final OperationLocks locks;

    public MigrationRunner(
            MigrationJobRepository jobs, BucketMappingRepository mappings, ShardRepository shards,
            ShardConnectionRegistry connections, BucketOrderRepository orders, OperationLocks locks) {
        this.jobs = jobs;
        this.mappings = mappings;
        this.shards = shards;
        this.connections = connections;
        this.orders = orders;
        this.locks = locks;
    }

    @Transactional
    public MigrationJob advance(long jobId) {
        locks.allowChanges();
        MigrationJob job = jobs.findByIdForUpdate(jobId).orElseThrow(MigrationJobNotFoundException::new);
        if (job.finishedAt() != null) {
            return job;
        }

        BucketMapping mapping = mappings.findByBucketIdForShare(job.bucketId())
                .orElseThrow(() -> new IllegalStateException("버킷 매핑을 찾을 수 없습니다."));
        if ("CLEANING".equals(job.phase())) {
            checkMapping(mapping, job.targetShardId(), Math.addExact(job.sourceMappingVersion(), 1), "NORMAL");
            orders.readBatch(activeShard(job.targetShardId()), job.bucketId(), null, 1);
            cleanSource(job);
        } else {
            String expectedState = "PENDING".equals(job.phase()) ? "NORMAL" : "MIGRATING";
            checkMapping(mapping, job.sourceShardId(), job.sourceMappingVersion(), expectedState);
            JdbcTemplate target = activeShard(job.targetShardId());
            switch (job.phase()) {
                case "PENDING" -> beginCopy(
                        job, activeShard(job.sourceShardId()), target); // 1번 수정 -> 진욱//
                case "COPYING" -> copyNextBatch(job, activeShard(job.sourceShardId()), target);
                case "VERIFYING" -> {
                    verifyCopies(job.bucketId(), activeShard(job.sourceShardId()), target);
                    jobs.markVerified(job);
                }
                case "SWITCHING" -> switchOwner(job, target);
                default -> throw new IllegalStateException("재배치 작업 단계가 올바르지 않습니다.");
            }
        }
        return jobs.findById(jobId).orElseThrow(MigrationJobNotFoundException::new);
    }

    @Transactional
    public MigrationJob cancelPending(long jobId) {
        locks.allowChanges();
        MigrationJob job = jobs.findByIdForUpdate(jobId).orElseThrow(MigrationJobNotFoundException::new);
        if ("CANCELLED".equals(job.phase())) {
            return job;
        }
        if (!"PENDING".equals(job.phase())) {
            throw new IllegalStateException("복사를 시작하기 전의 작업만 취소할 수 있습니다.");
        }
        jobs.cancelPending(job);
        return jobs.findById(jobId).orElseThrow(MigrationJobNotFoundException::new);
    }

    // 2번 수정 -> 진욱//
    private void beginCopy(
            MigrationJob job, JdbcTemplate source, JdbcTemplate target) {

        locks.drainWrites(job.bucketId());

        // 출발 및 도착 샤드의 주문 테이블 접근 가능 여부를 확인합니다.
        orders.readBatch(source, job.bucketId(), null, 1);
        orders.readBatch(target, job.bucketId(), null, 1);

        requireChanged(mappings.beginMigration(job));
        jobs.advance(job, "COPYING");
    }

    private void copyNextBatch(MigrationJob job, JdbcTemplate source, JdbcTemplate target) {
        List<Order> batch = orders.readBatch(source, job.bucketId(), job.lastCopiedOrderId(), BATCH_SIZE);
        // 3번 수정 -> 진욱//
        if (batch.isEmpty()) {
            orders.readBatch(target, job.bucketId(), null, 1);
            jobs.advance(job, "VERIFYING");
            return;
        }

        TransactionTemplate targetTransaction = new TransactionTemplate(
                new DataSourceTransactionManager(Objects.requireNonNull(target.getDataSource())));
        targetTransaction.executeWithoutResult(status -> orders.copyBatch(target, batch));
        // 목적지 커밋이 끝난 뒤에만 메타 DB의 체크포인트를 갱신합니다.
        jobs.saveCheckpoint(job, batch.getLast().orderId(), batch.size());
    }

    private void switchOwner(MigrationJob job, JdbcTemplate target) {
        if (job.verifiedAt() == null) {
            throw new IllegalStateException("복사 검증이 끝나야 소유권을 바꿀 수 있습니다.");
        }
        verifyCopies(job.bucketId(), activeShard(job.sourceShardId()), target);
        locks.drainReads(job.bucketId());
        requireChanged(mappings.switchOwner(job));
        jobs.advance(job, "CLEANING");
    }

    private void cleanSource(MigrationJob job) {
        JdbcTemplate source = activeShard(job.sourceShardId());
        int removed = orders.deleteBatch(source, job.bucketId(), BATCH_SIZE);
        // 정리 직후 연결이 끊겨도 다음 호출에서 남은 행부터 정리할 수 있습니다.
        if (removed == 0) {
            jobs.markCompleted(job);
        } else {
            jobs.touchCleaning(job);
        }
    }

    private void verifyCopies(int bucketId, JdbcTemplate source, JdbcTemplate target) {
        UUID after = null;
        while (true) {
            List<Order> expected = orders.readBatch(source, bucketId, after, BATCH_SIZE);
            List<Order> actual = orders.readBatch(target, bucketId, after, BATCH_SIZE);
            if (!expected.equals(actual)) {
                throw new IllegalStateException("출발·도착 샤드의 주문 ID 또는 내용이 일치하지 않습니다.");
            }
            if (expected.isEmpty()) {
                return;
            }
            after = expected.getLast().orderId();
        }
    }

    private JdbcTemplate activeShard(int shardId) {
        String key = shards.findActiveConnectionKeyForShare(shardId)
                .orElseThrow(() -> new IllegalStateException("재배치에 필요한 샤드가 ACTIVE 상태여야 합니다."));
        return connections.getJdbcTemplate(key);
    }

    private static void checkMapping(BucketMapping mapping, int owner, long version, String state) {
        requireChanged(mapping.shardId() == owner && mapping.version() == version && state.equals(mapping.state()));
    }

    private static void requireChanged(boolean matches) {
        if (!matches) {
            throw new IllegalStateException("현재 버킷 소유권 또는 버전이 작업 기록과 일치하지 않습니다.");
        }
    }
}
