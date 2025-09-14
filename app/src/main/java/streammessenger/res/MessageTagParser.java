package streammessenger.res;

import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.cloud.FirestoreClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nullable;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import org.json.JSONObject;



public class MessageTagParser {
    @SuppressWarnings("unused")
    private Socket socket;
    private StartElement messageStartElement;
    private XMLEventReader reader;
    private Logger logger = Logger.getLogger("messageLogger");
    private DatabaseManagement databaseManagement;

    public MessageTagParser(){
        databaseManagement = DatabaseManagement.getInstance(CredentialManager.getPassword(), CredentialManager.getDatabaseUsername(), CredentialManager.getDatabaseName());
    }

    public MessageTagParser(Socket conn, StartElement startElement, XMLEventReader reader){
        this.socket = conn;
        this.messageStartElement = startElement;
        this.reader = reader;
        databaseManagement = DatabaseManagement.getInstance(CredentialManager.getPassword(), CredentialManager.getDatabaseUsername(), CredentialManager.getDatabaseName());
    }

    public void start(StartElement startElement, XMLEventReader reader) throws IOException{
        String from = startElement.getAttributeByName(new QName("from")).getValue();
        String to = startElement.getAttributeByName(new QName("to")).getValue();
        String messageId = startElement.getAttributeByName(new QName("id")).getValue();
        Attribute typeAttr = startElement.getAttributeByName(new QName("type"));

        String type = typeAttr != null ? typeAttr.getValue() : null;
        String url = null;
        String content = null;
        String timestamp = null;

        Session session = SessionManager.getInstance().getSession(from);

        boolean isReceived = false;

        if(!session.isAuthenticated() || session.getSessionState() == SessionState.INITIAL){

            OutputStreamWriter writer = new OutputStreamWriter(session.getSocket().getOutputStream());

            writer.write("<stream:failure xmlns='urn:ietf:params:ns:xmpp-auth'>\n"
            +"<not-authorized> Not Authenticated </not-authorized>\n"+
            "</stream:failure>\n");

            writer.write("</stream:stream>");

            writer.flush();

            session.getSocket().close();

            session = null;
            
            return;
        }

        StringBuilder message = new StringBuilder();

        message.append("<stream:message\n");
        message.append("from='"+ from +"'\n");
        message.append("to='"+ to +"'\n");
        if(typeAttr != null){message.append("type='"+typeAttr.getValue()+"'\n");}
        message.append("id='"+ messageId + "'>\n");

        try{
            while(reader.hasNext()){
            XMLEvent event = reader.nextEvent();
            
            if(event.isCharacters() && event.asCharacters().isWhiteSpace()) continue;

            if(event.isStartElement()){
                String tagName = event.asStartElement().getName().getLocalPart();

                switch(tagName){
                    case "body":
                        StringBuilder body = new StringBuilder();
                        content = new String();
                        body.append("<body>");

                        while(reader.hasNext()){
                            event = reader.nextEvent();
                            if(event.isCharacters() && event.asCharacters().isWhiteSpace()) continue;

                            if(event.isCharacters()){
                                content += event.asCharacters().getData();
                                body.append(event.asCharacters().getData());
                            }

                            if(event.isEndElement() && event.asEndElement().getName().getLocalPart().equals("body")){
                                body.append(("</body>\n"));
                                message.append(body.toString());
                                break;
                            }
                        }
                        break;

                    case "timestamp":
                        StringBuilder timestampBuiler = new StringBuilder();
                        timestampBuiler.append("<timestamp>");
                        timestamp = new String();
                        
                        while(reader.hasNext()){
                            event = reader.nextEvent();
                            if(event.isCharacters() && event.asCharacters().isWhiteSpace()) continue;

                            if(event.isCharacters()){
                                timestampBuiler.append(event.asCharacters().getData());
                                timestamp += event.asCharacters().getData();
                            }

                            if(event.isEndElement() && event.asEndElement().getName().getLocalPart().equals("timestamp")){
                                timestampBuiler.append("</timestamp>\n");
                                message.append(String.valueOf(timestampBuiler));
                                break;
                            }
                        }
                        break;

                    case "disappear":
                        break;

                    case "audio":
                        break;

                    case "video":
                        break;

                    case "received":
                        String namespace = event.asStartElement().getName().getNamespaceURI();

                        /*if(namespace.equals("urn:xmpp:receipts:client")){
                            logger.info("The client has received the receipts deleting from cache now..."+messageId);
                            databaseManagement.deleteReceivedReceiptsFromCache(messageId);
                        }else{
                            message.append("<received xmlns='urn:xmpp:receipts'/>\n");
                            isReceived = true;
                        }*/

                        switch (namespace) {
                            /**
                            * The client sending back a <received xmlns='urn:xmpp:receipts:client'/>
                            * that it has received the receipt that the intending receiver 
                            * has received the message, so the server can treat it or remove it
                            * from the local cache as being treated
                            */
                            case "urn:xmpp:receipts:client":
                                logger.info("Removing the receipts from cache ...");
                                databaseManagement.deleteReceivedReceiptsFromCache(messageId);
                                break;

                            /**
                            * The receiver sending back an acknowledgement as a response to 
                            * <request xmlns='urn:xmpp:receipts' /> that it has received
                            * and that the sender can be notified of it
                            *
                            * The isReceived = true is used to mark the present processing message tag
                            * as a receipts and it should not be treated as a complete message tag
                            */
                            case "urn:xmpp:receipts":
                                message.append("<received xmlns='urn:xmpp:receipts'/>");
                                isReceived = true;
                                break;    
                        
                            default:
                                break;
                        }
                        break;

                    case "request":
                        message.append("<request xmlns='urn:xmpp:receipts'/>\n");
                        break;
                    default:
                        break;                               
                }
            }
        
            if(event.isEndElement() && event.asEndElement().getName().getLocalPart().equals("message")) break; // Breaking out of the outer loop
        }

        }catch(XMLStreamException exception){
            logger.info("Error occurred message: "+exception.getMessage());
        }

        /**
        * Checking if the sender is in the receiver rosters
        * If not, include the user meta-data into the message tag
        */
        if(!databaseManagement.isFriends(session.getUid(), to)){
            StreamUser user = databaseManagement.getUserByContactId(from);

            if(user != null){
                message.append("<sender-meta xmlns='urn:xmpp:sender:meta:0'>\n");

                message.append("<display-name>");
                message.append(user.getDisplayName());
                message.append("</display-name>\n");

                message.append("<phone-number>");
                message.append(from);
                message.append("</phone-number>\n");

                message.append("<uid>");
                message.append(user.getUid());
                message.append("</uid>\n");

                message.append("<avatar>");
                message.append(user.getAvatarUrl());
                message.append("</avatar>\n");

                message.append("<status>");
                message.append(user.getStatus());
                message.append("</status>\n");

                message.append("</sender-meta>\n");
            }
        }

        message.append("</stream:message>\n");

        message.append("<not-implemented />\n"); //This should not be processed on the client side as it's used to flush out the complete <stream:message></stream:message>
        
        /**
         * The message will have to be cache locally regardless until the 
         * receiver acknowledge that it has received it
         * which only happends when the receiver sends back
         * a <received /> tag element that it has received the 
         * message, so the server would be able to remove the message
         * from the local storage or cache
         */

        if(!isReceived){
            
            /**
            * Sending back an acknowledgement to the sender 
            * that the message is now in the hands of the server
            */
            OutputStreamWriter writer = new OutputStreamWriter(session.getSocket().getOutputStream());
            writer.write("<stream:message from='"+ from +"' to='"+ to +"' id='"+ messageId +"'> <received xmlns='urn:xmpp:receipts:server'/> </stream:message>");
            writer.flush();

            /// Caching the message locally
            databaseManagement.offlineMessages(from, to, type, content, url, messageId, timestamp);

            /**
            * Checking if the intended receipient is online
            */
            if(Server.connections.containsKey(to)){
                Session toSession = SessionManager.getInstance().getSession(to);
        
                Socket socket = toSession.getSocket();

                OutputStreamWriter recWriter = new OutputStreamWriter(socket.getOutputStream());
                recWriter.write(String.valueOf(message));
                recWriter.flush();
            }

            
            sendNotificationLatest(from, to, messageId, type, content, url);
        }else{
            /**
             * Note that:
             *  Here also, if the original sender of the message is not online
             *  then the received receipts will also have to be cache locally
             *  till the user reconnects
             */
            if(!(to.startsWith("+234"))) to = databaseManagement.getContactById(to);

            /**
            * Removing the message from the cache as processed
            */
            databaseManagement.removeMessageFromCache(messageId);

            /**
            * Checking if the original sender of the message is
            * online such that the receipts could be route to them
            * else cache the receipts
            */
            if(Server.connections.containsKey(to)){
                Session rec = SessionManager.getInstance().getSession(to);
                OutputStreamWriter writer = new OutputStreamWriter(rec.getSocket().getOutputStream());
                writer.write("<stream:message from='"+ from +"' to='"+to+"' id='"+messageId+"'> <received xmlns='urn:xmpp:receipts'/> </stream:message>");
                writer.flush();

            }else{
                databaseManagement.cacheReceiverReceivedReceipts(from, to, messageId);
            }
            
        }

        isReceived = false; //Sets it back to default at the end of the message processing
    }
    

