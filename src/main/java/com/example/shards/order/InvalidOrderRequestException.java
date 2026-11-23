package com.example.shards.order;

// 사용자 번호나 주문 금액이 잘못됐다는 것을 나타냅니다.
public class InvalidOrderRequestException extends IllegalArgumentException {

    // 오류 설명만 전달할 때 사용합니다.
    public InvalidOrderRequestException(String message) {
        super(message);
    }

    // 원래 발생한 예외도 함께 보관할 때 사용합니다.
    public InvalidOrderRequestException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}