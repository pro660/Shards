package com.example.shards.order;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

// 전달받은 샤드 연결로 주문 데이터를 저장합니다.
@Repository
public class OrderRepository {

    public Order insert(
            JdbcTemplate shardJdbcTemplate,
            UUID orderId,
            long userId,
            int bucketId,
            BigDecimal amount
    ) {
        // 상태와 시각은 DB에 설정한 기본값을 사용합니다.
        // RETURNING으로 실제 저장된 행의 값을 돌려받습니다.
        String sql = """
                INSERT INTO orders (
                    order_id,
                    user_id,
                    bucket_id,
                    amount
                )
                VALUES (?, ?, ?, ?)
                RETURNING
                    order_id,
                    user_id,
                    bucket_id,
                    amount,
                    status,
                    created_at,
                    updated_at
                """;

        // INSERT 결과로 반환된 한 행을 Order 객체로 바꿉니다.
        return shardJdbcTemplate.queryForObject(
                sql,
                (rs, rowNum) -> new Order(
                        rs.getObject("order_id", UUID.class),
                        rs.getLong("user_id"),
                        rs.getInt("bucket_id"),
                        rs.getBigDecimal("amount"),
                        rs.getString("status"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class)
                ),
                orderId,
                userId,
                bucketId,
                amount
        );
    }
}