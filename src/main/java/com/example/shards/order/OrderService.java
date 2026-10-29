package com.example.shards.order;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

import com.example.shards.sharding.ShardRoute;
import com.example.shards.sharding.ShardRouter;
import org.springframework.stereotype.Service;

// 주문 생성에 필요한 검사와 처리 순서를 담당합니다.
@Service
public class OrderService {

    // orders.amount의 NUMERIC(12, 2) 범위에 맞춘 최댓값입니다.
    private static final BigDecimal MAX_AMOUNT =
            new BigDecimal("9999999999.99");

    private final ShardRouter shardRouter;
    private final OrderRepository orderRepository;

    public OrderService(
            ShardRouter shardRouter,
            OrderRepository orderRepository
    ) {
        this.shardRouter = shardRouter;
        this.orderRepository = orderRepository;
    }

    public Order create(long userId, BigDecimal amount) {

        // 잘못된 입력은 DB 조회 전에 거부합니다.
        if (userId <= 0) {
            throw new IllegalArgumentException(
                    "userId는 1 이상이어야 합니다."
            );
        }

        if (amount == null) {
            throw new IllegalArgumentException(
                    "주문 금액은 필수입니다."
            );
        }

        if (amount.signum() < 0 || amount.compareTo(MAX_AMOUNT) > 0) {
            throw new IllegalArgumentException(
                    "주문 금액은 0 이상 9999999999.99 이하여야 합니다."
            );
        }

        BigDecimal normalizedAmount;

        try {
            // 값을 바꾸지 않고 소수 둘째 자리까지 표현합니다.
            normalizedAmount = amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            // 반올림이 필요한 금액은 그대로 거부합니다.
            throw new IllegalArgumentException(
                    "주문 금액은 소수 둘째 자리까지 표현할 수 있어야 합니다.",
                    e
            );
        }

        // 사용자 번호로 버킷을 계산하고 샤드 상태를 검사합니다.
        ShardRoute route = shardRouter.route(userId);

        // 샤드 선택이 성공한 뒤 주문 번호를 생성합니다.
        UUID orderId = UUID.randomUUID();

        // 라우터가 선택한 연결과 버킷 번호를 저장 기능에 전달합니다.
        return orderRepository.insert(
                route.jdbcTemplate(),
                orderId,
                userId,
                route.mapping().bucketId(),
                normalizedAmount
        );
    }
}