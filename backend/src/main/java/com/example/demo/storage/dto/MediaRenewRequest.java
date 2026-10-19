package com.example.demo.storage.dto;

/** Request to extend the currently held media processing lease. */
public record MediaRenewRequest(int jobVersion, String leaseToken) {
}
