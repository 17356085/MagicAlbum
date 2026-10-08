package com.example.demo.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class AuthCodeFinishRequest {
    @NotNull(message = "channel 不能为空")
    private AuthCodeChannel channel;

    @NotBlank(message = "address 不能为空")
    private String address;

    @NotBlank(message = "code 不能为空")
    private String code;

    @NotBlank(message = "session 不能为空")
    private String session;

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

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getSession() {
        return session;
    }

    public void setSession(String session) {
        this.session = session;
    }
}
