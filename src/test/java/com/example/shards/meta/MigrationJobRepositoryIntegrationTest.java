package com.example.shards.meta;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class MigrationJobRepositoryIntegrationTest {

    @Autowired
    private MigrationJobRepository migrationJobRepository;

    @Autowired
    private BucketMappingRepository bucketMappingRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private BucketMapping mapping;
    private int targetShardId;
    private MigrationJob testJob;

    @BeforeEach
    void insertPendingJobForShardC() {
        assertThat(migrationJobRepository.findUnfinished())
                .as("run this test on a development database without an unfinished migration")
                .isEmpty();
        mapping = bucketMappingRepository.findByBucketId(5);
        targetShardId = jdbcTemplate.queryForObject(
                "SELECT shard_id FROM public.shards WHERE connection_key = 'shardC'", Integer.class
        );
        testJob = migrationJobRepository.insert(
                mapping.bucketId(), mapping.shardId(), targetShardId, mapping.version()
        );
    }

    @Test
    void readsInsertedPendingJob() {
        assertThat(testJob.jobId()).isPositive();
        assertThat(testJob.bucketId()).isEqualTo(mapping.bucketId());
        assertThat(testJob.sourceShardId()).isEqualTo(mapping.shardId());
        assertThat(testJob.targetShardId()).isEqualTo(targetShardId);
        assertThat(testJob.sourceMappingVersion()).isEqualTo(mapping.version());
        assertThat(testJob.phase()).isEqualTo("PENDING");
        assertThat(testJob.copiedOrderCount()).isZero();
        assertThat(testJob.lastCopiedOrderId()).isNull();
        assertThat(testJob.verifiedAt()).isNull();
        assertThat(testJob.lastError()).isNull();
        assertThat(testJob.createdAt()).isNotNull();
        assertThat(testJob.updatedAt()).isEqualTo(testJob.createdAt());
        assertThat(testJob.finishedAt()).isNull();

        assertThat(migrationJobRepository.findById(testJob.jobId())).contains(testJob);
        assertThat(migrationJobRepository.findUnfinished()).contains(testJob);
    }

    @Test
    void findsCancelledJobOnlyById() {
        jdbcTemplate.update("""
                UPDATE public.migration_jobs
                SET phase = 'CANCELLED', finished_at = CURRENT_TIMESTAMP
                WHERE job_id = ?
                """, testJob.jobId());

        MigrationJob cancelledJob = migrationJobRepository.findById(testJob.jobId()).orElseThrow();
        assertThat(cancelledJob.phase()).isEqualTo("CANCELLED");
        assertThat(cancelledJob.finishedAt()).isNotNull();
        assertThat(migrationJobRepository.findUnfinished()).isEmpty();
    }

    @AfterTransaction
    void verifiesTestJobWasRolledBack() {
        if (testJob != null) {
            assertThat(migrationJobRepository.findById(testJob.jobId())).isEmpty();
        }
    }
}
