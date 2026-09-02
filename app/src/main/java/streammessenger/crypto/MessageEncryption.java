package streammessenger.crypto;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * End-to-end message encryption using AES-256-GCM.
 * <p>
 * The server NEVER sees plaintext. Only ciphertext is stored/routed.
 * <p>
 * Key Exchange (done on client, not server):
 *   1. Alice and Bob each have an ECDH key pair (Curve25519 / EC)
 *   2. They exchange public keys via the server's key store
 *   3. Both derive the SAME shared secret independently:
 *      shared_secret = ECDH(alice_private, bob_public)
 *                    = ECDH(bob_private, alice_public)
 *   4. This shared secret never touches the server
 * <p>
 * Per-message encryption:
 *   1. Generate a random 256-bit AES key for each message
 *   2. Encrypt the message with AES-256-GCM
 *   3. Encrypt the AES key with the shared ECDH secret
 *   4. Send: encrypted_message + encrypted_key + iv
 * <p>
 * This class runs on the CLIENT only.
 * The server stores and routes the ciphertext blobs.
 */
public final class MessageEncryption {

    private static final String AES_ALGORITHM  = "AES/GCM/NoPadding";
    private static final String ECDH_ALGORITHM = "ECDH";
    private static final String KEY_ALGORITHM  = "EC";
    private static final int    GCM_TAG_LENGTH = 128; // bits
    private static final int    IV_LENGTH      = 12;  // bytes

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private MessageEncryption() {}

    // =========================================================================
    // Key Pair Generation (called once per user on first install)
    // =========================================================================

