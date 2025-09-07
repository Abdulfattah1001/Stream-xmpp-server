package streammessenger;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;
import java.util.logging.Logger;

import streammessenger.res.CredentialManager;
import streammessenger.res.CustomHttpServer;
import streammessenger.res.DatabaseManagement;
import streammessenger.res.FirebaseSetup;
import streammessenger.res.Server;
import streammessenger.res.StreamServer;

public class App {
    private final static Logger logger = Logger.getLogger("database");

    public static void main(String[] args) {

        DatabaseManagement db = null;
        Properties properties = new Properties();
        String dbpassword = null;
        String dbname = null;

        try{
            FirebaseSetup.setUp();
        } catch (IOException e) {
            logger.info("Error from Firebase set up: "+e.getMessage());
        }
        
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
                        .setPort(5222)
                        .setDBManagement(db).build();

            Server serverV2 = new Server.Builder().setDB(db).setAddress("0.0.0.0").setPort(5222).build();

            CustomHttpServer httpServer = new CustomHttpServer(3001, "0.0.0.0", db);
            httpServer.start();

            //server.start();
            serverV2.start();
        }catch(Exception exception){
            logger.info("Error occurred: "+exception.getMessage());
        }
    }
}