package streammessenger.res;

public class Authentication {
    public boolean isTokenValid(String token){
        return FirebaseTokenValidation.isTokenValid(token);
    }
}