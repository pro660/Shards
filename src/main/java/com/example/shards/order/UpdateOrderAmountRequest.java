package com.example.shards.order;

import java.math.BigDecimal;

// 주문 금액을 바꿀 때 요청 본문에서 받는 값입니다.
public record UpdateOrderAmountRequest(BigDecimal amount) {
}
