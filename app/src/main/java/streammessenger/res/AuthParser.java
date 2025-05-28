package streammessenger.res;

import com.google.api.core.ApiFuture;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;

import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.util.Base64;
import java.util.concurrent.ExecutionException;
import java.util.logging.Logger;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;



///This class is used to authenticate the user

public class AuthParser {
    private static final org.slf4j.Logger log = LoggerFactory.getLogger(AuthParser.class);
    private final XMLEventReader reader;
    private final StartElement authStartElement;
    private final static Logger logger = Logger.getLogger("authentication");
    
    private final Socket socketConnection;
    @SuppressWarnings("unused")
    private final DatabaseManagement db;

    private final StreamServer.ConnectionHandler connectionHandler;

    public AuthParser(XMLEventReader reader, StartElement startElement, DatabaseManagement databaseManagement, Socket connection, StreamServer.ConnectionHandler connectionHandler){
        this.reader = reader;
        this.authStartElement = startElement;
        this.db = databaseManagement;
        this.socketConnection = connection;
        this.connectionHandler = connectionHandler;
    }


    @Deprecated
    public static String decodeAndGetContact(String token){
        //TODO: The secret key should be stored securely
        String contact = null;
        try{
            Jws<Claims> claims = Jwts.parser().verifyWith(Keys.hmacShaKeyFor("ABDULFATTAH-ABDULFATTAH-ABDULFATTAH-ABDULFATTAH-ABDULFATTAH-ABDULFATTAH-ABDULFATTAH-ABDULFATTAH-ABDULFATTAH".getBytes())).build().parseSignedClaims(token);
            contact = (String) claims.getPayload().get("phone_number");
            
            @SuppressWarnings("unused")
            String user_id = (String) claims.getPayload().get("user_id");

        }catch(JwtException exception){
            logger.info("Error occurred in authparser:  "+exception.getLocalizedMessage());
        }
        return contact;
    }

    /**
     * validate the user token against the database or
     * any other means of validation
     * @param token
     * @return a boolean value indicating
     * the auth state as either true or false
     */
    //private boolean isUserIdValid(String token){
    //    return db.authenticateUserToken(token);
    //}

    /**
     * This function is used to parse the XML Stream <auth> tag of the ongoing stream
     * connection from the client to the server
     * @throws XMLStreamException An XMLStreamException is throwing should any error relating
     * XML Parse occurs
     */
    public void parseAuthXMLStream() throws XMLStreamException {
        if(reader != null){
            while(reader.hasNext()){ //While repetition needs to be removed
                XMLEvent event  = reader.nextEvent();
                if(authStartElement.isStartElement()){

                    //TODO: Authenticating the user should be delegated to Firebase API [Firebase Authentication]
                    
                    @SuppressWarnings("unused")
                    String tagName = authStartElement.getName().getLocalPart();
                    @SuppressWarnings("unused")
                    String namespace = authStartElement.getName().getNamespaceURI();

                    if(event.isCharacters() && event.asCharacters().isWhiteSpace()) event = reader.nextEvent();

                    String contact1 = event.asCharacters().getData();
                    contact1 = new String(Base64.getDecoder().decode(contact1));
                    String contact = "+2349063109106";
                    boolean isTokenValid = true;

                    try {
                        FirebaseToken token = FirebaseAuth.getInstance().verifyIdTokenAsync(contact1).get();
                        logger.info("User ID is: "+token.getUid());
                    } catch (InterruptedException e) {
                        logger.info("Error occurred :"+e.getMessage());
                    } catch (ExecutionException e) {
                        logger.info("Error occurred  2:"+e.getMessage());
                    }

                    //Get the token body that was sent to the server
                    /**String tokenBody = event.asCharacters().getData();  //The encoded userId string
                    //String decodedTokenBody = new String(Base64.getDecoder().decode(tokenBody)); //The decoded userId string
                    TokenBasedValidation tokenBasedValidation = new TokenBasedValidation(tokenBody);

                    //String contact = AuthParser.decodeAndGetContact(decodedTokenBody);
                    String contact = tokenBasedValidation.getPhoneNumber();

                    @SuppressWarnings("unused")
                    String user_id = tokenBasedValidation.getUserId();

                    if(tokenBasedValidation.isUserAuthenticated()){
                        try{
                            OutputStream os = socketConnection.getOutputStream();
                            OutputStreamWriter writer = new OutputStreamWriter(os);

                            writer.write("<success xmlns='urn:ietf:params:xml:ns:xmpp-sasl' />");
                            
                            StreamServer.connections.put(contact, socketConnection);

                            connectionHandler.setUserContact(contact);

                        }catch(IOException exception){
                            logger.info("Error occurred: "+exception.getMessage());
                        }
                    }else{
                        try{
                            OutputStream os = socketConnection.getOutputStream();
                            OutputStreamWriter writer = new OutputStreamWriter(os);

                            writer.write("""
                                <stream:failure xmlns='urn:ietf:params:xml:ns:xmpp-sasl'>
                                    <not-authorized> Invalid Token </not-authorized>
                                </stream:failure>
                                """);

                            writer.write("""
                                </stream:stream>
                            """);

                            writer.flush();

                            socketConnection.close();
                        }catch(IOException exception){}
                    }

                    break;*/

                    if(isTokenValid){
                        logger.info("The user contact is  "+contact);
                        try{
                            OutputStream os = socketConnection.getOutputStream();
                            OutputStreamWriter writer = new OutputStreamWriter(os);

                            writer.write("<success xmlns='urn:ietf:params:xml:ns:xmpp-sasl' />");
                            
                            StreamServer.connections.put(contact, socketConnection);

                            connectionHandler.setUserContact(contact);

                        }catch(IOException exception){
                            logger.info("Error occurred: "+exception.getMessage());
                        }
                    }else{
                        try{
                            OutputStream os = socketConnection.getOutputStream();
                            OutputStreamWriter writer = new OutputStreamWriter(os);

                            writer.write("""
                                <stream:failure xmlns='urn:ietf:params:xml:ns:xmpp-sasl'>
                                    <not-authorized> Invalid Token </not-authorized>
                                </stream:failure>
                                """);

                            writer.write("""
                                </stream:stream>
                            """);

                            writer.flush();

                            socketConnection.close();
                        }catch(IOException exception){}
                    }

                    break;
                }
            }
        }
    }
}