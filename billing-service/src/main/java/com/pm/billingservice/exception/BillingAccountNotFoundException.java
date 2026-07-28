package com.pm.billingservice.exception;

import java.util.UUID;

/** Thrown when a billing-account lookup finds nothing. Mapped to HTTP 404 by the handler. */
public class BillingAccountNotFoundException extends RuntimeException {

    public BillingAccountNotFoundException(UUID id) {
        super("No billing account found with id " + id);
    }
}
