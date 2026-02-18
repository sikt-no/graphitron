package no.sikt.graphitron.codereferences.records;

import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.CustomerRecord;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.RentalRecord;

import java.util.List;

public class ListedAndSingularInputJavaRecord {
    private List<RentalRecord> rental;
    private CustomerRecord customer;

    public List<RentalRecord> getRental() {
        return rental;
    }

    public void setRental(List<RentalRecord> rental) {
        this.rental = rental;
    }

    public CustomerRecord getCustomer() {
        return customer;
    }

    public void setCustomer(CustomerRecord customer) {
        this.customer = customer;
    }
}