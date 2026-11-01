package com.example.demo.user.connected.service;

import com.example.demo.user.connected.entity.UserConnectedAccount;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/** An opaque snapshot marker; authorization still requires the user's credentials. */
@Component
public class ConnectedAccountBindingTag {
    private static final byte[] CONTEXT = "bluealbum/connected-account-binding/v1".getBytes(StandardCharsets.UTF_8);
    private final byte[] key;

    public ConnectedAccountBindingTag(@Value("${app.security.jwt.secret:}") String secret) {
        key = (secret == null ? "" : secret).getBytes(StandardCharsets.UTF_8);
        if (key.length < 32) throw new IllegalStateException("关联账号校验密钥未配置");
    }

    public String forBinding(UserConnectedAccount account) {
        if (account.getId() == null || account.getUserId() == null || account.getProvider() == null
                || account.getExternalId() == null || account.getExternalId().isBlank()) {
            throw new IllegalArgumentException("关联账号信息不完整");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(CONTEXT);
            mac.update(ByteBuffer.allocate(16).putLong(account.getId()).putLong(account.getUserId()).array());
            updateText(mac, account.getProvider());
            updateText(mac, account.getExternalId());
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("无法校验关联账号", failure);
        }
    }

    public boolean matches(UserConnectedAccount account, String supplied) {
        if (supplied == null || supplied.length() != 64 || !supplied.matches("^[0-9a-f]{64}$")) return false;
        if (account.getId() == null || account.getUserId() == null || account.getProvider() == null
                || account.getExternalId() == null || account.getExternalId().isBlank() || account.getLinkedAt() == null) return false;
        return MessageDigest.isEqual(forBinding(account).getBytes(StandardCharsets.US_ASCII),
                supplied.getBytes(StandardCharsets.US_ASCII));
    }

    private static void updateText(Mac mac, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        mac.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        mac.update(bytes);
    }
}
