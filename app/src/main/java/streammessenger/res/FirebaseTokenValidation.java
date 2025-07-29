package streammessenger.res;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;

public class FirebaseTokenValidation {

    /**
     * @param token The token to validate with the firebase SDK APIs
     * @return A boolean state representing the token validation state
     * */
    public static boolean isTokenValid(String token)  {
        FirebaseToken verifiedToken;
        try {
            verifiedToken = FirebaseAuth.getInstance().verifyIdToken(token);
            return verifiedToken.getUid() != null;
        } catch (FirebaseAuthException ignore) {}
        return false;
    }
}
