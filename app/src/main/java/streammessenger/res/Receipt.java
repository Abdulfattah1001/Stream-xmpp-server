package streammessenger.res;

import java.util.logging.Logger;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;

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
        logger.info("The id of the message to processed is "+messageId);
        try{
            while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();
            if(event.isCharacters() && event.asCharacters().isWhiteSpace()) event = reader.nextEvent();
            if(event.isEndElement() && event.asEndElement().getName().getLocalPart().equals("received")) break;
            }
        }catch(XMLStreamException exception){
            logger.info("Error occurred processing received element");
        }
    }
}