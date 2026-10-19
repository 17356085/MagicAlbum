package com.example.demo.governance.dto;

import java.time.Instant;

public record PostingRestrictionRequest(Boolean restricted, Instant until, String reason) {
}