    public void processMessage() throws XMLStreamException{
        String sender = messageStartElement.getAttributeByName(new QName("from")).getValue();
        String receiver = messageStartElement.getAttributeByName(new QName("to")).getValue();
        String messageId = messageStartElement.getAttributeByName(new QName("id")).getValue();

        Attribute timestampAttr = messageStartElement.getAttributeByName(new QName("timestamp"));
        Attribute typeAttr = messageStartElement.getAttributeByName(new QName("type"));

        StringBuilder message = new StringBuilder();
        message.append("<stream:message\n")
                .append("from='"+ sender +"'\n");

        if(timestampAttr != null) message.append("timestamp='"+ timestampAttr.getValue() +"'\n");

        if(typeAttr != null) message.append("type='"+ typeAttr.getValue() +"'\n");

        message.append("to='"+ receiver +"'\n")
                .append("id='"+ messageId +"'>\n");


        StringBuilder body = null;    
        while(reader.hasNext()){
            XMLEvent event = reader.nextEvent();

            if(event.isStartElement()){
                StartElement tagStartElement = event.asStartElement();
                String tagName = tagStartElement.getName().getLocalPart();

                switch (tagName) {
                    case "body":
                        message.append("<body>");
                        body = new StringBuilder("");
                        while(reader.hasNext()){
                            event = reader.nextEvent();

                            if(event.isCharacters() && event.asCharacters().isWhiteSpace()) continue;

                            if(event.isCharacters()){
                                body.append(event.asCharacters().getData());
                            }

                            if(event.isEndElement() && event.asEndElement().getName().getLocalPart().equals("body")){
                                message.append(body); // The content of the message
                                message.append("</body>"); //Message end tag
                                break;
                            }
                        }
                        break;
                    case "disappear":
                        message.append("<disappear>\n");

                        while (reader.hasNext()) {
                            event = reader.nextEvent();

                            if(event.isCharacters() && event.asCharacters().isWhiteSpace()) continue;

                            if(event.isStartElement() && event.asStartElement().getName().getLocalPart().equals("expire-after")){
                                message.append("<expire-after>");
                                StringBuilder expire = new StringBuilder();
                                while(reader.hasNext()){
                                    event = reader.nextEvent();

                                    if(event.isCharacters() && event.asCharacters().isWhiteSpace()) continue;

                                    if(event.isCharacters()){
                                        expire.append(event.asCharacters().getData());
                                    }

                                    if(event.isEndElement() && event.asEndElement().getName().getLocalPart().equals("expire-after")){
                                        message.append(expire);
                                        message.append("</expire-after>");
                                        break; //Done processing <expire-after></expire-after>
                                    }
                                }
                            }

                            if(event.isEndElement() && event.asEndElement().getName().getLocalPart().equals("disappear")){
                                message.append("</disappear>");
                                break; //Done processing <diappear> ... </disappear>
                            }
                        }

                    case "audio":
                        
                        String audioUrl = tagStartElement.getAttributeByName(new QName("url")).getValue();

                        while(reader.hasNext()){
                            event = reader.nextEvent();

                            if(event.isCharacters() && event.asCharacters().isWhiteSpace()) continue;

                            if(event.isEndElement() && event.asEndElement().getName().getLocalPart().equals("audio")){
                                message.append("<audio url='"+ audioUrl +"'/>");
                                break; //Done processing the <audio /> tags
                            }
                        }    

                        break;
                    case "video":
                    
                        String videoUrl = tagStartElement.getAttributeByName(new QName("url")).getValue();

                        while(reader.hasNext()){
                            event = reader.nextEvent();

                            if(event.isCharacters() && event.asCharacters().isWhiteSpace()) continue;

                            if(event.isEndElement() && event.asEndElement().getName().getLocalPart().equals("video")){
                                message.append("<video url='"+ videoUrl +"'/>");
                                break; //Done processing the <video /> tags
                            }
                        }    

                        break;     
                        
                    case "received": //Received receipts processing
                        while(reader.hasNext()){
                            event = reader.nextEvent();

                            if(event.isCharacters() && event.asCharacters().isWhiteSpace()) continue;

                            if(event.isEndElement() && event.asEndElement().getName().getLocalPart().equals("received")){
                                
                                message.append("<received xmlns='urn:xmpp:receipts:received'/>\n");
                                break;
                            }
                        }
                        break;
                    
                    case "request": //The sender of the message explicity requesting for the received receipt
                        message.append("<request xmlns='urn:xmpp:receipts' />\n");
                        break;
                            
                    default:
                        break;
                }
            }

            if(event.isEndElement() && event.asEndElement().getName().getLocalPart().equals("message")){
                break;
            }
        }


        if(databaseManagement.isFriends(receiver, sender) != true){
            
            StreamUser user = databaseManagement.getUserByContactId(sender);

            if(user != null){
                message.append("<sender-meta xmlns='urn:xmpp:sender:meta:0'>\n");

                //Display name of the sender
                message.append("<display-name>\n");
                message.append(user.getDisplayName()+"\n");
                message.append("</display-name>\n");



                //Display name of the sender
                message.append("<phone-number>\n");
                message.append(sender);
                message.append("</phone-number>\n");

                //Display name of the sender
                message.append("<uid>\n");
                message.append(user.getUid());
                message.append("</uid>\n");


                //The avatar url of the sender
                message.append("<avatar>\n");
                message.append(user.getAvatarUrl());
                message.append("</avatar>\n");

                message.append("<status>");
                message.append(user.getStatus());
                message.append("</status>\n");

                message.append("</sender-meta>\n");
            }
            
        }

        message.append("</stream:message>\n");
        message.append("<not-implemented />\n");

        if(StreamServer.connections.containsKey(receiver)){
            Socket sock = StreamServer.connections.get(receiver);
            if(sock.isConnected()){
                try{
                    OutputStreamWriter receiver_writer = new OutputStreamWriter(sock.getOutputStream());
                    receiver_writer.write(String.valueOf(message));
                    receiver_writer.flush();
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error ", e);
                    //TODO
                }
            }
        }else{
            //databaseManagement.cacheMessageForOfflineUser(sender, receiver, messageType, messageId, body.toString(), timestamp, mediaUrl);
        }

        if(typeAttr != null){
            String messageType = typeAttr.getValue();
            sendNotification(receiver, sender, body.toString(), messageType, messageId);
            //sendNotificationOverHttp(receiver, sender, body.toString(), messageType, messageId);
        }
    }

