package com.example.demo.user.service;

import com.example.demo.auth.service.otp.AuthVerifyService;
import com.example.demo.user.dto.RegisterRequest;
import com.example.demo.user.dto.UserDto;
import com.example.demo.user.dto.UserSummaryDto;
import com.example.demo.user.dto.BasicInfoUpdateRequest;
import com.example.demo.user.dto.ChangePasswordRequest;
import com.example.demo.user.entity.User;
import com.example.demo.user.entity.UserProfile;
import com.example.demo.user.repo.UserProfileRepository;
import com.example.demo.user.repo.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class UserService {
    private static final int MAX_PASSWORD_UTF8_BYTES = 72;
    private static final Set<String> PUBLIC_SEARCH_FIELDS = Set.of("username", "nickname");

    private final UserRepository userRepository;
    private final UserProfileRepository userProfileRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthVerifyService authVerifyService;

    public UserService(
            UserRepository userRepository,
            UserProfileRepository userProfileRepository,
            PasswordEncoder passwordEncoder,
            AuthVerifyService authVerifyService
    ) {
        this.userRepository = userRepository;
        this.userProfileRepository = userProfileRepository;
        this.passwordEncoder = passwordEncoder;
        this.authVerifyService = authVerifyService;
    }

    public boolean isUsernameAvailable(String username) {
        return !userRepository.existsByUsername(username);
    }

    public UserDto register(RegisterRequest req) {
        validatePasswordUtf8Length(req.getPassword());
        authVerifyService.verify(req.getVerifyToken(), req.getVerifyProvider(), req.getVerifyScene(), "register");
        // 唯一性校验
        if (userRepository.existsByUsername(req.getUsername())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户名不可重复");
        }
        if (userRepository.existsByEmail(req.getEmail())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "邮箱已被使用");
        }
        if (userRepository.existsByPhone(req.getPhone())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该手机号已被占用");
        }

        // 密码加密
        String hash = passwordEncoder.encode(req.getPassword());

        // 持久化
        User u = new User();
        u.setUsername(req.getUsername());
        u.setEmail(req.getEmail());
        u.setPhone(req.getPhone());
        u.setPasswordHash(hash);
        u.setEmailVerified(false);
        u.setPhoneVerified(false);
        User saved = userRepository.save(u);

        // 映射响应
        UserDto dto = new UserDto();
        dto.setId(saved.getId());
        dto.setUsername(saved.getUsername());
        dto.setEmail(saved.getEmail());
        dto.setPhone(saved.getPhone());
        dto.setCreatedAt(saved.getCreatedAt());
        return dto;
    }

    public Page<UserSummaryDto> list(String q, int page, int size, String fields) {
        PageRequest pr = PageRequest.of(Math.max(page, 1) - 1, Math.min(Math.max(size, 1), 20));
        String keyword = (q == null || q.isBlank()) ? null : q.trim();
        Set<String> selectedFields = resolvePublicSearchFields(fields);
        Page<User> p = userRepository.searchPublic(keyword, selectedFields.contains("username"),
                selectedFields.contains("nickname"), pr);
        List<Long> userIds = p.getContent().stream()
                .map(User::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (userIds.isEmpty()) {
            return p.map(user -> toSummaryDto(user, null));
        }
        Map<Long, UserProfile> profilesByUserId = new HashMap<>();
        for (UserProfile profile : userProfileRepository.findByUserIdIn(userIds)) {
            if (profile != null && profile.getUserId() != null) {
                profilesByUserId.put(profile.getUserId(), profile);
            }
        }
        return p.map(user -> toSummaryDto(user, profilesByUserId.get(user.getId())));
    }

    private static Set<String> resolvePublicSearchFields(String fields) {
        if (fields == null || fields.isBlank()) return PUBLIC_SEARCH_FIELDS;
        Set<String> selected = new HashSet<>();
        for (String field : fields.split(",", -1)) {
            String normalized = field.trim().toLowerCase(Locale.ROOT);
            if (!PUBLIC_SEARCH_FIELDS.contains(normalized)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "搜索字段仅支持 username、nickname");
            }
            selected.add(normalized);
        }
        return selected;
    }

    private UserDto toDto(User u) {
        UserDto dto = new UserDto();
        dto.setId(u.getId());
        dto.setUsername(u.getUsername());
        dto.setEmail(u.getEmail());
        dto.setPhone(u.getPhone());
        dto.setCreatedAt(u.getCreatedAt());
        return dto;
    }

    private UserSummaryDto toSummaryDto(User user, UserProfile profile) {
        UserSummaryDto dto = new UserSummaryDto();
        dto.setId(user.getId());
        dto.setUsername(user.getUsername());
        dto.setCreatedAt(user.getCreatedAt());

        if (profile != null) {
            dto.setNickname(profile.getNickname());
            dto.setAvatarUrl(profile.getAvatarUrl());
        }
        return dto;
    }

    public UserDto getById(Long id) {
        User u = userRepository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
        return toDto(u);
    }

    @Transactional
    public UserDto updateBasicInfo(Long userId, BasicInfoUpdateRequest req) {
        User u = userRepository.findByIdForAccountUpdate(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
        String nextUsername = (req.getUsername() == null) ? u.getUsername() : req.getUsername().trim();
        String nextEmail = (req.getEmail() == null) ? u.getEmail() : req.getEmail().trim();
        String nextPhone = (req.getPhone() == null) ? u.getPhone() : req.getPhone().trim();

        // 基本校验
        if (nextUsername.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户名不能为空");
        if (nextEmail.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "邮箱不能为空");
        if (nextPhone.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "手机号不能为空");

        boolean emailChanged = !Objects.equals(nextEmail, u.getEmail());
        boolean phoneChanged = !Objects.equals(nextPhone, u.getPhone());

        // 唯一性校验（排除自身）
        userRepository.findByUsername(nextUsername).ifPresent(other -> {
            if (!other.getId().equals(userId)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户名不可重复");
        });
        userRepository.findByEmail(nextEmail).ifPresent(other -> {
            if (!other.getId().equals(userId)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "邮箱已被使用");
        });
        userRepository.findByPhone(nextPhone).ifPresent(other -> {
            if (!other.getId().equals(userId)) throw new ResponseStatusException(HttpStatus.CONFLICT, "该手机号已被占用");
        });

        u.setUsername(nextUsername);
        u.setEmail(nextEmail);
        u.setPhone(nextPhone);
        if (emailChanged) u.setEmailVerified(false);
        if (phoneChanged) u.setPhoneVerified(false);
        User saved = userRepository.save(u);
        return toDto(saved);
    }

    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest req) {
        User u = userRepository.findByIdForAccountUpdate(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
        String current = (req.getCurrentPassword() == null) ? "" : req.getCurrentPassword();
        String next = (req.getNewPassword() == null) ? "" : req.getNewPassword();
        if (current.isBlank() || next.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码不能为空");
        if (!passwordEncoder.matches(current, u.getPasswordHash())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前密码不正确");
        if (next.length() < 8) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "新密码长度至少8位");
        if (!next.matches("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "新密码需包含大小写字母和数字");
        }
        validatePasswordUtf8Length(next);
        u.setPasswordHash(passwordEncoder.encode(next));
        userRepository.save(u);
    }

    private void validatePasswordUtf8Length(String password) {
        if (password != null && password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_UTF8_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码过长，请减少字符数量");
        }
    }
}
