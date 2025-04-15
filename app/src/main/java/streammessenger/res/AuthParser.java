package streammessenger.res;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.util.Properties;
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


public class AuthParser {
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

    public static String decodeAndGetContact(String token){
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
        /**Properties properties = new Properties();
        try{
            FileInputStream fileInputStream = new FileInputStream("config.properties");
            properties.load(fileInputStream);
        }catch(IOException exception){}*/
        if(reader != null){
            while(reader.hasNext()){ //While repetition needs to be removed
                XMLEvent event  = reader.nextEvent();
                if(authStartElement.isStartElement()){
                    
                    @SuppressWarnings("unused")
                    String tagName = authStartElement.getName().getLocalPart();
                    @SuppressWarnings("unused")
                    String namespace = authStartElement.getName().getNamespaceURI();

                    if(event.isCharacters() && event.asCharacters().isWhiteSpace()) event = reader.nextEvent();

                    /**if(properties.getProperty("authMechanism").equals("TOKEN")){
                        logger.info("Token base authentication mechanism enabled");
                        
                    }*/

                    //Get the token body that was sent to the server
                    String tokenBody = event.asCharacters().getData();  //The encoded userId string
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

                    break;
                    
                    /**try{
                        OutputStream os = socketConnection.getOutputStream();
                        OutputStreamWriter writer = new OutputStreamWriter(os);
                        //If the token is Valid
                        if(isUserIdValid(contact)) writer.write("<success xmlns='urn:ietf:params:xml:ns:xmpp-sasl' />");
                        if(isUserIdValid(contact)) StreamServer.connections.put(contact, socketConnection);
                        if(isUserIdValid(contact)) connectionHandler.setUserContact(contact);
                        //If the token is InValid 
                        if(!isUserIdValid(contact)) {
                            logger.info("The user is invalidated");
                            writer.write("""
                                    <stream:failure xmlns='urn:ietf:params:xml:ns:xmpp-sasl'>
                                    <not-authorized>
                                    Invalid Token\s
                                    </not-authorized>
                                    </stream:failure>
                                    """);
                            //Send stream closing tag
                            writer.write("</stream:stream>");
                            writer.flush();
                            //Close the socket connection
                            socketConnection.close();
                        }
                        
                        if(socketConnection.isConnected()) writer.flush();
                        break; //Break out of the loop when completes
                    }catch(IOException exception){
                        logger.info(() -> "Error occurred trying to send message to the user: "+exception.getMessage());
                    }*/

                }
            }
        }
    }
}