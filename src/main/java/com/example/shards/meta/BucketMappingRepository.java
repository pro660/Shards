package com.example.shards.meta;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

// 메타 DB에서 버킷 매핑을 조회하는 역할을 맡습니다.
@Repository
public class BucketMappingRepository {

    private static final String SELECT_MAPPINGS = """
            SELECT
                b.bucket_id,
                b.shard_id,
                s.shard_name,
                s.connection_key,
                b.state,
                b.version,
                s.status AS shard_status
            FROM bucket_mapping AS b
            JOIN shards AS s
                ON b.shard_id = s.shard_id
            """;

    private static final RowMapper<BucketMapping> BUCKET_MAPPING_ROW_MAPPER =
            (rs, rowNum) -> new BucketMapping(
                    rs.getInt("bucket_id"),
                    rs.getInt("shard_id"),
                    rs.getString("shard_name"),
                    rs.getString("connection_key"),
                    rs.getString("state"),
                    rs.getLong("version"),
                    rs.getString("shard_status")
            );

    private final JdbcTemplate jdbcTemplate;

    // 현재 설정된 메타 DB용 SQL 실행 도구를 전달받습니다.
    public BucketMappingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // 버킷 번호 하나에 해당하는 매핑 정보를 조회합니다.
    public BucketMapping findByBucketId(int bucketId) {

        // DB에 접근하기 전에 잘못된 버킷 번호를 거부합니다.
        if (bucketId < 0 || bucketId >= 16) {
            throw new IllegalArgumentException(
                    "bucketId는 0 이상 15 이하여야 합니다."
            );
        }

        return jdbcTemplate.queryForObject(
                SELECT_MAPPINGS + "WHERE b.bucket_id = ?",
                BUCKET_MAPPING_ROW_MAPPER,
                bucketId
        );
    }

    public List<BucketMapping> findAll() {
        // 집계 한 번에 사용할 전체 버킷 매핑을 한 쿼리로 읽습니다.
        return jdbcTemplate.query(SELECT_MAPPINGS + "ORDER BY b.bucket_id", BUCKET_MAPPING_ROW_MAPPER);
    }
}
