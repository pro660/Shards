package com.example.shards.meta;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

// 메타 DB에서 버킷 매핑을 조회하는 역할을 맡습니다.
@Repository
public class BucketMappingRepository {

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

        // 버킷 매핑과 샤드 정보를 담당 샤드 번호로 연결합니다.
        // ? 위치의 값은 SQL 실행 시 별도로 전달합니다.
        String sql = """
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
                WHERE b.bucket_id = ?
                """;

        // 조회한 한 행을 BucketMapping 객체 하나로 바꿉니다.
        return jdbcTemplate.queryForObject(
                sql,
                (rs, rowNum) -> new BucketMapping(
                        rs.getInt("bucket_id"),
                        rs.getInt("shard_id"),
                        rs.getString("shard_name"),
                        rs.getString("connection_key"),
                        rs.getString("state"),
                        rs.getLong("version"),
                        rs.getString("shard_status")
                ),
                bucketId
        );
    }
}