package streammessenger.res;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;

import java.io.FileInputStream;
import java.io.IOException;

public class FirebaseSetup {
    public static void setUp() throws IOException {

        FileInputStream fileInputStream = new FileInputStream("credentials.json");
        FirebaseOptions options = FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.fromStream(fileInputStream))
                .build();
        FirebaseApp.initializeApp(options);
        fileInputStream.close();
    }
}
