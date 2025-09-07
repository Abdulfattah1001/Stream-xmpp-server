package streammessenger.res;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;

public class AuthTagParser {
    private static final Logger logger = Logger.getLogger("authentication");

    private static final SecureRandom secureRandom = new SecureRandom();
    
    
    public AuthTagParser(){}

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

    public void start(StartElement startElement, XMLEventReader reader, String sessionId, DatabaseManagement db, Session session) throws XMLStreamException{
        //Session session = SessionManager.getInstance().getSession(sessionId);
        
        while(reader.hasNext()){
            XMLEvent event = reader.nextEvent();

            if(event.isCharacters() && event.asCharacters().isWhiteSpace()) continue;

            String token = event.asCharacters().getData();

            token = new String(Base64.getDecoder().decode(token));

            try{
                FirebaseToken validatedToken = FirebaseAuth.getInstance().verifyIdTokenAsync(token).get();

                if(validatedToken.getUid() != null){

                    String jid = db.getContactById(validatedToken.getUid());

                    session.setSessionState(SessionState.AUTHENTICATED); //Updates the session state
                    session.setContactId(jid);
                    session.setIsAuthenticated(true);

                    //Caching the user session
                    Server.connections.put(jid, session);

                    logger.info("Successfully authenticated user");

                    OutputStreamWriter writer = new OutputStreamWriter(session.getSocket().getOutputStream());
                    writer.write("<success xmlns='urn:ietf:params:xml:ns:xmpp-sasl'/>\n");
                    writer.flush();


                    ScheduledExecutorService pingExecutorService = Executors.newSingleThreadScheduledExecutor();
                    ScheduledFuture<?> future = pingExecutorService.scheduleAtFixedRate(()->{
                        if(session.isAuthenticated()){
                            Socket socket = session.getSocket();

                            if(socket.isClosed() || !socket.isConnected()){
                                try{
                                    logger.warning("Detected broken connection for session: "+session.getSessionId());
                                    socket.close();

                                    Server.connections.remove(session.getSessionId());

                                    session.getScheduledFuture().cancel(true);
                                }catch(IOException ex){
                                    logger.info("Error occurred canceling the future: "+ex.getMessage());
                                }
                            }
                        }
                    }, 2, 2, TimeUnit.MINUTES);
                    session.setScheduledFuture(future);

                    //TODO: The client should resend the stream header and also presence
                    //Such that the offline messages can be checked
                }else{
                    logger.info("The user is not authenticated");

                    OutputStreamWriter writer = new OutputStreamWriter(session.getSocket().getOutputStream());
                    writer.write("<stream:failure xmlns='urn:ietf:params:xml:ns:xmpp-sasl'>\n"
                        +"<not-authorized> Invalid token </not-authorized>\n"
                    +"</stream:failure>\n");

                    writer.write("</stream:stream>"); //Close the stream

                    writer.flush();

                }
            }catch(InterruptedException | ExecutionException | IOException exception){}

            break; //Break out of the loop
        }
    }
}