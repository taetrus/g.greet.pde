import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

// Encoding canary — must stay compilable: çğıöşü ÇĞİÖŞÜ
/**
 * Produces ENC(...) values for the external config files
 * (configs/&lt;bundle&gt;/conf/*.properties). Run via the encrypt-config.{sh,bat}
 * wrappers (single-file source-launch, like BundleBuilder):
 *
 *   encrypt-config.sh &lt;plaintext&gt;            prints ENC(base64) to paste into a .properties file
 *   encrypt-config.sh --decrypt 'ENC(...)'   round-trip check: prints the plaintext
 *
 * AES-128/GCM with a fresh random 12-byte IV per run, IV prepended to the
 * ciphertext — encrypting the same plaintext twice yields different ENC()
 * strings; both decrypt fine.
 *
 * The XOR-split key constants MUST stay identical to
 * com.kk.greet.ui/src/com/kk/greet/ui/ConfCrypto.java (duplicated because
 * scripts/ cannot depend on bundle code). Reality check: the same key ships
 * inside the (obfuscated) bundle, so this is a deterrent against casual
 * inspection, not a security boundary.
 */
public final class EncryptConfig {

	// XOR-split AES-128 key: key[i] = K1[i] ^ K2[i]. Keep in sync with ConfCrypto.
	private static final byte[] K1 = { (byte) 0x4B, (byte) 0x21, (byte) 0x7E, (byte) 0x03, (byte) 0x9C, (byte) 0x5A,
			(byte) 0xE7, (byte) 0x30, (byte) 0x12, (byte) 0xD8, (byte) 0x66, (byte) 0xAF, (byte) 0x08, (byte) 0xC4,
			(byte) 0x59, (byte) 0x71 };
	private static final byte[] K2 = { (byte) 0x3D, (byte) 0x92, (byte) 0x40, (byte) 0xEE, (byte) 0x27, (byte) 0xB1,
			(byte) 0x0C, (byte) 0x85, (byte) 0xF3, (byte) 0x1A, (byte) 0xCD, (byte) 0x52, (byte) 0x9B, (byte) 0x60,
			(byte) 0xE2, (byte) 0x17 };

	private static final int IV_LEN = 12;
	private static final int TAG_BITS = 128;

	public static void main(String[] args) throws Exception {
		if (args.length == 1) {
			System.out.println("ENC(" + encrypt(args[0]) + ")");
		} else if (args.length == 2 && "--decrypt".equals(args[0])) {
			System.out.println(decrypt(unwrap(args[1])));
		} else {
			System.err.println("usage: encrypt-config <plaintext>          print ENC(...) for a .properties value");
			System.err.println("       encrypt-config --decrypt 'ENC(..)'  round-trip check");
			System.exit(2);
		}
	}

	private static String unwrap(String value) {
		String trimmed = value.trim();
		if (trimmed.startsWith("ENC(") && trimmed.endsWith(")")) {
			return trimmed.substring(4, trimmed.length() - 1);
		}
		return trimmed;
	}

	private static String encrypt(String plaintext) throws Exception {
		byte[] iv = new byte[IV_LEN];
		new SecureRandom().nextBytes(iv);
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(TAG_BITS, iv));
		byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
		byte[] blob = new byte[iv.length + ct.length];
		System.arraycopy(iv, 0, blob, 0, iv.length);
		System.arraycopy(ct, 0, blob, iv.length, ct.length);
		return Base64.getEncoder().encodeToString(blob);
	}

	private static String decrypt(String base64) throws Exception {
		byte[] blob = Base64.getDecoder().decode(base64);
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(), "AES"),
				new GCMParameterSpec(TAG_BITS, blob, 0, IV_LEN));
		return new String(cipher.doFinal(blob, IV_LEN, blob.length - IV_LEN), StandardCharsets.UTF_8);
	}

	private static byte[] key() {
		byte[] key = new byte[K1.length];
		for (int i = 0; i < key.length; i++) {
			key[i] = (byte) (K1[i] ^ K2[i]);
		}
		return key;
	}

}
