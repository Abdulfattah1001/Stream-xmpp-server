package streammessenger.res;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.json.JSONArray;
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

                    if(management != null){
                        
                        try {
                            management.newUser(contact,  user_id);

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
        
        server.createContext("/api/roster", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException{
                String method = exchange.getRequestMethod();

                if(method.equalsIgnoreCase("POST")){
                    OutputStream os = exchange.getResponseBody();
                    InputStream is = exchange.getRequestBody();

                    OutputStreamWriter writer = new OutputStreamWriter(os);
                    InputStreamReader inputStreamReader = new InputStreamReader(is);
                    BufferedReader read = new BufferedReader(inputStreamReader);

                    StringBuilder builder = new StringBuilder();
                    String line;

                    while((line =  read.readLine()) != null){
                        builder.append(line);
                    }

                    try{
                        logger.info("The data sent from the backend server is: \n"+builder.toString());
                        JSONObject rObject = new JSONObject(builder.toString());
                        //logger.info("The user contacts is: "+builder.toString());
                        //logger.info("The user is: "+rObject.getString("uid"));
                        String uid = rObject.getString("uid"); //The owner of the rosters

                        JSONArray rArray = new JSONArray(rObject.get("data").toString());

                        for(int i = 0; i < rArray.length(); i++){
                            JSONObject object = rArray.getJSONObject(i);

                            String rosterUID = object.getString("uid");  //The user unique identity ID
                            String displayName = object.getString("displayName"); //The user preffered name
                            String savedName = object.getString("savedName"); //The saved name of the user 
                            String displayStatus = object.getString("displayStatus"); //The user display status
                            String contactId = object.getString("phoneNumber"); //The phoneNumber in E164 international format
                            //String fcmToken = object.getString("fcmToken"); //The FCM Token of the user

                            management.insertItemIntoRoster(uid, contactId, displayName);
                        }
                        
                    }catch(JSONException exception){
                        logger.info("Error occurred: "+exception.getMessage());
                    }

                    try{
                        JSONObject response = new JSONObject();
                        response.put("status", "sucsess");
                        response.put("message", "rosters updates successfully");
                        response.put("code", "200");

                        writer.write(response.toString());
                        writer.flush();
                        writer.close();

                    }catch(JSONException exception){}
                }
            }
        });

        server.createContext("/api/rosters", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange){
                String method = exchange.getRequestMethod();
                String query = exchange.getRequestURI().getQuery();

                OutputStream os = exchange.getResponseBody();

                OutputStreamWriter writer = new OutputStreamWriter(os);

                //Map<String, String> result = new HashMap();

                String[] pairs = query.split("&");
                String[] keyValue =  pairs[0].split("=");

                logger.info("The query is "+query);
                logger.info("The keyvalue is "+pairs[0]);

                if(method.equalsIgnoreCase("GET")){
                    List<HashMap<String, Object>> rosters = management.getRosters(keyValue[1]);
                    logger.info("The rosters result is "+rosters);

                    try{
                        JSONArray array = new JSONArray();
                        for(HashMap<String, Object> ros : rosters){
                            JSONObject object = new JSONObject();
                            object.put("contactId", ros.get("contactId"));
                            object.put("displayName", ros.get("displayName"));

                            array.put(object);
                        }

                        writer.write(array.toString());
                        writer.flush();


                    }catch(JSONException | IOException exception){

                    }
                }
            }
        });
        
        
        server.createContext("/api/users", new HttpHandler(){
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
                        String uid = jsonObject.getString("uid");
                        String displayName = jsonObject.getString("displayName");
                        String bgUrl = jsonObject.getString("bgUrl");
                        String status = jsonObject.getString("status");
                        String phoneNumber = jsonObject.getString("phoneNumber");

                        management.updateUserInfo(uid, phoneNumber, displayName, bgUrl, status);


                        JSONObject response = new JSONObject();
                        response.put("status", 200);
                        response.put("message", "User info update successfully");

                        writer.write(response.toString());
                        writer.flush();

                    }catch(JSONException exception){
                        logger.info("error updating user info: "+exception.getMessage());
                    }
                }
            }
        });

        server.setExecutor(null);
        server.start();
    }
}
