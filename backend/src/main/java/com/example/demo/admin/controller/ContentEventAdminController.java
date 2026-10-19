package com.example.demo.admin.controller;

import com.example.demo.admin.security.AdminAccessService;
import com.example.demo.threads.event.service.ContentEventOutboxService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin/content-events")
@ConditionalOnProperty(name = "app.content.events.enabled", havingValue = "true")
public class ContentEventAdminController {
    private final AdminAccessService access;
    private final ContentEventOutboxService outbox;

    public ContentEventAdminController(AdminAccessService access, ContentEventOutboxService outbox) {
        this.access = access;
        this.outbox = outbox;
    }

    @GetMapping("/diagnostics")
    public ContentEventOutboxService.Diagnostics diagnostics(Authentication authentication) {
        access.requireAdmin(userId(authentication));
        return outbox.getDiagnostics();
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<Void> retry(@PathVariable Long id, Authentication authentication) {
        access.requireAdmin(userId(authentication));
        if (!outbox.manualRetry(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "可重试内容事件不存在");
        }
        return ResponseEntity.noContent().build();
    }

    private Long userId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        return authentication.getPrincipal() instanceof Number number ? number.longValue() : null;
    }
}
