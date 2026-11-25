package com.example.shards.order;

import java.util.List;
import java.util.UUID;

import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class BucketOrderRepository {

    private static final String ORDER_COLUMNS =
            "order_id, user_id, bucket_id, amount, status, created_at, updated_at";

    public List<Order> readBatch(JdbcTemplate shard, int bucketId, UUID after, int limit) {
        String select = "SELECT " + ORDER_COLUMNS + " FROM public.orders WHERE bucket_id = ?";
        if (after == null) {
            return shard.query(select + " ORDER BY order_id LIMIT ?", OrderRepository.ORDER_ROW_MAPPER, bucketId, limit);
        }
        return shard.query(select + " AND order_id > ? ORDER BY order_id LIMIT ?",
                OrderRepository.ORDER_ROW_MAPPER, bucketId, after, limit);
    }

    public void copyBatch(JdbcTemplate target, List<Order> batch) {
        for (Order order : batch) {
            int inserted = target.update("""
                    INSERT INTO public.orders (order_id, user_id, bucket_id, amount, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (order_id) DO NOTHING
                    """, order.orderId(), order.userId(), order.bucketId(), order.amount(), order.status(),
                    order.createdAt(), order.updatedAt());
            if (inserted == 0 && !matchesStoredOrder(target, order)) {
                throw new IllegalStateException("도착 샤드에서 같은 주문 번호의 내용이 일치하지 않습니다.");
            }
        }
    }

    private boolean matchesStoredOrder(JdbcTemplate target, Order expected) {
        return DataAccessUtils.optionalResult(target.query(
                "SELECT " + ORDER_COLUMNS + " FROM public.orders WHERE order_id = ?",
                OrderRepository.ORDER_ROW_MAPPER, expected.orderId()
        )).filter(expected::equals).isPresent();
    }

    public int deleteBatch(JdbcTemplate shard, int bucketId, int limit) {
        return shard.update("""
                DELETE FROM public.orders
                WHERE bucket_id = ? AND order_id IN (
                    SELECT order_id FROM public.orders WHERE bucket_id = ? ORDER BY order_id LIMIT ?
                )
                """, bucketId, bucketId, limit);
    }
}
