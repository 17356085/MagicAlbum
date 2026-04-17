package com.example.demo.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class GoogleUserProfile {
    private String sub;
    private String name;
    private String email;

    @JsonProperty("email_verified")
    private Boolean emailVerified;

    private String picture;

    public String getSub() {
        return sub;
    }

    public void setSub(String sub) {
        this.sub = sub;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
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

    public String getPicture() {
        return picture;
    }

    public void setPicture(String picture) {
        this.picture = picture;
    }
}
