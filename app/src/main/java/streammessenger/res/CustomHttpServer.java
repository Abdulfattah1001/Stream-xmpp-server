package streammessenger.res;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.json.JSONException;
import org.json.JSONObject;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

public class CustomHttpServer {
    private final int PORT;
    @SuppressWarnings("unused")
    private final String address;
    private final Logger logger = Logger.getLogger("http_server");
    private DatabaseManagement management = null;

    public CustomHttpServer(int port, String serverAddress, DatabaseManagement db){
        this.PORT = port;
        this.address = serverAddress;
        this.management = db;
    }

    public void start() throws IOException{

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/api/create-account", new HttpHandler(){
            /**
             * Send back a json represents if the account creation is successfull {"status": 200, "message": "Account creation successful"},
             * Prevent multiple account creation
             */
            @SuppressWarnings("null")
            @Override
            public void handle(HttpExchange exchange) throws IOException{
            
            String method = exchange.getRequestMethod();

            if(method.equalsIgnoreCase("POST")){
                OutputStream os = exchange.getResponseBody();
                InputStream is = exchange.getRequestBody();
                OutputStreamWriter writer = new OutputStreamWriter(os);
                InputStreamReader reader = new InputStreamReader(is);
                BufferedReader read = new BufferedReader(reader);

                StringBuilder builder = new StringBuilder();
                String line;
                while((line = read.readLine()) != null){
                    builder.append(line);
                }
                try{
                    JSONObject jsonObject = new JSONObject(builder.toString());
                    
                    String contact = jsonObject.getString("contactId"); //The phone number of the user in E164 Format
                    String user_id = jsonObject.getString("uid"); //The unique user ID of the user as generated on the Platform Backend Server
                    String contactId = "stream@" + contact; //Example is stream@+2349063109106

                    if(management != null){
                        
                        try {
                            management.newUser(contactId,  user_id);

                            String response = "{\"status\": success, \"message\": Account created successfully, \"data\": "+ user_id +"}";

                            exchange.sendResponseHeaders(200, response.getBytes().length);
                            writer.write(response);
                            writer.flush();
                            writer.close();
                        } catch (SQLException e) {
                            logger.log(Level.WARNING, "Error occurred: ", e);
                        }
                    }
                }catch(JSONException exception){
                    logger.info(() -> "Error occurred: JSON"+exception.getMessage());
                }
                logger.info(() -> "The request body is: "+builder.toString());
            }
            }
        });
        server.setExecutor(null);
        server.start();
    }
}
