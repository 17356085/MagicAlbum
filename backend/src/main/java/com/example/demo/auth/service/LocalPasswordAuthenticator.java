package com.example.demo.auth.service;

import com.example.demo.user.entity.User;
import com.example.demo.user.repo.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

/** Resolves and checks the same local credentials used by password login. */
@Service
public class LocalPasswordAuthenticator {
    private final UserRepository users;
    private final PasswordEncoder passwords;

    public LocalPasswordAuthenticator(UserRepository users, PasswordEncoder passwords) {
        this.users = users;
        this.passwords = passwords;
    }

    public User authenticate(String email, String phone, String password) {
        if ((email == null || email.isBlank()) && (phone == null || phone.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "必须提供邮箱或手机号");
        }
        Optional<User> found = email != null && !email.isBlank()
                ? users.findByEmail(email)
                : users.findByPhone(phone);
        User user = found.orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误"));
        if (!passwords.matches(password, user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        return user;
    }
}
