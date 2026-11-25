package com.example.shards.meta;

public final class OperationBusyException extends IllegalStateException {

    public OperationBusyException(String message) {
        super(message);
    }
}
