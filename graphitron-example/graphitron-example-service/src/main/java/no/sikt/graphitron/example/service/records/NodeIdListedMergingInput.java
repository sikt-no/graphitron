package no.sikt.graphitron.example.service.records;

import no.sikt.graphitron.example.generated.jooq.tables.records.RentalRecord;

import java.util.List;

/**
 * Test Java record (POJO) for listed @nodeId merging testing.
 * Both customerIds and inventoryIds are [ID!]! lists that map to the 'rental' field,
 * which is a List of RentalRecords each having both CUSTOMER_ID and INVENTORY_ID populated.
 */
public class NodeIdListedMergingInput {
    private String name;
    private List<RentalRecord> rental;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<RentalRecord> getRental() {
        return rental;
    }

    public void setRental(List<RentalRecord> rental) {
        this.rental = rental;
    }
}
