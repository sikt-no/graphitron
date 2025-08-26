package no.sikt.graphitron.example.service;

import no.sikt.graphitron.example.generated.jooq.tables.records.CustomerRecord;
import no.sikt.graphitron.example.generated.jooq.tables.records.FilmRecord;
import no.sikt.graphitron.example.service.records.HelloWorldInput;
import no.sikt.graphitron.example.service.records.HelloWorldRecord;
import org.jooq.DSLContext;

import java.util.List;

public class HelloWorldService {
    public HelloWorldService(DSLContext context) {
    }

    public HelloWorldRecord helloWorldAgain(String name) {
        var record = new HelloWorldRecord();
        record.setGreeting(String.format("Hello, World and %s!", name));
        return record;
    }

    public HelloWorldRecord helloWorldAgainWithJooqRecordInput(CustomerRecord input) {
        var record = new HelloWorldRecord();
        record.setGreeting(String.format("Hello, customer with CUSTOMER_ID %d!", input.getCustomerId()));
        return record;
    }

    public HelloWorldRecord helloWorldAgainWithJavaRecordInput(HelloWorldInput input) {
        var record = new HelloWorldRecord();
        record.setGreeting(String.format("Hello, customer with node ID %s!", input.getCustomerId()));
        return record;
    }

    public List<HelloWorldRecord> helloWorldWithSplitQueryField(List<String> addressIds) {
        return addressIds.stream()
                .map(
                addressId -> {
                    var record = new HelloWorldRecord();
                    record.setGreeting("Hello, World!");
                    if (addressId == null || !addressId.isEmpty()) {
                        var film = new FilmRecord();
                        film.setFilmId(addressId);
                        record.setFilm(film);
                    }

                    var customer = new CustomerRecord();
                    customer.setCustomerId(1);
                    record.setCustomer(customer);
                    return record;
                }
        ).toList();
    }
}
