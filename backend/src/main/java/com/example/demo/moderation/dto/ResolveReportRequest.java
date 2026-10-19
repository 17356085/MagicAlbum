package com.example.demo.moderation.dto;

import com.example.demo.moderation.model.ReportAction;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class ResolveReportRequest {

    @NotNull
    private ReportAction action;

    @NotBlank
    @Size(max = 500)
    private String reason;

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public ReportAction getAction() {
        return action;
    }

    public void setAction(ReportAction action) {
        this.action = action;
    }
}
