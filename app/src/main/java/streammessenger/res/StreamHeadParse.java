package streammessenger.res;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.logging.Logger;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.events.StartElement;

public class StreamHeadParse {
    private final Logger logger = Logger.getLogger("stream_head");

    private static final SecureRandom secureRandom = new SecureRandom();

    public StreamHeadParse(){}

    /*
    * Generate and returns a unique and collision avoidable string
    */
    private String hexSessionId(){
        byte[] randomBytes = new byte[16];
        secureRandom.nextBytes(randomBytes);

        StringBuilder sb = new StringBuilder();
        for(byte b : randomBytes){
            sb.append(String.format("%02x", b));
        }

        return sb.toString();
    }


    public void start(StartElement startElement,XMLEventReader reader, Session session) throws IOException{

        Socket soc = session.getSocket();
        OutputStreamWriter writer = new OutputStreamWriter(soc.getOutputStream());


        switch (session.getSessionState()) {
            case INITIAL:
                writer.write("<?xml version='1.0'?>\n"
                +"<stream:stream\n"
                +"version='1.0'\n"
                +"xmlns='jabber:server'\n"
                +"id='"+ session.getSessionId() +"'\n"
                +"xmlns:stream='http://etherx.jabber.org/streams'>\n");

                writer.flush();

                /**
                 * Any other features should also be sent here
                 */
                writer.write("<features xmlns='urn:ietf:params:xml:ns:xmpp-sasl' />\n");
                writer.flush();

                break;

            case STARTTLS_NEGOTIATED:
                break;
             
            case AUTHENTICATED:
                session.setSessionId(hexSessionId()); //Updates the sessionId of the stream
                writer.write("<stream:stream\n"
                +"version='1.0'\n"
                +"xmlns='jabber:server'\n"
                +"id='"+ session.getSessionId() +"'\n"
                +"xmlns:stream='http://etherx.jabber.org/streams'>\n");

                writer.flush();

                checkAndSendCacheMessages(session, Server.getInstance().getDB());

                checkAndSendReceivedReceipts(session, Server.getInstance().getDB());
                
                break;
                
            case RESUMING:
                break;
                
            case BOUND:
                break;

            default:
                break;
        }
    }

    public void checkAndSendReceivedReceipts(Session session, DatabaseManagement db){
        ArrayList<HashMap<String, Object>> receipts = (ArrayList<HashMap<String, Object>>) db.getReceiverReceipts(session.getContactId());

        if(!receipts.isEmpty()){
            StringBuilder receiptsBuilder = new StringBuilder();

            for(HashMap<String, Object> receipt : receipts){
                receiptsBuilder.append("<stream:message from='"+receipt.get("senderId")+"' to='"+receipt.get("receiverId")+"' id='"+receipt.get("messageId")+"'>\n");
                receiptsBuilder.append("<received xmlns='urn:xmpp:receipts'/>\n");
                receiptsBuilder.append("</stream:message>");
            }

            try{
                OutputStreamWriter writer = new OutputStreamWriter(session.getSocket().getOutputStream());
                writer.write(String.valueOf(receiptsBuilder));
                writer.flush();
            }catch(IOException exception){
                logger.info("Error occurred getting and sending receipts: "+exception.getMessage());
            }
        }else{
            logger.info("Receiver receipts is empty");
        }
    }

    private void checkAndSendCacheMessages(Session session, DatabaseManagement db){
        ArrayList<HashMap<String, Object>> messages = (ArrayList<HashMap<String, Object>>) db.getOfflineMessages(session.getContactId());

        if(!messages.isEmpty()){
            StringBuilder messagesBuilder = new StringBuilder();
            for(HashMap<String, Object> msg : messages){

                messagesBuilder.append("<stream:message\n"+
                "from='"+msg.get("senderId")+"'\n"
                +"to='"+msg.get("receiverId")+"'\n"
                +"id='"+msg.get("messageId")+"'\n"
                +"type='TEXT'>\n");

                messagesBuilder.append("<body>");
                messagesBuilder.append(msg.get("body"));
                messagesBuilder.append("</body>\n");

                messagesBuilder.append("<request xmlns='urn:xmpp:receipts'/>\n");

                messagesBuilder.append("<timestamp>");
                messagesBuilder.append(msg.get("timestamp"));
                messagesBuilder.append("</timestamp>\n");
                
                
                messagesBuilder.append("</stream:message>\n");
                
                messagesBuilder.append("<not-implemented/>\n");
            }

            try{
                OutputStreamWriter writer = new OutputStreamWriter(session.getSocket().getOutputStream());
                writer.write(String.valueOf(messagesBuilder));
                writer.flush();

                logger.info("All pending messages send successfully");
            }catch(IOException exception){
                logger.info("Error occurred sending messages");
            }
        }
    }
}