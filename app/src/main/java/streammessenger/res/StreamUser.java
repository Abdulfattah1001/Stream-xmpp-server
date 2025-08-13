package streammessenger.res;

public class StreamUser {
    private String uid;
    private String contactId;
    private String displayName;
    private String avatarUrl;
    private String displayStatus;

    public StreamUser(String uid, String displayName, String avatarUrl, String status){
        this.uid = uid;
        this.displayName = displayName;
        this.avatarUrl = avatarUrl;
        this.displayStatus = status;
    }

    public String getUid(){
        return this.uid;
    }

    public String getDisplayName(){
        return this.displayName;
    }

    public String getAvatarUrl(){
        return this.avatarUrl;
    }
    public String getStatus(){
        return this.displayStatus;
    }
}
