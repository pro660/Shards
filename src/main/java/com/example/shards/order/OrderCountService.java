package com.example.shards.order;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.shards.meta.BucketMapping;
import com.example.shards.meta.BucketMappingRepository;
import com.example.shards.sharding.BucketHasher;
import com.example.shards.sharding.ShardConnectionRegistry;
import org.springframework.stereotype.Service;

@Service
public class OrderCountService {

    private final BucketMappingRepository bucketMappingRepository;
    private final ShardConnectionRegistry shardConnectionRegistry;
    private final OrderRepository orderRepository;

    public OrderCountService(
            BucketMappingRepository bucketMappingRepository,
            ShardConnectionRegistry shardConnectionRegistry,
            OrderRepository orderRepository
    ) {
        this.bucketMappingRepository = bucketMappingRepository;
        this.shardConnectionRegistry = shardConnectionRegistry;
        this.orderRepository = orderRepository;
    }

    public long countAllOrders() {
        List<BucketMapping> mappings = bucketMappingRepository.findAll();
        if (mappings.size() != BucketHasher.BUCKET_COUNT) {
            throw new IllegalStateException("전체 주문을 집계하려면 모든 버킷의 소유권이 필요합니다.");
        }

        // 버킷 번호를 소유 샤드별로 모아, 샤드당 한 번만 집계합니다.
        Map<String, List<Integer>> bucketsByShard = new LinkedHashMap<>();
        for (BucketMapping mapping : mappings) {
            bucketsByShard.computeIfAbsent(mapping.connectionKey(), key -> new ArrayList<>())
                    .add(mapping.bucketId());
        }

        long totalCount = 0;
        for (var shard : bucketsByShard.entrySet()) {
            long shardCount = orderRepository.countByBucketIds(
                    shardConnectionRegistry.getJdbcTemplate(shard.getKey()),
                    shard.getValue()
            );
            totalCount = Math.addExact(totalCount, shardCount);
        }
        return totalCount;
    }
}
