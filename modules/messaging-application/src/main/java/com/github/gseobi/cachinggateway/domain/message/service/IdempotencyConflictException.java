package com.github.gseobi.cachinggateway.domain.message.service;

public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException() {
        super("Idempotency-Key was used for a different request");
    }
}
