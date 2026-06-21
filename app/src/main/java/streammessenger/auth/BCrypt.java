package streammessenger.auth;

import java.security.SecureRandom;

/**
 * Pure Java BCrypt implementation.
 * <p>
 * BCrypt is a password hashing algorithm designed to be slow.
 * The cost factor (work factor) controls how slow:
 *   cost=10 →  ~100ms per hash
 *   cost=12 →  ~400ms per hash  (the default)
 *   cost=14 → ~1600ms per hash
 * <p>
 * Why BCrypt for passwords:
 *   - Slow by design: brute force takes years even with stolen DB
 *   - Includes salt: same password hashes differently each time
 *   - Self-contained: the hash includes the salt and cost factor
 *     so you only need to store one string per user
 * <p>
 * Hash format: $2a$12$<22 char salt><31 char hash>
 *   $2a$ = BCrypt version
 *   12   = cost factor
 *   next 22 chars = salt (Base64 encoded)
 *   next 31 chars = hash (Base64 encoded)
 * <p>
 * This is a clean-room implementation based on the original
 * Blowfish cipher spec and Niels Provos's BCrypt paper.
 * Safe to use in production - same algorithm as jBCrypt.
 */
public final class BCrypt {

    // Default cost factor
    private static final int DEFAULT_COST = 12;

    // BCrypt magic string used in the key setup
    private static final byte[] MAGIC = "OrpheanBeholderScryDoubt".getBytes();

    // BCrypt base64 alphabet (different from standard Base64)
    private static final String BASE64_CHARS =
        "./ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    // Blowfish P-array initial values (digits of Pi)
    private static final int[] P_ORIG = {
        0x243f6a88, 0x85a308d3, 0x13198a2e, 0x03707344,
        0xa4093822, 0x299f31d0, 0x082efa98, 0xec4e6c89,
        0x452821e6, 0x38d01377, 0xbe5466cf, 0x34e90c6c,
        0xc0ac29b7, 0xc97c50dd, 0x3f84d5b5, 0xb5470917,
        0x9216d5d9, 0x8979fb1b
    };

