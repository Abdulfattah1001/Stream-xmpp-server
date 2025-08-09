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
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
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


        while(reader.hasNext()){
            XMLEvent event = reader.nextEvent();

            if(event.isStartElement()){
                StartElement tagStartElement = event.asStartElement();
                String tagName = tagStartElement.getName().getLocalPart();

                switch (tagName) {
                    case "body":
                        message.append("<body>");
                        StringBuilder body = new StringBuilder("");
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
                message.append(sender+"\n");
                message.append("</phone-number>\n");

                //Display name of the sender
                message.append("<uid>\n");
                message.append(user.getUid()+"\n");
                message.append("</uid>\n");


                //The avatar url of the sender
                message.append("<avatar>\n");
                message.append(user.getAvatarUrl()+"\n");
                message.append("</avatar>\n");

                message.append("</sender-meta>\n");
            }
            
        }

        message.append("</stream:message>\n");
        message.append("<not-implemented />\n");

        logger.info("The complete message to send is: \n"+message);

        if(StreamServer.connections.containsKey(receiver)){
            Socket sock = StreamServer.connections.get(receiver);
            if(sock.isConnected()){
                try{
                    OutputStreamWriter receiver_writer = new OutputStreamWriter(sock.getOutputStream());
                    receiver_writer.write(String.valueOf(message));
                    receiver_writer.flush();
                } catch (Exception e) {
                    //databaseManagement.cacheMessageForOfflineUser(sender, receiver, messageType, messageId, body.toString(), timestamp, mediaUrl);
                }
            }
        }else{
            //databaseManagement.cacheMessageForOfflineUser(sender, receiver, messageType, messageId, body.toString(), timestamp, mediaUrl);
        }
    }

    /**
     * Notify the receiver of the message
     * @param receiver_id The receiver id of the message
     * @param sender_id The sender id of the message
     * @param message_content The actual message content to sent it might be null for media type
     */
    @SuppressWarnings("unused")
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