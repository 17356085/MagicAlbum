package com.example.demo.moderation.service;

import com.example.demo.chat.service.ChatMessageModeration;
import com.example.demo.moderation.dto.CreateReportRequest;
import com.example.demo.moderation.dto.ReportResponse;
import com.example.demo.moderation.dto.ReportResolutionResponse;
import com.example.demo.moderation.dto.ResolveReportRequest;
import com.example.demo.moderation.entity.ContentReport;
import com.example.demo.moderation.model.ReportAction;
import com.example.demo.moderation.model.ReportStatus;
import com.example.demo.moderation.model.ReportTargetType;
import com.example.demo.posts.service.PostService;
import com.example.demo.threads.service.ContentReportTargets;
import com.example.demo.threads.service.ThreadService;
import com.example.demo.moderation.repo.ContentReportRepository;
import com.example.demo.governance.service.GovernanceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Objects;

@Service
public class ContentReportService {
    private GovernanceService governance;
    private ChatMessageModeration chatModeration;
    private ChatModerationClient remoteModeration;
    private ChatReportDecisionStore chatDecisions;

    @Autowired
    public void setGovernanceService(GovernanceService governance) { this.governance = governance; }

    @Autowired
    public void setChatModeration(ChatMessageModeration chatModeration) { this.chatModeration = chatModeration; }

    @Autowired(required = false)
    void configureRemoteModeration(ChatModerationClient client, ChatReportDecisionStore decisions) {
        this.remoteModeration = client;
        this.chatDecisions = decisions;
    }

    private static final int MAX_PAGE_SIZE = 50;

    private final ContentReportRepository reportRepository;
    private final ContentReportTargets targets;
    private final ThreadService threadService;
    private final PostService postService;

    public ContentReportService(ContentReportRepository reportRepository,
                                ContentReportTargets targets,
                                ThreadService threadService,
                                PostService postService) {
        this.reportRepository = reportRepository;
        this.targets = targets;
        this.threadService = threadService;
        this.postService = postService;
    }

