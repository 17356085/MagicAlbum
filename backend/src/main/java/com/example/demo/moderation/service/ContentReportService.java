package com.example.demo.moderation.service;

import com.example.demo.moderation.dto.CreateReportRequest;
import com.example.demo.moderation.dto.ReportResponse;
import com.example.demo.moderation.dto.ReportResolutionResponse;
import com.example.demo.moderation.dto.ResolveReportRequest;
import com.example.demo.moderation.entity.ContentReport;
import com.example.demo.moderation.model.ReportAction;
import com.example.demo.moderation.model.ReportStatus;
import com.example.demo.moderation.model.ReportTargetType;
import com.example.demo.posts.entity.Post;
import com.example.demo.posts.repo.PostRepository;
import com.example.demo.posts.service.PostService;
import com.example.demo.threads.entity.Thread;
import com.example.demo.threads.repo.ThreadRepository;
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

@Service
public class ContentReportService {
    private GovernanceService governance;

    @Autowired
    public void setGovernanceService(GovernanceService governance) { this.governance = governance; }

    private static final String NORMAL_STATUS = "NORMAL";
    private static final int MAX_PAGE_SIZE = 50;

    private final ContentReportRepository reportRepository;
    private final ThreadRepository threadRepository;
    private final PostRepository postRepository;
    private final ThreadService threadService;
    private final PostService postService;

    public ContentReportService(ContentReportRepository reportRepository,
                                ThreadRepository threadRepository,
                                PostRepository postRepository,
                                ThreadService threadService,
                                PostService postService) {
        this.reportRepository = reportRepository;
        this.threadRepository = threadRepository;
        this.postRepository = postRepository;
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

        requireNormalTarget(request.getTargetType(), request.getTargetId());

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
        if (governance != null) lockAndCheckScope(reviewerId, report, request.getAction());
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

        report.setReviewerId(reviewerId);
        report.setResolvedAt(Instant.now());
        report.setStatus(resultingStatus);
        report.setResolutionReason(reason);
        if (governance != null) governance.audit(reviewerId, "REPORT_" + request.getAction().name(),
                report.getTargetType().name(), report.getTargetId(), reason);
        return toResolutionResponse(reportRepository.save(report));
    }

    @Transactional(readOnly = true)
    public Page<ReportResponse> listForReviewer(Long actor, ReportStatus status, int page, int size) {
        if (governance == null) return list(status, page, size);
        governance.requireManager(actor);
        var access = governance.access(actor);
        if (access.admin()) return list(status, page, size);
        if (access.moderatedSectionIds().isEmpty()) return Page.empty();
        var pageable = PageRequest.of(Math.max(page, 1) - 1, Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        return reportRepository.findScoped((status == null ? ReportStatus.PENDING : status).name(),
                access.moderatedSectionIds(), pageable).map(this::toResponse);
    }

    private void lockAndCheckScope(Long actor, ContentReport report, ReportAction action) {
        Long threadId = report.getTargetType() == ReportTargetType.THREAD ? report.getTargetId()
                : postRepository.findThreadIdByPostId(report.getTargetId()).orElse(null);
        if (threadId == null && action == ReportAction.DISMISS && governance.isAdmin(actor)) return;
        Thread thread = threadId == null ? null : threadRepository.findByIdForInteraction(threadId).orElse(null);
        if (thread == null) {
            if (action == ReportAction.DISMISS && governance.isAdmin(actor)) return;
            throw targetNotFound();
        }
        governance.requireScope(actor, thread.getSectionId());
    }

    private void restoreTarget(ContentReport report) {
        if (report.getTargetType() == ReportTargetType.THREAD) threadService.restoreForModeration(report.getTargetId());
        else if (report.getTargetType() == ReportTargetType.POST) postService.restoreForModeration(report.getTargetId());
        else throw targetNotFound();
    }

    private void requireNormalTarget(ReportTargetType targetType, Long targetId) {
        if (targetType == ReportTargetType.THREAD) {
            Thread thread = threadRepository.findById(targetId).orElse(null);
            if (thread == null || !NORMAL_STATUS.equals(thread.getStatus())) {
                throw targetNotFound();
            }
            return;
        }

        Post post = postRepository.findById(targetId).orElse(null);
        if (post == null || !NORMAL_STATUS.equals(post.getStatus())) {
            throw targetNotFound();
        }
        Thread parentThread = threadRepository.findById(post.getThreadId()).orElse(null);
        if (parentThread == null || !NORMAL_STATUS.equals(parentThread.getStatus())) {
            throw targetNotFound();
        }
    }

    private void hideTarget(ContentReport report) {
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
        return postRepository.findById(report.getTargetId())
                .map(Post::getThreadId)
                .orElse(null);
    }
}