    /**
     * Generates an EC key pair for ECDH key exchange.
     * The PUBLIC key is uploaded to the server.
     * The PRIVATE key stays on the device - NEVER leaves.
     */
    public static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(KEY_ALGORITHM);
            kpg.initialize(256); // P-256 curve
            return kpg.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("EC key generation failed", e);
        }
    }

    /**
     * Encodes a public key to Base64 for transmission/storage.
     */
    public static String encodePublicKey(PublicKey publicKey) {
        return Base64.getEncoder()
                .encodeToString(publicKey.getEncoded());
    }

    /**
     * Decodes a Base64 public key received from the server.
     */
    public static PublicKey decodePublicKey(String base64Key) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(base64Key);
            X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
            KeyFactory kf = KeyFactory.getInstance(KEY_ALGORITHM);
            return kf.generatePublic(spec);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decode public key", e);
        }
    }

    // =========================================================================
    // Shared Secret Derivation (ECDH)
    // =========================================================================

    /**
     * Derives the shared secret between two parties.
     * <p>
     * Alice calls:  deriveSharedSecret(alice_private, bob_public)
     * Bob calls:    deriveSharedSecret(bob_private, alice_public)
     * Both get the SAME 32-byte secret.
     * The server never sees either private key or the shared secret.
     */
    public static byte[] deriveSharedSecret(PrivateKey ourPrivate,
                                             PublicKey theirPublic) {
        try {
            KeyAgreement ka = KeyAgreement.getInstance(ECDH_ALGORITHM);
            ka.init(ourPrivate);
            ka.doPhase(theirPublic, true);
            return ka.generateSecret();
        } catch (Exception e) {
            throw new RuntimeException("ECDH key agreement failed", e);
        }
    }

    /**
     * Derives a 32-byte AES key from the ECDH shared secret
     * using HKDF (HMAC-based Key Derivation Function).
     * <p>
     * The context string differentiates keys for different purposes
     * (message encryption vs metadata encryption vs etc.)
     */
    public static byte[] deriveEncryptionKey(byte[] sharedSecret,
                                              String context) {
        try {
            // HKDF-Extract: create PRK from shared secret
            javax.crypto.Mac hmac = javax.crypto.Mac.getInstance("HmacSHA256");
            byte[] salt = "xmpp-e2e-v1".getBytes(
                    java.nio.charset.StandardCharsets.UTF_8);
            hmac.init(new javax.crypto.spec.SecretKeySpec(salt, "HmacSHA256"));
            byte[] prk = hmac.doFinal(sharedSecret);

            // HKDF-Expand: derive key material
            hmac.init(new javax.crypto.spec.SecretKeySpec(prk, "HmacSHA256"));
            byte[] info = context.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] okm = new byte[32]; // 256 bits

            // T(1) = HMAC(PRK, "" || info || 0x01)
            byte[] t = new byte[info.length + 1];
            System.arraycopy(info, 0, t, 0, info.length);
            t[info.length] = 0x01;
            byte[] result = hmac.doFinal(t);
            System.arraycopy(result, 0, okm, 0, Math.min(result.length, 32));

            return okm;
        } catch (Exception e) {
            throw new RuntimeException("Key derivation failed", e);
        }
    }

    // =========================================================================
    // Text Message Encryption
    // =========================================================================

    /**
     * Encrypts a text message.
     *
     * @param plaintext   The message text
     * @param aesKey      32-byte AES-256 key (derived from shared secret)
     * @return EncryptedMessage containing ciphertext + iv
     */
    public static EncryptedMessage encryptText(String plaintext,
                                                byte[] aesKey) {
        try {
            byte[] plaintextBytes = plaintext.getBytes(
                    java.nio.charset.StandardCharsets.UTF_8);

            return encryptBytes(plaintextBytes, aesKey);

        } catch (Exception e) {
            throw new RuntimeException("Text encryption failed", e);
        }
    }

    /**
     * Decrypts a text message.
     *
     * @param encrypted   The EncryptedMessage from the server
     * @param aesKey      32-byte AES-256 key
     * @return The original plaintext
     */
    public static String decryptText(EncryptedMessage encrypted,
                                      byte[] aesKey) {
        try {
            byte[] plaintext = decryptBytes(encrypted, aesKey);
            return new String(plaintext, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Text decryption failed", e);
        }
    }

    // =========================================================================
    // Media Encryption
    // =========================================================================

    /**
     * Generates a random AES-256 key for encrypting a media file.
     * This key is then encrypted with the shared ECDH key and
     * sent to the recipient alongside the media reference.
     * <p>
     * Each media file gets its OWN random key.
     * Even if one media key is compromised, others are safe.
     */
    public static byte[] generateMediaKey() {
        try {
            KeyGenerator kg = KeyGenerator.getInstance("AES");
            kg.init(256, SECURE_RANDOM);
            SecretKey key = kg.generateKey();
            return key.getEncoded();
        } catch (Exception e) {
            throw new RuntimeException("Media key generation failed", e);
        }
    }

    /**
     * Encrypts raw media bytes (image, video, audio, file).
     *
     * @param mediaBytes  Raw media content
     * @param mediaKey    Random AES key for this specific media file
     * @return Encrypted media + iv
     */
    public static EncryptedMessage encryptMedia(byte[] mediaBytes,
                                                 byte[] mediaKey) {
        return encryptBytes(mediaBytes, mediaKey);
    }

    /**
     * Decrypts media bytes.
     */
    public static byte[] decryptMedia(EncryptedMessage encrypted,
                                       byte[] mediaKey) {
        return decryptBytes(encrypted, mediaKey);
    }

    /**
     * Encrypts the media key itself using the conversation shared key.
     * The encrypted media key is what gets stored in messages.encrypted_content.
     * <p>
     * This way:
     *   - Media file is encrypted with random mediaKey
     *   - mediaKey is encrypted with sharedKey
     *   - Server stores: encrypted_media_key + media_storage_path
     *   - Client: decrypts media_key → downloads and decrypts media file
     */
    public static EncryptedMessage encryptMediaKey(byte[] mediaKey,
                                                    byte[] sharedKey) {
        return encryptBytes(mediaKey, sharedKey);
    }

    public static byte[] decryptMediaKey(EncryptedMessage encryptedKey,
                                          byte[] sharedKey) {
        return decryptBytes(encryptedKey, sharedKey);
    }

    // =========================================================================
    // Core AES-256-GCM Operations
    // =========================================================================

    private static EncryptedMessage encryptBytes(byte[] plaintext,
                                                  byte[] keyBytes) {
        try {
            // Random IV - MUST be unique per encryption
            byte[] iv = new byte[IV_LENGTH];
            SECURE_RANDOM.nextBytes(iv);

            SecretKey key = new SecretKeySpec(keyBytes, "AES");
            Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key,
                    new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] ciphertext = cipher.doFinal(plaintext);

            return new EncryptedMessage(
                    Base64.getEncoder().encodeToString(ciphertext),
                    Base64.getEncoder().encodeToString(iv)
            );
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    private static byte[] decryptBytes(EncryptedMessage encrypted,
                                        byte[] keyBytes) {
        try {
            byte[] ciphertext = Base64.getDecoder()
                    .decode(encrypted.ciphertext());
            byte[] iv = Base64.getDecoder()
                    .decode(encrypted.iv());

            SecretKey key = new SecretKeySpec(keyBytes, "AES");
            Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }

    // =========================================================================
    // Integrity Verification
    // =========================================================================

    /**
     * Computes SHA-256 hash of plaintext media.
     * Stored alongside the encrypted media so the recipient can
     * verify integrity after downloading and decrypting.
     */
    public static String computeMediaHash(byte[] plaintextBytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(plaintextBytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    // =========================================================================
    // Inner types
    // =========================================================================

    /**
     * The result of encryption.
     * Both fields are Base64-encoded strings safe for JSON/DB storage.
     */
    public record EncryptedMessage(
            String ciphertext, // Base64 AES-GCM ciphertext
            String iv          // Base64 12-byte initialization vector
    ) {
        /**
         * Serializes to a compact format for XMPP stanza embedding.
         * Format: iv:ciphertext (both Base64, separated by colon)
         */
        public String toCompact() {
            return iv + ":" + ciphertext;
        }

        /**
         * Deserializes from compact format.
         */
        public static EncryptedMessage fromCompact(String compact) {
            int sep = compact.indexOf(':');
            if (sep == -1) throw new IllegalArgumentException(
                "Invalid compact format");
            return new EncryptedMessage(
                    compact.substring(sep + 1),
                    compact.substring(0, sep)
            );
        }
    }
}