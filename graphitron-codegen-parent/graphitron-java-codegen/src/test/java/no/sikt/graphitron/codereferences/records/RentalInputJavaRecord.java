package no.sikt.graphitron.codereferences.records;

import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.RentalRecord;

/**
 * Test Java record (POJO) with a jOOQ record field for @nodeId merging testing.
 * Used to test merging of multiple @nodeId fields into a single jOOQ record.
 * The RentalRecord has both CUSTOMER_ID and INVENTORY_ID columns.
 */
public class RentalInputJavaRecord {
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