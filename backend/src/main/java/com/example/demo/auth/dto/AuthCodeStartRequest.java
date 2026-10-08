package com.example.demo.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class AuthCodeStartRequest {
    @NotNull(message = "channel 不能为空")
    private AuthCodeChannel channel;

    @NotBlank(message = "address 不能为空")
    private String address;

    private String verifyToken;
    private String verifyProvider;
    private String verifyScene;

    public AuthCodeChannel getChannel() {
        return channel;
    }

    public void setChannel(AuthCodeChannel channel) {
        this.channel = channel;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getVerifyToken() {
        return verifyToken;
    }

    public void setVerifyToken(String verifyToken) {
        this.verifyToken = verifyToken;
    }

    public String getVerifyProvider() {
        return verifyProvider;
    }

    public void setVerifyProvider(String verifyProvider) {
        this.verifyProvider = verifyProvider;
    }

    public String getVerifyScene() {
        return verifyScene;
    }

    public void setVerifyScene(String verifyScene) {
        this.verifyScene = verifyScene;
    }
}
