package streammessenger.res;

import java.net.Socket;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

import javax.annotation.Nullable;

public class Session {
    private String sessionId = null;
    private String authenticationToken = null;
    private String contactId = null;
    private String uid = null;
    private boolean isAuthenticated = false;
    private Socket socket = null; //The Client socket instance
    private SessionState sessionState = SessionState.INITIAL;
    private ScheduledFuture scheduledFuture = null;

    public Session(Socket socket, String id){
        this.socket = socket;
        this.sessionId = id;
    }

    public String getSessionId(){
        return sessionId;
    }

    public String getAuthenticationToken(){
        return authenticationToken;
    }

    public String getContactId(){
        return contactId;
    }

    public void setUid(String uid){
        this.uid = uid;
    }

    public String getUid(){
        return this.uid;
    }

    public boolean isAuthenticated(){
        return isAuthenticated;
    }

    @Nullable
    public Socket getSocket(){
        return socket;
    }

    @Nullable
    public ScheduledFuture getScheduledFuture(){
        return scheduledFuture;
    }

    public void setSessionState(SessionState sessionState){
        this.sessionState = sessionState;
    }

    public void setSessionId(String id){
        this.sessionId = id;
    }

    public void setAuthenticatedToken(String token){
        this.authenticationToken = token;
    }

    public void setContactId(String contact){
        this.contactId = contact;
    }

    public void setIsAuthenticated(boolean state){
        this.isAuthenticated = state;
    }

    public void setSocket(Socket socket){
        this.socket = socket;
    }

    public void setScheduledFuture(ScheduledFuture service){
        this.scheduledFuture = service;
    }

    /**
     * Gets and return the session state associated with this session
     * @return
     */
    public SessionState getSessionState(){
        return sessionState;
    }
}