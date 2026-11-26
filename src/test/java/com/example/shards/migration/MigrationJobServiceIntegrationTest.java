package com.example.shards.migration;

import com.example.shards.meta.MigrationJob;
import com.example.shards.meta.MigrationJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class MigrationJobServiceIntegrationTest {

    private static final int FIRST_BUCKET = 5;
    private static final int SECOND_BUCKET = 10;
    private int sourceShard;
    private int targetShard;

    @Autowired
    private MigrationJobService service;

    @Autowired
    private MigrationJobRepository migrationJobRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void prepareActiveShards() {
        assertThat(migrationJobRepository.findUnfinished())
                .as("run this test on a development database without an unfinished migration")
                .isEmpty();
        // 테스트 트랜잭션이 끝나면 준비 과정에서 바꾼 샤드 상태도 돌아갑니다.
        jdbcTemplate.update("UPDATE public.shards SET status = 'ACTIVE' WHERE shard_id IN (1, 2, 3)");
        sourceShard = jdbcTemplate.queryForObject(
                "SELECT shard_id FROM public.bucket_mapping WHERE bucket_id = ?", Integer.class, FIRST_BUCKET);
        // 두 버킷 모두 등록 가능한 목적지를 실제 매핑에서 찾습니다.
        targetShard = jdbcTemplate.queryForObject("""
                SELECT shard_id FROM public.shards WHERE shard_id NOT IN (
                    SELECT shard_id FROM public.bucket_mapping WHERE bucket_id IN (?, ?)
                ) ORDER BY shard_id LIMIT 1
                """, Integer.class, FIRST_BUCKET, SECOND_BUCKET);
    }

    @Test
    void registersPendingJobWithCurrentMappingVersion() {
        long newVersion = jdbcTemplate.queryForObject("""
                UPDATE public.bucket_mapping
                SET version = version + 1
                WHERE bucket_id = ?
                RETURNING version
                """, Long.class, FIRST_BUCKET);

        MigrationJob job = service.register(FIRST_BUCKET, targetShard);

        assertThat(job.phase()).isEqualTo("PENDING");
        assertThat(job.sourceShardId()).isEqualTo(sourceShard);
        assertThat(job.targetShardId()).isEqualTo(targetShard);
        assertThat(job.sourceMappingVersion()).isEqualTo(newVersion);
        assertThat(migrationJobRepository.findUnfinished()).contains(job);
    }

    @Test
    void rejectsInvalidNumbers() {
        assertThatThrownBy(() -> service.register(16, targetShard))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("버킷 번호");
        assertThatThrownBy(() -> service.register(FIRST_BUCKET, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("도착 샤드 번호");
    }

    @Test
    void rejectsCurrentOwnerAsDestination() {
        assertThatThrownBy(() -> service.register(FIRST_BUCKET, sourceShard))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("서로 달라야");
    }

    @Test
    void rejectsUnknownDestination() {
        assertThatThrownBy(() -> service.register(FIRST_BUCKET, 999))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("도착 샤드를 찾을 수 없습니다");
    }

    @Test
    void rejectsMigratingBucket() {
        jdbcTemplate.update(
                "UPDATE public.bucket_mapping SET state = 'MIGRATING' WHERE bucket_id = ?", FIRST_BUCKET
        );

        assertThatThrownBy(() -> service.register(FIRST_BUCKET, targetShard))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NORMAL");
    }

    @ParameterizedTest
    @CsvSource({"true, 출발", "false, 도착"})
    void rejectsInactiveShard(boolean source, String shardRole) {
        int shardId = source ? sourceShard : targetShard;
        jdbcTemplate.update("UPDATE public.shards SET status = 'INACTIVE' WHERE shard_id = ?", shardId);

        assertThatThrownBy(() -> service.register(FIRST_BUCKET, targetShard))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(shardRole + " 샤드가 ACTIVE");
    }

    @Test
    void rejectsAnotherBucketWhileJobIsUnfinished() {
        MigrationJob firstJob = service.register(FIRST_BUCKET, targetShard);

        assertThatThrownBy(() -> service.register(SECOND_BUCKET, targetShard))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("이미 미완료 재배치 작업");

        assertThat(migrationJobRepository.findUnfinished()).contains(firstJob);
    }
}
