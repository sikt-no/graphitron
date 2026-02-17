package no.sikt.graphitron.example.service;

import no.sikt.graphitron.example.generated.jooq.tables.records.CustomerRecord;
import no.sikt.graphitron.example.generated.jooq.tables.records.FilmRecord;
import no.sikt.graphitron.example.service.records.HelloWorldInput;
import no.sikt.graphitron.example.service.records.HelloWorldRecord;
import no.sikt.graphitron.example.service.records.NodeIdConflictInput;
import no.sikt.graphitron.example.service.records.NodeIdMergingInput;
import no.sikt.graphitron.example.service.records.NodeIdToJooqInput;
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

    /**
     * Service method that tests @nodeId transformation to jOOQ record.
     * The input.getCustomer() returns a CustomerRecord with CUSTOMER_ID populated from the node ID.
     */
    public HelloWorldRecord helloWorldWithNodeIdToJooqRecord(NodeIdToJooqInput input) {
        var record = new HelloWorldRecord();
        var customerRecord = input.getCustomer();
        if (customerRecord != null) {
            record.setGreeting(String.format("Hello, customer with CUSTOMER_ID %d from jOOQ record!", customerRecord.getCustomerId()));
        } else {
            record.setGreeting(String.format("Hello, %s! No customer provided.", input.getName()));
        }
        return record;
    }

    /**
     * Service method that tests merging of two @nodeId fields into one jOOQ record.
     * The input.getRental() returns a RentalRecord with both CUSTOMER_ID and INVENTORY_ID populated.
     */
    public HelloWorldRecord helloWorldWithNodeIdMerging(NodeIdMergingInput input) {
        var record = new HelloWorldRecord();
        var rentalRecord = input.getRental();
        if (rentalRecord != null) {
            record.setGreeting(String.format("Rental with CUSTOMER_ID %d and INVENTORY_ID %d!",
                    rentalRecord.getCustomerId(), rentalRecord.getInventoryId()));
        } else {
            record.setGreeting(String.format("Hello, %s! No rental provided.", input.getName()));
        }
        return record;
    }

    /**
     * Service method that tests conflict detection when overlapping @nodeId fields have different values.
     * If filmActorId and actorId encode different ACTOR_IDs, the mapper should throw an exception
     * before this method is called.
     */
    public HelloWorldRecord helloWorldWithNodeIdConflict(NodeIdConflictInput input) {
        var record = new HelloWorldRecord();
        var filmActorRecord = input.getFilmActor();
        if (filmActorRecord != null) {
            record.setGreeting(String.format("FilmActor with ACTOR_ID %d and FILM_ID %d!",
                    filmActorRecord.getActorId(), filmActorRecord.getFilmId()));
        } else {
            record.setGreeting(String.format("Hello, %s! No film actor provided.", input.getName()));
        }
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

    public List<HelloWorldRecord> helloWorldWithSplitQueryListField() {
        var record1 = new HelloWorldRecord();
        var record2 = new HelloWorldRecord();

        record1.setGreeting("First");
        record2.setGreeting("Second");

        var film1 = new FilmRecord();
        film1.setFilmId("1");
        var film2 = new FilmRecord();
        film2.setFilmId("2");

        record1.setFilms(List.of(film2, film1));
        record2.setFilms(List.of());

        return List.of(record1, record2);
    }

    public List<HelloWorldRecord> helloWorldWithNullResolverKeys() {
        return List.of(new HelloWorldRecord());
    }
}