    /**
     * Notify the receiver of the message
     * @param receiver_id The receiver id of the message
     * @param sender_id The sender id of the message
     * @param message_content The actual message content to sent it might be null for media type
     * //TODO: Instead of hitting the firestore to get the receiver FCM TOKEN, it can be saved on the XMPP 
     * //Server instead.
     */
    private void sendNotification(String receiver_id, String sender_id, @Nullable String message_content, String type, String messageId){
        Firestore db = FirestoreClient.getFirestore();
        String uid = databaseManagement.getUserUID(receiver_id);
        
        try {
            DocumentSnapshot documentSnapshot = db.collection("users").document(uid).get().get();
            if(documentSnapshot.exists()){
                String token = (String) documentSnapshot.get("fcmToken");

                try {
                    JSONObject jsonObject = new JSONObject();
                    jsonObject.put("senderId", sender_id);
                    jsonObject.put("content", message_content);
                    jsonObject.put("messageId", messageId);
                    jsonObject.put("token", token);
                    // URL to connect to
                    URL url = new URL("http://192.168.160.26:3003/api/message/notify");

                    // Open connection
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true); // Needed for sending body
                    conn.setRequestProperty("Content-Type", "application/json"); // or application/x-www-form-urlencoded


                    // Send request
                    try (OutputStream os = conn.getOutputStream()) {
                        //byte[] input = jsonInputString.getBytes(StandardCharsets.UTF_8);
                        //os.write(input, 0, input.length);
                        OutputStreamWriter w = new OutputStreamWriter(os);
                        w.write(jsonObject.toString());
                        w.flush();
                    }

                    // Read response
                    int responseCode = conn.getResponseCode();
                    if (responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_CREATED) {
                        try (BufferedReader in = new BufferedReader(
                                new InputStreamReader(conn.getInputStream()))) {
                            String inputLine;
                            StringBuilder response = new StringBuilder();
                            while ((inputLine = in.readLine()) != null) {
                                response.append(inputLine);
                            }
                            System.out.println("Response: " + response.toString());
                        }
                    } else {
                        System.out.println("POST request failed.");
                    }


                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        } catch (InterruptedException | ExecutionException e) {
            logger.info("Error FCM: "+e.getMessage());
        } catch (Exception e) {
            logger.info("Error occurred sending FCM :"+e.getMessage());
        }
    }

    /**
     * Notify the receiver of the message
     * @param receiver_id The receiver id of the message
     * @param sender_id The sender id of the message
     * @param message_content The actual message content to sent it might be null for media type
     */
    private void sendNotificationLatest(String from, String to, String messageId, String type, String messageContent, String url){
        Session fromSession = SessionManager.getInstance().getSession(from);
        String uid = databaseManagement.getUserUID(to);

        try{
            JSONObject notificationObject = new JSONObject();
            notificationObject.put("from", fromSession.getUid());
            notificationObject.put("to", uid);
            notificationObject.put("content", messageContent);
            notificationObject.put("messageId", messageId);


            if(type != null){
                notificationObject.put("type", type);
            }

            if(url != null){
                notificationObject.put("url", url);
            }

            URL url1 = new URL("http://192.168.196.40:3003/api/v2/notification");

            HttpURLConnection connection = (HttpURLConnection) url1.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);

            OutputStream os = connection.getOutputStream();
            OutputStreamWriter writer = new OutputStreamWriter(os);
            writer.write(notificationObject.toString());
            writer.flush();
            

            int responseCode = connection.getResponseCode();
            
            if(responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_CREATED){
                try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(connection.getInputStream()))) {
                    String inputLine;
                    StringBuilder response = new StringBuilder();
                    while ((inputLine = in.readLine()) != null) {
                        response.append(inputLine);
                        }
                    System.out.println("Response: " + response.toString());
                }
            }else{
                logger.info("Post request failed");
            }

        }catch(Exception exception){
            logger.info("Error occurred: "+exception.getMessage());
        }
    }

}