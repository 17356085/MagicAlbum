package com.example.demo.auth.service.otp;

/**
 * Internal, deliberately small error contract between the Cognito transport
 * and the OTP authentication service.
 *
 * <p>The exception carries only a safe classification. It intentionally does
 * not retain a provider response body, credentials, session, or cause.</p>
 */
final class CognitoOtpProviderException extends RuntimeException {
    enum Kind {
        INVALID_CODE,
        UNAVAILABLE
    }

    private final Kind kind;

    private CognitoOtpProviderException(Kind kind) {
        super(kind == Kind.INVALID_CODE ? "OTP_CODE_INVALID" : "OTP_PROVIDER_UNAVAILABLE");
        this.kind = kind;
    }

    static CognitoOtpProviderException invalidCode() {
        return new CognitoOtpProviderException(Kind.INVALID_CODE);
    }

    static CognitoOtpProviderException unavailable() {
        return new CognitoOtpProviderException(Kind.UNAVAILABLE);
    }

    boolean isInvalidCode() {
        return kind == Kind.INVALID_CODE;
    }
}
