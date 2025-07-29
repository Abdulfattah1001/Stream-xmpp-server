package streammessenger.res;

import javax.annotation.Nonnull;

public class PasswordBasedAuth {
    @SuppressWarnings("unused")
    private final String password;

    @SuppressWarnings("unused")
    private final String username;


    public PasswordBasedAuth(@Nonnull String phone_number, @Nonnull String password){
        this.password = password;
        this.username = phone_number;
    }

    public void authenticate(){
        @SuppressWarnings("unused")
        DatabaseManagement db = DatabaseManagement.getInstance(CredentialManager.getPassword(), CredentialManager.getDatabaseName(),CredentialManager.getDatabaseUsername());
    }

}