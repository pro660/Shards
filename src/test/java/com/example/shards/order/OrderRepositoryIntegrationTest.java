package com.example.shards.order;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

import com.example.shards.sharding.ShardConnectionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// 실제 Spring 객체와 PostgreSQL을 사용하는 통합 테스트입니다.
@SpringBootTest
class OrderRepositoryIntegrationTest {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ShardConnectionRegistry shardConnectionRegistry;

    @Test
    void insertsOrderIntoShardAAndRollsBack() {
        verifyInsertAndRollback("shardA", "shards_a", 1L, 3);
    }

    @Test
    void insertsOrderIntoShardBAndRollsBack() {
        verifyInsertAndRollback("shardB", "shards_b", 101L, 10);
    }

    // 같은 검사를 A와 B에서 각각 실행합니다.
    private void verifyInsertAndRollback(
            String connectionKey,
            String expectedDatabase,
            long userId,
            int bucketId
    ) {
        JdbcTemplate jdbcTemplate =
                shardConnectionRegistry.getJdbcTemplate(connectionKey);

        // 저장하기 전에 실제 접속한 DB가 맞는지 확인합니다.
        assertEquals(
                expectedDatabase,
                jdbcTemplate.queryForObject(
                        "SELECT current_database()",
                        String.class
                )
        );

        // 선택한 샤드의 연결 풀을 가져옵니다.
        DataSource dataSource =
                Objects.requireNonNull(jdbcTemplate.getDataSource());

        // 이 샤드에서 실행할 트랜잭션을 준비합니다.
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource)
        );

        // 실행할 때마다 새로운 테스트 주문 번호를 만듭니다.
        UUID orderId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("15000.00");

        String countSql =
                "SELECT COUNT(*) FROM orders WHERE order_id = ?";

        transaction.executeWithoutResult(status -> {
            // 테스트가 정상 종료돼도 저장 내용을 확정하지 않습니다.
            status.setRollbackOnly();

            Order order = orderRepository.insert(
                    jdbcTemplate,
                    orderId,
                    userId,
                    bucketId,
                    amount
            );

            // 반환된 주문 정보와 DB 기본값을 확인합니다.
            assertEquals(orderId, order.orderId());
            assertEquals(userId, order.userId());
            assertEquals(bucketId, order.bucketId());
            assertEquals(amount, order.amount());
            assertEquals("CREATED", order.status());
            assertNotNull(order.createdAt());
            assertNotNull(order.updatedAt());
            // 저장 결과와 다시 조회한 주문의 모든 값이 일치해야 합니다.
            Order foundOrder = orderRepository.findById(
                    jdbcTemplate, orderId, userId, bucketId
            ).orElseThrow();

            assertEquals(order, foundOrder);

            // 같은 UUID라도 사용자 번호가 다르면 조회되지 않아야 합니다.
            assertTrue(orderRepository.findById(
                    jdbcTemplate, orderId, userId + 1, bucketId
            ).isEmpty());

            // 같은 UUID와 사용자 번호라도 버킷이 다르면 조회되지 않아야 합니다.
            assertTrue(orderRepository.findById(
                    jdbcTemplate, orderId, userId, bucketId + 1
            ).isEmpty());

            // 같은 트랜잭션 안에서는 저장한 주문이 조회돼야 합니다.
            Long count = jdbcTemplate.queryForObject(
                    countSql,
                    Long.class,
                    orderId
            );

            assertEquals(1L, count.longValue());
        });

        // 트랜잭션이 끝나고 되돌려졌으므로 주문이 남지 않아야 합니다.
        Long remaining = jdbcTemplate.queryForObject(
                countSql,
                Long.class,
                orderId
        );

        assertEquals(0L, remaining.longValue());
    }
}