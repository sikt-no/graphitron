package no.sikt.graphitron.example.service;

import no.sikt.graphitron.example.service.records.HelloWorldRecord;
import org.jooq.DSLContext;

public class HelloWorldService {
    public HelloWorldService(DSLContext context) {
    }

    public HelloWorldRecord helloWorldAgain(String name) {
        var record = new HelloWorldRecord();
        record.setGreeting(String.format("Hello, World and %s!", name));
        return record;
    }
}