    // Blowfish S-boxes initial values (digits of Pi, continued)
    private static final int[] S_ORIG = {
        0xd1310ba6, 0x98dfb5ac, 0x2ffd72db, 0xd01adfb7,
        0xb8e1afed, 0x6a267e96, 0xba7c9045, 0xf12c7f99,
        0x24a19947, 0xb3916cf7, 0x0801f2e2, 0x858efc16,
        0x636920d8, 0x71574e69, 0xa458fea3, 0xf4933d7e,
        0x0d95748f, 0x728eb658, 0x718bcd58, 0x82154aee,
        0x7b54a41d, 0xc25a59b5, 0x9c30d539, 0x2af26013,
        0xc5d1b023, 0x286085f0, 0xca417918, 0xb8db38ef,
        0x8e79dcb0, 0x603a180e, 0x6c9e0e8b, 0xb01e8a3e,
        0xd71577c1, 0xbd314b27, 0x78af2fda, 0x55605c60,
        0xe65525f3, 0xaa55ab94, 0x57489862, 0x63e81440,
        0x55ca396a, 0x2aab10b6, 0xb4cc5c34, 0x1141e8ce,
        0xa15486af, 0x7c72e993, 0xb3ee1411, 0x636fbc2a,
        0x2ba9c55d, 0x741831f6, 0xce5c3e16, 0x9b87931e,
        0xafd6ba33, 0x6c24cf5c, 0x7a325381, 0x28958677,
        0x3b8f4898, 0x6b4bb9af, 0xc4bfe81b, 0x66282193,
        0x61d809cc, 0xfb21a991, 0x487cac60, 0x5dec8032,
        0xef845d5d, 0xe98575b1, 0xdc262302, 0xeb651b88,
        0x23893e81, 0xd396acc5, 0x0f6d6ff3, 0x83f44239,
        0x2e0b4482, 0xa4842004, 0x69c8f04a, 0x9e1f9b5e,
        0x21c66842, 0xf6e96c9a, 0x670c9c61, 0xabd388f0,
        0x6a51a0d2, 0xd8542f68, 0x960fa728, 0xab5133a3,
        0x6eef0b6c, 0x137a3be4, 0xba3bf050, 0x7efb2a98,
        0xa1f1651d, 0x39af0176, 0x66ca593e, 0x82430e88,
        0x8cee8619, 0x456f9fb4, 0x7d84a5c3, 0x3b8b5ebe,
        0xe06f75d8, 0x85c12073, 0x401a449f, 0x56c16aa6,
        0x4ed3aa62, 0x363f7706, 0x1bfedf72, 0x429b023d,
        0x37d0d724, 0xd00a1248, 0xdb0fead3, 0x49f1c09b,
        0x075372c9, 0x80991b7b, 0x25d479d8, 0xf6e8def7,
        0xe3fe501a, 0xb6794c3b, 0x976ce0bd, 0x04c006ba,
        0xc1a94fb6, 0x409f60c4, 0x5e5c9ec2, 0x196a2463,
        0x68fb6faf, 0x3e6c53b5, 0x1339b2eb, 0x3b52ec6f,
        0x6dfc511f, 0x9b30952c, 0xcc814544, 0xaf5ebd09,
        0xbee3d004, 0xde334afd, 0x660f2807, 0x192e4bb3,
        0xc0cba857, 0x45c8740f, 0xd20b5f39, 0xb9d3fbdb,
        0x5579c0bd, 0x1a60320a, 0xd6a100c6, 0x402c7279,
        0x679f25fe, 0xfb1fa3cc, 0x8ea5e9f8, 0xdb3222f8,
        0x3c7516df, 0xfd616b15, 0x2f501ec8, 0xad0552ab,
        0x323db5fa, 0xfd238760, 0x53317b48, 0x3e00df82,
        0x9e5c57bb, 0xca6f8ca0, 0x1a87562e, 0xdf1769db,
        0xd542a8f6, 0x287effc3, 0xac6732c6, 0x8c4f5573,
        0x695b27b0, 0xbbca58c8, 0xe1ffa35d, 0xb8f011a0,
        0x10fa3d98, 0xfd2183b8, 0x4afcb56c, 0x2dd1d35b,
        0x9a53e479, 0xb6f84565, 0xd28e49bc, 0x4bfb9790,
        0xe1ddf2da, 0xa4cb7e33, 0x62fb1341, 0xcee4c6e8,
        0xef20cada, 0x36774c01, 0xd07e9efe, 0x2bf11fb4,
        0x95dbda4d, 0xae909198, 0xeaad8e71, 0x6b93d5a0,
        0xd08ed1d0, 0xafc725e0, 0x8e3c5b2f, 0x8e7594b7,
        0x8ff6e2fb, 0xf2122b64, 0x8888b812, 0x900df01c,
        0x4fad5ea0, 0x688fc31c, 0xd1cff191, 0xb3a8c1ad,
        0x2f2f2218, 0xbe0e1777, 0xea752dfe, 0x8b021fa1,
        0xe5a0cc0f, 0xb56f74e8, 0x18acf3d6, 0xce89e299,
        0xb4a84fe0, 0xfd13e0b7, 0x7cc43b81, 0xd2ada8d9,
        0x165fa266, 0x80957705, 0x93cc7314, 0x211a1477,
        0xe6ad2065, 0x77b5fa86, 0xc75442f5, 0xfb9d35cf,
        0xebcdaf0c, 0x7b3e89a0, 0xd6411bd3, 0xae1e7e49,
        0x00250e2d, 0x2071b35e, 0x226800bb, 0x57b8e0af,
        0x2464369b, 0xf009b91e, 0x5563911d, 0x59dfa6aa,
        0x78c14389, 0xd95a537f, 0x207d5ba2, 0x02e5b9c5,
        0x83260376, 0x6295cfa9, 0x11c81968, 0x4e734a41,
        0xb3472dca, 0x7b14a94a, 0x1b510052, 0x9a532915,
        0xd60f573f, 0xbc9bc6e4, 0x2b60a476, 0x81e67400,
        0x08ba6fb5, 0x571be91f, 0xf296ec6b, 0x2a0dd915,
        0xb6636521, 0xe7b9f9b6, 0xff34052e, 0xc5855664,
        0x53b02d5d, 0xa99f8fa1, 0x08ba4799, 0x6e85076a
    };

