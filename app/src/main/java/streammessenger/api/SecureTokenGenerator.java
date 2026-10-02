package streammessenger.api;

import java.security.SecureRandom;
import java.util.Base64;

public class SecureTokenGenerator {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 24; // ~32 char URL-safe string
    private static final String ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int TOKEN_LENGTH = 10;

    public static String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder()
                     .withoutPadding()
                     .encodeToString(bytes);
    }


    /*public static String generate() {
        StringBuilder sb = new StringBuilder(TOKEN_LENGTH);
        for (int i = 0; i < TOKEN_LENGTH; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }*/
}