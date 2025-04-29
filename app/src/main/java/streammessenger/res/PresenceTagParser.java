package streammessenger.res;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.util.logging.Logger;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;

import streammessenger.res.StreamServer.ConnectionHandler;


public class PresenceTagParser {
    @SuppressWarnings("unused")
    private final Socket connection;
    @SuppressWarnings("unused")
    private final XMLEventReader reader;
    @SuppressWarnings("unused")
    private final StartElement element;

    private final static Logger logger = Logger.getLogger("presence_logger");

    public PresenceTagParser(Socket socket, XMLEventReader reader, StartElement element){
        this.connection = socket;
        this.reader = reader;
        this.element = element;
    }

    public void parse(){
        String to = "+2349063109106";
        String from = "+2349063109106";
        
        
        if(to != null && from != null){
            DatabaseManagement db = DatabaseManagement.getInstance(CredentialManager.getPassword(), CredentialManager.getDatabaseName(), CredentialManager.getDatabaseUsername());
            SubscriptionStatus status = db.getSubscriptionStatus(to, from);

            if(status == SubscriptionStatus.BOTH || status == SubscriptionStatus.TO){
                
                if(StreamServer.connections.containsKey("+2349063109106")){ //If the user is online
                    Socket socket = StreamServer.connections.get("+2349063109106");
                    if(socket.isConnected()){
                        try{
                            OutputStreamWriter writer = new OutputStreamWriter(socket.getOutputStream());
                            writer.write("<presence from='"+to+"'></presence>");

                            writer.flush();
                        }catch(IOException exception){
                            logger.info("Error sending the presence info to the sender: "+exception.getMessage());
                        }
                    }
                }else{
                    
                    //TODO:Get the last seen of the user from the database
                    try{
                        Socket socket = StreamServer.connections.get("+2349063109106");
                        OutputStreamWriter writer = new OutputStreamWriter(socket.getOutputStream());
                        writer.write("<presence>\n"
                        +"<show> \n"
                        +"Last seen 9:00PM 2025\n"
                        +" </show>\n"
                        +"</presence>\n");

                        writer.flush();

                    }catch(IOException exception){
                        logger.info("Error occurred: "+exception.getMessage());
                    }
                }
            }
            
        }
    }

    /**
     * Fetches the user presence infomation
     * @param user_phone_number
     */
    //public void fetch_user_presence_info(String user_phone_number){}

    //TODO: The server looks up the user roster upon receiving a presenc info
    // and checks for the sub status to see those who are eligible to be notified
    // for the current user presence

    //What to look up for later
    /**
     * Roster presence flooding
     * Presence probing
     */
}