package com.kk.greet.ui;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Properties;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

// Encoding canary — must stay compilable: çğıöşü ÇĞİÖŞÜ
/**
 * Decrypts {@code ENC(base64)} values in external config files
 * (AES-128/GCM, 12-byte IV prepended to the ciphertext). Values are produced
 * by {@code scripts/EncryptConfig.java} — the key constants there MUST stay
 * identical to {@link #K1}/{@link #K2} here.
 *
 * Reality check: the key is embedded in this (obfuscated) bundle, so this is
 * a deterrent against casual inspection of the config files, not a security
 * boundary — anyone with the jar can recover the key. The key is XOR-split
 * across two arrays because ProGuard only renames identifiers; it never
 * touches constants, so a single literal would be visible to {@code strings}.
 */
final class ConfCrypto {

	// XOR-split AES-128 key: key[i] = K1[i] ^ K2[i].
	// Keep in sync with scripts/EncryptConfig.java.
	private static final byte[] K1 = { (byte) 0x4B, (byte) 0x21, (byte) 0x7E, (byte) 0x03, (byte) 0x9C, (byte) 0x5A,
			(byte) 0xE7, (byte) 0x30, (byte) 0x12, (byte) 0xD8, (byte) 0x66, (byte) 0xAF, (byte) 0x08, (byte) 0xC4,
			(byte) 0x59, (byte) 0x71 };
	private static final byte[] K2 = { (byte) 0x3D, (byte) 0x92, (byte) 0x40, (byte) 0xEE, (byte) 0x27, (byte) 0xB1,
			(byte) 0x0C, (byte) 0x85, (byte) 0xF3, (byte) 0x1A, (byte) 0xCD, (byte) 0x52, (byte) 0x9B, (byte) 0x60,
			(byte) 0xE2, (byte) 0x17 };

	private static final int IV_LEN = 12;
	private static final int TAG_BITS = 128;

	private ConfCrypto() {
	}

	/**
	 * Replaces every {@code ENC(...)} value in {@code props} with its decrypted
	 * plaintext. A value that cannot be decrypted is left as-is with a warning
	 * (fail-soft, like UiConfig's numeric fallback).
	 */
	static void resolve(Properties props) {
		for (String name : props.stringPropertyNames()) {
			String value = props.getProperty(name);
			if (value == null) {
				continue;
			}
			String trimmed = value.trim();
			if (trimmed.startsWith("ENC(") && trimmed.endsWith(")")) {
				try {
					props.setProperty(name, decrypt(trimmed.substring(4, trimmed.length() - 1)));
				} catch (Exception e) {
					System.err.println("ConfCrypto: cannot decrypt " + name + " (" + e + "), leaving as-is");
				}
			}
		}
	}

	private static String decrypt(String base64) throws Exception {
		byte[] blob = Base64.getDecoder().decode(base64);
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(), "AES"),
				new GCMParameterSpec(TAG_BITS, blob, 0, IV_LEN));
		byte[] plain = cipher.doFinal(blob, IV_LEN, blob.length - IV_LEN);
		return new String(plain, StandardCharsets.UTF_8);
	}

	private static byte[] key() {
		byte[] key = new byte[K1.length];
		for (int i = 0; i < key.length; i++) {
			key[i] = (byte) (K1[i] ^ K2[i]);
		}
		return key;
	}

}
