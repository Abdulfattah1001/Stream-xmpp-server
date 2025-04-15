package streammessenger.res;

public class InvalidTokenException extends Exception{
    public InvalidTokenException(){
        super();
    }
    public InvalidTokenException(String error_message){
        super(error_message);
    }
}
