package com.example.shards.order;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

// HTTP 요청을 받고, 처리 결과를 JSON 응답으로 반환합니다.
@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService orderService;
    private final OrderQueryService orderQueryService;

    public OrderController(OrderService orderService, OrderQueryService orderQueryService) {
        this.orderService = orderService;
        this.orderQueryService = orderQueryService;
    }

    // POST /orders 요청을 처리합니다.
    @PostMapping
    public ResponseEntity<Order> create(
            // 요청 본문의 JSON을 Java 객체로 받습니다.
            @RequestBody CreateOrderRequest request
    ) {
        // 입력 검사, 샤드 선택, 주문 저장은 서비스에 맡깁니다.
        Order order = orderService.create(
                request.userId(),
                request.amount()
        );

        // 저장이 성공했을 때 생성 완료 상태와 주문 정보를 반환합니다.
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(order);
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<Order> findById(
            @PathVariable("orderId") UUID orderId,
            @RequestParam("userId") long userId
    ) {
        Order order = orderService.findById(userId, orderId);
        return ResponseEntity.ok(order);
    }

    @GetMapping
    public ResponseEntity<List<Order>> findByUserId(
            @RequestParam("userId") long userId
    ) {
        // 조회가 성공했다면 목록이 비어 있어도 200을 반환합니다.
        List<Order> orders = orderService.findByUserId(userId);
        return ResponseEntity.ok(orders);
    }

    @GetMapping("/count")
    public ResponseEntity<OrderCountResponse> countAllOrders() {
        long totalCount = orderQueryService.countAllOrders();
        return ResponseEntity.ok(new OrderCountResponse(totalCount));
    }

    @GetMapping("/all")
    public ResponseEntity<List<Order>> findRecentOrders() {
        return ResponseEntity.ok(orderQueryService.findRecentOrders());
    }

    @PatchMapping("/{orderId}/amount")
    public ResponseEntity<Order> updateAmount(
            @PathVariable("orderId") UUID orderId,
            @RequestParam("userId") long userId,
            @RequestBody UpdateOrderAmountRequest request
    ) {
        Order order = orderService.updateAmount(userId, orderId, request.amount());
        return ResponseEntity.ok(order);
    }

    @PatchMapping("/{orderId}/status")
    public ResponseEntity<Order> updateStatus(
            @PathVariable("orderId") UUID orderId,
            @RequestParam("userId") long userId,
            @RequestBody UpdateOrderStatusRequest request
    ) {
        Order order = orderService.updateStatus(userId, orderId, request.status());
        return ResponseEntity.ok(order);
    }

    @DeleteMapping("/{orderId}")
    public ResponseEntity<Void> deleteById(
            @PathVariable("orderId") UUID orderId,
            @RequestParam("userId") long userId
    ) {
        orderService.deleteById(userId, orderId);
        return ResponseEntity.noContent().build();
    }
}
