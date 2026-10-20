package com.example.demo.ai.service;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RealAiProviderCancellationHandoffTest {
    @Test
    void summaryCancelsBodyWhenCompletedHeaderCallbackArrivesAfterCancellation() throws Exception {
        ControlledHttpClient httpClient = new ControlledHttpClient();
        RealAiProvider provider = provider(httpClient);
        AiSummaryCall call = provider.requestSummary("content", 512);
        httpClient.awaitHeaderCallbackRegistration();

        ControlledPublisher body = new ControlledPublisher();
        httpClient.complete(HttpResponseStub.ok(httpClient.request(), body));
        call.cancel();

        assertThat(httpClient.future.cancelAttempted).isTrue();
        assertThat(httpClient.future.lastCancelResult).isFalse();
        assertThat(summaryFailure(call).code()).isEqualTo(AiSummaryFailureCode.CANCELLED);

        httpClient.releaseHeaderCallback();
        body.awaitSubscription();
        assertThat(body.subscription.cancelled).isTrue();
        assertThat(body.subscription.requested).hasValue(0);
    }

    @Test
    void chatCancelsBodyWhenCompletedHeaderCallbackArrivesAfterCancellation() throws Exception {
        ControlledHttpClient httpClient = new ControlledHttpClient();
        RealAiProvider provider = provider(httpClient);
        AiStreamHandle handle = provider.chatStream(
                List.of(Map.of("role", "user", "content", "content")),
                512,
                ignored -> { },
                ignored -> { },
                () -> { }
        );
        httpClient.awaitHeaderCallbackRegistration();

        ControlledPublisher body = new ControlledPublisher();
        httpClient.complete(HttpResponseStub.ok(httpClient.request(), body));
        handle.cancel();

        assertThat(httpClient.future.cancelAttempted).isTrue();
        assertThat(httpClient.future.lastCancelResult).isFalse();
        assertThatThrownBy(() -> handle.completion().join())
                .isInstanceOf(CancellationException.class);

        httpClient.releaseHeaderCallback();
        body.awaitSubscription();
        assertThat(body.subscription.cancelled).isTrue();
        assertThat(body.subscription.requested).hasValue(0);
    }

    private static RealAiProvider provider(ControlledHttpClient httpClient) {
        return new RealAiProvider(
                "http://example.test",
                "test-only",
                "test",
                Duration.ofSeconds(2),
                httpClient
        );
    }

    private static AiSummaryException summaryFailure(AiSummaryCall call) {
        try {
            call.completion().join();
            throw new AssertionError("summary unexpectedly completed successfully");
        } catch (CompletionException ex) {
            assertThat(ex.getCause()).isInstanceOf(AiSummaryException.class);
            return (AiSummaryException) ex.getCause();
        }
    }

    private static final class ControlledHttpClient extends HttpClient {
        private final DeferredCallbackFuture<Flow.Publisher<List<ByteBuffer>>> future =
                new DeferredCallbackFuture<>();

        private volatile HttpRequest request;

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.of(Duration.ofSeconds(1));
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return SSLContextHolder.INSTANCE;
        }

        @Override
        public SSLParameters sslParameters() {
            return SSLContextHolder.INSTANCE.getDefaultSSLParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> HttpResponse<T> send(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler
        ) throws IOException, InterruptedException {
            throw new UnsupportedOperationException("controlled client only supports sendAsync");
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler
        ) {
            this.request = request;
            return (CompletableFuture<HttpResponse<T>>) (CompletableFuture<?>) future;
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler
        ) {
            return sendAsync(request, responseBodyHandler);
        }

        private HttpRequest request() {
            return request;
        }

        private void complete(HttpResponse<Flow.Publisher<List<ByteBuffer>>> response) {
            assertThat(future.complete(response)).isTrue();
        }

        private void awaitHeaderCallbackRegistration() throws InterruptedException {
            assertThat(future.callbackRegistered.await(2, TimeUnit.SECONDS)).isTrue();
        }

        private void releaseHeaderCallback() {
            future.releaseCallback();
        }
    }

    private static final class DeferredCallbackFuture<T> extends CompletableFuture<HttpResponse<T>> {
        private final CountDownLatch callbackRegistered = new CountDownLatch(1);
        private final AtomicBoolean cancelAttempted = new AtomicBoolean();
        private final AtomicBoolean lastCancelResult = new AtomicBoolean(true);
        private volatile BiConsumer<? super HttpResponse<T>, ? super Throwable> callback;

        @Override
        public CompletableFuture<HttpResponse<T>> whenComplete(
                BiConsumer<? super HttpResponse<T>, ? super Throwable> action
        ) {
            callback = action;
            callbackRegistered.countDown();
            return this;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelAttempted.set(true);
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            lastCancelResult.set(cancelled);
            return cancelled;
        }

        private void releaseCallback() {
            BiConsumer<? super HttpResponse<T>, ? super Throwable> registered = callback;
            if (registered == null) {
                throw new AssertionError("header callback was not registered");
            }
            registered.accept(getNow(null), null);
        }
    }

    private static final class ControlledPublisher implements Flow.Publisher<List<ByteBuffer>> {
        private final RecordingSubscription subscription = new RecordingSubscription();
        private final CountDownLatch subscribed = new CountDownLatch(1);

        @Override
        public void subscribe(Flow.Subscriber<? super List<ByteBuffer>> subscriber) {
            subscriber.onSubscribe(subscription);
            subscribed.countDown();
        }

        private void awaitSubscription() throws InterruptedException {
            assertThat(subscribed.await(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static final class RecordingSubscription implements Flow.Subscription {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicLong requested = new AtomicLong();

        @Override
        public void request(long count) {
            requested.addAndGet(count);
        }

        @Override
        public void cancel() {
            cancelled.set(true);
        }
    }

    private static final class HttpResponseStub<T> implements HttpResponse<T> {
        private final HttpRequest request;
        private final T body;

        private HttpResponseStub(HttpRequest request, T body) {
            this.request = request;
            this.body = body;
        }

        private static <T> HttpResponse<T> ok(HttpRequest request, T body) {
            return new HttpResponseStub<>(request, body);
        }

        @Override
        public int statusCode() {
            return 200;
        }

        @Override
        public HttpRequest request() {
            return request;
        }

        @Override
        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (name, value) -> true);
        }

        @Override
        public T body() {
            return body;
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }

    private static final class SSLContextHolder {
        private static final SSLContext INSTANCE = sslContext();

        private static SSLContext sslContext() {
            try {
                return SSLContext.getDefault();
            } catch (Exception ex) {
                throw new ExceptionInInitializerError(ex);
            }
        }
    }
}
