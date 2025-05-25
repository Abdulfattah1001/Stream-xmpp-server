package streammessenger.res;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.util.logging.Logger;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.events.StartElement;


public class PresenceTagParser {
    @SuppressWarnings("unused")
    private final Socket connection;
    @SuppressWarnings("unused")
    private final XMLEventReader reader;
    private final StartElement element;

    private final static Logger logger = Logger.getLogger("presence_logger");

    public PresenceTagParser(Socket socket, XMLEventReader reader, StartElement element){
        this.connection = socket;
        this.reader = reader;
        this.element = element;
    }

    public void parse(){
        String to = element.getAttributeByName(new QName("to")).getValue();

        String from = element.getAttributeByName(new QName("from")).getValue();

        
        if(to != null && from != null){
            DatabaseManagement db = DatabaseManagement.getInstance(CredentialManager.getPassword(), CredentialManager.getDatabaseName(), CredentialManager.getDatabaseUsername());
            SubscriptionStatus status = db.getSubscriptionStatus(to, from);

            //Checking the subscription status of the sender to the receiver
            if(status == SubscriptionStatus.BOTH || status == SubscriptionStatus.TO){
                if(StreamServer.connections.containsKey(to)){ //If the user is online
                    Socket socket = StreamServer.connections.get(from); //Getting the connections
                    if(socket.isConnected()){ //If the connection is still available, then the user is online
                        try{
                            OutputStreamWriter writer = new OutputStreamWriter(socket.getOutputStream());
                            writer.write("<presence from='"+from+"'><show> Online </show></presence>");//Sends the connection status to the sender

                            writer.flush();
                        }catch(IOException exception){
                            logger.info("Error sending the presence info to the sender: "+exception.getMessage());
                        }
                    }
                }else{
                    //TODO:Get the last seen of the user from the database
                    try{
                        Socket socket = StreamServer.connections.get(from); ///Get the socket connection of the sender
                        OutputStreamWriter writer = new OutputStreamWriter(socket.getOutputStream());
                        String form = String.format("""
                            <presence from='%s'> 
                                <show> Last seen 9:00PM 2025 </show>
                            </presence>
                        """, from);
                        writer.write(form);

                        writer.flush();

                    }catch(IOException exception){
                        logger.info("Error occurred: "+exception.getMessage());
                    }
                }
            }
            
        }
    }
}