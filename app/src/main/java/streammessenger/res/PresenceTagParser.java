package streammessenger.res;

import java.net.Socket;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.events.XMLEvent;


public class PresenceTagParser {
    @SuppressWarnings("unused")
    private final Socket connection;
    @SuppressWarnings("unused")
    private final XMLEventReader reader;
    @SuppressWarnings("unused")
    private final XMLEvent event;

    public PresenceTagParser(Socket socket, XMLEventReader reader, XMLEvent event){
        this.connection = socket;
        this.reader = reader;
        this.event = event;
    }

    public void parse(){
        while(reader.hasNext()){
            
        }
    }

    /**
     * Fetches the user presence infomation
     * @param user_phone_number
     */
    public void fetch_user_presence_info(String user_phone_number){}
}