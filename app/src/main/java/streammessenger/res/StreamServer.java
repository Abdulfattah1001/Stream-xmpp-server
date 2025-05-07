package streammessenger.res;


import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;


/**
 * The Entry into the custom xmppp protocol implementation
 * */

public class StreamServer {
    private boolean isHTTPSEnabled = false;
    private final int PORT;
    @SuppressWarnings("unused")
    private final String address;
    private final Logger logger = Logger.getLogger("stream_server");
    
    public volatile  static ConcurrentHashMap<String, Socket>  connections = new ConcurrentHashMap<String, Socket>();
    private final DatabaseManagement databaseManagement;

    private static StreamServer instance = null;

    
    private StreamServer(Builder builder){
        this.PORT = builder.PORT;
        this.address = builder.address;
        this.isHTTPSEnabled = builder.enableHttps;
        this.databaseManagement = builder.databaseManagement;

        if(this.databaseManagement != null){
            try{
                this.databaseManagement.connects();
            }catch(Exception exception){
                logger.info(() -> "Error occurred: "+exception.getMessage());
            }
        }
    }

    /**
     * Returns the connection instance
     * @param server The server address
     * @param port The port to listen to
     * @param isHTTPSEnabled Either HTTP is enabled or not
     * @param db The database connection instance
     * @return An instance of the connection
     */
    public static StreamServer getInstance(String server, int port, boolean isHTTPSEnabled, DatabaseManagement db){
        if(instance == null){
            instance = new Builder()
                    .setAddress(server)
                    .setPort(port)
                    .setHTTP(isHTTPSEnabled)
                    .setDBManagement(db)
                    .build();
        }
        return instance;
    }


    /**
     * Starts the server connection to listen on the specify address
     */
    public void start(){
        try(ServerSocket socket = new ServerSocket(PORT)){
            while(true){
                Socket clientSocketConnection = socket.accept(); //Get's the client socket connection
                ExecutorService poolJob = Executors.newFixedThreadPool(10);
                poolJob.execute(new ConnectionHandler(clientSocketConnection)); //Handles the client connection on a separate thread
            }
        }catch(IOException exception){
            logger.info(() -> "Error occurred starting stream server: "+exception.getMessage());
        }
    }

    /**
     * A Callable implementation to handle user-connection to the server and also validate the 
     * Connection at the same time
     */
    class ConnectionHandler implements Runnable {
        private final Socket connection;
        private boolean isConnectionSecure = false; //Is user connection to the server is encrypted
        private boolean isUserAuthenticated = false; //Is the user authenticated
        private int streamRestarterCounter = 0; //A variable that track how many time the stream has been restarted

        private String user_contact = null;
        
        public ConnectionHandler(Socket socket){
            this.connection = socket;
        }

        public void setUserContact(String contact){
            this.user_contact = contact;
        }

    
        
        private String streamHeader(){
            return new String("<?xml version='1.0'?>\n"+
                              "<stream:stream\n"+
                                "xmlns='jabber:server'\n"+
                                "xmlns:stream='http://etherx.jabber.org/streams'>\n");
        }

        /**
         * This function is called to process the initial XML Stream that's
         * sent from the client side which is different from the XML that's
         * sent after restarting the XML Stream
         * @param startElement
         */
        private void processInitialStream(StartElement startElement){
            //Increment the streamRestarterCounter
            streamRestarterCounter++;

            String streamNamespace = startElement.getName().getNamespaceURI();
            

            try{
                //Validating the namespace
                if(streamNamespace.equals("http://etherx.jabber.org/streams") ){
                    OutputStream os = connection.getOutputStream();
                    OutputStreamWriter writer = new OutputStreamWriter(os);
                    writer.write(streamHeader());

                    //If HTTPS is enabled, send the STARTTLS features else pass
                    if(isHTTPSEnabled) writer.write(getStartTLSString());
                    //If HTTPS is not enable
                    if(!isHTTPSEnabled){
                        writer.write(getAuthStreamFeatures());
                    }
                    writer.flush();
                }else{
                    //Close the socket connection 
                    OutputStream os = connection.getOutputStream();
                    OutputStreamWriter writer = new OutputStreamWriter(os);
                    writer.write("<failure> invalid-namespace </failure>");
                    writer.write("</stream:stream>");
                    writer.flush();
                    connection.close(); //Close the client connection
                }
            }catch(IOException exception){
                logger.info(() -> "Error occurred :"+exception.getMessage());
            }

        }

        private boolean verifyClientConnection(){
            return true;
        }