    @Transactional
    public ReportResponse create(Long reporterId, CreateReportRequest request) {
        requireAuthenticated(reporterId);
        if (request == null || request.getTargetType() == null || request.getTargetId() == null
                || request.getTargetId() <= 0 || request.getReason() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "举报参数不合法");
        }
        if (request.getDetail() != null && request.getDetail().length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "举报详情不能超过500字");
        }

        // A replay of the same idempotency key returns the original metadata, even if
        // the target changed after the report was first accepted.
        // Serialize same-reporter requests before the first lookup, so MySQL's
        // consistent snapshot starts after a competing insert has committed.
        if (governance != null) governance.lockActor(reporterId);
        ContentReport existing = reportRepository
                .findByReporterIdAndTargetTypeAndTargetId(
                        reporterId, request.getTargetType(), request.getTargetId())
                .orElse(null);
        if (existing != null) {
            return toResponse(existing);
        }

        if (request.getTargetType() == ReportTargetType.CHAT_MESSAGE) {
            requireReportableChat(reporterId, request.getTargetId());
        } else {
            requireNormalTarget(request.getTargetType(), request.getTargetId());
        }

        ContentReport report = new ContentReport();
        report.setReporterId(reporterId);
        report.setTargetType(request.getTargetType());
        report.setTargetId(request.getTargetId());
        report.setReason(request.getReason());
        report.setDetail(normalizeDetail(request.getDetail()));
        report.setStatus(ReportStatus.PENDING);
        return toResponse(reportRepository.save(report));
    }

    @Transactional(readOnly = true)
    public Page<ReportResponse> list(ReportStatus status, int page, int size) {
        ReportStatus actualStatus = status == null ? ReportStatus.PENDING : status;
        int actualPage = Math.max(page, 1);
        int actualSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Sort sort = Sort.by(
                Sort.Order.desc("createdAt"),
                Sort.Order.desc("id")
        );
        Page<ContentReport> reports = reportRepository.findByStatus(
                actualStatus,
                PageRequest.of(actualPage - 1, actualSize, sort)
        );
        return reports.map(this::toResponse);
    }

    @Transactional
    public ReportResolutionResponse resolve(Long reviewerId, Long reportId, ResolveReportRequest request) {
        requireAuthenticated(reviewerId);
        if (reportId == null || reportId <= 0 || request == null || request.getAction() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "处理参数不合法");
        }

        String reason = request.getReason() == null ? "" : request.getReason().trim();
        if (reason.isEmpty() || reason.length() > 500) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "处理理由需为1至500字");
        if (governance != null) {
            governance.lockPolicy();
            governance.requireManager(reviewerId);
        }

        ContentReport report = reportRepository.findByIdForUpdate(reportId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "举报不存在"));
        if (report.getTargetType() == ReportTargetType.CHAT_MESSAGE) {
            if (governance == null || !governance.isAdmin(reviewerId) || chatModeration == null) throw targetNotFound();
            if (remoteModeration != null) return resolveRemoteChat(reviewerId, report, request.getAction(), reason);
        } else if (governance != null) {
            lockAndCheckScope(reviewerId, report, request.getAction());
        }
        ReportStatus resultingStatus = resultingStatus(request.getAction());

        if (request.getAction() == ReportAction.RESTORE) {
            if (report.getStatus() == ReportStatus.RESTORED && java.util.Objects.equals(reason, report.getResolutionReason())) {
                return toResolutionResponse(report);
            }
            if (report.getStatus() != ReportStatus.HIDDEN) throw new ResponseStatusException(HttpStatus.CONFLICT, "只能恢复审核隐藏的内容");
            restoreTarget(report);
        } else if (report.getStatus() != null && report.getStatus() != ReportStatus.PENDING) {
            if (report.getStatus() == resultingStatus && java.util.Objects.equals(reason, report.getResolutionReason())) {
                return toResolutionResponse(report);
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "举报已经被处理");
        }

        if (request.getAction() == ReportAction.HIDE) {
            hideTarget(report);
        }

        return completeResolution(report, reviewerId, request.getAction(), reason);
    }

    private void requireReportableChat(long reporter, long message) {
        if (remoteModeration != null) remoteModeration.requireReportable(reporter, message);
        else if (chatModeration != null) chatModeration.requireReportable(reporter, message);
        else throw targetNotFound();
    }

    private ReportResolutionResponse resolveRemoteChat(long reviewer, ContentReport report, ReportAction action, String reason) {
        var pending = chatDecisions.pendingForReport(report.getId()).orElse(null);
        if (pending != null) {
            if (pending.reviewerId() != reviewer || action != reportAction(pending) || !reason.equals(pending.reason())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "已有待聊天服务确认的审核操作");
            }
            return pendingResponse(report, action);
        }
        if (report.getStatus() == resultingStatus(action) && Objects.equals(reason, report.getResolutionReason())) {
            return toResolutionResponse(report);
        }
        ReportStatus required = action == ReportAction.RESTORE ? ReportStatus.HIDDEN : ReportStatus.PENDING;
        if (report.getStatus() != required) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, action == ReportAction.RESTORE
                    ? "只能恢复审核隐藏的内容" : "举报已经被处理");
        }
        if (action == ReportAction.DISMISS) return completeResolution(report, reviewer, action, reason);
        chatDecisions.enqueue(report.getId(), report.getTargetId(), reviewer, action == ReportAction.HIDE, reason);
        return pendingResponse(report, action);
    }

    @Transactional
    public void confirmChatDecision(ChatReportDecisionStore.Decision decision) {
        governance.lockPolicy();
        ContentReport report = reportRepository.findByIdForUpdate(decision.reportId()).orElseThrow();
        if (!chatDecisions.confirm(decision.decisionId())) return;
        ReportStatus required = decision.hidden() ? ReportStatus.PENDING : ReportStatus.HIDDEN;
        if (report.getStatus() != required || report.getTargetType() != ReportTargetType.CHAT_MESSAGE
                || !Objects.equals(report.getTargetId(), decision.messageId())) {
            throw new IllegalStateException("Chat moderation confirmation did not match the pending case");
        }
        completeResolution(report, decision.reviewerId(), reportAction(decision), decision.reason());
    }

    private ReportResolutionResponse pendingResponse(ContentReport report, ReportAction action) {
        ReportResolutionResponse response = toResolutionResponse(report);
        response.setPendingAction(action);
        return response;
    }

    private static ReportAction reportAction(ChatReportDecisionStore.Decision decision) {
        return decision.hidden() ? ReportAction.HIDE : ReportAction.RESTORE;
    }

    private ReportResolutionResponse completeResolution(ContentReport report, long reviewerId, ReportAction action, String reason) {
        report.setReviewerId(reviewerId);
        report.setResolvedAt(Instant.now());
        report.setStatus(resultingStatus(action));
        report.setResolutionReason(reason);
        if (governance != null) governance.audit(reviewerId, "REPORT_" + action.name(),
                report.getTargetType().name(), report.getTargetId(), reason);
        return toResolutionResponse(reportRepository.save(report));
    }

    @Transactional(readOnly = true)
    public Page<ReportResponse> listForReviewer(Long actor, ReportStatus status, int page, int size) {
        if (governance == null) return list(status, page, size);
        governance.requireManager(actor);
        var access = governance.access(actor);
        if (access.admin()) return list(status, page, size).map(report -> {
            if (report.getTargetType() == ReportTargetType.CHAT_MESSAGE && chatModeration != null) {
                addChatEvidence(report);
            }
            return report;
        });
        if (access.moderatedSectionIds().isEmpty()) return Page.empty();
        var pageable = PageRequest.of(Math.max(page, 1) - 1, Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        return reportRepository.findScoped((status == null ? ReportStatus.PENDING : status).name(),
                access.moderatedSectionIds(), pageable).map(this::toResponse);
    }

    private void addChatEvidence(ReportResponse report) {
        if (remoteModeration == null) {
            report.setChatEvidence(chatModeration.reportEvidence(report.getTargetId()));
            return;
        }
        chatDecisions.pendingForReport(report.getId()).ifPresent(decision -> report.setPendingAction(reportAction(decision)));
        try {
            report.setChatEvidence(remoteModeration.evidence(report.getTargetId()));
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode() != HttpStatus.SERVICE_UNAVAILABLE) throw exception;
            report.setChatEvidenceUnavailable(true);
        }
    }

    private void lockAndCheckScope(Long actor, ContentReport report, ReportAction action) {
        Long sectionId = targets.lockTargetSection(report.getTargetId(),
                report.getTargetType() != ReportTargetType.THREAD).orElse(null);
        if (sectionId == null) {
            if (action == ReportAction.DISMISS && governance.isAdmin(actor)) return;
            throw targetNotFound();
        }
        governance.requireScope(actor, sectionId);
    }

    private void restoreTarget(ContentReport report) {
        if (report.getTargetType() == ReportTargetType.THREAD) threadService.restoreForModeration(report.getTargetId());
        else if (report.getTargetType() == ReportTargetType.POST) postService.restoreForModeration(report.getTargetId());
        else if (report.getTargetType() == ReportTargetType.CHAT_MESSAGE) chatModeration.setMessageHidden(report.getTargetId(), false);
        else throw targetNotFound();
    }

    private void requireNormalTarget(ReportTargetType targetType, Long targetId) {
        boolean reportable = targetType == ReportTargetType.THREAD
                ? targets.isThreadReportable(targetId) : targets.isPostReportable(targetId);
        if (!reportable) {
            throw targetNotFound();
        }
    }

    private void hideTarget(ContentReport report) {
        if (report.getTargetType() == ReportTargetType.CHAT_MESSAGE) {
            chatModeration.setMessageHidden(report.getTargetId(), true);
            return;
        }
        if (report.getTargetType() == ReportTargetType.THREAD) {
            threadService.hideForModeration(report.getTargetId());
            return;
        }
        if (report.getTargetType() == ReportTargetType.POST) {
            postService.hideForModeration(report.getTargetId());
            return;
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "举报目标不存在");
    }

    private ReportStatus resultingStatus(ReportAction action) {
        return switch (action) {
            case HIDE -> ReportStatus.HIDDEN;
            case DISMISS -> ReportStatus.DISMISSED;
            case RESTORE -> ReportStatus.RESTORED;
        };
    }

    private String normalizeDetail(String detail) {
        if (detail == null) {
            return null;
        }
        String normalized = detail.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private void requireAuthenticated(Long userId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
    }

    private ResponseStatusException targetNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "举报目标不存在");
    }

    private ReportResponse toResponse(ContentReport report) {
        ReportResponse response = new ReportResponse(
                report.getId(),
                report.getReporterId(),
                report.getTargetType(),
                report.getTargetId(),
                report.getReason(),
                report.getDetail(),
                report.getStatus(),
                report.getCreatedAt(),
                targetThreadId(report)
        );
        response.setResolutionReason(report.getResolutionReason());
        return response;
    }

    private ReportResolutionResponse toResolutionResponse(ContentReport report) {
        ReportResolutionResponse response = new ReportResolutionResponse(
                report.getId(),
                report.getReporterId(),
                report.getTargetType(),
                report.getTargetId(),
                report.getReason(),
                report.getDetail(),
                report.getStatus(),
                report.getCreatedAt(),
                targetThreadId(report),
                report.getReviewerId(),
                report.getResolvedAt()
        );
        response.setResolutionReason(report.getResolutionReason());
        return response;
    }

    private Long targetThreadId(ContentReport report) {
        if (report.getTargetType() != ReportTargetType.POST || report.getTargetId() == null) {
            return null;
        }
        return targets.findPostThreadId(report.getTargetId()).orElse(null);
    }
}
