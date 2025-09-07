package streammessenger.res;

import java.util.concurrent.ScheduledExecutorService;

import javax.annotation.Nullable;

/**
 * A Singleton Pattern Session Manager that manages each client connection
 * session
 */
public class SessionManager {
    private static SessionManager instance = null;

    private SessionManager(){

    }


    public static SessionManager getInstance(){
        if(instance == null){
            instance = new SessionManager();
        }

        return instance;
    }


    /**
     * Gets and return a session associated with the session Id 
     * passed
     * @param sessionId The sessionId of the session to gets
     * @return Session
     */
    public Session getSession(String sessionId){
        if(Server.connections.contains(sessionId)){
            System.out.println("The Session is present....");
        }
        return Server.connections.get(sessionId);
    }


    /**
     * Starts sending a Whitespace keepalive to the client with
     * the associate id of sessionId
     * @param sessionId The associated SessionId
     * @param service The instance of ScheduledExecutorService that is used to periodically sends a ping to the client
     */
    public void startPingingSessionId(String sessionId, ScheduledExecutorService service){}
}
