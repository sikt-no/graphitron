package no.sikt.graphitron.example.service;

import no.sikt.graphitron.example.generated.jooq.tables.Customer;
import org.jooq.Field;
import org.jooq.impl.DSL;

public class CustomerExternalFields {
    public static Field<String> nameFormatted(Customer customer) {
        return DSL.concat(customer.FIRST_NAME, DSL.inline(" "), customer.LAST_NAME);
    }
}
