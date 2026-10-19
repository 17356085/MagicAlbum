package com.example.demo.storage.service;

import com.example.demo.storage.dto.MediaUploadIntent;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Internal upload plan. Never bind this object from a client request. */
public record PreparedImage(String provider, String objectKey, String url,
                            String contentType, String sha256, byte[] bytes) {
    public PreparedImage {
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    public static PreparedImage of(String provider, String key, String url,
                                   ImageUploadValidator.ValidatedImage image) {
        try {
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image.bytes()));
            return new PreparedImage(provider, key, url, image.contentType(), sha, image.bytes());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public MediaUploadIntent intent() {
        return new MediaUploadIntent(provider, objectKey, url, contentType, bytes.length, sha256);
    }
}
