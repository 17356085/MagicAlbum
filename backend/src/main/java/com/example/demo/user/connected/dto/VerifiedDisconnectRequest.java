package com.example.demo.user.connected.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record VerifiedDisconnectRequest(
        @NotBlank @Size(max = 255) String identifier,
        @NotBlank @Size(max = 1024) String currentPassword,
        @NotBlank @Size(min = 64, max = 64) @Pattern(regexp = "^[0-9a-f]{64}$") String bindingTag
) { }
