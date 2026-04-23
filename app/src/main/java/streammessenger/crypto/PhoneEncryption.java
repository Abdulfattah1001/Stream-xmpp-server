package streammessenger.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.logging.Logger;

/**
 * AES-256-GCM encryption for phone numbers at rest.
 *
 * WHY ENCRYPT PHONE NUMBERS:
 * ───────────────────────────
 * Even though we hash phone numbers for lookups, we also store
 * the original number for:
 *   - Account recovery
 *   - Displaying to the user their own number
 *   - OTP re-verification if needed
 *
 * If the DB is stolen, unencrypted phone numbers are PII (GDPR violation).
 * With AES-256-GCM: stolen DB reveals nothing about phone numbers.
 *
 * KEY MANAGEMENT:
 * ────────────────
 * The encryption key is NEVER stored in the database.
 * It lives in an environment variable (dev) or KMS (production).
 *
 * In production, use:
 *   AWS KMS:          aws-encryption-sdk-java
 *   Google Cloud KMS: google-cloud-kms
 *   HashiCorp Vault:  vault-java-driver
 * <p>
 * For now: key from environment variable PHONE_ENCRYPTION_KEY (32 bytes, hex)
 * <p>
 * Format stored in DB:
 *   BASE64(iv):BASE64(ciphertext)
 *   Example: "dGVzdGl2MTI=:YWVzY2lwaGVydGV4dA=="
 */
public final class PhoneEncryption {

    private static final Logger logger =
            Logger.getLogger(PhoneEncryption.class.getName());

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_LENGTH      = 12;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    // Loaded once at startup from environment
    private final byte[] encryptionKey;

    private static PhoneEncryption instance;

    private PhoneEncryption(byte[] key) {
        this.encryptionKey = key;
    }

    /**
     * Initializes the encryption service from the environment variable.
     * Must be called once at server startup before any DB operations.
     *
     * Set the key:
     *   export PHONE_ENCRYPTION_KEY=$(openssl rand -hex 32)
     *
     * The key is 64 hex chars = 32 bytes = 256-bit AES key.
     */
    public static synchronized PhoneEncryption initialize() {
        if (instance != null) return instance;

        String hexKey = System.getenv("PHONE_ENCRYPTION_KEY");
        if (hexKey == null || hexKey.isBlank()) {
            throw new IllegalStateException(
                "PHONE_ENCRYPTION_KEY environment variable not set. " +
                "Generate one with: openssl rand -hex 32"
            );
        }

        if (hexKey.length() != 64) {
            throw new IllegalStateException(
                "PHONE_ENCRYPTION_KEY must be exactly 64 hex chars (32 bytes). " +
                "Got: " + hexKey.length() + " chars"
            );
        }

        byte[] key = hexToBytes(hexKey);
        instance = new PhoneEncryption(key);
        logger.info("Phone encryption initialized.");
        return instance;
    }

    public static PhoneEncryption getInstance() {
        if (instance == null) {
            throw new IllegalStateException(
                "PhoneEncryption not initialized. Call initialize() first.");
        }
        return instance;
    }

    // =========================================================================
    // Encrypt
    // =========================================================================

    /**
     * Encrypts a phone number for storage.
     *
     * @param phoneNumber E.164 format: +2348012345678
     * @return Encrypted string in format: BASE64(iv):BASE64(ciphertext)
     */
    public String encrypt(String phoneNumber) {
        if (phoneNumber == null) return null;

        try {
            // Random IV - different for every encryption
            byte[] iv = new byte[IV_LENGTH];
            SECURE_RANDOM.nextBytes(iv);

            SecretKeySpec keySpec =
                    new SecretKeySpec(encryptionKey, "AES");
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec,
                    new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] ciphertext = cipher.doFinal(
                    phoneNumber.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            String ivBase64   = Base64.getEncoder().encodeToString(iv);
            String ctBase64   = Base64.getEncoder().encodeToString(ciphertext);

            return ivBase64 + ":" + ctBase64;

        } catch (Exception e) {
            throw new RuntimeException("Phone encryption failed", e);
        }
    }

    // =========================================================================
    // Decrypt
    // =========================================================================

    /**
     * Decrypts a stored phone number.
     *
     * @param encrypted Format: BASE64(iv):BASE64(ciphertext)
     * @return Original E.164 phone number
     */
    public String decrypt(String encrypted) {
        if (encrypted == null) return null;

        try {
            int sep = encrypted.indexOf(':');
            if (sep == -1) {
                throw new IllegalArgumentException(
                    "Invalid encrypted format - missing separator");
            }

            byte[] iv         = Base64.getDecoder()
                    .decode(encrypted.substring(0, sep));
            byte[] ciphertext = Base64.getDecoder()
                    .decode(encrypted.substring(sep + 1));

            SecretKeySpec keySpec =
                    new SecretKeySpec(encryptionKey, "AES");
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec,
                    new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext,
                    java.nio.charset.StandardCharsets.UTF_8);

        } catch (Exception e) {
            throw new RuntimeException("Phone decryption failed", e);
        }
    }

    // =========================================================================
    // Key rotation support
    // =========================================================================

    /**
     * Re-encrypts a phone number with a new key.
     * Used during key rotation to migrate all stored numbers.
     *
     * Steps for key rotation:
     *   1. Set NEW_PHONE_ENCRYPTION_KEY env var
     *   2. Call rotateKey() for each user record
     *   3. Update PHONE_ENCRYPTION_KEY to the new key
     *   4. Remove NEW_PHONE_ENCRYPTION_KEY env var
     */
    public String rotateKey(String encryptedWithOldKey,
                             byte[] newKey) {
        String plaintext = decrypt(encryptedWithOldKey);
        // Re-encrypt with new key (temporary PhoneEncryption instance)
        PhoneEncryption newEncryption = new PhoneEncryption(newKey);
        return newEncryption.encrypt(plaintext);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] result = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            result[i / 2] = (byte) Integer.parseInt(
                    hex.substring(i, i + 2), 16);
        }
        return result;
    }
}