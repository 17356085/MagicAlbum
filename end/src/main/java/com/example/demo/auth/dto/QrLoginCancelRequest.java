package com.example.demo.auth.dto;

import jakarta.validation.constraints.NotBlank;

public class QrLoginCancelRequest {
    @NotBlank(message = "qrId 不能为空")
    private String qrId;

    public String getQrId() {
        return qrId;
    }

    public void setQrId(String qrId) {
        this.qrId = qrId;
    }
}
