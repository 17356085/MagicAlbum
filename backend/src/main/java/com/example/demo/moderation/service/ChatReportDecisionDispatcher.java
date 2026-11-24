package com.example.demo.moderation.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.chat.owner", havingValue = "remote")
class ChatReportDecisionDispatcher {
    private static final Logger LOG = LoggerFactory.getLogger(ChatReportDecisionDispatcher.class);
    private final ChatReportDecisionStore decisions;
    private final ChatModerationClient chat;
    private final ContentReportService reports;

    ChatReportDecisionDispatcher(ChatReportDecisionStore decisions, ChatModerationClient chat, ContentReportService reports) {
        this.decisions = decisions;
        this.chat = chat;
        this.reports = reports;
    }

    @Bean("chatGovernanceScheduler")
    static ThreadPoolTaskScheduler chatGovernanceScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("chat-governance-");
        return scheduler;
    }

    @Scheduled(fixedDelayString = "${app.chat.governance.poll-delay-ms:1000}", scheduler = "chatGovernanceScheduler")
    void deliverPendingDecisions() {
        for (var decision : decisions.claim(8)) {
            try (var requestContext = MDC.putCloseable("requestId", decision.decisionId().toString())) {
                var outcome = chat.apply(decision);
                if (outcome == ChatModerationClient.Outcome.APPLIED) {
                    reports.confirmChatDecision(decision);
                } else {
                    decisions.fail(decision.decisionId(), decision.attempts(), outcome.name());
                    LOG.warn("chat_report_decision_failed id={} reason={}", decision.decisionId(), outcome);
                }
            } catch (RuntimeException failure) {
                decisions.retry(decision.decisionId(), decision.attempts());
                LOG.warn("chat_report_decision_retry id={} attempt={} reason={}",
                        decision.decisionId(), decision.attempts(), failure.getClass().getSimpleName());
            }
        }
    }
}
