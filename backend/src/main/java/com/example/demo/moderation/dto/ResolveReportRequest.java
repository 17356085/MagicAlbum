package com.example.demo.moderation.dto;

import com.example.demo.moderation.model.ReportAction;
import jakarta.validation.constraints.NotNull;

public class ResolveReportRequest {

    @NotNull
    private ReportAction action;

    public ReportAction getAction() {
        return action;
    }

    public void setAction(ReportAction action) {
        this.action = action;
    }
}
