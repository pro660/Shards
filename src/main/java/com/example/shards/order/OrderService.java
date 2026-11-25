package com.example.shards.order;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.example.shards.sharding.ShardRoute;
import com.example.shards.sharding.ShardRouter;
import org.springframework.stereotype.Service;

// 주문 요청의 입력 검사와 처리 순서를 담당합니다.
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
        validateUserId(userId);
        BigDecimal normalizedAmount = normalizeAmount(amount);

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

    public Order findById(long userId, UUID orderId) {
        validateUserId(userId);

        // 주문을 생성할 때와 같은 규칙으로 현재 담당 샤드를 찾습니다.
        ShardRoute route = shardRouter.route(userId);

        return orderRepository.findById(
                route.jdbcTemplate(),
                orderId,
                userId,
                route.mapping().bucketId()
        ).orElseThrow(OrderNotFoundException::new);
    }

    public List<Order> findByUserId(long userId) {
        validateUserId(userId);

        // 사용자 번호로 현재 담당 샤드 한 곳을 찾습니다.
        ShardRoute route = shardRouter.route(userId);

        return orderRepository.findByUserId(
                route.jdbcTemplate(),
                userId,
                route.mapping().bucketId()
        );
    }

    public Order updateAmount(long userId, UUID orderId, BigDecimal amount) {
        validateUserId(userId);
        BigDecimal normalizedAmount = normalizeAmount(amount);

        ShardRoute route = shardRouter.route(userId);

        return orderRepository.updateAmount(
                route.jdbcTemplate(),
                orderId,
                userId,
                route.mapping().bucketId(),
                normalizedAmount
        ).orElseThrow(OrderNotFoundException::new);
    }

    public Order updateStatus(long userId, UUID orderId, String requestedStatus) {
        validateUserId(userId);
        if (!"PAID".equals(requestedStatus) && !"CANCELLED".equals(requestedStatus)) {
            throw new InvalidOrderRequestException("status는 PAID 또는 CANCELLED여야 합니다.");
        }

        ShardRoute route = shardRouter.route(userId);
        int bucketId = route.mapping().bucketId();

        Optional<Order> updatedOrder = orderRepository.updateStatus(
                route.jdbcTemplate(), orderId, userId, bucketId, requestedStatus
        );
        if (updatedOrder.isPresent()) {
            return updatedOrder.get();
        }

        // 변경된 행이 없으면 같은 샤드에서 주문의 현재 상태를 확인합니다.
        Order currentOrder = orderRepository.findById(
                route.jdbcTemplate(), orderId, userId, bucketId
        ).orElseThrow(OrderNotFoundException::new);

        // 요청한 상태로 이미 바뀌어 있다면 다시 저장할 필요가 없습니다.
        if (requestedStatus.equals(currentOrder.status())) {
            return currentOrder;
        }

        throw new OrderStatusConflictException(currentOrder.status(), requestedStatus);
    }

    public void deleteById(long userId, UUID orderId) {
        validateUserId(userId);
        ShardRoute route = shardRouter.route(userId);

        boolean deleted = orderRepository.deleteById(
                route.jdbcTemplate(), orderId, userId, route.mapping().bucketId()
        );
        if (!deleted) {
            throw new OrderNotFoundException();
        }
    }

    private static void validateUserId(long userId) {
        if (userId <= 0) {
            throw new InvalidOrderRequestException(
                    "userId는 1 이상이어야 합니다."
            );
        }
    }

    // 주문 생성과 금액 수정에 같은 금액 규칙을 적용합니다.
    private static BigDecimal normalizeAmount(BigDecimal amount) {
        if (amount == null) {
            throw new InvalidOrderRequestException("주문 금액은 필수입니다.");
        }

        if (amount.signum() < 0 || amount.compareTo(MAX_AMOUNT) > 0) {
            throw new InvalidOrderRequestException(
                    "주문 금액은 0 이상 9999999999.99 이하여야 합니다."
            );
        }

        try {
            // 값을 반올림하지 않고 소수 둘째 자리까지 표현합니다.
            return amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new InvalidOrderRequestException(
                    "주문 금액은 소수 둘째 자리까지 표현할 수 있어야 합니다.",
                    e
            );
        }
    }
}
