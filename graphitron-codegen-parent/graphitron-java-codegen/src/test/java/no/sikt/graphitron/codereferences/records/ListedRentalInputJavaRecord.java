package no.sikt.graphitron.codereferences.records;

import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.RentalRecord;

import java.util.List;

public class ListedRentalInputJavaRecord {
    private List<RentalRecord> rental;

    public List<RentalRecord> getRental() {
        return rental;
    }

    public void setRental(List<RentalRecord> rental) {
        this.rental = rental;
    }
}