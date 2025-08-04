package streammessenger.res;

import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.cloud.FirestoreClient;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;

import java.io.OutputStreamWriter;
import java.net.Socket;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;

import javax.annotation.Nullable;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;



public class MessageTagParser {
    @SuppressWarnings("unused")
    private final Socket socket;
    private final StartElement messageStartElement;
    private final XMLEventReader reader;
    private final Logger logger = Logger.getLogger("messageLogger");
    private final DatabaseManagement databaseManagement;

    public MessageTagParser(Socket conn, StartElement startElement, XMLEventReader reader){
        this.socket = conn;
        this.messageStartElement = startElement;
        this.reader = reader;
        databaseManagement = DatabaseManagement.getInstance(CredentialManager.getPassword(), CredentialManager.getDatabaseUsername(), CredentialManager.getDatabaseName());
    }

    public void parseMessageTag() throws XMLStreamException {
        String sender_contact = messageStartElement.getAttributeByName(new QName("from")).getValue();
        String receiver_contact = messageStartElement.getAttributeByName(new QName("to")).getValue();
        String messageType = messageStartElement.getAttributeByName(new QName("type")).getValue();
        String timestamp = messageStartElement.getAttributeByName(new QName("timestamp")).getValue();
        String message_id = messageStartElement.getAttributeByName(new QName("id")).getValue();
        String mediaUrl = null;

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
                        mediaUrl = url;
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
                        logger.info("Processing DISAPPEARING MESSAGE");
                        String text = "This message timer has been updated, Tap To change";
                        message.append("<body>");
                        message.append(text);
                        message.append("</body>");
                        
                        while (reader.hasNext()) {
                            event = reader.nextEvent();

                            if(event.isCharacters() && event.asCharacters().isWhiteSpace()) event = reader.nextEvent();
                            
                            if(event.isStartElement() && event.asStartElement().getName().getLocalPart().equals("disappear")){

                                logger.info("Processing the disappear element tag");
                                message.append("<disappear>");
                            }
                            
                            if(event.isStartElement() && event.asStartElement().getName().getLocalPart().equals("expire-after")){
                                logger.info("expire body  ........................");
                                message.append("<expire-after>");
                                StringBuilder expireBody = new StringBuilder();
                                while(reader.hasNext()){
                                    logger.info("Start only once 3........................................");
                                    event = reader.nextEvent();
                                    if(event.isCharacters() && event.asCharacters().isWhiteSpace()) event = reader.nextEvent();

                                    if(event.isCharacters()){
                                        expireBody.append(event.asCharacters().getData());
                                    }
                                        
                                    logger.info("The expire duration is: "+expireBody);

                                    if(event.isEndElement() && event.asEndElement().getName().getLocalPart().equals("expire-after")){
                                        message.append("</expire-after>");
                                        logger.info("Expire-after tag reached");
                                        message.append(expireBody);
                                        break;
                                    }
                                }

                                //break;

                            }

                            if(event.isEndElement() && event.asEndElement().getName().getLocalPart().equals("disappear")){
                                logger.info("DISAPPEAR ENDIN");
                                break;
                            }
                            //break;
                        }

                        logger.info("BREAK OUT OF BODY TAG");
                        
                        break;
                }
            }


            if(event.isEndElement() && event.asEndElement().getName().getLocalPart().equals("body")){
                logger.info("Ending of the body message tag element reached");
                break;
            } ///Break out of the loop

            if(event.isEndElement() && event.asEndElement().getName().getLocalPart().equals("message")){
                logger.info("Ending of the message tag element reached");
                break;
            } ///Break out of the loop
            
        }


        if(databaseManagement.isFriends(receiver_contact, sender_contact) == true){
            
            StreamUser user = databaseManagement.getUserByContactId(sender_contact);

            if(user != null){
                message.append("<sender-meta xmlns='urn:xmpp:sender:meta:0'>\n");

                //Display name of the sender
                message.append("<display-name>\n");
                message.append(user.getDisplayName()+"\n");
                message.append("</display-name>\n");

                //The avatar url of the sender
                message.append("<avatar>\n");
                message.append(user.getAvatarUrl()+"\n");
                message.append("</avatar>\n");

                message.append("</sender-meta>\n");
            }
            
        }

        message.append("<request xmlns='urn:xmpp:receipts'/>\n"); //Read receipt for messaging

        message.append("</stream:message>");

        if(StreamServer.connections.containsKey(receiver_contact)){
            Socket sock = StreamServer.connections.get(receiver_contact);
            if(sock.isConnected()){
                try{
                    OutputStreamWriter receiver_writer = new OutputStreamWriter(sock.getOutputStream());
                    receiver_writer.write(String.valueOf(message));
                    receiver_writer.flush();
                } catch (Exception e) {
                    databaseManagement.cacheMessageForOfflineUser(sender_contact, receiver_contact, messageType, message_id, body.toString(), timestamp, mediaUrl);
                }
            }
        }else{
            databaseManagement.cacheMessageForOfflineUser(sender_contact, receiver_contact, messageType, message_id, body.toString(), timestamp, mediaUrl);
        }
        logger.info("The complete message to send is: "+message);
    }

    /**
     * Notify the receiver of the message
     * @param receiver_id The receiver id of the message
     * @param sender_id The sender id of the message
     * @param message_content The actual message content to sent it might be null for media type
     */
    private void sendNotification(String receiver_id, String sender_id, @Nullable String message_content){
        Firestore db = FirestoreClient.getFirestore();
        try {
            DocumentSnapshot documentSnapshot = db.collection("users").document(receiver_id).get().get();
            if(documentSnapshot.exists()){
                String token = (String) documentSnapshot.get("fcm_token");
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
