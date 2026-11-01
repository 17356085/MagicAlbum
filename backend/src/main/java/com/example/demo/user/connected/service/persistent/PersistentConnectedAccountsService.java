package com.example.demo.user.connected.service.persistent;

import com.example.demo.user.connected.dto.ConnectedAccountDto;
import com.example.demo.user.connected.entity.UserConnectedAccount;
import com.example.demo.user.connected.repo.UserConnectedAccountRepository;
import com.example.demo.user.connected.service.ConnectedAccountsService;
import com.example.demo.user.connected.service.ConnectedAccountBindingTag;
import com.example.demo.user.repo.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class PersistentConnectedAccountsService implements ConnectedAccountsService {
    private static final String[] DEFAULT_PROVIDERS = new String[] {"github", "google", "wechat"};

    private final UserConnectedAccountRepository repository;
    private final UserRepository users;
    private final ConnectedAccountBindingTag bindingTags;

    public PersistentConnectedAccountsService(UserConnectedAccountRepository repository, UserRepository users,
                                              ConnectedAccountBindingTag bindingTags) {
        this.repository = repository;
        this.users = users;
        this.bindingTags = bindingTags;
    }

    @Override
    public Map<String, Object> list(Long userId) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("items", listFromRepository(userId));
        return body;
    }

    @Override
    public ConnectedAccountDto connect(Long userId, String provider) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "请先完成第三方授权，不能直接标记为已绑定");
    }

    @Override
    @Transactional
    public void bindExternalAccount(Long userId, String provider, String externalId, String displayName) {
        if (externalId == null || externalId.isBlank()) {
            throw new IllegalArgumentException("第三方账号标识不能为空");
        }
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("第三方平台不能为空");
        }
        String normalized = normalizeProvider(provider);
        String normalizedExternalId = externalId.trim();
        users.findByIdForAccountUpdate(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
        UserConnectedAccount entity = repository.findByUserIdAndProvider(userId, normalized)
                .orElseGet(UserConnectedAccount::new);
        if (entity.getExternalId() != null && !entity.getExternalId().isBlank()
                && !entity.getExternalId().equals(normalizedExternalId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该平台已关联其他账号");
        }
        entity.setUserId(userId);
        entity.setProvider(normalized);
        entity.setExternalId(normalizedExternalId);
        entity.setDisplayName(trimToNull(displayName));
        entity.setLinkedAt(Instant.now());
        repository.save(entity);
    }

    @Override
    public Long findUserIdByExternalAccount(String provider, String externalId) {
        if (provider == null || provider.isBlank() || externalId == null || externalId.isBlank()) {
            return null;
        }
        return repository.findByProviderAndExternalId(normalizeProvider(provider), externalId.trim())
                .map(UserConnectedAccount::getUserId)
                .orElse(null);
    }

    @Override
    public void disconnect(Long userId, String provider) {
        // The product has no verified alternative-login flow yet. Removing a
        // binding can therefore leave an OAuth-only account inaccessible.
        throw new ResponseStatusException(HttpStatus.CONFLICT, "暂不支持解绑第三方账号");
    }

    private List<ConnectedAccountDto> listFromRepository(Long userId) {
        Map<String, ConnectedAccountDto> items = new java.util.LinkedHashMap<>();
        for (String provider : DEFAULT_PROVIDERS) {
            items.put(provider, new ConnectedAccountDto(provider, false, null, null));
        }
        for (UserConnectedAccount entity : repository.findByUserId(userId)) {
            items.put(normalizeProvider(entity.getProvider()), toDto(entity));
        }
        return new ArrayList<>(items.values());
    }

    private ConnectedAccountDto toDto(UserConnectedAccount entity) {
        boolean authorized = entity.getExternalId() != null && !entity.getExternalId().isBlank()
                && entity.getLinkedAt() != null;
        ConnectedAccountDto dto = new ConnectedAccountDto(
                normalizeProvider(entity.getProvider()),
                authorized,
                authorized ? entity.getDisplayName() : null,
                authorized ? entity.getLinkedAt() : null
        );
        if (authorized) dto.setBindingTag(bindingTags.forBinding(entity));
        return dto;
    }

    private String normalizeProvider(String provider) {
        return provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
