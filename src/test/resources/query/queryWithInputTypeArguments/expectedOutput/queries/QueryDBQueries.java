package fake.code.generated.queries.query;
import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;
import fake.graphql.example.model.Customer;
import fake.graphql.example.model.CustomerEmail;
import fake.graphql.example.model.CustomerInput;
import fake.graphql.example.model.Film;
import java.lang.Integer;
import java.lang.String;
import java.util.List;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;
public class QueryDBQueries {
    public static Customer customersNoPageForQuery(DSLContext ctx, String active, String storeId,
            CustomerInput pin, SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                CUSTOMER.getId(),
                                select.optional("firstName", CUSTOMER.FIRST_NAME),
                                select.optional("lastName", CUSTOMER.LAST_NAME),
                                DSL.row(
                                        select.optional("email/privateEmail", CUSTOMER.EMAIL),
                                        select.optional("email/workEmail", CUSTOMER.EMAIL)
                                ).mapping(Functions.nullOnAllNull(CustomerEmail::new))
                        ).mapping(Functions.nullOnAllNull(Customer::new))
                )
                .from(CUSTOMER)
                .where(CUSTOMER.ACTIVE.eq(active))
                .and(CUSTOMER.STORE_ID.eq(storeId))
                .and(pin != null && pin.getFirstName() != null ? CUSTOMER.FIRST_NAME.eq(pin.getFirstName()) : DSL.noCondition())
                .and(pin != null ? CUSTOMER.LAST_NAME.eq(pin.getLastName()) : DSL.noCondition())
                .and(pin != null && pin.getEmail() != null && pin.getEmail().getPrivateEmail() != null ? CUSTOMER.EMAIL.eq(pin.getEmail().getPrivateEmail()) : DSL.noCondition())
                .and(pin != null && pin.getEmail() != null ? CUSTOMER.EMAIL.eq(pin.getEmail().getWorkEmail()) : DSL.noCondition())
                .fetchOne(it -> it.into(Customer.class));
    }
    public static List<Customer> customersWithPageForQuery(DSLContext ctx, String active,
            List<Integer> storeIds, CustomerInput pin, Integer pageSize, String after,
            SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                CUSTOMER.getId(),
                                select.optional("firstName", CUSTOMER.FIRST_NAME),
                                select.optional("lastName", CUSTOMER.LAST_NAME),
                                DSL.row(
                                        select.optional("email/privateEmail", CUSTOMER.EMAIL),
                                        select.optional("email/workEmail", CUSTOMER.EMAIL)
                                ).mapping(Functions.nullOnAllNull(CustomerEmail::new))
                        ).mapping(Functions.nullOnAllNull(Customer::new))
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
                .fetch(it -> it.into(Customer.class));
    }
    public static List<Film> filmsForQuery(DSLContext ctx, String releaseYear, Integer pageSize,
            String after, SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                FILM.getId()
                        ).mapping(Functions.nullOnAllNull(Film::new))
                )
                .from(FILM)
                .where(FILM.RELEASE_YEAR.eq(releaseYear))
                .orderBy(FILM.getIdFields())
                .seek(FILM.getIdValues(after))
                .limit(pageSize + 1)
                .fetch(it -> it.into(Film.class));
    }
    public static Integer countCustomersWithPageForQuery(DSLContext ctx, String active,
            List<Integer> storeIds, CustomerInput pin) {
        return ctx
                .select(DSL.count())
                .from(CUSTOMER)
                .where(CUSTOMER.ACTIVE.eq(active))
                .and(storeIds != null && storeIds.size() > 0 ? CUSTOMER.STORE_ID.in(storeIds) : DSL.noCondition())
                .and(pin != null && pin.getFirstName() != null ? CUSTOMER.FIRST_NAME.eq(pin.getFirstName()) : DSL.noCondition())
                .and(pin != null ? CUSTOMER.LAST_NAME.eq(pin.getLastName()) : DSL.noCondition())
                .and(pin != null && pin.getEmail() != null && pin.getEmail().getPrivateEmail() != null ? CUSTOMER.EMAIL.eq(pin.getEmail().getPrivateEmail()) : DSL.noCondition())
                .and(pin != null && pin.getEmail() != null ? CUSTOMER.EMAIL.eq(pin.getEmail().getWorkEmail()) : DSL.noCondition())
                .fetchOne(0, Integer.class);
    }
    public static Integer countFilmsForQuery(DSLContext ctx, String releaseYear) {
        return ctx
                .select(DSL.count())
                .from(FILM)
                .where(FILM.RELEASE_YEAR.eq(releaseYear))
                .fetchOne(0, Integer.class);
    }
}