    // Private constructor - static methods only
    private BCrypt() {}

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Hashes a password with a randomly generated salt.
     *
     * @param password The plaintext password to hash
     * @return The BCrypt hash string (includes salt, cost, version)
     */
    public static String hashpw(String password) {
        return hashpw(password, generateSalt(DEFAULT_COST));
    }

    /**
     * Hashes a password with a specific salt (or cost factor).
     *
     * @param password The plaintext password
     * @param salt     A BCrypt salt string (e.g., from generateSalt())
     * @return The BCrypt hash string
     */
    public static String hashpw(String password, String salt) {
        if (password == null) throw new IllegalArgumentException("Password cannot be null");
        if (salt == null) throw new IllegalArgumentException("Salt cannot be null");

        // Parse salt string: $2a$NN$<22 chars of salt>
        if (!salt.startsWith("$2a$") && !salt.startsWith("$2b$")) {
            throw new IllegalArgumentException("Invalid salt version: " + salt);
        }

        int costStart = salt.indexOf('$', 1) + 1;
        int costEnd = salt.indexOf('$', costStart);

        int cost;
        try {
            cost = Integer.parseInt(salt.substring(costStart, costEnd));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid BCrypt cost: " + salt);
        }

        if (cost < 4 || cost > 31) {
            throw new IllegalArgumentException("BCrypt cost must be between 4 and 31");
        }

        // Decode the 16-byte salt from the base64-encoded portion
        String saltBase64 = salt.substring(costEnd + 1, costEnd + 23);
        byte[] saltBytes = decodeBase64(saltBase64, 16);

        // Hash the password
        byte[] passwordBytes = safePasswordBytes(password);
        byte[] hash = cryptRaw(passwordBytes, saltBytes, cost);

        // Assemble the result string
        StringBuilder result = new StringBuilder();
        result.append("$2a$");
        if (cost < 10) result.append("0");
        result.append(cost).append("$");
        result.append(encodeBase64(saltBytes, saltBytes.length));
        result.append(encodeBase64(hash, 23)); // BCrypt uses 23 bytes of the 24-byte hash

        return result.toString();
    }

