package com.example.shards.order;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// HTTP 요청을 받고, 처리 결과를 JSON 응답으로 반환합니다.
@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService orderService;

    // Spring이 기존 OrderService 객체를 전달합니다.
    public OrderController(OrderService orderService) {
        this.orderService = orderService;
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
}