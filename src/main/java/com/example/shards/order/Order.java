package com.example.shards.order;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

// orders 테이블의 한 행을 Java 객체로 표현합니다.
public record Order(
        // 주문 한 건을 구분하는 번호입니다.
        UUID orderId,

        // 주문한 사용자의 번호입니다.
        long userId,

        // 이 주문이 속한 버킷 번호입니다.
        int bucketId,

        // 소수 금액을 정확하게 표현합니다.
        BigDecimal amount,

        // CREATED, PAID, CANCELLED 중 하나입니다.
        String status,

        // 주문이 생성된 시점입니다.
        OffsetDateTime createdAt,

        // 주문이 마지막으로 수정된 시점입니다.
        OffsetDateTime updatedAt
) {
}