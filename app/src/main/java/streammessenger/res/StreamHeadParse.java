package streammessenger.res;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.security.SecureRandom;
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

                //TODO: Proceeds to check for any pending messages, receipts, e.t.c
                break;
                
            case RESUMING:
                break;
                
            case BOUND:
                break;

            default:
                break;
        }
    }
}
