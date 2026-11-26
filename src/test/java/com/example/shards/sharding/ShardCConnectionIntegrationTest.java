package com.example.shards.sharding;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

// C를 띄운 환경에서만 실제 JDBC 연결을 검사합니다.
@EnabledIfEnvironmentVariable(named = "RUN_SHARD_C_TEST", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ShardCConnectionIntegrationTest {

    @Autowired
    private ShardConnectionRegistry registry;

    @Value("${SHARD_C_DB_NAME}")
    private String expectedDatabaseName;

    @Test
    void opensShardCConnectionAndReadsOrdersTable() {
        JdbcTemplate shardC = registry.getJdbcTemplate("shardC");

        assertThat(shardC.queryForObject("SELECT current_database()", String.class))
                .isEqualTo(expectedDatabaseName);

        // 테이블과 주문 번호 열에 접근할 수 있는지 검사합니다. 실제 주문은 읽지 않습니다.
        assertThat(shardC.queryForList("SELECT order_id FROM public.orders LIMIT 0", UUID.class))
                .isEmpty();
    }
}
