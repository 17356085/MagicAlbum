package com.example.demo.storage.dto;

import java.time.Instant;

/** New expiry returned after a successful lease renewal. */
public record MediaRenewResponse(Instant leaseUntil) {
}
