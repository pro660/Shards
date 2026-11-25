package com.example.shards.meta;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class MigrationJobRepository {

    private static final String JOB_COLUMNS = """
            job_id, bucket_id, source_shard_id, target_shard_id, source_mapping_version,
            phase, last_copied_order_id, copied_order_count, verified_at, last_error,
            created_at, updated_at, finished_at
            """;
    private static final String SELECT_JOBS = "SELECT " + JOB_COLUMNS + "FROM public.migration_jobs ";

    // INSERT와 SELECT의 결과를 같은 Java 타입으로 바꿉니다.
    private static final RowMapper<MigrationJob> MIGRATION_JOB_ROW_MAPPER =
            (rs, rowNum) -> new MigrationJob(
                    rs.getLong("job_id"),
                    rs.getInt("bucket_id"),
                    rs.getInt("source_shard_id"),
                    rs.getInt("target_shard_id"),
                    rs.getLong("source_mapping_version"),
                    rs.getString("phase"),
                    rs.getObject("last_copied_order_id", UUID.class),
                    rs.getLong("copied_order_count"),
                    rs.getObject("verified_at", OffsetDateTime.class),
                    rs.getString("last_error"),
                    rs.getObject("created_at", OffsetDateTime.class),
                    rs.getObject("updated_at", OffsetDateTime.class),
                    rs.getObject("finished_at", OffsetDateTime.class)
            );

    private final JdbcTemplate jdbcTemplate;

    // 버킷 매핑과 같은 메타 DB 연결을 사용합니다.
    public MigrationJobRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<MigrationJob> tryInsert(
            int bucketId, int sourceShardId, int targetShardId, long sourceMappingVersion
    ) {
        // 미완료 작업의 인덱스와 충돌하면 RETURNING 결과가 비어 있습니다.
        String sql = """
                INSERT INTO public.migration_jobs (bucket_id, source_shard_id, target_shard_id, source_mapping_version)
                VALUES (?, ?, ?, ?)
                ON CONFLICT ((1)) WHERE finished_at IS NULL DO NOTHING
                RETURNING
                """ + JOB_COLUMNS;
        return DataAccessUtils.optionalResult(jdbcTemplate.query(
                sql, MIGRATION_JOB_ROW_MAPPER, bucketId, sourceShardId, targetShardId, sourceMappingVersion
        ));
    }

    public Optional<MigrationJob> findById(long jobId) {
        return DataAccessUtils.optionalResult(jdbcTemplate.query(
                SELECT_JOBS + "WHERE job_id = ?", MIGRATION_JOB_ROW_MAPPER, jobId
        ));
    }

    public Optional<MigrationJob> findUnfinished() {
        // 메타 DB의 부분 고유 인덱스가 이 조건에 해당하는 작업을 최대 1개로 제한합니다.
        return DataAccessUtils.optionalResult(jdbcTemplate.query(
                SELECT_JOBS + "WHERE finished_at IS NULL", MIGRATION_JOB_ROW_MAPPER
        ));
    }
}
