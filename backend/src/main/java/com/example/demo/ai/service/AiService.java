package com.example.demo.ai.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class AiService {
    private static final Logger log = LoggerFactory.getLogger(AiService.class);

    private final AiProvider aiProvider;
    private final MeterRegistry meterRegistry;
    private final String providerMode;
    private final String modelName;

    public AiService(
            @Value("${ai.base-url}") String baseUrl,
            @Value("${ai.api-key:}") String apiKey,
            @Value("${ai.model}") String modelName,
            @Value("${ai.provider:real}") String providerMode,
            @Value("${ai.request-timeout-seconds:30}") long timeoutSeconds,
            ObjectProvider<MeterRegistry> meterRegistryProvider
    ) {
        this.providerMode = providerMode == null ? "real" : providerMode.trim().toLowerCase();
        this.modelName = modelName == null ? "" : modelName.trim();
        this.meterRegistry = meterRegistryProvider == null ? null : meterRegistryProvider.getIfAvailable();
        if ("mock".equals(this.providerMode)) {
            this.aiProvider = new MockAiProvider();
            return;
        }
        if (!"real".equals(this.providerMode)) {
            throw new IllegalStateException("AI provider 配置无效");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("AI API key 未配置；本地测试请显式设置 ai.provider=mock");
        }
        long safeTimeoutSeconds = Math.max(5L, Math.min(timeoutSeconds, 120L));
        this.aiProvider = new RealAiProvider(baseUrl, apiKey, this.modelName, Duration.ofSeconds(safeTimeoutSeconds));
    }

    public String generateSummary(String content) {
        return aiProvider.generateSummary(content);
    }

    public AiStreamHandle chatStream(
            List<Map<String, String>> messages,
            int maxOutputTokens,
            java.util.function.Consumer<String> onContent,
            java.util.function.Consumer<Throwable> onError,
            Runnable onComplete
    ) {
        meter("ai.requests").increment();
        long started = System.nanoTime();
        AtomicLong outputChars = new AtomicLong();
        AiStreamHandle handle = aiProvider.chatStream(
                messages,
                maxOutputTokens,
                content -> {
                    outputChars.addAndGet(content == null ? 0 : content.length());
                    onContent.accept(content);
                },
                error -> {
                    meter("ai.failures").increment();
                    log.warn("ai_stream_failed provider={} model={} error={}",
                            providerMode, modelName, error == null ? "unknown" : error.getClass().getSimpleName());
                    onError.accept(new RuntimeException("AI 服务暂不可用"));
                },
                () -> {
                    meter("ai.output.characters").increment(outputChars.get());
                    log.info("ai_stream_completed provider={} model={} output_chars={} duration_ms={}",
                            providerMode, modelName, outputChars.get(),
                            (System.nanoTime() - started) / 1_000_000L);
                    onComplete.run();
                }
        );
        return new AiStreamHandle(handle.completion(), handle::cancel);
    }

    private io.micrometer.core.instrument.Counter meter(String name) {
        if (meterRegistry == null) {
            return io.micrometer.core.instrument.Counter.builder(name).register(
                    SimpleMeterRegistryHolder.INSTANCE);
        }
        return io.micrometer.core.instrument.Counter.builder(name)
                .tag("provider", providerMode)
                .register(meterRegistry);
    }

    private static final class SimpleMeterRegistryHolder {
        private static final io.micrometer.core.instrument.simple.SimpleMeterRegistry INSTANCE =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    }
}
