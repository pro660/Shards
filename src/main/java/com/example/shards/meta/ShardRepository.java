package com.example.shards.meta;

import java.util.List;
import java.util.Optional;

import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ShardRepository {

    private final JdbcTemplate jdbcTemplate;

    public ShardRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<String> findStatusByIdForShare(int shardId) {
        // 등록 트랜잭션이 끝날 때까지 도착 샤드 상태도 보호합니다.
        return DataAccessUtils.optionalResult(jdbcTemplate.queryForList(
                "SELECT status FROM public.shards WHERE shard_id = ? FOR SHARE",
                String.class, shardId
        ));
    }

    public Optional<String> findActiveConnectionKeyForShare(int shardId) {
        return DataAccessUtils.optionalResult(jdbcTemplate.queryForList(
                "SELECT connection_key FROM public.shards WHERE shard_id = ? AND status = 'ACTIVE' FOR SHARE",
                String.class, shardId
        ));
    }

    public List<ShardInfo> findAll() {
        return jdbcTemplate.query("""
                SELECT shard_id, shard_name, connection_key, status FROM public.shards ORDER BY shard_id
                """, (row, rowNum) -> new ShardInfo(
                row.getInt("shard_id"), row.getString("shard_name"), row.getString("connection_key"),
                row.getString("status")
        ));
    }

    public record ShardInfo(int shardId, String shardName, String connectionKey, String status) {
    }
}
