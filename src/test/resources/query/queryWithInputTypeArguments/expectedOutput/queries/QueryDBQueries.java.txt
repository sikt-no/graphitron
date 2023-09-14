package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.package.model.Customer;
import fake.graphql.example.package.model.CustomerEmail;
import fake.graphql.example.package.model.CustomerInput;
import fake.graphql.example.package.model.Film;
import java.lang.Integer;
import java.lang.String;
import java.util.List;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public List<Customer> customersNoPageForQuery(DSLContext ctx, String active,
            List<String> storeIds, CustomerInput pin, SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                CUSTOMER.getId().as("id"),
                                select.optional("firstName", CUSTOMER.FIRST_NAME).as("firstName"),
                                select.optional("lastName", CUSTOMER.LAST_NAME).as("lastName"),
                                DSL.row(
                                        select.optional("email/privateEmail", CUSTOMER.EMAIL).as("privateEmail"),
                                        select.optional("email/workEmail", CUSTOMER.EMAIL).as("workEmail")
                                ).mapping(Functions.nullOnAllNull(CustomerEmail::new)).as("email")
                        ).mapping(Functions.nullOnAllNull(Customer::new)).as("customersNoPage")
                )
                .from(CUSTOMER)
                .where(CUSTOMER.ACTIVE.eq(active))
                .and(storeIds != null && storeIds.size() > 0 ? CUSTOMER.STORE_ID.in(storeIds) : DSL.noCondition())
                .and(pin != null && pin.getFirstName() != null ? CUSTOMER.FIRST_NAME.eq(pin.getFirstName()) : DSL.noCondition())
                .and(pin != null ? CUSTOMER.LAST_NAME.eq(pin.getLastName()) : DSL.noCondition())
                .and(pin != null && pin.getEmail() != null && pin.getEmail().getPrivateEmail() != null ? CUSTOMER.EMAIL.eq(pin.getEmail().getPrivateEmail()) : DSL.noCondition())
                .and(pin != null && pin.getEmail() != null ? CUSTOMER.EMAIL.eq(pin.getEmail().getWorkEmail()) : DSL.noCondition())
                .orderBy(CUSTOMER.getIdFields())
                .fetch(0, Customer.class);
    }

    public List<Customer> customersWithPageForQuery(DSLContext ctx, String active,
            List<Integer> storeIds, CustomerInput pin, Integer pageSize, String after,
            SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                CUSTOMER.getId().as("id"),
                                select.optional("firstName", CUSTOMER.FIRST_NAME).as("firstName"),
                                select.optional("lastName", CUSTOMER.LAST_NAME).as("lastName"),
                                DSL.row(
                                        select.optional("email/privateEmail", CUSTOMER.EMAIL).as("privateEmail"),
                                        select.optional("email/workEmail", CUSTOMER.EMAIL).as("workEmail")
                                ).mapping(Functions.nullOnAllNull(CustomerEmail::new)).as("email")
                        ).mapping(Functions.nullOnAllNull(Customer::new)).as("customersWithPage")
                )
                .from(CUSTOMER)
                .where(CUSTOMER.ACTIVE.eq(active))
                .and(storeIds != null && storeIds.size() > 0 ? CUSTOMER.STORE_ID.in(storeIds) : DSL.noCondition())
                .and(pin != null && pin.getFirstName() != null ? CUSTOMER.FIRST_NAME.eq(pin.getFirstName()) : DSL.noCondition())
                .and(pin != null ? CUSTOMER.LAST_NAME.eq(pin.getLastName()) : DSL.noCondition())
                .and(pin != null && pin.getEmail() != null && pin.getEmail().getPrivateEmail() != null ? CUSTOMER.EMAIL.eq(pin.getEmail().getPrivateEmail()) : DSL.noCondition())
                .and(pin != null && pin.getEmail() != null ? CUSTOMER.EMAIL.eq(pin.getEmail().getWorkEmail()) : DSL.noCondition())
                .orderBy(CUSTOMER.getIdFields())
                .seek(CUSTOMER.getIdValues(after))
                .limit(pageSize + 1)
                .fetch(0, Customer.class);
    }

    public List<Film> filmsForQuery(DSLContext ctx, String releaseYear, Integer pageSize,
            String after, SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                FILM.getId().as("id")
                        ).mapping(Functions.nullOnAllNull(Film::new)).as("films")
                )
                .from(FILM)
                .where(FILM.RELEASE_YEAR.eq(releaseYear))
                .orderBy(FILM.getIdFields())
                .seek(FILM.getIdValues(after))
                .limit(pageSize + 1)
                .fetch(0, Film.class);
    }

    public Integer countCustomersWithPageForQuery(DSLContext ctx, String active,
            List<Integer> storeIds, CustomerInput pin) {
        return ctx
                .select(DSL.count().as("totalCount"))
                .from(CUSTOMER)
                .where(CUSTOMER.ACTIVE.eq(active))
                .and(storeIds != null && storeIds.size() > 0 ? CUSTOMER.STORE_ID.in(storeIds) : DSL.noCondition())
                .and(pin != null && pin.getFirstName() != null ? CUSTOMER.FIRST_NAME.eq(pin.getFirstName()) : DSL.noCondition())
                .and(pin != null ? CUSTOMER.LAST_NAME.eq(pin.getLastName()) : DSL.noCondition())
                .and(pin != null && pin.getEmail() != null && pin.getEmail().getPrivateEmail() != null ? CUSTOMER.EMAIL.eq(pin.getEmail().getPrivateEmail()) : DSL.noCondition())
                .and(pin != null && pin.getEmail() != null ? CUSTOMER.EMAIL.eq(pin.getEmail().getWorkEmail()) : DSL.noCondition())
                .fetchOne(0, Integer.class);
    }

    public Integer countFilmsForQuery(DSLContext ctx, String releaseYear) {
        return ctx
                .select(DSL.count().as("totalCount"))
                .from(FILM)
                .where(FILM.RELEASE_YEAR.eq(releaseYear))
                .fetchOne(0, Integer.class);
    }
}