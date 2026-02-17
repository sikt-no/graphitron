package no.sikt.graphitron.example.service.records;

import no.sikt.graphitron.example.generated.jooq.tables.records.RentalRecord;

/**
 * Test Java record (POJO) for @nodeId merging testing.
 * Both customerId and inventoryId @nodeId fields map to the 'rental' field,
 * which is a RentalRecord that will have both CUSTOMER_ID and INVENTORY_ID populated.
 */
public class NodeIdMergingInput {
    private String name;
    private RentalRecord rental;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public RentalRecord getRental() {
        return rental;
    }

    public void setRental(RentalRecord rental) {
        this.rental = rental;
    }
}
