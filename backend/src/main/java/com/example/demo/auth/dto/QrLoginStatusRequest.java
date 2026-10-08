package com.example.demo.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record QrLoginStatusRequest(
        @NotBlank String qrId,
        @NotBlank String pollToken
) {
}
