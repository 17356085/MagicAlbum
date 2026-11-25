package com.example.chatprocess.governance;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/** Private core-to-chat moderation receiver. */
@RestController
@RequestMapping("/internal/v1/chat")
public class ChatGovernanceController {
    private final ChatGovernanceService governance;

    public ChatGovernanceController(ChatGovernanceService governance) {
        this.governance = governance;
    }

    @ModelAttribute
    public void noStore(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    @PostMapping("/report-access")
    public ResponseEntity<Void> reportAccess(@Valid @RequestBody ReportAccessRequest request) {
        governance.requireReportable(parsePositive(request.messageId(), "消息编号"),
                parsePositive(request.reporterId(), "举报人编号"));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/report-evidence")
    public ResponseEntity<ReportEvidenceResponse> reportEvidence(@Valid @RequestBody ReportEvidenceRequest request) {
        String body = governance.reportEvidence(parsePositive(request.messageId(), "消息编号"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ReportEvidenceResponse(body));
    }

    @PostMapping("/moderation-decisions")
    public ResponseEntity<ModerationDecisionReceipt> moderationDecision(
            @Valid @RequestBody ModerationDecisionRequest request) {
        long version = parsePositive(request.version(), "审核版本");
        long messageId = parsePositive(request.messageId(), "消息编号");
        long reportId = parsePositive(request.reportId(), "举报编号");
        ChatGovernanceService.Receipt receipt = governance.apply(request.decisionId(), version, messageId, reportId,
                request.hidden());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ModerationDecisionReceipt(
                receipt.decisionId(), Long.toString(receipt.version()), Long.toString(receipt.messageId()),
                Long.toString(receipt.reportId()), receipt.hidden(), receipt.outcome()));
    }

    private static long parsePositive(String raw, String field) {
        if (raw == null || !raw.matches("[0-9]+")) throw bad(field);
        try {
            long value = Long.parseLong(raw);
            if (value <= 0) throw bad(field);
            return value;
        } catch (NumberFormatException ex) {
            throw bad(field);
        }
    }

    private static ResponseStatusException bad(String field) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, field + "无效");
    }

    public record ReportAccessRequest(@NotBlank String messageId, @NotBlank String reporterId) {}

    public record ReportEvidenceRequest(@NotBlank String messageId) {}

    public record ModerationDecisionRequest(@NotNull UUID decisionId, @NotBlank String version,
                                            @NotBlank String messageId, @NotBlank String reportId,
                                            @NotNull Boolean hidden) {}

    public record ReportEvidenceResponse(String body) {}

    public record ModerationDecisionReceipt(UUID decisionId, String version, String messageId,
                                            String reportId, boolean hidden,
                                            ChatGovernanceService.Outcome outcome) {}
}
