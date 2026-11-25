package com.example.shards.order;

import com.example.shards.sharding.ShardConnectionRegistry;
import org.springframework.stereotype.Service;

@Service
public class OrderCountService {

    private final ShardConnectionRegistry shardConnectionRegistry;
    private final OrderRepository orderRepository;

    public OrderCountService(
            ShardConnectionRegistry shardConnectionRegistry,
            OrderRepository orderRepository
    ) {
        this.shardConnectionRegistry = shardConnectionRegistry;
        this.orderRepository = orderRepository;
    }

    public long countAllOrders() {
        // 집계의 첫 단계에서는 A와 B에 실제로 저장된 행을 모두 셉니다.
        long shardACount = orderRepository.countAll(shardConnectionRegistry.getJdbcTemplate("shardA"));
        long shardBCount = orderRepository.countAll(shardConnectionRegistry.getJdbcTemplate("shardB"));
        return Math.addExact(shardACount, shardBCount);
    }
}
