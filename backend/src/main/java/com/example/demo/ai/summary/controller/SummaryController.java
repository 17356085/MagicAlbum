package com.example.demo.ai.summary.controller;

import com.example.demo.ai.service.AiQuotaService;
import com.example.demo.ai.summary.dto.SummaryView;
import com.example.demo.ai.summary.service.SummaryTaskService;
import com.example.demo.common.RequestIdentity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ai/summary")
public class SummaryController {
    private final SummaryTaskService tasks;
    private final AiQuotaService quota;
    public SummaryController(SummaryTaskService tasks, AiQuotaService quota) { this.tasks = tasks; this.quota = quota; }

    @GetMapping("/{threadId}")
    public SummaryView read(@PathVariable Long threadId, @AuthenticationPrincipal Long actor) { return tasks.read(threadId, actor); }

    @GetMapping("/tasks/{taskId}")
    public SummaryView task(@PathVariable String taskId, @AuthenticationPrincipal Long actor) { return tasks.readTask(taskId, actor); }

    @PostMapping("/{threadId}")
    public SummaryView start(@PathVariable Long threadId, @AuthenticationPrincipal Long actor,
                             @RequestHeader("Idempotency-Key") String key,
                             @RequestParam(defaultValue = "false") boolean force, HttpServletRequest request) {
        quota.enforceRequest(actor, RequestIdentity.clientIp(request));
        return tasks.start(threadId, actor, key, force);
    }

    @PostMapping("/{threadId}/tasks/{taskId}/cancel")
    public SummaryView cancel(@PathVariable Long threadId, @PathVariable String taskId, @AuthenticationPrincipal Long actor) {
        return tasks.cancel(threadId, taskId, actor);
    }
}
