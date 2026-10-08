package com.example.demo.auth.dto;

import com.example.demo.user.dto.UserDto;

public class LoginResponse {
    private String accessToken;
    private UserDto user;

    public LoginResponse() {}
    public LoginResponse(String accessToken, UserDto user) {
        this.accessToken = accessToken;
        this.user = user;
    }

    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }

    public UserDto getUser() { return user; }
    public void setUser(UserDto user) { this.user = user; }
}