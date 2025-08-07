package streammessenger.res;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.util.logging.Logger;
import java.util.stream.Stream;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;

import streammessenger.res.StreamServer.ConnectionHandler;


/**
 * XEP-0184
 * Implementation of the received receipt
 * @author Abdulfattah
 * @version 1.0.0
 */
public class Receipt {
    private XMLEventReader reader;
    private StartElement startElement;
    private final static Logger logger = Logger.getLogger("receipt");

    public Receipt(StartElement startElement, XMLEventReader reader){
        this.startElement = startElement;
        this.reader = reader;
    }

    public void start(){
        logger.info("Processing received receipt message....");
        String messageId = startElement.getAttributeByName(new QName("id")).getValue();
        String from = startElement.getAttributeByName(new QName("from")).getValue();
        String to = startElement.getAttributeByName(new QName("to")).getValue();
        
        logger.info("The id of the message to processed is "+messageId);
        logger.info("The sender of the message to processed is "+to);
        logger.info("The receiver of the message to processed is "+from);

        try{
            if(StreamServer.connections.contains(to)){
                logger.info("The receiver of the receivpt is online");
                Socket conn = StreamServer.connections.get(to);
                OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream());


                writer.write("<not-implemented />\n");
                writer.flush();
            }else{
                logger.info("The receiver of the receipt is offline..");
            }
        }catch(IOException exception){
            logger.info("Error sending receipt..."+exception.getMessage());
        }
        try{
            while (reader.hasNext()) {

                XMLEvent event = reader.nextEvent();

                if(event.isCharacters() && event.asCharacters().isWhiteSpace()) continue;

                if(event.isEndElement() && event.asEndElement().getName().getLocalPart().equals("received")) break;

            }
        }catch(XMLStreamException exception){
            logger.info("Error occurred processing received element");
        }
    }
}