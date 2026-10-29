package com.example.shards.sharding;

import com.example.shards.meta.BucketMapping;
import org.springframework.jdbc.core.JdbcTemplate;

// 조회 당시의 매핑 정보와 선택한 SQL 실행 도구를 함께 전달합니다.
public record ShardRoute(
        BucketMapping mapping,
        JdbcTemplate jdbcTemplate
) {
}