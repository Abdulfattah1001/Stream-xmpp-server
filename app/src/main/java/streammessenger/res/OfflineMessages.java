package streammessenger.res;

import java.util.logging.Logger;

public class OfflineMessages {
    private final Logger logger = Logger.getLogger("offline_messages");
    private final String uid;

    public OfflineMessages(String uid){
        this.uid = uid;
    }

    private boolean checkForOfflineMessages(){
        DatabaseManagement db = DatabaseManagement.getInstance(CredentialManager.getPassword(), CredentialManager.getDatabaseUsername(), CredentialManager.getDatabaseName());
        if(db.checkOfflineMessages(this.uid)){
            logger.info("Offline message are available for user "+this.uid);
        }else{
            logger.info("No offline message are available for user "+this.uid);
        }

        return false;
    }

    public void sendOfflineMessages(){
        DatabaseManagement db = DatabaseManagement.getInstance(CredentialManager.getPassword(), CredentialManager.getDatabaseUsername(), CredentialManager.getDatabaseName());
    }
}
