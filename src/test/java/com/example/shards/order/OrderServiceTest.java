package com.example.shards.order;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.example.shards.meta.BucketMapping;
import com.example.shards.sharding.ShardRoute;
import com.example.shards.sharding.ShardRouter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OrderServiceTest {

    private final ShardRouter shardRouter = mock(ShardRouter.class);
    private final OrderRepository orderRepository =
            mock(OrderRepository.class);

    // 서비스 자체는 실제 구현을 사용합니다.
    private final OrderService service =
            new OrderService(shardRouter, orderRepository);

    @ParameterizedTest
    @CsvSource({
            "15000, 15000.00",
            "0, 0.00",
            "1.230, 1.23",
            "9999999999.99, 9999999999.99"
    })
    void createsOrderWithNormalizedAmount(
            String input,
            String expected
    ) {
        BigDecimal expectedAmount = new BigDecimal(expected);
        JdbcTemplate shardB = mock(JdbcTemplate.class);

        BucketMapping mapping = new BucketMapping(
                10, 2, "B", "shardB", "NORMAL", 1L, "ACTIVE"
        );

        when(shardRouter.route(101L))
                .thenReturn(new ShardRoute(mapping, shardB));

        // DB가 반환할 시각을 흉내 내기 위한 고정된 테스트 값입니다.
        OffsetDateTime time =
                OffsetDateTime.parse("2026-01-01T00:00:00Z");

        // 서비스가 만든 UUID를 받아 저장 결과를 흉내 냅니다.
        when(orderRepository.insert(
                eq(shardB),
                any(UUID.class),
                eq(101L),
                eq(10),
                eq(expectedAmount)
        )).thenAnswer(invocation -> new Order(
                invocation.getArgument(1, UUID.class),
                101L,
                10,
                expectedAmount,
                "CREATED",
                time,
                time
        ));

        Order order = service.create(101L, new BigDecimal(input));

        assertNotNull(order);
        assertNotNull(order.orderId());
        assertEquals(expectedAmount, order.amount());

        // 사용자에 맞는 라우터를 호출했는지 확인합니다.
        verify(shardRouter).route(101L);

        // 올바른 연결·사용자·버킷·금액으로 한 번 저장했는지 확인합니다.
        verify(orderRepository).insert(
                eq(shardB),
                any(UUID.class),
                eq(101L),
                eq(10),
                eq(expectedAmount)
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "-0.01",
            "10000000000.00",
            "15000.001"
    })
    void rejectsInvalidAmount(String input) {
        assertThrows(
                InvalidOrderRequestException.class,
                () -> service.create(101L, new BigDecimal(input))
        );

        // 잘못된 금액이면 라우팅과 저장 모두 실행하지 않아야 합니다.
        verifyNoInteractions(shardRouter, orderRepository);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, Long.MIN_VALUE})
    void rejectsInvalidUserId(long userId) {
        assertThrows(
                InvalidOrderRequestException.class,
                () -> service.create(userId, BigDecimal.TEN)
        );

        verifyNoInteractions(shardRouter, orderRepository);
    }

    @Test
    void rejectsMissingAmount() {
        assertThrows(
                InvalidOrderRequestException.class,
                () -> service.create(101L, null)
        );

        verifyNoInteractions(shardRouter, orderRepository);
    }

    @Test
    void doesNotInsertWhenRoutingIsRejected() {
        // 라우터가 사용 불가능한 샤드라고 판단한 상황입니다.
        when(shardRouter.route(101L))
                .thenThrow(new IllegalStateException("샤드 사용 불가"));

        assertThrows(
                IllegalStateException.class,
                () -> service.create(101L, BigDecimal.TEN)
        );

        verify(shardRouter).route(101L);

        // 라우팅이 실패했으므로 저장 요청은 없어야 합니다.
        verifyNoInteractions(orderRepository);
    }
}