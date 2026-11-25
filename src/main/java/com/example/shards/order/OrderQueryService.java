package com.example.shards.order;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.shards.meta.BucketMapping;
import com.example.shards.meta.BucketMappingRepository;
import com.example.shards.sharding.BucketHasher;
import com.example.shards.sharding.ShardConnectionRegistry;
import org.springframework.stereotype.Service;

@Service
public class OrderQueryService {

    private static final int RECENT_ORDER_LIMIT = 50;

    // SQL과 같은 순서로 비교합니다. UUID는 표준 문자열 순서가 PostgreSQL의 바이트 순서와 같습니다.
    private static final Comparator<Order> NEWEST_FIRST = Comparator
            .comparing((Order order) -> order.createdAt().toInstant())
            .thenComparing(order -> order.orderId().toString())
            .reversed();

    private final BucketMappingRepository bucketMappingRepository;
    private final ShardConnectionRegistry shardConnectionRegistry;
    private final OrderRepository orderRepository;

    public OrderQueryService(
            BucketMappingRepository bucketMappingRepository,
            ShardConnectionRegistry shardConnectionRegistry,
            OrderRepository orderRepository
    ) {
        this.bucketMappingRepository = bucketMappingRepository;
        this.shardConnectionRegistry = shardConnectionRegistry;
        this.orderRepository = orderRepository;
    }

    public long countAllOrders() {
        long totalCount = 0;
        for (var shard : findOwnedBucketsByShard().entrySet()) {
            long shardCount = orderRepository.countByBucketIds(
                    shardConnectionRegistry.getJdbcTemplate(shard.getKey()),
                    shard.getValue()
            );
            totalCount = Math.addExact(totalCount, shardCount);
        }
        return totalCount;
    }

    public List<Order> findRecentOrders() {
        List<Order> candidates = new ArrayList<>();
        for (var shard : findOwnedBucketsByShard().entrySet()) {
            // 한 샤드에서 50위보다 뒤인 주문은 전체 상위 50건에도 들어갈 수 없습니다.
            candidates.addAll(orderRepository.findRecentByBucketIds(
                    shardConnectionRegistry.getJdbcTemplate(shard.getKey()),
                    shard.getValue(),
                    RECENT_ORDER_LIMIT
            ));
        }

        return candidates.stream()
                .sorted(NEWEST_FIRST)
                .limit(RECENT_ORDER_LIMIT)
                .toList();
    }

    private Map<String, List<Integer>> findOwnedBucketsByShard() {
        List<BucketMapping> mappings = bucketMappingRepository.findAll();
        if (mappings.size() != BucketHasher.BUCKET_COUNT) {
            throw new IllegalStateException("전체 주문을 조회하려면 모든 버킷의 소유권이 필요합니다.");
        }

        // 집계와 목록 조회가 같은 소유권 규칙을 사용합니다.
        Map<String, List<Integer>> bucketsByShard = new LinkedHashMap<>();
        for (BucketMapping mapping : mappings) {
            bucketsByShard.computeIfAbsent(mapping.connectionKey(), key -> new ArrayList<>())
                    .add(mapping.bucketId());
        }

        return bucketsByShard;
    }
}
