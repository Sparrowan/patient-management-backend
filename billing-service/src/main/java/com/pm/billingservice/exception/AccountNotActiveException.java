package com.pm.billingservice.exception;

import com.pm.billingservice.model.AccountStatus;
import java.util.UUID;

/**
 * Thrown when money movement is attempted on an account that is not {@code ACTIVE} (e.g. it has been
 * {@code CLOSED} or {@code SUSPENDED}). Mapped to HTTP 409 by the handler.
 */
public class AccountNotActiveException extends RuntimeException {

    public AccountNotActiveException(UUID accountId, AccountStatus status) {
        super("Account " + accountId + " is not active (status " + status
                + "); money movement is not allowed");
    }
}
