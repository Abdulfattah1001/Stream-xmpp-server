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
        
        logger.info(() -> "Processing the info query tag ...");
        if(iqStartElement != null){
            String iqType = iqStartElement.getAttributeByName(new QName("type")).getValue(); //The type of InfoQuery Tag
            String userJID = iqStartElement.getAttributeByName(new QName("from")).getValue(); //The userId of the current user
            logger.info("Type is "+iqType +" And the contact is: "+userJID);
            if(StreamServer.connections.containsKey(userJID)) logger.info("Wow");

            //if(!StreamServer.connections.contains(userJID)) throw new IllegalStateException("Error the initiating is not connected");
            //A InfoQuery to set or update user roster
            if(iqType.equals("set")){

                while(reader.hasNext()){
                    XMLEvent event = reader.nextEvent();
                    if(event.isStartElement()){
                        StartElement startElement = event.asStartElement();
                        String tagName = startElement.getName().getLocalPart();
                        if(tagName.equals("query")){

                            while(reader.hasNext()){

                                XMLEvent event2 = reader.nextEvent();
                                if(event2.isCharacters() && event2.asCharacters().isWhiteSpace()) event2 = reader.nextEvent();

                                if(event2.isStartElement() && event2.asStartElement().getName().getLocalPart().equals("item")){
                                    
                                    StartElement itemStartElement = event2.asStartElement();
                                    String contactId = itemStartElement.getAttributeByName(new QName("jid")).getValue(); //The contactId of the user to add to the roster
                                    String nickname = itemStartElement.getAttributeByName(new QName("nickname")).getValue(); //The Nickname of the user to add, it might benull
                                    logger.info("The user name is:"+nickname+"--- and contact is: "+contactId);
                                    databaseManagement.updateRoster(userJID, contactId, nickname);
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
