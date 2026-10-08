package com.example.demo.auth.dto;

import java.time.OffsetDateTime;

public class QrLoginCreateResponse {
    private String qrId;
    private String pollToken;
    private String qrUrl;
    private OffsetDateTime expiresAt;
    private QrLoginStatus status;

    public String getQrId() {
        return qrId;
    }

    public void setQrId(String qrId) {
        this.qrId = qrId;
    }

    public String getPollToken() {
        return pollToken;
    }

    public void setPollToken(String pollToken) {
        this.pollToken = pollToken;
    }

    public String getQrUrl() {
        return qrUrl;
    }

    public void setQrUrl(String qrUrl) {
        this.qrUrl = qrUrl;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(OffsetDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public QrLoginStatus getStatus() {
        return status;
    }

    public void setStatus(QrLoginStatus status) {
        this.status = status;
    }
}
