package com.example.shards.sharding;

import java.util.Map;
import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

// 메타 DB의 연결 키와 실제 샤드용 SQL 실행 도구를 연결합니다.
@Component
public class ShardConnectionRegistry {

    private final Map<String, JdbcTemplate> jdbcTemplates;

    public ShardConnectionRegistry(
            // 앞에서 shardA 표시를 붙인 연결 풀을 받습니다.
            @Qualifier("shardA") DataSource shardADataSource,

            // 앞에서 shardB 표시를 붙인 연결 풀을 받습니다.
            @Qualifier("shardB") DataSource shardBDataSource
    ) {
        // 각각의 연결 풀을 사용하는 SQL 실행 도구를 만듭니다.
        JdbcTemplate shardA = new JdbcTemplate(shardADataSource);
        JdbcTemplate shardB = new JdbcTemplate(shardBDataSource);

        // 키 이름은 메타 DB의 connection_key 값과 같아야 합니다.
        this.jdbcTemplates = Map.of(
                "shardA", shardA,
                "shardB", shardB
        );
    }

    // 연결 키에 맞는 SQL 실행 도구를 반환합니다.
    public JdbcTemplate getJdbcTemplate(String connectionKey) {

        // 등록되지 않은 연결을 임의의 다른 샤드로 보내지 않습니다.
        if (connectionKey == null
                || !jdbcTemplates.containsKey(connectionKey)) {
            throw new IllegalArgumentException(
                    "등록되지 않은 샤드 연결 키입니다: " + connectionKey
            );
        }

        return jdbcTemplates.get(connectionKey);
    }
}