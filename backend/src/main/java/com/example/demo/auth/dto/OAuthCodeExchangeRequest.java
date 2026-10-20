package com.example.demo.auth.dto;

import jakarta.validation.constraints.NotBlank;

public class OAuthCodeExchangeRequest {
    @NotBlank(message = "OAuth code 不能为空")
    private String code;

    @NotBlank(message = "OAuth 登录校验信息不能为空")
    @jakarta.validation.constraints.Pattern(regexp = "[A-Za-z0-9._~-]{43,128}")
    private String codeVerifier;

    public String getCodeVerifier() { return codeVerifier; }
    public void setCodeVerifier(String codeVerifier) { this.codeVerifier = codeVerifier; }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }
}
