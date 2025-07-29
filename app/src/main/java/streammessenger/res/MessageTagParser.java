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

import javax.annotation.Nullable;
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

        StringBuilder message = new StringBuilder();
        message.append("<stream:message\n")
                .append("from='" + sender_contact + "'\n")
                .append("to='" + receiver_contact + "'\n")
                .append("message_id='" + message_id + "'\n")
                .append("timestamp='" + timestamp + "'\n")
                .append("type='" + messageType + "'>\n");


        StringBuilder body = new StringBuilder("");

        while(reader.hasNext()){
            XMLEvent event = reader.nextEvent();
            if(event.isStartElement() && event.asStartElement().getName().getLocalPart().equals("body")){
                switch (messageType){
                    case "TEXT":
                        while (reader.hasNext()){
                            event = reader.nextEvent();
                            if(event.isCharacters() && event.asCharacters().isWhiteSpace()) event = reader.nextEvent();

                            if(event.isCharacters()){
                                body.append(event.asCharacters().getData());
                            }
                            if(event.isEndElement() && event.asEndElement().getName().getLocalPart().equals("body")){

                                if(!body.isEmpty()){
                                    message.append("<body>"); /// Start tag of the body element
                                    message.append(body); /// The main content of the message
                                    message.append("</body>"); /// The close tag of the message
                                }

                                break;///Break out of the inner loop
                            }
                        }
                        break;
                    case "IMAGE":
                        StartElement bodyStartElement = event.asStartElement();
                        String url = bodyStartElement.getAttributeByName(new QName("url")).getValue();
                        message.append("<body url='"+ url +"'>\n");
                        while (reader.hasNext()){
                            event = reader.nextEvent();
                            if(event.isCharacters() && event.asCharacters().isWhiteSpace()) event = reader.nextEvent();

                            if(event.isCharacters()){
                                body.append(event.asCharacters().getData()); /// Appends the Caption of the image
                            }
                            if(event.isEndElement() && event.asEndElement().getName().getLocalPart().equals("body")){
                                if(!body.isEmpty()){
                                    message.append(body);
                                }
                                message.append("</body>"); /// Appends the close tag for the message <body></body> element
                                break;
                            }
                        }
                        break;
                    case "VIDEO":
                        break;
                    case "AUDIO": //Voice note
                        break;
                    case "DISAPPEARING_MESSAGE": //Disappearing message
                        break;
                }
            }

            if(event.isEndElement() && event.asEndElement().getName().getLocalPart().equals("body")) break; ///Break out of the loop
        }

        message.append("</stream:message>");

        //send_notification(sender_contact, receiver_contact, body.toString()); //TODO:  To be modified for all types of messages

        if(StreamServer.connections.containsKey(receiver_contact)){
            Socket sock = StreamServer.connections.get(receiver_contact);
            logger.info("The receiver is online");
            if(sock.isConnected()){
                try{
                    OutputStreamWriter receiver_writer = new OutputStreamWriter(sock.getOutputStream());
                    receiver_writer.write(String.valueOf(message));
                    receiver_writer.flush();
                } catch (Exception e) {
                    logger.info("Error occurred sending message to the receiver: "+e.getMessage());
                }

            }

        }else{
            logger.info("The receiver is offline....");
        }
        logger.info("The complete message to send is: "+message);
    }

    /**
     * Notify the receiver of the message
     * @param receiver_id The receiver id of the message
     * @param sender_id The sender id of the message
     * @param message_content The actual message content to sent it might be null for media type
     */
    private void send_notification(String receiver_id, String sender_id, @Nullable String message_content){
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
