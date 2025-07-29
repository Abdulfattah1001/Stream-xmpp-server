package streammessenger.res;


import java.util.Base64;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * @version 1.0.0
 * @author Abdulfattah Ameen
 */
public class TokenBasedValidation {
    private String AUTH_KEY = "ABDULFATTAH-ABDULFATTAH-ABDULFATTAH-ABDULFATTAH-ABDULFATTAH-ABDULFATTAH-ABDULFATTAH-ABDULFATTAH-ABDULFATTAH";
    private String phone_number = null;
    private String user_id = null;

    public TokenBasedValidation(String token){
        String decodedTokenBody = new String(Base64.getDecoder().decode(token));
        try{
            Jws<Claims> claims = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(AUTH_KEY.getBytes())).build().parseSignedClaims(decodedTokenBody);
            phone_number = (String) claims.getPayload().get("phone_number");

            user_id = (String) claims.getPayload().get("user_id");
        }catch(JwtException exception){
            System.out.println("Error occurred==>"+exception.getMessage());
        }
    }

    public String getPhoneNumber(){
        return phone_number;
    }

    public String getUserId(){
        return user_id;
    }

    public boolean isUserAuthenticated(){
        //DatabaseManagement db = DatabaseManager.getDBConnection();
        DatabaseManagement db = DatabaseManagement.getInstance(CredentialManager.getPassword(), CredentialManager.getDatabaseUsername(), CredentialManager.getDatabaseName());

        return true;
        /**if(db != null){
            return db.is_user_exists_by_phone_number(phone_number);
        }else throw new IllegalStateException("The Database is not instantiated yet, try initiating the database");*/
        
    }
}
