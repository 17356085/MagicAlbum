package com.example.demo.common;

import java.util.List;
import org.springframework.data.domain.Page;

/** Stable public pagination contract; page is one based. */
public record PageResponse<T>(List<T> items, int page, int size, long total) {
    public PageResponse {
        items = List.copyOf(items);
        if (page < 1 || size < 1 || total < 0) {
            throw new IllegalArgumentException("Invalid pagination metadata");
        }
    }

    public static <T> PageResponse<T> from(Page<T> result) {
        return new PageResponse<>(result.getContent(), result.getNumber() + 1,
                result.getSize(), result.getTotalElements());
    }
}
