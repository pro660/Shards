package com.example.shards.meta;

import com.example.shards.sharding.BucketHasher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class OperationLocks {

    // 백업 도구도 같은 메타 DB에서 (715000, 0)을 사용합니다.
    private static final int MAINTENANCE = 715000;
    private static final int BUCKET_WRITE = 715001;
    private static final int BUCKET_READ = 715002;

    private final JdbcTemplate jdbcTemplate;

    public OperationLocks(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void allowChanges() {
        requireShared(MAINTENANCE, 0);
    }

    public void allowWrite(int bucketId) {
        allowChanges();
        requireShared(BUCKET_WRITE, bucketId);
    }

    public void allowRead(int bucketId) {
        requireShared(BUCKET_READ, bucketId);
    }

    public void allowReadAll() {
        requireTransaction();
        Boolean acquired = jdbcTemplate.queryForObject("""
                SELECT bool_and(pg_try_advisory_xact_lock_shared(?, bucket_id))
                FROM generate_series(0, ?) AS buckets(bucket_id)
                """, Boolean.class, BUCKET_READ, BucketHasher.BUCKET_COUNT - 1);
        checkAcquired(acquired);
    }

    public void drainWrites(int bucketId) {
        requireTransaction();
        checkAcquired(jdbcTemplate.queryForObject(
                "SELECT pg_try_advisory_xact_lock(?, ?)", Boolean.class, BUCKET_WRITE, bucketId
        ));
    }

    public void drainReads(int bucketId) {
        requireTransaction();
        checkAcquired(jdbcTemplate.queryForObject(
                "SELECT pg_try_advisory_xact_lock(?, ?)", Boolean.class, BUCKET_READ, bucketId
        ));
    }

    private void requireShared(int group, int key) {
        requireTransaction();
        checkAcquired(jdbcTemplate.queryForObject(
                "SELECT pg_try_advisory_xact_lock_shared(?, ?)", Boolean.class, group, key
        ));
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("작업 잠금에는 메타 DB 트랜잭션이 필요합니다.");
        }
    }

    private static void checkAcquired(Boolean acquired) {
        if (!Boolean.TRUE.equals(acquired)) {
            throw new OperationBusyException("다른 작업이 진행 중입니다. 잠시 뒤 다시 시도해 주세요.");
        }
    }
}
