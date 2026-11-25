package com.example.shards.order;

import java.util.List;
import java.util.stream.IntStream;

import com.example.shards.meta.BucketMapping;
import com.example.shards.meta.BucketMappingRepository;
import com.example.shards.sharding.BucketHasher;
import com.example.shards.sharding.ShardConnectionRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderQueryServiceTest {

    @Test
    void usesMetadataOwnerInsteadOfInitialBucketRanges() {
        BucketMappingRepository mappingRepository = mock(BucketMappingRepository.class);
        ShardConnectionRegistry registry = mock(ShardConnectionRegistry.class);
        OrderRepository orderRepository = mock(OrderRepository.class);
        JdbcTemplate shardA = mock(JdbcTemplate.class);
        JdbcTemplate shardB = mock(JdbcTemplate.class);

        // 초기 매핑과 달리 버킷 3의 소유자가 B인 상황을 만듭니다.
        List<BucketMapping> mappings = IntStream.range(0, BucketHasher.BUCKET_COUNT)
                .mapToObj(bucketId -> {
                    boolean ownedByA = bucketId < 8 && bucketId != 3;
                    return ownedByA
                            ? new BucketMapping(bucketId, 1, "A", "shardA", "NORMAL", 1L, "ACTIVE")
                            : new BucketMapping(bucketId, 2, "B", "shardB", "NORMAL", 1L, "ACTIVE");
                })
                .toList();

        when(mappingRepository.findAll()).thenReturn(mappings);
        when(registry.getJdbcTemplate("shardA")).thenReturn(shardA);
        when(registry.getJdbcTemplate("shardB")).thenReturn(shardB);
        when(orderRepository.countByBucketIds(shardA, List.of(0, 1, 2, 4, 5, 6, 7))).thenReturn(4L);
        when(orderRepository.countByBucketIds(shardB, List.of(3, 8, 9, 10, 11, 12, 13, 14, 15))).thenReturn(5L);

        OrderQueryService service = new OrderQueryService(
                mappingRepository, registry, orderRepository, mock(com.example.shards.meta.OperationLocks.class)
        );

        assertThat(service.countAllOrders()).isEqualTo(9);
    }
}
