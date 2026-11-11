package com.example.demo.threads.dto;

import java.time.Instant;

public record ThreadReadState(Long id, String status, Instant updatedAt) {
}
