package com.example.demo.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public class LoginRequest {
    // 二选一：email 或 phone
    @Email(message = "邮箱格式不正确")
    private String email;

    private String phone;

    @NotBlank(message = "密码不能为空")
    private String password;

    private String verifyToken;

    private String verifyProvider;

    private String verifyScene;

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getVerifyToken() { return verifyToken; }
    public void setVerifyToken(String verifyToken) { this.verifyToken = verifyToken; }

    public String getVerifyProvider() { return verifyProvider; }
    public void setVerifyProvider(String verifyProvider) { this.verifyProvider = verifyProvider; }

    public String getVerifyScene() { return verifyScene; }
    public void setVerifyScene(String verifyScene) { this.verifyScene = verifyScene; }
}
