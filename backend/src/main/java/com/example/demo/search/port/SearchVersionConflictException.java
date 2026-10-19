package com.example.demo.search.port;

/**
 * Raised when one event version is reused for a different content event.
 */
public class SearchVersionConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SearchVersionConflictException() {
        super("VERSION_CONFLICT");
    }
}
