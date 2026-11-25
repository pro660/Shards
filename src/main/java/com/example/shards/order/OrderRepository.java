package com.example.shards.order;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

// 전달받은 샤드 연결로 주문 데이터를 저장합니다.
@Repository
public class OrderRepository {

    // 저장 결과와 조회 결과를 같은 방식으로 Order 객체로 바꿉니다.
    static final RowMapper<Order> ORDER_ROW_MAPPER =
            (rs, rowNum) -> new Order(
                    rs.getObject("order_id", UUID.class),
                    rs.getLong("user_id"),
                    rs.getInt("bucket_id"),
                    rs.getBigDecimal("amount"),
                    rs.getString("status"),
                    rs.getObject("created_at", OffsetDateTime.class),
                    rs.getObject("updated_at", OffsetDateTime.class)
            );

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
                ORDER_ROW_MAPPER,
                orderId,
                userId,
                bucketId,
                amount
        );
    }

    public Optional<Order> findById(
            JdbcTemplate shardJdbcTemplate,
            UUID orderId,
            long userId,
            int bucketId
    ) {
        // 이미 선택된 샤드에서 사용자와 버킷이 모두 일치하는 주문을 찾습니다.
        String sql = """
            SELECT
                order_id,
                user_id,
                bucket_id,
                amount,
                status,
                created_at,
                updated_at
            FROM orders
            WHERE order_id = ?
              AND user_id = ?
              AND bucket_id = ?
            """;

        // 일치하는 행이 없으면 빈 Optional을 돌려줍니다.
        return DataAccessUtils.optionalResult(
                shardJdbcTemplate.query(
                        sql,
                        ORDER_ROW_MAPPER,
                        orderId,
                        userId,
                        bucketId
                )
        );
    }

    public List<Order> findByUserId(
            JdbcTemplate shardJdbcTemplate,
            long userId,
            int bucketId
    ) {
        // 같은 버킷에 여러 사용자가 있으므로 사용자 번호도 함께 확인합니다.
        String sql = """
                SELECT
                    order_id,
                    user_id,
                    bucket_id,
                    amount,
                    status,
                    created_at,
                    updated_at
                FROM orders
                WHERE user_id = ?
                  AND bucket_id = ?
                ORDER BY created_at DESC, order_id DESC
                """;

        // 조회한 각 행을 Order로 바꿉니다. 결과가 0건이면 빈 리스트입니다.
        return shardJdbcTemplate.query(
                sql,
                ORDER_ROW_MAPPER,
                userId,
                bucketId
        );
    }

    public long countByBucketIds(JdbcTemplate shardJdbcTemplate, List<Integer> bucketIds) {
        if (bucketIds.isEmpty()) {
            return 0;
        }

        // 샤드가 소유한 버킷의 주문만 세도록 번호 목록을 SQL에 전달합니다.
        String sql = "SELECT COUNT(*) FROM orders WHERE bucket_id IN (:bucketIds)";
        NamedParameterJdbcTemplate namedJdbcTemplate = new NamedParameterJdbcTemplate(shardJdbcTemplate);
        return namedJdbcTemplate.queryForObject(sql, Map.of("bucketIds", bucketIds), Long.class);
    }

    public List<Order> findRecentByBucketIds(
            JdbcTemplate shardJdbcTemplate, List<Integer> bucketIds, int limit,
            OffsetDateTime beforeCreatedAt, UUID beforeOrderId
    ) {
        if (bucketIds.isEmpty()) {
            return List.of();
        }

        // 이 샤드가 소유한 버킷의 주문만 정렬해 필요한 만큼 가져옵니다.
        String sql = """
                SELECT order_id, user_id, bucket_id, amount, status, created_at, updated_at
                FROM orders
                WHERE bucket_id IN (:bucketIds)
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("bucketIds", bucketIds)
                .addValue("limit", limit);
        if (beforeCreatedAt != null) {
            // 같은 시각에는 UUID가 더 작은 주문부터 이어서 읽습니다.
            sql += " AND (created_at, order_id) < (:beforeCreatedAt, :beforeOrderId)\n";
            params.addValue("beforeCreatedAt", beforeCreatedAt).addValue("beforeOrderId", beforeOrderId);
        }
        sql += """
                ORDER BY created_at DESC, order_id DESC
                LIMIT :limit
                """;
        NamedParameterJdbcTemplate namedJdbcTemplate = new NamedParameterJdbcTemplate(shardJdbcTemplate);
        return namedJdbcTemplate.query(sql, params, ORDER_ROW_MAPPER);
    }

    public Optional<Order> updateAmount(
            JdbcTemplate shardJdbcTemplate,
            UUID orderId,
            long userId,
            int bucketId,
            BigDecimal amount
    ) {
        // 수정 대상 확인과 금액 변경을 한 SQL 문에서 처리합니다.
        String sql = """
                UPDATE orders
                SET amount = ?,
                    updated_at = statement_timestamp()
                WHERE order_id = ?
                  AND user_id = ?
                  AND bucket_id = ?
                RETURNING
                    order_id,
                    user_id,
                    bucket_id,
                    amount,
                    status,
                    created_at,
                    updated_at
                """;

        // 조건에 맞는 행이 없다면 빈 Optional을 반환합니다.
        return DataAccessUtils.optionalResult(
                shardJdbcTemplate.query(
                        sql,
                        ORDER_ROW_MAPPER,
                        amount,
                        orderId,
                        userId,
                        bucketId
                )
        );
    }

    public Optional<Order> updateStatus(
            JdbcTemplate shardJdbcTemplate,
            UUID orderId,
            long userId,
            int bucketId,
            String requestedStatus
    ) {
        // 동시에 다른 요청이 와도 현재 상태가 CREATED일 때만 변경합니다.
        String sql = """
                UPDATE orders
                SET status = ?,
                    updated_at = statement_timestamp()
                WHERE order_id = ?
                  AND user_id = ?
                  AND bucket_id = ?
                  AND status = 'CREATED'
                RETURNING
                    order_id,
                    user_id,
                    bucket_id,
                    amount,
                    status,
                    created_at,
                    updated_at
                """;

        return DataAccessUtils.optionalResult(
                shardJdbcTemplate.query(
                        sql,
                        ORDER_ROW_MAPPER,
                        requestedStatus,
                        orderId,
                        userId,
                        bucketId
                )
        );
    }

    public boolean deleteById(
            JdbcTemplate shardJdbcTemplate,
            UUID orderId,
            long userId,
            int bucketId
    ) {
        // 같은 샤드에 있는 다른 사용자의 주문을 건드리지 않도록 조건을 묶습니다.
        String sql = """
                DELETE FROM orders
                WHERE order_id = ?
                  AND user_id = ?
                  AND bucket_id = ?
                """;

        // 주문 번호가 기본 키이므로 삭제되는 행은 최대 한 건입니다.
        return shardJdbcTemplate.update(sql, orderId, userId, bucketId) == 1;
    }
}
