package com.example.shards.order;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import com.example.shards.meta.BucketMapping;
import com.example.shards.meta.BucketMappingRepository;
import com.example.shards.meta.OperationLocks;
import com.example.shards.sharding.BucketHasher;
import com.example.shards.sharding.ShardConnectionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class OrderQueryService {

    private static final Logger log = LoggerFactory.getLogger(OrderQueryService.class);
    private static final int MAX_PAGE_SIZE = 50;

    // SQL과 같은 순서로 비교합니다. UUID는 표준 문자열 순서가 PostgreSQL의 바이트 순서와 같습니다.
    private static final Comparator<Order> NEWEST_FIRST = Comparator
            .comparing((Order order) -> order.createdAt().toInstant())
            .thenComparing(order -> order.orderId().toString())
            .reversed();

    private final BucketMappingRepository bucketMappingRepository;
    private final ShardConnectionRegistry shardConnectionRegistry;
    private final OrderRepository orderRepository;
    private final OperationLocks locks;

    public OrderQueryService(
            BucketMappingRepository bucketMappingRepository,
            ShardConnectionRegistry shardConnectionRegistry,
            OrderRepository orderRepository,
            OperationLocks locks
    ) {
        this.bucketMappingRepository = bucketMappingRepository;
        this.shardConnectionRegistry = shardConnectionRegistry;
        this.orderRepository = orderRepository;
        this.locks = locks;
    }

    public long countAllOrders() {
        long totalCount = 0;
        for (var shard : findOwnedBucketsByShard().entrySet()) {
            long shardCount = queryShard(shard.getKey(),
                    jdbcTemplate -> orderRepository.countByBucketIds(jdbcTemplate, shard.getValue())
            );
            totalCount = Math.addExact(totalCount, shardCount);
        }
        return totalCount;
    }

    public List<Order> findRecentOrders(int size, OffsetDateTime beforeCreatedAt, UUID beforeOrderId) {
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidOrderRequestException("size는 1부터 " + MAX_PAGE_SIZE + "까지 입력해 주세요.");
        }
        if ((beforeCreatedAt == null) != (beforeOrderId == null)) {
            throw new InvalidOrderRequestException("다음 페이지에는 beforeCreatedAt과 beforeOrderId가 모두 필요합니다.");
        }

        List<Order> candidates = new ArrayList<>();
        for (var shard : findOwnedBucketsByShard().entrySet()) {
            // 모든 샤드가 같은 주문 다음부터 읽으므로 샤드마다 한 페이지 분량이면 충분합니다.
            candidates.addAll(queryShard(shard.getKey(),
                    jdbcTemplate -> orderRepository.findRecentByBucketIds(
                            jdbcTemplate, shard.getValue(), size, beforeCreatedAt, beforeOrderId
                    )
            ));
        }

        return candidates.stream()
                .sorted(NEWEST_FIRST)
                .limit(size)
                .toList();
    }

    private <T> T queryShard(String connectionKey, Function<JdbcTemplate, T> query) {
        JdbcTemplate jdbcTemplate = shardConnectionRegistry.getJdbcTemplate(connectionKey);
        try {
            return query.apply(jdbcTemplate);
        } catch (DataAccessResourceFailureException | TransientDataAccessResourceException exception) {
            // 앞에서 읽은 결과를 반환하지 않고 전체 요청을 실패 처리합니다.
            log.warn("Order query failed on shard {} ({})", connectionKey, exception.getClass().getSimpleName());
            throw new OrderQueryUnavailableException(exception);
        }
    }

    private Map<String, List<Integer>> findOwnedBucketsByShard() {
        locks.allowReadAll();
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
