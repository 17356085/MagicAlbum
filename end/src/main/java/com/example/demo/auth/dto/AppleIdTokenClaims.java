package com.example.demo.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class AppleIdTokenClaims {
    private String iss;
    private String aud;
    private String sub;
    private String email;

    @JsonProperty("email_verified")
    private Boolean emailVerified;

    @JsonProperty("is_private_email")
    private Boolean privateEmail;

    public String getIss() {
        return iss;
    }

    public void setIss(String iss) {
        this.iss = iss;
    }

    public String getAud() {
        return aud;
    }

    public void setAud(String aud) {
        this.aud = aud;
    }

    public String getSub() {
        return sub;
    }

    public void setSub(String sub) {
        this.sub = sub;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Boolean getEmailVerified() {
        return emailVerified;
    }

    public void setEmailVerified(Boolean emailVerified) {
        this.emailVerified = emailVerified;
    }

    public Boolean getPrivateEmail() {
        return privateEmail;
    }

    public void setPrivateEmail(Boolean privateEmail) {
        this.privateEmail = privateEmail;
    }
}
