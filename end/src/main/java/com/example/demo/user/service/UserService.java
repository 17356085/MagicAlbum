package com.example.demo.user.service;

import com.example.demo.user.dto.RegisterRequest;
import com.example.demo.user.dto.UserDto;
import com.example.demo.user.dto.BasicInfoUpdateRequest;
import com.example.demo.user.dto.ChangePasswordRequest;
import com.example.demo.user.entity.User;
import com.example.demo.user.repo.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public boolean isUsernameAvailable(String username) {
        return !userRepository.existsByUsername(username);
    }

    public UserDto register(RegisterRequest req) {
        // 唯一性校验
        if (userRepository.existsByUsername(req.getUsername())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户名不可重复");
        }
        if (userRepository.existsByEmail(req.getEmail())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "邮箱已被使用");
        }
        if (userRepository.existsByPhone(req.getPhone())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "手机号已被使用");
        }

        // 密码加密
        String hash = passwordEncoder.encode(req.getPassword());

        // 持久化
        User u = new User();
        u.setUsername(req.getUsername());
        u.setEmail(req.getEmail());
        u.setPhone(req.getPhone());
        u.setPasswordHash(hash);
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

    public Page<UserDto> list(String q, int page, int size, String fields) {
        PageRequest pr = PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(size, 1), 20));
        String keyword = (q == null || q.isBlank()) ? null : q.trim();
        boolean includeNickname = fields != null && fields.toLowerCase().contains("nickname");
        Page<User> p = includeNickname ? userRepository.searchWithNickname(keyword, pr)
                                       : userRepository.search(keyword, pr);
        return p.map(this::toDto);
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

    public UserDto getById(Long id) {
        User u = userRepository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
        return toDto(u);
    }

    public UserDto updateBasicInfo(Long userId, BasicInfoUpdateRequest req) {
        User u = userRepository.findById(userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
        String nextUsername = (req.getUsername() == null) ? u.getUsername() : req.getUsername().trim();
        String nextEmail = (req.getEmail() == null) ? u.getEmail() : req.getEmail().trim();
        String nextPhone = (req.getPhone() == null) ? u.getPhone() : req.getPhone().trim();

        // 基本校验
        if (nextUsername.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户名不能为空");
        if (nextEmail.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "邮箱不能为空");
        if (nextPhone.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "手机号不能为空");

        // 唯一性校验（排除自身）
        userRepository.findByUsername(nextUsername).ifPresent(other -> {
            if (!other.getId().equals(userId)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "用户名不可重复");
        });
        userRepository.findByEmail(nextEmail).ifPresent(other -> {
            if (!other.getId().equals(userId)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "邮箱已被使用");
        });
        userRepository.findByPhone(nextPhone).ifPresent(other -> {
            if (!other.getId().equals(userId)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "手机号已被使用");
        });

        u.setUsername(nextUsername);
        u.setEmail(nextEmail);
        u.setPhone(nextPhone);
        User saved = userRepository.save(u);
        return toDto(saved);
    }

    public void changePassword(Long userId, ChangePasswordRequest req) {
        User u = userRepository.findById(userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
        String current = (req.getCurrentPassword() == null) ? "" : req.getCurrentPassword();
        String next = (req.getNewPassword() == null) ? "" : req.getNewPassword();
        if (current.isBlank() || next.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码不能为空");
        if (!passwordEncoder.matches(current, u.getPasswordHash())) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "当前密码不正确");
        if (next.length() < 6) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "新密码长度至少6位");
        u.setPasswordHash(passwordEncoder.encode(next));
        userRepository.save(u);
    }
}