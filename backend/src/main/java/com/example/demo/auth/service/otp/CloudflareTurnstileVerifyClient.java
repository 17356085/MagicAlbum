package com.example.demo.auth.service.otp;

import com.example.demo.auth.dto.TurnstileSiteVerifyResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Component
public class CloudflareTurnstileVerifyClient implements TurnstileVerifyClient {
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(5);

    private final RestClient restClient;
    private final String secretKey;

    @Autowired
    public CloudflareTurnstileVerifyClient(
            @Value("${app.auth.verify.turnstile.verify-url:https://challenges.cloudflare.com/turnstile/v0/siteverify}") String verifyUrl,
            @Value("${app.auth.verify.turnstile.secret-key:}") String secretKey
    ) {
        this(verifyUrl, secretKey, DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT);
    }

    CloudflareTurnstileVerifyClient(
            String verifyUrl,
            String secretKey,
            Duration connectTimeout,
            Duration readTimeout
    ) {
        this.restClient = RestClient.builder()
                .baseUrl(verifyUrl)
                .requestFactory(requestFactory(connectTimeout, readTimeout))
                .build();
        this.secretKey = secretKey == null ? "" : secretKey.trim();
    }

    @Override
    public TurnstileSiteVerifyResponse verify(String token) {
        if (secretKey.isBlank()) {
            throw new IllegalStateException("Turnstile secret key 未配置");
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("secret", secretKey);
        form.add("response", token);

        TurnstileSiteVerifyResponse response = restClient.post()
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(TurnstileSiteVerifyResponse.class);

        if (response == null) {
            throw new IllegalStateException("Turnstile verify 响应为空");
        }
        return response;
    }

    private static SimpleClientHttpRequestFactory requestFactory(Duration connectTimeout, Duration readTimeout) {
        long connectTimeoutMillis = requireTimeoutMillis(connectTimeout, "connect");
        long readTimeoutMillis = requireTimeoutMillis(readTimeout, "read");

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Math.toIntExact(connectTimeoutMillis));
        factory.setReadTimeout(Math.toIntExact(readTimeoutMillis));
        return factory;
    }

    private static long requireTimeoutMillis(Duration timeout, String kind) {
        final long timeoutMillis;
        try {
            timeoutMillis = timeout == null ? 0 : timeout.toMillis();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Turnstile " + kind + " timeout is out of range", exception);
        }
        if (timeoutMillis < 1 || timeoutMillis > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Turnstile " + kind + " timeout must be between 1 and 2147483647 ms");
        }
        return timeoutMillis;
    }
}
