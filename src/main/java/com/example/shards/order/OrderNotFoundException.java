package com.example.shards.order;

// 요청한 사용자 범위에서 주문을 찾지 못했음을 나타냅니다.
public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException() {
        super("해당 사용자의 주문을 찾을 수 없습니다.");
    }
}