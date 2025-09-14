package streammessenger.res;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;

public class Server {
    private final int PORT;
    private final String address;
    private static final SecureRandom secureRandom = new SecureRandom();
    private final static Logger logger = Logger.getLogger("Server");
    public volatile static ConcurrentHashMap<String, Session> connections = new ConcurrentHashMap<String, Session>();
    private final DatabaseManagement db;
    private static Server instance = null;

    private Server(Builder builder){
        PORT = builder.getPort();
        address = builder.getAddress();
        this.db = builder.getDb();
    }

    public static Server getInstance(){
        if(instance == null) throw new IllegalStateException("Error getting the Server inistance");
        return instance;
    }

    public void start(){
        try(ServerSocket socket = new ServerSocket(PORT)){
            SessionManager.getInstance(); //Initialize the SessionManager
            ExecutorService jobPool = Executors.newFixedThreadPool(10);
            while(true){
                Socket connection = socket.accept();
                jobPool.execute(new Connectionhandler(connection, hexSessionId()));
            }
        }catch(IOException exception){}
    }

    public DatabaseManagement getDB(){
        return db;
    }
    
    
    /**
     * Generates and returns a unique and collision resistant
     * id for each stream
     * @return A string that represents the generated id
     * @deprecated
     */
    private String uniqueIdGenerator(){
        byte[] randomBytes = new byte[16];
        secureRandom.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }


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

    private class Connectionhandler implements Runnable{
        private final Socket socket;
        private SessionState sessionState = SessionState.INITIAL;
        private final String id; //The id that uniquely identifies the session


        public Connectionhandler(Socket soc, String id){
            this.socket = soc;
            this.id = id;
        }

        @Override
        public void run() {
            /**
             * A session is created for the client but it's not cache until
             * the client is authenticated only then will it be cache or stored
             * for resuming later or reconnecting
             */
            Session session = new Session(socket, id);
            session.setSessionState(sessionState); //Sets the session state to INITIAL
            startStream(session);
        }

        private void startStream(Session session){
            try(InputStream is = socket.getInputStream()){
                XMLInputFactory factory = XMLInputFactory.newInstance();
                XMLEventReader reader = factory.createXMLEventReader(is);

                StreamHeadParse streamParser = new StreamHeadParse();

                while(reader.hasNext()){
                    XMLEvent event = reader.nextEvent();

                    if(event.isCharacters() && event.asCharacters().isWhiteSpace()) continue; //Handling of whitespace keepalive mechanism

                    if(event.isEndElement() && event.asEndElement().getName().getLocalPart().equals("stream")){}

                    if(event.isStartElement()){
                        StartElement sE = event.asStartElement();
                        String tagName = sE.getName().getLocalPart();

                        switch (tagName) {
                            case "stream":
                                streamParser.start(sE, reader, session); //Passing the latest state of StartElement, XMLEventReader and session 
                                break;

                            case "starttls":
                                break;    

                            case "auth":
                                AuthTagParser authTagParser = new AuthTagParser();
                                authTagParser.start(sE, reader, id, db, session);
                                break;     

                            case "message":
                                MessageTagParser message = new MessageTagParser();
                                message.start(sE, reader);
                                break;
                                
                            case "presence":
                                break;
                                
                            case "iq":
                                break;
                                
                            case "enable":
                                //TODO: Gets the namespace of the tag
                                //TODO: Returns a <enabled xmlns='urn:xmpp:sm:3' resume=true id='some-long-id'/>
                                break;    
                            default:
                                break;
                        }
                    }
                }
            }catch(Exception exception){
                if(exception instanceof IOException){
                    logger.warning("Closing the client connection due to IOException");
                    if(socket.isClosed() || !socket.isConnected()){
                        connections.remove(session.getContactId()); //Removing the session instance from the ConcurrentHashMap
                        try {
                            session.getSocket().close();
                            session = null; //Set it up for Garbage collections
                        } catch (IOException e) {}
                    }
                }
                if(exception instanceof XMLStreamException){
                    logger.warning("Closing the client connection due to XML error: "+exception.getLocalizedMessage());
                    Socket socket =  session.getSocket();
                    if(socket.isClosed() || !socket.isConnected()){
                        try{
                            socket.close();
                            Server.connections.remove(session.getContactId());
                            session.setSocket(null);
                            session = null;
                        }catch(IOException ignore){}
                    }
                }
            }
        }
    }
    

    public static class Builder {
        private int PORT;
        private String address;
        private DatabaseManagement databaseManagement;

        public Builder setPort(int port){
            this.PORT = port;
            return this;
        }

        public Builder setAddress(String address){
            this.address = address;
            return this;
        }

        public Builder setDB(DatabaseManagement db){
            this.databaseManagement = db;
            return this;
        }

        public int getPort(){
            return PORT;
        }

        public String getAddress(){
            return address;
        }

        public DatabaseManagement getDb(){
            return this.databaseManagement;
        }

        public Server build(){
            instance =  new Server(this);
            return instance;
        }
    }

}