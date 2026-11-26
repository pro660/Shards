package com.example.shards.order;

final class OrderQueryUnavailableException extends RuntimeException {

    OrderQueryUnavailableException(String reason) {
        super(reason);
    }

    OrderQueryUnavailableException(Throwable cause) {
        super("A shard database is unavailable for the order query", cause);
    }
}
