package no.sikt.graphitron.example.service;

import no.sikt.graphitron.example.generated.jooq.tables.records.CustomerRecord;
import org.jooq.DSLContext;

import static no.sikt.graphitron.example.generated.jooq.Tables.CUSTOMER;

public class CustomerService {
    public CustomerService(DSLContext context) {
    }

    public CustomerRecord customer() {
        return null;
    }
}
