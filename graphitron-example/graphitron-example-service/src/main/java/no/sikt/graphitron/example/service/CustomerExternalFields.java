package no.sikt.graphitron.example.service;

import no.sikt.graphitron.example.generated.jooq.tables.Customer;
import no.sikt.graphitron.example.generated.jooq.tables.Film;
import org.jooq.Field;
import org.jooq.impl.DSL;

public class CustomerExternalFields {
    public static Field<String> nameFormatted(Customer customer) {
        return DSL.concat(customer.FIRST_NAME, DSL.inline(" "), customer.LAST_NAME);
    }

    public static Field<String> filmIdAsExternalField(Film film) {
        return DSL.concat(film.FILM_ID, "!");
    }
}
