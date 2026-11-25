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

    private static final int BUCKET_ON_A = 5;
    private static final int BUCKET_ON_B = 10;
    private static final int SHARD_A = 1;
    private static final int SHARD_C = 3;

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
    }

    @Test
    void registersPendingJobWithCurrentMappingVersion() {
        long newVersion = jdbcTemplate.queryForObject("""
                UPDATE public.bucket_mapping
                SET version = version + 1
                WHERE bucket_id = ?
                RETURNING version
                """, Long.class, BUCKET_ON_A);

        MigrationJob job = service.register(BUCKET_ON_A, SHARD_C);

        assertThat(job.phase()).isEqualTo("PENDING");
        assertThat(job.sourceShardId()).isEqualTo(SHARD_A);
        assertThat(job.targetShardId()).isEqualTo(SHARD_C);
        assertThat(job.sourceMappingVersion()).isEqualTo(newVersion);
        assertThat(migrationJobRepository.findUnfinished()).contains(job);
    }

    @Test
    void rejectsInvalidNumbers() {
        assertThatThrownBy(() -> service.register(16, SHARD_C))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("버킷 번호");
        assertThatThrownBy(() -> service.register(BUCKET_ON_A, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("도착 샤드 번호");
    }

    @Test
    void rejectsCurrentOwnerAsDestination() {
        assertThatThrownBy(() -> service.register(BUCKET_ON_A, SHARD_A))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("서로 달라야");
    }

    @Test
    void rejectsUnknownDestination() {
        assertThatThrownBy(() -> service.register(BUCKET_ON_A, 999))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("도착 샤드를 찾을 수 없습니다");
    }

    @Test
    void rejectsMigratingBucket() {
        jdbcTemplate.update(
                "UPDATE public.bucket_mapping SET state = 'MIGRATING' WHERE bucket_id = ?", BUCKET_ON_A
        );

        assertThatThrownBy(() -> service.register(BUCKET_ON_A, SHARD_C))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NORMAL");
    }

    @ParameterizedTest
    @CsvSource({"1, 출발", "3, 도착"})
    void rejectsInactiveShard(int shardId, String shardRole) {
        jdbcTemplate.update("UPDATE public.shards SET status = 'INACTIVE' WHERE shard_id = ?", shardId);

        assertThatThrownBy(() -> service.register(BUCKET_ON_A, SHARD_C))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(shardRole + " 샤드가 ACTIVE");
    }

    @Test
    void rejectsAnotherBucketWhileJobIsUnfinished() {
        MigrationJob firstJob = service.register(BUCKET_ON_A, SHARD_C);

        assertThatThrownBy(() -> service.register(BUCKET_ON_B, SHARD_C))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("이미 미완료 재배치 작업");

        assertThat(migrationJobRepository.findUnfinished()).contains(firstJob);
    }
}
