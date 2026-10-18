package com.example.demo.common;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;

import java.sql.SQLException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerPhoneConflictTest {

    @Test
    void maps_phone_unique_constraint_to_sanitized_conflict_response() {
        String phone = "13800138000";
        DataIntegrityViolationException exception = new DataIntegrityViolationException(
                "could not execute statement",
                new SQLException("Duplicate entry '" + phone + "' for key 'users_phone_uq'")
        );

        ResponseEntity<Map<String, Object>> response = new GlobalExceptionHandler()
                .handleDataIntegrityViolation(exception);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody())
                .containsEntry("status", 409)
                .containsEntry("message", "该手机号已被占用");
        assertThat(String.valueOf(response.getBody()))
                .doesNotContain(phone)
                .doesNotContain("Duplicate entry")
                .doesNotContain("users_phone_uq");
    }
}
