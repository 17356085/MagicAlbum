package com.example.demo.user.service;

import com.example.demo.user.dto.RegisterRequest;
import com.example.demo.user.dto.UserDto;
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
        return dto;
    }

    public Page<UserDto> list(String q, int page, int size) {
        PageRequest pr = PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(size, 1), 20));
        Page<User> p = userRepository.search((q == null || q.isBlank()) ? null : q, pr);
        return p.map(this::toDto);
    }

    private UserDto toDto(User u) {
        UserDto dto = new UserDto();
        dto.setId(u.getId());
        dto.setUsername(u.getUsername());
        dto.setEmail(u.getEmail());
        dto.setPhone(u.getPhone());
        return dto;
    }
}