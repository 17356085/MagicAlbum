package com.example.demo.auth.service.otp;

import com.example.demo.auth.JwtTokenProvider;
import com.example.demo.auth.dto.AuthCodeChannel;
import com.example.demo.auth.dto.AuthCodeFinishRequest;
import com.example.demo.auth.dto.AuthCodeStartRequest;
import com.example.demo.auth.dto.AuthCodeStartResponse;
import com.example.demo.auth.dto.CognitoAuthFlowResponse;
import com.example.demo.auth.dto.LoginResponse;
import com.example.demo.user.dto.UserDto;
import com.example.demo.user.entity.User;
import com.example.demo.user.entity.UserProfile;
import com.example.demo.user.repo.UserProfileRepository;
import com.example.demo.user.repo.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class CognitoOtpAuthService {
    private static final Logger log = LoggerFactory.getLogger(CognitoOtpAuthService.class);

    private final CognitoOtpClient cognitoOtpClient;
    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final AuthVerifyService authVerifyService;
    private final InMemoryEmailOtpService inMemoryEmailOtpService;
    private final long emailOtpExpireSeconds;
    private final long phoneOtpExpireSeconds;
    private final long sendCooldownSeconds;

    public CognitoOtpAuthService(
            CognitoOtpClient cognitoOtpClient,
            UserRepository userRepository,
            UserProfileRepository userProfileRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider,
            AuthVerifyService authVerifyService,
            InMemoryEmailOtpService inMemoryEmailOtpService,
            @Value("${app.auth.cognito.email-otp-expire-seconds:300}") long emailOtpExpireSeconds,
            @Value("${app.auth.cognito.phone-otp-expire-seconds:300}") long phoneOtpExpireSeconds,
            @Value("${app.auth.cognito.send-cooldown-seconds:60}") long sendCooldownSeconds
    ) {
        this.cognitoOtpClient = cognitoOtpClient;
        this.userRepository = userRepository;
        this.userProfileRepository = userProfileRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.authVerifyService = authVerifyService;
        this.inMemoryEmailOtpService = inMemoryEmailOtpService;
        this.emailOtpExpireSeconds = Math.max(emailOtpExpireSeconds, 60);
        this.phoneOtpExpireSeconds = Math.max(phoneOtpExpireSeconds, 60);
        this.sendCooldownSeconds = Math.max(sendCooldownSeconds, 30);
    }

    public AuthCodeStartResponse start(AuthCodeStartRequest request) {
        authVerifyService.verify(request.getVerifyToken(), request.getVerifyProvider(), request.getVerifyScene(), "login");
        if (request.getChannel() == AuthCodeChannel.email) {
            return startEmailOtp(request.getAddress());
        }
        if (request.getChannel() == AuthCodeChannel.phone) {
            return startPhoneOtp(request.getAddress());
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前不支持该 channel");
    }

    public LoginResponse finish(AuthCodeFinishRequest request) {
        if (request.getChannel() == AuthCodeChannel.email) {
            return finishEmailOtp(request);
        }
        if (request.getChannel() == AuthCodeChannel.phone) {
            return finishPhoneOtp(request);
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前不支持该 channel");
    }

    private AuthCodeStartResponse startEmailOtp(String rawEmail) {
        String email = normalizeEmail(rawEmail);
        try {
            InMemoryEmailOtpService.EmailOtpTicket ticket = inMemoryEmailOtpService.start(email, emailOtpExpireSeconds, sendCooldownSeconds);
            return new AuthCodeStartResponse(
                    AuthCodeChannel.email,
                    maskEmail(email),
                    ticket.sessionId(),
                    emailOtpExpireSeconds,
                    sendCooldownSeconds
            );
        } catch (Exception ex) {
            if (ex instanceof ResponseStatusException responseStatusException) {
                throw responseStatusException;
            }
            log.error("Failed to start local EMAIL_OTP flow for email={}", maskEmail(email), ex);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Email OTP 发起失败，请稍后重试");
        }
    }

    private AuthCodeStartResponse startPhoneOtp(String rawPhone) {
        String phone = normalizePhone(rawPhone);
        CognitoAuthFlowResponse response;
        try {
            response = initiateOtpChallenge(phone, "SMS_OTP");
        } catch (Exception ex) {
            log.error("Failed to start Cognito SMS_OTP challenge for phone={}", maskPhone(phone), ex);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Phone OTP 发起失败，请检查 Cognito 配置与短信通道");
        }

        if (!"SMS_OTP".equals(trimToEmpty(response.getChallengeName())) || trimToEmpty(response.getSession()).isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Cognito 未返回 SMS_OTP 挑战");
        }

        return new AuthCodeStartResponse(
                AuthCodeChannel.phone,
                maskPhone(phone),
                response.getSession(),
                phoneOtpExpireSeconds,
                sendCooldownSeconds
        );
    }

    private LoginResponse finishEmailOtp(AuthCodeFinishRequest request) {
        String email = normalizeEmail(request.getAddress());

        try {
            inMemoryEmailOtpService.verify(email, trimToEmpty(request.getSession()), trimToEmpty(request.getCode()));
        } catch (Exception ex) {
            if (ex instanceof ResponseStatusException responseStatusException) {
                throw responseStatusException;
            }
            log.warn("Failed to finish local EMAIL_OTP flow for email={}", maskEmail(email), ex);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "邮箱验证码无效或已过期");
        }

        User user = resolveOrCreateLocalUserByEmail(email);
        String token = jwtTokenProvider.generateToken(user.getId(), user.getUsername());
        return new LoginResponse(token, toUserDto(user));
    }

    private LoginResponse finishPhoneOtp(AuthCodeFinishRequest request) {
        String phone = normalizePhone(request.getAddress());

        CognitoAuthFlowResponse response;
        try {
            response = cognitoOtpClient.respondToChallenge(
                    "SMS_OTP",
                    phone,
                    Map.of("SMS_OTP_CODE", trimToEmpty(request.getCode())),
                    trimToEmpty(request.getSession())
            );
        } catch (Exception ex) {
            log.warn("Failed to finish Cognito SMS_OTP challenge for phone={}", maskPhone(phone), ex);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "手机验证码无效、已过期或 Cognito 校验失败");
        }

        if (response.getAuthenticationResult() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "手机验证码登录未完成");
        }

        User user = resolveOrCreateLocalUserByPhone(phone);
        String token = jwtTokenProvider.generateToken(user.getId(), user.getUsername());
        return new LoginResponse(token, toUserDto(user));
    }

    private CognitoAuthFlowResponse initiateOtpChallenge(String username, String preferredChallenge) {
        CognitoAuthFlowResponse response = cognitoOtpClient.initiateUserAuth(username, preferredChallenge);
        if ("SELECT_CHALLENGE".equals(trimToEmpty(response.getChallengeName()))) {
            response = cognitoOtpClient.respondToChallenge(
                    "SELECT_CHALLENGE",
                    username,
                    Map.of("ANSWER", preferredChallenge),
                    response.getSession()
            );
        }
        return response;
    }

    private String normalizeEmail(String value) {
        String email = trimToEmpty(value).toLowerCase();
        if (email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "邮箱不能为空");
        }
        if (!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "邮箱格式不正确");
        }
        return email;
    }

    private String normalizePhone(String value) {
        String phone = trimToEmpty(value);
        if (phone.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "手机号不能为空");
        }
        if (!phone.matches("^1\\d{10}$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "手机号格式不正确");
        }
        return phone;
    }

    private String maskEmail(String email) {
        int atIndex = email.indexOf('@');
        if (atIndex <= 1) {
            return "****" + email.substring(Math.max(atIndex, 0));
        }
        String local = email.substring(0, atIndex);
        String domain = email.substring(atIndex);
        String prefix = local.substring(0, Math.min(2, local.length()));
        return prefix + "****" + domain;
    }

    private String maskPhone(String phone) {
        if (phone.length() < 7) {
            return "****";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private User resolveOrCreateLocalUserByEmail(String email) {
        Optional<User> existing = userRepository.findByEmail(email);
        if (existing.isPresent()) {
            return existing.get();
        }

        User user = new User();
        user.setUsername(resolveUniqueUsername(email));
        user.setEmail(email);
        user.setPhone(generateSyntheticPhone(email));
        user.setPasswordHash(passwordEncoder.encode("cognito_email_otp_" + UUID.randomUUID()));
        User saved = userRepository.save(user);

        UserProfile profile = userProfileRepository.findById(saved.getId()).orElseGet(UserProfile::new);
        profile.setUserId(saved.getId());
        if (profile.getNickname() == null || profile.getNickname().isBlank()) {
            String nickname = email.contains("@") ? email.substring(0, email.indexOf('@')) : email;
            profile.setNickname(nickname);
        }
        userProfileRepository.save(profile);
        return saved;
    }

    private User resolveOrCreateLocalUserByPhone(String phone) {
        Optional<User> existing = userRepository.findByPhone(phone);
        if (existing.isPresent()) {
            return existing.get();
        }

        User user = new User();
        user.setUsername(resolveUniqueUsername(phone));
        user.setPhone(phone);
        user.setEmail(generateSyntheticEmail(phone));
        user.setPasswordHash(passwordEncoder.encode("cognito_phone_otp_" + UUID.randomUUID()));
        User saved = userRepository.save(user);

        UserProfile profile = userProfileRepository.findById(saved.getId()).orElseGet(UserProfile::new);
        profile.setUserId(saved.getId());
        if (profile.getNickname() == null || profile.getNickname().isBlank()) {
            profile.setNickname("用户" + phone.substring(Math.max(0, phone.length() - 4)));
        }
        userProfileRepository.save(profile);
        return saved;
    }

    private String resolveUniqueUsername(String seed) {
        String base = seed.contains("@") ? seed.substring(0, seed.indexOf('@')) : seed;
        base = base.replaceAll("[^A-Za-z0-9_]", "_");
        if (base.isBlank()) {
            base = "otp_user";
        }
        if (base.length() < 3 || seed.matches("^1\\d{10}$")) {
            base = base + (seed.matches("^1\\d{10}$") ? "_ph" : "_em");
        }
        base = base.length() > 48 ? base.substring(0, 48) : base;

        String candidate = base;
        int seq = 1;
        while (userRepository.existsByUsername(candidate)) {
            candidate = (base.length() > 42 ? base.substring(0, 42) : base) + "_" + seq++;
        }
        return candidate;
    }

    private String generateSyntheticEmail(String seed) {
        String email = "phone_" + trimToEmpty(seed) + "@otp.local";
        if (!userRepository.existsByEmail(email)) {
            return email;
        }
        long numeric = Math.abs(trimToEmpty(seed).hashCode());
        String candidate = "phone_" + numeric + "@otp.local";
        while (userRepository.existsByEmail(candidate)) {
            numeric++;
            candidate = "phone_" + numeric + "@otp.local";
        }
        return candidate;
    }

    private String generateSyntheticPhone(String seed) {
        long numeric = Math.abs(trimToEmpty(seed).hashCode());
        String suffix = String.format("%08d", numeric % 100_000_000L);
        String candidate = "198" + suffix;
        while (userRepository.existsByPhone(candidate)) {
            numeric++;
            suffix = String.format("%08d", numeric % 100_000_000L);
            candidate = "198" + suffix;
        }
        return candidate;
    }

    private UserDto toUserDto(User user) {
        UserDto dto = new UserDto();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setEmail(user.getEmail());
        dto.setPhone(user.getPhone());
        dto.setCreatedAt(user.getCreatedAt());
        return dto;
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
