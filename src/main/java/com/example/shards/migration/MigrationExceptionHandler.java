package com.example.shards.migration;

import com.example.shards.meta.OperationBusyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes = MigrationController.class)
public class MigrationExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(MigrationExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> accessDenied(ResponseStatusException exception) {
        HttpHeaders headers = new HttpHeaders();
        if (exception.getStatusCode().equals(HttpStatus.UNAUTHORIZED)) {
            headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        return new ResponseEntity<>(exception.getBody(), headers, exception.getStatusCode());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail invalid(IllegalArgumentException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(MigrationJobNotFoundException.class)
    public ProblemDetail missing(MigrationJobNotFoundException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail conflict(IllegalStateException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler({OperationBusyException.class, PessimisticLockingFailureException.class})
    public ProblemDetail busy() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "다른 작업이 진행 중입니다.");
    }

    @ExceptionHandler({DataAccessException.class, CannotCreateTransactionException.class, TransactionSystemException.class})
    public ProblemDetail databaseError(RuntimeException exception) {
        log.warn("Migration database request failed ({})", exception.getClass().getSimpleName());
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "재배치에 필요한 DB 작업에 실패했습니다.");
    }
}
