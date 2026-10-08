package com.example.demo.auth.service.otp;

import com.example.demo.auth.dto.TurnstileSiteVerifyResponse;

public interface TurnstileVerifyClient {
    TurnstileSiteVerifyResponse verify(String token);
}
