package com.example.shards.order;

// 현재 주문 상태에서 요청한 상태로 바꿀 수 없을 때 사용합니다.
public class OrderStatusConflictException extends RuntimeException {

    public OrderStatusConflictException(String currentStatus, String requestedStatus) {
        super("주문 상태를 " + currentStatus + "에서 " + requestedStatus + "로 변경할 수 없습니다.");
    }
}
