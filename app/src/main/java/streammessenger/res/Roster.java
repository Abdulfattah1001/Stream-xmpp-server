package streammessenger.res;

import javax.xml.stream.XMLEventReader;
import javax.xml.stream.events.StartElement;

public class Roster {
    private final StartElement startElement;

    private final XMLEventReader reader;

    public Roster(StartElement startElement, XMLEventReader reader){
        this.startElement = startElement;
        this.reader = reader;
    }

    public void start(){
        
    }
}
