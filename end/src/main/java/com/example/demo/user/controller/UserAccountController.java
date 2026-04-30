package com.example.demo.user.controller;

import com.example.demo.user.dto.BasicInfoUpdateRequest;
import com.example.demo.user.dto.ChangePasswordRequest;
import com.example.demo.user.dto.RegisterRequest;
import com.example.demo.user.dto.UserDto;
import com.example.demo.user.dto.UserSummaryDto;
import com.example.demo.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/users")
public class UserAccountController extends AbstractUserControllerSupport {
    private final UserService userService;

    public UserAccountController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/availability")
    public Map<String, Boolean> checkAvailability(@RequestParam String username) {
        return Map.of("available", userService.isUsernameAvailable(username));
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> listUsers(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "fields", required = false) String fields
    ) {
        Page<UserSummaryDto> pageData = userService.list(q, page, size, fields);
        Map<String, Object> body = new HashMap<>();
        body.put("items", pageData.getContent());
        body.put("page", page);
        body.put("size", size);
        body.put("total", pageData.getTotalElements());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/register")
    public ResponseEntity<UserDto> register(@Valid @RequestBody RegisterRequest req) {
        UserDto created = userService.register(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/me/basic")
    public ResponseEntity<UserDto> getMyBasic() {
        return ResponseEntity.ok(userService.getById(getUserId()));
    }

    @PatchMapping("/me/basic")
    public ResponseEntity<UserDto> updateMyBasic(@Valid @RequestBody BasicInfoUpdateRequest payload) {
        return ResponseEntity.ok(userService.updateBasicInfo(getUserId(), payload));
    }

    @PostMapping("/me/password")
    public ResponseEntity<Void> changeMyPassword(@Valid @RequestBody ChangePasswordRequest payload) {
        userService.changePassword(getUserId(), payload);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }
}
