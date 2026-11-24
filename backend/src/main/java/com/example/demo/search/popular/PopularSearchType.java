package com.example.demo.search.popular;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.Arrays;

public enum PopularSearchType {
    THREADS("threads"),
    USERS("users");

    private final String value;

    PopularSearchType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static PopularSearchType parse(String raw) {
        if (raw == null) {
            throw new InvalidPopularSearchTypeException(raw);
        }
        return Arrays.stream(values())
                .filter(type -> type.value.equalsIgnoreCase(raw.trim()))
                .findFirst()
                .orElseThrow(() -> new InvalidPopularSearchTypeException(raw));
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public static final class InvalidPopularSearchTypeException extends RuntimeException {
        public InvalidPopularSearchTypeException(String raw) {
            super("type must be threads or users");
        }
    }
}