        private void startStreamingXML(InputStream is){
            try {
                XMLInputFactory factory = XMLInputFactory.newInstance(); //Get's the XMLInputFactory
                XMLEventReader xmlEventReader = factory.createXMLEventReader(is); //Creates an XMLEventReader
                while(xmlEventReader.hasNext()){ //Process the stream while the reader has next data

                    XMLEvent event = xmlEventReader.nextEvent(); //Gets the next Event

                    if(event.isEndElement() && event.asEndElement().getName().getLocalPart().equals("stream")){
                        try{
                            connections.get(user_contact).close(); //Get the user socket and close the connection
                            connections.remove(user_contact); //Remove the user connection from the hashmap
                            connection.close(); //Close the client connection, Same thin
                            //TODO: The presence table can now be updated
                        }catch(IOException exception){
                            logger.info("Error closing and tearing down the user connection: "+exception.getMessage());
                        }
                    }

                    if(event.isStartElement()){
                        StartElement startElement = event.asStartElement();
                        String tagName = startElement.getName().getLocalPart(); //Get's the  tag Name of the Event
                        switch (tagName) {
                            case "stream":
                                if(streamRestarterCounter == 0){
                                    processInitialStream(startElement);
                                }else{
                                    if(isHTTPSEnabled){
                                        //Verify the connections
                                        boolean isSocketSecure = verifyClientConnection();
                                        if(isSocketSecure){
                                            //Only the STARTTLS requirement has been met
                                            if(isConnectionSecure && isUserAuthenticated == false){
                                                processSecureStream(startElement);
                                            }
    
                                            if(isConnectionSecure && isUserAuthenticated){
                                                processSecureAuthStream(startElement);
                                            }
                                        }else{
                                            if(user_contact != null && connections.containsKey(user_contact)){
                                                try{
                                                    connections.get(user_contact).close();
                                                } catch (IOException e) {
                                                    logger.info("Error occurred");
                                                }
                                            }else{
                                                try{
                                                    connection.close();
                                                } catch (IOException e) {
                                                    logger.info("Error occurred");
                                                }
                                            }
                                        }
                                    }else{
                                        //If the channel is not secure i.e testing enviroment
                                        processUnsecureAuthStream(startElement);
                                    }
                                                                        
                                    //if the connection has been upgraded
                                    if(isConnectionSecure){
                                        processSecureStream(startElement);
                                    }
                                }
                                
                                break;

                            case "starttls":
                                StartTLSParser startTLSParser = new StartTLSParser(connection, xmlEventReader, startElement);
                                startTLSParser.parseStartTls();
                                break;
                                
                            case "auth":
                                AuthParser authParser = new AuthParser(xmlEventReader, startElement, databaseManagement,connection, this);
                                authParser.parseAuthXMLStream();
                                break;
                            
                            case "message":
                                MessageTagParser messageTagParser = new MessageTagParser(connection, startElement, xmlEventReader);
                                messageTagParser.parseMessageTag();
                                break;

                            case "iq": //The Roster presence information enquiries
                                InfoQueryParser infoQueryParser = new InfoQueryParser(connection, xmlEventReader, startElement, databaseManagement);
                                infoQueryParser.InfoQueryTagParser();
                                break;

                            case "presence":
                                PresenceTagParser presenceTagParser = new PresenceTagParser(connection, xmlEventReader, startElement);
                                presenceTagParser.parse();
                                break;
                            default:
                                break;
                        }
                    }
                }
            } catch (XMLStreamException exception) {

                //Test if the user is still connected because this error is also thrown when the connection closes abrubtly
                if(!connection.isConnected()){
                    try{
                        connections.remove(user_contact); //Remove the user socket connection from the connections
                        connection.close(); //Closes the user socket connection
                        //TODO: The presence database can now be updated
                    }catch(IOException e){
                        logger.info("Error occurred tearing down the user connection on disconnect: "+e.getMessage());
                    }
                }else{
                    //Else  the user really sent a malformed XML tag element
                    logger.info("The user really sent a malformed xml tag: "+exception.getMessage());
                }
            }
        }

        private String getStartTLSString(){
            return "<stream:features xmlns='urn:ietf:params:xml:ns:xmpp-starttls' />";
        }


        private String getAuthStreamFeatures(){
            return "<features xmlns='urn:ietf:params:xml:ns:xmpp-sasl'/>";
        }

        private void processUnsecureAuthStream(StartElement startElement){
            logger.info(() -> "Unsecure Auth called");
        }

        private void processSecureAuthStream(StartElement startElement){
            //After the user is authenticated, updates the userId and other variable
            logger.info(() -> "The user has been authenticated successfully");
        }

        /**
         * A function that was called to process the XML if the user connection to the server is secure
         * @param streamElement
         */
        private void processSecureStream(StartElement streamElement){
            //Verify the connection to see if truly the connection has beem upgraded
            boolean isSocketSecure = verifyClientConnection();
            if(isSocketSecure){
                //Proceed
                try{
                    OutputStream os = connection.getOutputStream();
                    OutputStreamWriter writer = new OutputStreamWriter(os);
                    writer.write("<stream:stream\n"
                                    +"xmlns='jabber:server'\n"
                                    +"xmlns:stream='http://etherx.jabber.org/streams'>");
                    writer.write(getAuthStreamFeatures());
                    writer.flush();
                }catch(IOException exception){

                }
            }else{
                //Abort the connection due to invalid OR un-authorize access
            }
            
        }

        @Override
        public void run(){
            try{
                InputStream is = connection.getInputStream();
                startStreamingXML(is);
            }catch(IOException exception){
                logger.info(() -> "Error occurred: "+exception.getMessage());
            }
        }
    }

    public static class Builder {
        private int PORT;
        private String address;
        private boolean enableHttps = false;
        private DatabaseManagement databaseManagement = null;

        public Builder setPort(int port){
            this.PORT = port;
            return this;
        }

        public Builder setAddress(String address){
            this.address = address;
            return this;
        }

        public Builder setHTTP(boolean isHttp){
            this.enableHttps = isHttp;
            return this;
        }

        public Builder setDBManagement(DatabaseManagement db){
            this.databaseManagement = db;
            return this;
        }

        public StreamServer build(){
            return new StreamServer(this);
        }
    }
}