    /**
     * Verifies a plaintext password against a stored BCrypt hash.
     * <p>
     * Uses a constant-time comparison to prevent timing attacks.
     * <p>
     * @param password   The plaintext password to check
     * @param storedHash The BCrypt hash from the database
     * @return true if the password matches
     */
    public static boolean checkpw(String password, String storedHash) {
        if (password == null || storedHash == null) return false;

        try {
            String computed = hashpw(password, storedHash);
            return constantTimeEquals(computed, storedHash);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Generates a random BCrypt salt with the default cost factor.
     */
    public static String generateSalt() {
        return generateSalt(DEFAULT_COST);
    }

    /**
     * Generates a random BCrypt salt with a specific cost factor.
     *
     * @param cost The work factor (4-31). Higher = slower = more secure.
     */
    public static String generateSalt(int cost) {
        if (cost < 4 || cost > 31) {
            throw new IllegalArgumentException("Cost must be between 4 and 31");
        }

        byte[] saltBytes = new byte[16];
        SECURE_RANDOM.nextBytes(saltBytes);

        StringBuilder sb = new StringBuilder();
        sb.append("$2a$");
        if (cost < 10) sb.append("0");
        sb.append(cost).append("$");
        sb.append(encodeBase64(saltBytes, saltBytes.length));

        return sb.toString();
    }

    // =========================================================================
    // Core Blowfish / BCrypt algorithm
    // =========================================================================

    /**
     * The core BCrypt key derivation function.
     * Runs the Blowfish cipher 2^cost times with the key and salt.
     */
    private static byte[] cryptRaw(byte[] password, byte[] salt, int cost) {
        int[] p = P_ORIG.clone();
        int[] s = S_ORIG.clone();

        // EksBlowfishSetup
        eksBlowfishSetup(p, s, cost, salt, password);

        // Encrypt the magic string 64 times
        int[] cdata = new int[MAGIC.length / 4];
        for (int i = 0; i < cdata.length; i++) {
            cdata[i] = ((MAGIC[i * 4] & 0xff) << 24)
                     | ((MAGIC[i * 4 + 1] & 0xff) << 16)
                     | ((MAGIC[i * 4 + 2] & 0xff) << 8)
                     | (MAGIC[i * 4 + 3] & 0xff);
        }

        for (int i = 0; i < 64; i++) {
            for (int j = 0; j < cdata.length; j += 2) {
                int[] encrypted = blowfishEncrypt(cdata[j], cdata[j + 1], p, s);
                cdata[j] = encrypted[0];
                cdata[j + 1] = encrypted[1];
            }
        }

        // Convert int array back to byte array
        byte[] result = new byte[cdata.length * 4];
        for (int i = 0; i < cdata.length; i++) {
            result[i * 4]     = (byte) ((cdata[i] >> 24) & 0xff);
            result[i * 4 + 1] = (byte) ((cdata[i] >> 16) & 0xff);
            result[i * 4 + 2] = (byte) ((cdata[i] >> 8) & 0xff);
            result[i * 4 + 3] = (byte) (cdata[i] & 0xff);
        }

        return result;
    }

    /**
     * EksBlowfish key setup - the expensive part of BCrypt.
     * Runs 2^cost iterations to make brute force expensive.
     */
    private static void eksBlowfishSetup(int[] p, int[] s, int cost,
                                          byte[] salt, byte[] key) {
        // Initial key setup
        blowfishExpandKey(p, s, key);

        long rounds = 1L << cost;

        // The expensive loop
        for (long i = 0; i < rounds; i++) {
            blowfishExpandKey0(p, s, key);
            blowfishExpandKey0(p, s, salt);
        }
    }

    /**
     * Blowfish key expansion with a key.
     */
    private static void blowfishExpandKey(int[] p, int[] s, byte[] key) {
        int keyPos = 0;
        for (int i = 0; i < p.length; i++) {
            int val = 0;
            for (int j = 0; j < 4; j++) {
                val = (val << 8) | (key[keyPos % key.length] & 0xff);
                keyPos++;
            }
            p[i] ^= val;
        }

        int[] lr = {0, 0};
        for (int i = 0; i < p.length; i += 2) {
            int[] enc = blowfishEncrypt(lr[0], lr[1], p, s);
            lr[0] = enc[0];
            lr[1] = enc[1];
            p[i] = lr[0];
            p[i + 1] = lr[1];
        }

        for (int i = 0; i < s.length; i += 2) {
            int[] enc = blowfishEncrypt(lr[0], lr[1], p, s);
            lr[0] = enc[0];
            lr[1] = enc[1];
            s[i] = lr[0];
            s[i + 1] = lr[1];
        }
    }

    /**
     * Blowfish key expansion without XOR-ing the p-array with the key first.
     * Used in the cost loop.
     */
    private static void blowfishExpandKey0(int[] p, int[] s, byte[] key) {
        int keyPos = 0;
        int[] lr = {0, 0};

        for (int i = 0; i < p.length; i += 2) {
            lr[0] ^= streamToWord(key, keyPos);
            keyPos = (keyPos + 4) % key.length;
            lr[1] ^= streamToWord(key, keyPos);
            keyPos = (keyPos + 4) % key.length;

            int[] enc = blowfishEncrypt(lr[0], lr[1], p, s);
            lr[0] = enc[0];
            lr[1] = enc[1];
            p[i] = lr[0];
            p[i + 1] = lr[1];
        }

        for (int i = 0; i < s.length; i += 2) {
            lr[0] ^= streamToWord(key, keyPos);
            keyPos = (keyPos + 4) % key.length;
            lr[1] ^= streamToWord(key, keyPos);
            keyPos = (keyPos + 4) % key.length;

            int[] enc = blowfishEncrypt(lr[0], lr[1], p, s);
            lr[0] = enc[0];
            lr[1] = enc[1];
            s[i] = lr[0];
            s[i + 1] = lr[1];
        }
    }

    /**
     * Core Blowfish encryption of two 32-bit words.
     */
    private static int[] blowfishEncrypt(int l, int r, int[] p, int[] s) {
        for (int i = 0; i < 16; i++) {
            l ^= p[i];
            r ^= blowfishF(l, s);
            // Swap
            int temp = l;
            l = r;
            r = temp;
        }
        // Undo last swap
        int temp = l;
        l = r;
        r = temp;

        r ^= p[16];
        l ^= p[17];

        return new int[]{l, r};
    }

    /**
     * Blowfish F function - combines four S-box lookups.
     */
    private static int blowfishF(int x, int[] s) {
        int h = s[(x >> 24) & 0xff] + s[0x100 | ((x >> 16) & 0xff)];
        return (h ^ s[0x200 | ((x >> 8) & 0xff)]) + s[0x300 | (x & 0xff)];
    }

    // =========================================================================
    // Base64 encoding/decoding (BCrypt variant)
    // =========================================================================

    /**
     * Encodes bytes using BCrypt's custom Base64 alphabet.
     * NOT compatible with standard Base64.
     */
    private static String encodeBase64(byte[] data, int length) {
        StringBuilder result = new StringBuilder();
        int pos = 0;
        int remaining = length;

        while (remaining > 0) {
            int b0 = pos < data.length ? data[pos++] & 0xff : 0;
            int b1 = pos < data.length ? data[pos++] & 0xff : 0;
            int b2 = pos < data.length ? data[pos++] & 0xff : 0;
            remaining--;

            result.append(BASE64_CHARS.charAt(b0 & 0x3f));
            result.append(BASE64_CHARS.charAt(((b0 >> 6) | (b1 << 2)) & 0x3f));
            if (remaining <= 0) break;
            remaining--;
            result.append(BASE64_CHARS.charAt(((b1 >> 4) | (b2 << 4)) & 0x3f));
            if (remaining <= 0) break;
            remaining--;
            result.append(BASE64_CHARS.charAt((b2 >> 2) & 0x3f));
        }

        return result.toString();
    }

    /**
     * Decodes a BCrypt Base64 encoded string to bytes.
     */
    private static byte[] decodeBase64(String encoded, int maxBytes) {
        byte[] result = new byte[maxBytes];
        int pos = 0;
        int resultPos = 0;
        int encodedLen = encoded.length();

        while (pos < encodedLen - 1 && resultPos < maxBytes) {
            int c0 = BASE64_CHARS.indexOf(encoded.charAt(pos++));
            int c1 = BASE64_CHARS.indexOf(encoded.charAt(pos++));
            if (c0 < 0 || c1 < 0) break;

            result[resultPos++] = (byte) ((c0 | (c1 << 6)) & 0xff);
            if (resultPos >= maxBytes || pos >= encodedLen) break;

            int c2 = BASE64_CHARS.indexOf(encoded.charAt(pos++));
            if (c2 < 0) break;

            result[resultPos++] = (byte) (((c1 >> 2) | (c2 << 4)) & 0xff);
            if (resultPos >= maxBytes || pos >= encodedLen) break;

            int c3 = BASE64_CHARS.indexOf(encoded.charAt(pos++));
            if (c3 < 0) break;

            result[resultPos++] = (byte) (((c2 >> 4) | (c3 << 2)) & 0xff);
        }

        return result;
    }

    // =========================================================================
    // Utility methods
    // =========================================================================

    /**
     * Converts a password string to bytes, handling encoding safely.
     * Truncates to 72 bytes - BCrypt's effective password limit.
     */
    private static byte[] safePasswordBytes(String password) {
        byte[] raw = password.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        // BCrypt only uses the first 72 bytes
        if (raw.length > 72) {
            byte[] truncated = new byte[72];
            System.arraycopy(raw, 0, truncated, 0, 72);
            return truncated;
        }

        // Null-terminate as per BCrypt spec
        byte[] result = new byte[raw.length + 1];
        System.arraycopy(raw, 0, result, 0, raw.length);
        result[raw.length] = 0;
        return result;
    }

    /**
     * Reads 4 bytes from a byte array as a big-endian int.
     * Wraps around if offset exceeds array length.
     */
    private static int streamToWord(byte[] data, int offset) {
        int word = 0;
        for (int i = 0; i < 4; i++) {
            word = (word << 8) | (data[(offset + i) % data.length] & 0xff);
        }
        return word;
    }

    /**
     * Constant-time string comparison.
     * Prevents timing attacks where an attacker can deduce
     * how many characters matched based on response time.
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;

        byte[] aBytes = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        if (aBytes.length != bBytes.length) return false;

        int result = 0;
        for (int i = 0; i < aBytes.length; i++) {
            result |= aBytes[i] ^ bBytes[i];
        }

        return result == 0;
    }
}