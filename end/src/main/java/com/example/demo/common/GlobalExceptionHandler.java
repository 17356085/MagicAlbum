package com.example.demo.common;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = "请求参数不合法";
        FieldError fieldError = ex.getBindingResult().getFieldError();
        if (fieldError != null && fieldError.getDefaultMessage() != null) {
            message = fieldError.getDefaultMessage();
        }
        return build(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(ConstraintViolationException ex) {
        String message = ex.getMessage() == null ? "请求参数不合法" : ex.getMessage();
        return build(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        String message = resolveDataIntegrityMessage(ex);
        log.warn("Data integrity violation mapped to business error: {}", message, ex);
        return build(HttpStatus.CONFLICT, message);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException ex) {
        String message = ex.getReason() == null ? "请求失败" : ex.getReason();
        if (ex.getStatusCode().is5xxServerError()) {
            log.error("Request failed with status={} message={}", ex.getStatusCode().value(), message, ex);
        } else {
            log.debug("Request failed with status={} message={}", ex.getStatusCode().value(), message);
        }
        return build(HttpStatus.valueOf(ex.getStatusCode().value()), message);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnknown(Exception ex) {
        log.error("Unhandled server exception", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误");
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("status", status.value());
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }

    private String resolveDataIntegrityMessage(DataIntegrityViolationException ex) {
        String raw = extractRootMessage(ex).toLowerCase();

        if (raw.contains("users_email_uq")) {
            if (raw.contains("@oauth.local")) {
                return "第三方登录关联本地账号时发生邮箱冲突，请稍后重试或联系管理员处理历史账号数据";
            }
            return "该邮箱已被占用";
        }

        if (raw.contains("users_phone_uq") || raw.contains("users_phone_idx")) {
            return "该手机号已被占用";
        }

        if (raw.contains("users_username") || raw.contains("username")) {
            return "该用户名已被占用";
        }

        if (raw.contains("uk_user_connected_accounts_user_provider")) {
            return "当前账号已绑定该第三方登录方式";
        }

        if (raw.contains("uk_user_connected_accounts_provider_external")) {
            return "该第三方账号已绑定到其他本地账号";
        }

        return "数据写入冲突，请稍后重试";
    }

    private String extractRootMessage(Throwable ex) {
        Throwable current = ex;
        Throwable root = ex;
        while (current != null) {
            root = current;
            current = current.getCause();
        }
        String message = root == null ? null : root.getMessage();
        if (message == null || message.isBlank()) {
            message = ex == null ? null : ex.getMessage();
        }
        return message == null ? "" : message;
    }
}
