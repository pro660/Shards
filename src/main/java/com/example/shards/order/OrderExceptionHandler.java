package com.example.shards.order;

import com.example.shards.meta.OperationBusyException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

// 주문 Controller에서 발생한 예외를 HTTP 오류 응답으로 바꿉니다.
@RestControllerAdvice(assignableTypes = OrderController.class)
public class OrderExceptionHandler {

    // 서비스가 판단한 주문 입력 오류를 처리합니다.
    @ExceptionHandler(InvalidOrderRequestException.class)
    public ProblemDetail handleInvalidOrderRequest(
            InvalidOrderRequestException exception
    ) {
        // 상태 코드와 사용자에게 보여줄 오류 설명을 담습니다.
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                exception.getMessage()
        );

        problem.setTitle("Invalid order request");

        return problem;
    }

    // JSON 문법이 틀리거나 값을 요청 객체로 변환하지 못하면 처리합니다.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableRequest(
            HttpMessageNotReadableException exception
    ) {
        // 내부 변환 오류 대신 이해하기 쉬운 안내를 반환합니다.
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "요청 JSON의 형식과 사용자 번호·금액의 자료형을 확인해 주세요."
        );

        problem.setTitle("Invalid request body");

        return problem;
    }

    @ExceptionHandler(OrderNotFoundException.class)
    public ProblemDetail handleOrderNotFound(OrderNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND,
                exception.getMessage()
        );
        problem.setTitle("Order not found");
        return problem;
    }

    @ExceptionHandler(OrderStatusConflictException.class)
    public ProblemDetail handleOrderStatusConflict(OrderStatusConflictException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                exception.getMessage()
        );
        problem.setTitle("Order status conflict");
        return problem;
    }

    @ExceptionHandler(OrderQueryUnavailableException.class)
    public ProblemDetail handleOrderQueryUnavailable() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE,
                "일부 샤드 DB에 접근할 수 없어 전체 주문을 조회할 수 없습니다."
        );
        problem.setTitle("Order query unavailable");
        return problem;
    }

    @ExceptionHandler(OperationBusyException.class)
    public ProblemDetail handleBusyOperation(OperationBusyException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
        problem.setTitle("Order operation temporarily unavailable");
        return problem;
    }

    @ExceptionHandler({DataAccessResourceFailureException.class, TransientDataAccessResourceException.class,
            CannotCreateTransactionException.class, TransactionSystemException.class})
    public ProblemDetail handleShardConnectionFailure() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.SERVICE_UNAVAILABLE, "주문을 담당하는 DB에 접근할 수 없습니다."
        );
        problem.setTitle("Order database unavailable");
        return problem;
    }
}
