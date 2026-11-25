package com.example.shards.order;

final class OrderQueryUnavailableException extends RuntimeException {

    OrderQueryUnavailableException(Throwable cause) {
        super("A shard database is unavailable for the order query", cause);
    }
}
