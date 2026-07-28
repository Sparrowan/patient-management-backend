package com.pm.billingservice.exception;

import java.util.UUID;

/** Thrown when opening a second billing account for a patient that already has one. HTTP 409. */
public class AccountAlreadyExistsException extends RuntimeException {

    public AccountAlreadyExistsException(UUID patientId) {
        super("A billing account already exists for patient " + patientId);
    }
}
