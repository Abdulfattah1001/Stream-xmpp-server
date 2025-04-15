package streammessenger.res;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.util.logging.Logger;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.events.StartElement;

public class StartTLSParser {
   private final Socket socket;
   @SuppressWarnings("unused")
   private final XMLEventReader reader;
   private final StartElement startTlsStartElement;
   private final Logger logger = Logger.getLogger("startTLS") ;
   @SuppressWarnings("unused")
   private int restarter = 0;

   public StartTLSParser(Socket socket, XMLEventReader reader, StartElement startElement){
    this.socket = socket;
    this.reader = reader;
    this.startTlsStartElement = startElement;
   }

   public void parseStartTls(){
        logger.info(() -> "Processing STARTTLS Tag ...");
        if(startTlsStartElement != null){
            @SuppressWarnings("unused")
            String namespace = startTlsStartElement.getAttributeByName(new QName("")).getValue();
            try{
                OutputStream os = socket.getOutputStream();
                OutputStreamWriter writer = new OutputStreamWriter(os);
                writer.write("<stream:proceed xmlns='urn:ietf:params:xml:ns:xmpp-sasl' />");
                writer.flush();

            }catch(IOException exception){
                logger.info(() -> "Error occurred processing startTLS tag: "+exception.getMessage());
            }
        }
   }
}
