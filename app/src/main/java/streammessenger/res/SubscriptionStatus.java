package streammessenger.res;

public enum SubscriptionStatus {
    NONE("none"),
    BOTH("both"),
    TO("to"),
    FROM("from");

    private final String value;

    SubscriptionStatus(String value){
        this.value = value;
    }

    public static SubscriptionStatus fromString(String value){
        for(SubscriptionStatus s: SubscriptionStatus.values()){
            if(s.value.equalsIgnoreCase(value)) return s;
        }

        return null;
    }


    public String getValue(){
        return this.value;
    }
}