package com.example.demo.user.connected.service;

import com.example.demo.auth.service.LocalPasswordAuthenticator;
import com.example.demo.common.DistributedRateLimiter;
import com.example.demo.user.connected.repo.UserConnectedAccountRepository;
import com.example.demo.user.connected.entity.UserConnectedAccount;
import com.example.demo.user.entity.User;
import com.example.demo.user.repo.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.regex.Pattern;

/** Removes a binding only after proving the current user's local login credentials. */
@Service
public class VerifiedConnectedAccountRemovalService {
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern PHONE = Pattern.compile("^1\\d{10}$");
    private static final String INVALID_CREDENTIALS = "邮箱、手机号或密码不正确";

    private final UserRepository users;
    private final UserConnectedAccountRepository accounts;
    private final LocalPasswordAuthenticator passwordAuthenticator;
    private final DistributedRateLimiter rateLimiter;
    private final ConnectedAccountBindingTag bindingTags;

    public VerifiedConnectedAccountRemovalService(UserRepository users, UserConnectedAccountRepository accounts,
            LocalPasswordAuthenticator passwordAuthenticator, DistributedRateLimiter rateLimiter,
            ConnectedAccountBindingTag bindingTags) {
        this.users = users;
        this.accounts = accounts;
        this.passwordAuthenticator = passwordAuthenticator;
        this.rateLimiter = rateLimiter;
        this.bindingTags = bindingTags;
    }

    @Transactional
    public void disconnect(Long userId, String provider, String identifier, String currentPassword, String bindingTag) {
        if (userId == null || userId <= 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        String normalizedProvider = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        String address = identifier == null ? "" : identifier.trim();
        if (normalizedProvider.isBlank() || normalizedProvider.length() > 32 || address.length() > 255
                || currentPassword == null || currentPassword.isBlank() || currentPassword.length() > 1024) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, INVALID_CREDENTIALS);
        }
        boolean isEmail = address.contains("@");
        if (isEmail) address = address.toLowerCase(Locale.ROOT);
        if (!(isEmail ? EMAIL.matcher(address).matches() : PHONE.matcher(address).matches())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, INVALID_CREDENTIALS);
        }

        // Share the existing account-password budget and login-address budget.
        rateLimiter.enforce("password-change-user", String.valueOf(userId), 3600, 10);
        rateLimiter.enforce("login-account", address, 600, 12);
        User locked = users.findByIdForAccountUpdate(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, INVALID_CREDENTIALS));
        User authenticated;
        try {
            authenticated = passwordAuthenticator.authenticate(isEmail ? address : null,
                    isEmail ? null : address, currentPassword);
        } catch (ResponseStatusException rejected) {
            if (rejected.getStatusCode().value() != HttpStatus.UNAUTHORIZED.value()) throw rejected;
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, INVALID_CREDENTIALS);
        }
        if (!locked.getId().equals(authenticated.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, INVALID_CREDENTIALS);
        }
        UserConnectedAccount binding = accounts.findForUpdate(userId, normalizedProvider)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "关联状态已变更，请刷新后重试"));
        if (!bindingTags.matches(binding, bindingTag)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "关联状态已变更，请刷新后重试");
        }
        accounts.delete(binding);
    }
}
