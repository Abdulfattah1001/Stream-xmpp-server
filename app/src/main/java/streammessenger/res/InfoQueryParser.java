package streammessenger.res;

import java.net.Socket;
import java.util.logging.Logger;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;

public class InfoQueryParser {
    @SuppressWarnings("unused")
    private final Socket conn;
    private final XMLEventReader reader;
    private final StartElement iqStartElement;
     private final Logger logger = Logger.getLogger("iqParser");
    private final DatabaseManagement databaseManagement;

    public InfoQueryParser(Socket socket, XMLEventReader reader, StartElement startElement, DatabaseManagement db){
        this.conn = socket;
        this.reader = reader;
        this.iqStartElement = startElement;
        this.databaseManagement = db;
    }

    public void InfoQueryTagParser() throws XMLStreamException {
        
        if(iqStartElement != null){
            String iqType = iqStartElement.getAttributeByName(new QName("type")).getValue(); //The type of InfoQuery Tag
            String userJID = iqStartElement.getAttributeByName(new QName("from")).getValue(); //The userId of the current user
            logger.info("The user to updates it roster is:  " + userJID);
            logger.info("The type of roster set is  " + iqType);

            if(iqType.equals("set")){
                logger.info("Setting a user roster...");

                while(reader.hasNext()){
                    XMLEvent event = reader.nextEvent();
                    if(event.isStartElement()){
                        StartElement startElement = event.asStartElement();
                        String tagName = startElement.getName().getLocalPart();
                        if(tagName.equals("query")){
                            logger.info("Encounter Query...");
                            while(reader.hasNext()){

                                XMLEvent event2 = reader.nextEvent();
                                if(event2.isCharacters() && event2.asCharacters().isWhiteSpace()) event2 = reader.nextEvent();

                                if(event2.isStartElement() && event2.asStartElement().getName().getLocalPart().equals("item")){
                                    
                                    StartElement itemStartElement = event2.asStartElement();
                                    String contactId = itemStartElement.getAttributeByName(new QName("jid")).getValue(); //The contactId of the user to add to the roster
                                    logger.info("The user name --- and contact is: "+contactId);
                                    //TODO: Update the user roster
                                }else if(event.isEndElement() && event2.asEndElement().getName().getLocalPart().equals("query")) break; else{
                                    break;
                                }
                            }
                        }
                    }

                    if(event.isEndElement() && event.asEndElement().getName().getLocalPart().equals("query")) break;
                }
            }

            if(iqType.equals("get")){
                while(reader.hasNext()){
                    XMLEvent event = reader.nextEvent();
                    if(event.isStartElement() && event.asStartElement().getName().getLocalPart().equals("query")){
                        StartElement queryStartElement = event.asStartElement();
                        String namespace = queryStartElement.getName().getNamespaceURI();
                        logger.info(() -> "The Query Namespace is: "+namespace);
                        if(databaseManagement != null){
                            databaseManagement.getRosters(userJID);
                        }
                    }

                        //If the event is EndElement, break-out of the loop
                    if(event.isEndElement()) break;
                }
            }
        }
    }
}
