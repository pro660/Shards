package com.example.shards.order;

import java.math.BigDecimal;

// 주문 생성 요청에서 받을 값만 담습니다.
// 주문 번호와 버킷 번호는 서버에서 결정합니다.
public record CreateOrderRequest(
        // 주문을 생성할 사용자 번호입니다.
        long userId,

        // 주문 금액입니다. 실제 유효성 검사는 서비스가 담당합니다.
        BigDecimal amount
) {
}