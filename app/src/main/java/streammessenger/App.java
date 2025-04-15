package streammessenger;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;
import java.util.logging.Logger;

import streammessenger.res.CredentialManager;
import streammessenger.res.CustomHttpServer;
import streammessenger.res.DatabaseManagement;
import streammessenger.res.StreamServer;

public class App {
    private final static Logger logger = Logger.getLogger("database");

    public static void main(String[] args) {
        DatabaseManagement db = null;
        Properties properties = new Properties();
        String dbpassword = null;
        String dbname = null;
        
        try{
            FileInputStream fileInputStream = new FileInputStream("config.properties");
            properties.load(fileInputStream);
            dbname = properties.getProperty("dbname");
            dbpassword = properties.getProperty("dbpassword");

            CredentialManager.setPassword(dbpassword);
            CredentialManager.setDatabaseName(dbname);

        }catch(IOException exception){}
        
        try{
            db = DatabaseManagement.getInstance("Fattah*11", "stream","stream");
            db.connects();
            StreamServer server = new StreamServer
                        .Builder()
                        .setAddress("0.0.0.0")
                        .setPort(3000)
                        .setDBManagement(db).build();

            CustomHttpServer httpServer = new CustomHttpServer(3001, "0.0.0.0", db);
            httpServer.start();

            server.start();
        }catch(Exception exception){
            logger.info("Error occurred: "+exception.getMessage());
        }
    }

}
