package streammessenger.res;

public class CredentialManager {
    private static String databaseName;
    private static String databasePassword;
    private static String username;

    public static void setDatabaseName(String name){
        CredentialManager.databaseName = name;
    }

    public static void setPassword(String password){
        CredentialManager.databasePassword = password;
    }

    public static void setUsername(String username){
        CredentialManager.username = username;
    }

    public static String getDatabaseName(){
        return CredentialManager.databaseName;
    }

    public static String getPassword(){
        return CredentialManager.databasePassword;
    }

    public static String getDatabaseUsername(){
        return CredentialManager.username;
    }
}
