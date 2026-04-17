package com.example.demo.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class CognitoAuthenticationResult {
    @JsonProperty("AccessToken")
    private String accessToken;

    @JsonProperty("IdToken")
    private String idToken;

    @JsonProperty("RefreshToken")
    private String refreshToken;

    @JsonProperty("TokenType")
    private String tokenType;

    @JsonProperty("ExpiresIn")
    private Long expiresIn;

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getIdToken() {
        return idToken;
    }

    public void setIdToken(String idToken) {
        this.idToken = idToken;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }

    public String getTokenType() {
        return tokenType;
    }

    public void setTokenType(String tokenType) {
        this.tokenType = tokenType;
    }

    public Long getExpiresIn() {
        return expiresIn;
    }

    public void setExpiresIn(Long expiresIn) {
        this.expiresIn = expiresIn;
    }
}
