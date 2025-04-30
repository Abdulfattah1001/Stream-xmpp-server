package streammessenger.res;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.util.logging.Logger;

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
    private final DatabaseManagement db;

    public MessageTagParser(Socket conn, StartElement startElement, XMLEventReader reader){
        this.socket = conn;
        this.messageStartElement = startElement;
        this.reader = reader;
        db = null;
    }

    public void parseMessageTag() throws XMLStreamException {
        String sender_contact = messageStartElement.getAttributeByName(new QName("from")).getValue();
        String receiver_contact = messageStartElement.getAttributeByName(new QName("to")).getValue();
        String messageType = messageStartElement.getAttributeByName(new QName("type")).getValue();
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
                            if(StreamServer.connections.containsKey(receiver_contact)){
                                logger.info("The receiver is online, routing the message to the user");
                                try{
                                    OutputStreamWriter receiver_writer = new OutputStreamWriter(StreamServer.connections.get(receiver_contact).getOutputStream());
                                    receiver_writer.write("<stream:message\n" +
                                            "from='"+sender_contact+"'\n" +
                                            "to='"+receiver_contact+"'\n" +
                                            "type='"+"TEXT"+"'>\n" +
                                            "<body>"+ body +"</body>\n" +
                                            "</stream:message>");
                                    receiver_writer.flush();
                                } catch (IOException e) {
                                    logger.info("Error occurred sending message to the receiver: "+e.getMessage());
                                }
                            }
                        }
                        if(event.isEndElement() && event.asEndElement().getName().getLocalPart().equals("body")) break;
                    }
                    if(StreamServer.connections.containsKey(receiver_contact)) {
                        try{
                            Socket receiver_socket = StreamServer.connections.get(receiver_contact);
                            OutputStreamWriter writer = new OutputStreamWriter(receiver_socket.getOutputStream());

                            writer.write("<stream:message\n"+
                                            "from='" + sender_contact + "'\n"+
                                            "to='" + receiver_contact + "'\n"+
                                            "type='" + messageType + "'>\n"
                                               +"<body>" + body + "</body>\n"
                                        +"</stream:message>");

                            writer.flush();
                        }catch(IOException exception){
                            logger.info("Error occurred sending message to the receiver: "+exception.getMessage());
                        }
                    }else{
                        persistMessageForOfflineUser(receiver_contact, sender_contact, body.toString(), messageType);
                        if(db != null){
                            //TODO: Cache the message for the user pending till reconnection
                        }
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
    private void send_notification(String receiver_id, String sender_id, String message_content){}
}
