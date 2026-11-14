package com.example.demo.posts.dto;

import java.time.Instant;

public record PostReadState(Long id, Long threadId, String status, Instant updatedAt) {
}
