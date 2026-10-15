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

        ContentReport report = reportRepository.findById(reportId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "举报不存在"));
        ReportStatus resultingStatus = resultingStatus(request.getAction());

        if (report.getStatus() != null && report.getStatus() != ReportStatus.PENDING) {
            if (report.getStatus() == resultingStatus) {
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
        return toResolutionResponse(reportRepository.save(report));
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
        return action == ReportAction.HIDE ? ReportStatus.HIDDEN : ReportStatus.DISMISSED;
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
        return new ReportResponse(
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
    }

    private ReportResolutionResponse toResolutionResponse(ContentReport report) {
        return new ReportResolutionResponse(
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
    }

    private Long targetThreadId(ContentReport report) {
        if (report.getTargetType() != ReportTargetType.POST || report.getTargetId() == null) {
            return null;
        }
        return postRepository.findById(report.getTargetId())
                .filter(post -> !"DELETED".equals(post.getStatus()))
                .map(Post::getThreadId)
                .orElse(null);
    }
}
