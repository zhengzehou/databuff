package com.databuff.apm.web.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Reversible password encryption for the login page.
 * <p>
 * Frontend encrypts with crypto-js {@code AES/ECB/PKCS7} using the same 16-byte key and sends
 * Base64; the backend decrypts to plaintext, then either runs the local admin check
 * ({@code PortalPasswordCodec} re-digests the plaintext) or forwards the plaintext to the
 * employee passport service.
 */
@Component
public class PasswordAesUtil {

    private static final String TRANSFORMATION = "AES/ECB/PKCS5Padding";

    /** Default 16-byte dev key — must match the frontend {@code encrypt.ts} key. */
    public static final String DEFAULT_KEY = "ApmWebLoginKey01";

    private final SecretKeySpec key;

    public PasswordAesUtil(
            @Value("${apm.security.password-encrypt-key:" + DEFAULT_KEY + "}") String key) {
        this.key = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES");
    }

    /**
     * Decrypt a Base64 AES/ECB ciphertext produced by the frontend.
     *
     * @return the plaintext, or {@code null} when the input is not valid AES ciphertext
     */
    public String decrypt(String base64Ciphertext) {
        if (base64Ciphertext == null || base64Ciphertext.isBlank()) {
            return null;
        }
        try {
            byte[] cipher = Base64.getDecoder().decode(base64Ciphertext);
            Cipher c = Cipher.getInstance(TRANSFORMATION);
            c.init(Cipher.DECRYPT_MODE, key);
            return new String(c.doFinal(cipher), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** Encrypt a plaintext to the same Base64 form the frontend produces (tests/verification). */
    public String encrypt(String plaintext) {
        try {
            Cipher c = Cipher.getInstance(TRANSFORMATION);
            c.init(Cipher.ENCRYPT_MODE, key);
            byte[] cipher = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(cipher);
        } catch (Exception e) {
            throw new IllegalStateException("AES encrypt failed: " + e.getMessage(), e);
        }
    }
}
