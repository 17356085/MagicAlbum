package com.example.demo.auth.service.otp;

import com.example.demo.auth.dto.TurnstileSiteVerifyResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

@Component
public class CloudflareTurnstileVerifyClient implements TurnstileVerifyClient {
    private final RestClient restClient;
    private final String secretKey;

    public CloudflareTurnstileVerifyClient(
            @Value("${app.auth.verify.turnstile.verify-url:https://challenges.cloudflare.com/turnstile/v0/siteverify}") String verifyUrl,
            @Value("${app.auth.verify.turnstile.secret-key:}") String secretKey
    ) {
        this.restClient = RestClient.builder().baseUrl(verifyUrl).build();
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
                .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(TurnstileSiteVerifyResponse.class);

        if (response == null) {
            throw new IllegalStateException("Turnstile verify 响应为空");
        }
        return response;
    }
}
