package com.example.demo.admin.controller;

import com.example.demo.admin.security.AdminAccessService;
import com.example.demo.threads.event.service.ContentKafkaOutbox;
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
@RequestMapping("/api/v1/admin/content-events/kafka")
@ConditionalOnProperty(name = {"app.content.events.enabled", "app.content.events.kafka.enabled"}, havingValue = "true")
public class KafkaContentEventAdminController {
    private final AdminAccessService access;
    private final ContentKafkaOutbox outbox;

    public KafkaContentEventAdminController(AdminAccessService access, ContentKafkaOutbox outbox) {
        this.access = access;
        this.outbox = outbox;
    }

    @GetMapping("/diagnostics")
    public ContentKafkaOutbox.Diagnostics diagnostics(Authentication authentication) {
        requireAdmin(authentication);
        return outbox.diagnostics();
    }

    @PostMapping("/{id}/replay")
    public ResponseEntity<Void> replay(@PathVariable long id, Authentication authentication) {
        requireAdmin(authentication);
        if (!outbox.replayFailed(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "待重放 Kafka 事件不存在");
        }
        return ResponseEntity.noContent().build();
    }

    private void requireAdmin(Authentication authentication) {
        Long userId = authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof Number number ? number.longValue() : null;
        access.requireAdmin(userId);
    }
}
