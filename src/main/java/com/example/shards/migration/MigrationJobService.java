package com.example.shards.migration;

import com.example.shards.meta.BucketMapping;
import com.example.shards.meta.BucketMappingRepository;
import com.example.shards.meta.MigrationJob;
import com.example.shards.meta.MigrationJobRepository;
import com.example.shards.meta.OperationLocks;
import com.example.shards.meta.ShardRepository;
import com.example.shards.sharding.BucketHasher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MigrationJobService {

    private final BucketMappingRepository bucketMappingRepository;
    private final ShardRepository shardRepository;
    private final MigrationJobRepository migrationJobRepository;
    private final OperationLocks locks;

    public MigrationJobService(
            BucketMappingRepository bucketMappingRepository,
            ShardRepository shardRepository,
            MigrationJobRepository migrationJobRepository,
            OperationLocks locks
    ) {
        this.bucketMappingRepository = bucketMappingRepository;
        this.shardRepository = shardRepository;
        this.migrationJobRepository = migrationJobRepository;
        this.locks = locks;
    }

    @Transactional
    public MigrationJob register(int bucketId, int targetShardId) {
        if (bucketId < 0 || bucketId >= BucketHasher.BUCKET_COUNT) {
            throw new IllegalArgumentException("버킷 번호는 0부터 " + (BucketHasher.BUCKET_COUNT - 1) + "까지입니다.");
        }
        if (targetShardId < 1 || targetShardId > Short.MAX_VALUE) {
            throw new IllegalArgumentException("도착 샤드 번호는 1부터 " + Short.MAX_VALUE + "까지입니다.");
        }

        locks.allowChanges();
        // 출발 샤드와 버전은 같은 트랜잭션에서 현재 매핑을 읽어 결정합니다.
        BucketMapping mapping = bucketMappingRepository.findByBucketIdForShare(bucketId)
                .orElseThrow(() -> new IllegalArgumentException("버킷의 매핑을 찾을 수 없습니다."));
        if (mapping.shardId() == targetShardId) {
            throw new IllegalArgumentException("출발 샤드와 도착 샤드는 서로 달라야 합니다.");
        }
        if (!"NORMAL".equals(mapping.state())) {
            throw new IllegalStateException("NORMAL 상태의 버킷만 재배치를 등록할 수 있습니다.");
        }
        if (!"ACTIVE".equals(mapping.shardStatus())) {
            throw new IllegalStateException("출발 샤드가 ACTIVE 상태여야 합니다.");
        }

        String targetStatus = shardRepository.findStatusByIdForShare(targetShardId)
                .orElseThrow(() -> new IllegalArgumentException("도착 샤드를 찾을 수 없습니다."));
        if (!"ACTIVE".equals(targetStatus)) {
            throw new IllegalStateException("도착 샤드가 ACTIVE 상태여야 합니다.");
        }

        return migrationJobRepository.tryInsert(bucketId, mapping.shardId(), targetShardId, mapping.version())
                .orElseThrow(() -> new IllegalStateException("이미 미완료 재배치 작업이 있습니다."));
    }
}
