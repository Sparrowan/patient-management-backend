package com.pm.authservice.web;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.pm.authservice.exception.EmailAlreadyExistsException;
import com.pm.authservice.exception.UsernameAlreadyExistsException;

/**
 * Central error handling as RFC 7807 {@link ProblemDetail} (matching the other services). Extends
 * {@link ResponseEntityExceptionHandler} so framework errors — including bean-validation 400s — come
 * back as ProblemDetail too.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail handleAuthentication(AuthenticationException ex) {
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        problem.setTitle("Authentication failed");
        return problem;
    }

    @ExceptionHandler(UsernameAlreadyExistsException.class)
    public ProblemDetail handleUsernameExists(UsernameAlreadyExistsException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Username already in use");
        return problem;
    }

    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ProblemDetail handleEmailExists(EmailAlreadyExistsException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Email already in use");
        return problem;
    }

    /** Catches the unique-constraint race (two concurrent signups) that slips past the existsBy checks. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrity(DataIntegrityViolationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "The request conflicts with existing data (a unique value is already in use).");
        problem.setTitle("Data conflict");
        return problem;
    }
}
