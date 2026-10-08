package com.example.demo.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class RegisterRequest {

    @NotBlank
    @Size(min = 3, max = 64)
    private String username;

    @NotBlank
    @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不正确")
    private String phone;

    @NotBlank
    @Email
    private String email;

    @NotBlank
    @Size(min = 8, message = "密码不少于 8 位")
    private String password;

    private String verifyToken;

    private String verifyProvider;

    private String verifyScene;

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getVerifyToken() { return verifyToken; }
    public void setVerifyToken(String verifyToken) { this.verifyToken = verifyToken; }

    public String getVerifyProvider() { return verifyProvider; }
    public void setVerifyProvider(String verifyProvider) { this.verifyProvider = verifyProvider; }

    public String getVerifyScene() { return verifyScene; }
    public void setVerifyScene(String verifyScene) { this.verifyScene = verifyScene; }
}
