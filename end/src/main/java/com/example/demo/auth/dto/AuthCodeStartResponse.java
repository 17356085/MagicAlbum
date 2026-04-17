package com.example.demo.auth.dto;

public class AuthCodeStartResponse {
    private AuthCodeChannel channel;
    private String maskedAddress;
    private String session;
    private long expireSeconds;
    private long cooldownSeconds;

    public AuthCodeStartResponse() {
    }

    public AuthCodeStartResponse(AuthCodeChannel channel, String maskedAddress, String session, long expireSeconds, long cooldownSeconds) {
        this.channel = channel;
        this.maskedAddress = maskedAddress;
        this.session = session;
        this.expireSeconds = expireSeconds;
        this.cooldownSeconds = cooldownSeconds;
    }

    public AuthCodeChannel getChannel() {
        return channel;
    }

    public void setChannel(AuthCodeChannel channel) {
        this.channel = channel;
    }

    public String getMaskedAddress() {
        return maskedAddress;
    }

    public void setMaskedAddress(String maskedAddress) {
        this.maskedAddress = maskedAddress;
    }

    public String getSession() {
        return session;
    }

    public void setSession(String session) {
        this.session = session;
    }

    public long getExpireSeconds() {
        return expireSeconds;
    }

    public void setExpireSeconds(long expireSeconds) {
        this.expireSeconds = expireSeconds;
    }

    public long getCooldownSeconds() {
        return cooldownSeconds;
    }

    public void setCooldownSeconds(long cooldownSeconds) {
        this.cooldownSeconds = cooldownSeconds;
    }
}
