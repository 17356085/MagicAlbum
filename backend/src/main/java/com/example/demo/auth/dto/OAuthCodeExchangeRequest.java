package com.example.demo.auth.dto;

import jakarta.validation.constraints.NotBlank;

public class OAuthCodeExchangeRequest {
    @NotBlank(message = "OAuth code 不能为空")
    private String code;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }
}
