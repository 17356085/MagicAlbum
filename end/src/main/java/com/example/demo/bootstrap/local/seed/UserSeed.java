package com.example.demo.bootstrap.local.seed;

public record UserSeed(
        int index,
        String username,
        String nickname,
        String location,
        String bio,
        String homepage,
        String link,
        String email,
        String phone
) {
}
