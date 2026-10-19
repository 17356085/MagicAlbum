package com.example.demo.storage.dto;

/** Permanent, worker-reported processing failure. */
public record MediaFailureRequest(int jobVersion, String leaseToken, String errorCode) {
}
