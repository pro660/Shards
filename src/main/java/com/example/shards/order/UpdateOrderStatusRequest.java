package com.example.shards.order;

// 요청 본문에서 주문을 바꿀 상태를 읽습니다.
public record UpdateOrderStatusRequest(String status) {
}
