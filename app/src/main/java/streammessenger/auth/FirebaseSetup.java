package streammessenger.auth;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

public class FirebaseSetup {
    public static void setup() throws IOException {
        FileInputStream fis = new FileInputStream("config.properties");
        Properties p = new Properties();
        p.load(fis);
        String environment = p.getProperty("ENV");
        boolean isDev = environment.equals("DEV");
        FileInputStream fileInputStream = new FileInputStream(isDev ? "credentials.json" : "prod_credentials.json");

        FirebaseOptions options = FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.fromStream(fileInputStream))
                .build();
        FirebaseApp.initializeApp(options);
        fileInputStream.close();
    }
}
