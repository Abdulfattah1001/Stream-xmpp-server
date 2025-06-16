package streammessenger.res;

import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.cloud.FirestoreClient;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;

import javax.net.ssl.HttpsURLConnection;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;

import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.LoggerFactory;

public class MessageTagParser {
    private static final org.slf4j.Logger log = LoggerFactory.getLogger(MessageTagParser.class);
    @SuppressWarnings("unused")
    private final Socket socket;
    private final StartElement messageStartElement;
    private final XMLEventReader reader;
    private final Logger logger = Logger.getLogger("messageLogger");
    private final DatabaseManagement db;

    public MessageTagParser(Socket conn, StartElement startElement, XMLEventReader reader){
        this.socket = conn;
        this.messageStartElement = startElement;
        this.reader = reader;
        db = null;
    }

    public void parseMessageTag() throws XMLStreamException {
        logger.info("Message processing ...");
        String sender_contact = messageStartElement.getAttributeByName(new QName("from")).getValue();
        String receiver_contact = messageStartElement.getAttributeByName(new QName("to")).getValue();
        String messageType = messageStartElement.getAttributeByName(new QName("type")).getValue();
        String timestamp = messageStartElement.getAttributeByName(new QName("timestamp")).getValue();
        String message_id = messageStartElement.getAttributeByName(new QName("id")).getValue();

        StringBuilder body = new StringBuilder();

        if(reader != null){
            while(reader.hasNext()){
                XMLEvent event = reader.nextEvent();
                if(event.isStartElement() && event.asStartElement().getName().getLocalPart().equals("body")){
                    while(reader.hasNext()){
                        event = reader.nextEvent();
                        if(event.isCharacters() && event.asCharacters().isWhiteSpace()) event = reader.nextEvent();
                        if(event.isCharacters()){
                            body.append(event.asCharacters().getData());
                            ///Sending a notification to the receiver
                            send_notification(sender_contact, receiver_contact, body.toString());

                            if(StreamServer.connections.containsKey(receiver_contact)){
                                Socket sock = StreamServer.connections.get(receiver_contact);
                                if(sock.isConnected()){
                                    try{
                                        String msg = "<stream:message\n" +
                                                "from='"+sender_contact+"'\n" +
                                                "to='"+receiver_contact+"'\n" +
                                                "message_id='"+message_id+"'\n" +
                                                "timestamp='"+timestamp+"'\n" +
                                                "type='"+ messageType +"'>\n" +
                                                "<body>"+ body +"</body>\n" +
                                                "</stream:message>\n";
                                        OutputStreamWriter receiver_writer = new OutputStreamWriter(sock.getOutputStream());
                                        receiver_writer.write(msg);
                                        receiver_writer.flush();
                                    } catch (Exception e) {
                                        logger.info("Error occurred sending message to the receiver: "+e.getMessage());
                                    }
                                    /**try{
                                        logger.info("Sending data to the backend...");
                                        //URL url = new URL("https://stream-server-js.onrender.com/api/message/notify");
                                        URL url = new URL("http://192.168.104.136:3003/api/message/notify");

                                        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
    
                                        connection.setRequestMethod("POST");
    
                                        connection.setRequestProperty("Content-Type", "application/json");
                                        connection.setDoOutput(true);
    
                                        //String payload = String.format("{\"contact_id\":\"%s\", \"message\":\"%s\"}", receiver_contact, body);
    
                                        JSONObject jsonPayload = new JSONObject();
                                        jsonPayload.put("receiver_id", receiver_contact);
                                        jsonPayload.put("message", body);
                                        jsonPayload.put("sender_contact", sender_contact);
    
                                        OutputStreamWriter wr = new OutputStreamWriter(connection.getOutputStream());
                                        wr.write(jsonPayload.toString());
                                        wr.flush();
    
                                        int responseCode = connection.getResponseCode();
    
                                        logger.info("Response code is: "+responseCode);
    
                                        connection.disconnect();

                                    }catch(IOException | JSONException exception){
                                        logger.info("Error occurred sending data to backend"+exception.getMessage());
                                    }*/
                                }else{
                                    //TODO: the user connections is no longer valid
                                    logger.info("The user is not connected at the moment");
                                }
                                
                            }
                        }
                        if(event.isEndElement() && event.asEndElement().getName().getLocalPart().equals("body")) break;
                    }

                }

                if(event.isEndElement() && event.asEndElement().getName().getLocalPart().equals("message")) break;
            }
        }
    }

    private void persistMessageForOfflineUser(String receiver_contact, String  sender_contact, String content, String message_id){
        if(db != null){
            logger.info("Qeuing the message for the user");
            db.offline_message(sender_contact, receiver_contact, content);
        }
    }

    /**
     * Notify the receiver of the message
     * @param receiver_id
     * @param sender_id
     * @param message_content
     */
    private void send_notification(String receiver_id, String sender_id, String message_content){
        Firestore db = FirestoreClient.getFirestore();
        try {
            DocumentSnapshot documentSnapshot = db.collection("users").document(receiver_id).get().get();
            if(documentSnapshot.exists()){
                String token = (String) documentSnapshot.get("fcm_token");
                logger.info("The receiver token is: "+token);
                Message message = Message.builder()
                        .setToken(token)
                        .setNotification(Notification.builder()
                                .setTitle(sender_id)
                                .setBody(message_content)
                                .build())
                        .build();
                FirebaseMessaging.getInstance().send(message);
            }
        } catch (InterruptedException | ExecutionException e) {
            logger.info("Error FCM: "+e.getMessage());
        } catch (FirebaseMessagingException e) {
            logger.info("Error occurred sending FCM :"+e.getMessage());
        }
    }
}
