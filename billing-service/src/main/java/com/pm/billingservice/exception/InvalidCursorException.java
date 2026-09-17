package com.pm.billingservice.exception;

/**
 * Thrown when a pagination {@code cursor} can't be decoded — a client hand-built or corrupted the
 * opaque token. Mapped to HTTP 400 (it's a bad request parameter, not a server fault).
 */
public class InvalidCursorException extends RuntimeException {

    public InvalidCursorException(Throwable cause) {
        super("The pagination cursor is invalid. Omit it for the first page, or use the nextCursor "
                + "from a previous response.", cause);
    }
}
