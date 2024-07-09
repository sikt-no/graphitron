package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.model.Customer;
import fake.graphql.example.model.Country;
import java.lang.String;
import java.util.List;
import java.util.Map;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Record2;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public static Map<String, Customer> customersForQuery(DSLContext ctx, List<String> storeIds,
                                                   SelectionSet select) {
        return ctx
                .select(
                        CUSTOMER.STORE_ID,
                        DSL.row(
                                CUSTOMER.getId()
                        ).mapping(Functions.nullOnAllNull(Customer::new))
                )
                .from(CUSTOMER)
                .where(storeIds.size() > 0 ? CUSTOMER.STORE_ID.in(storeIds) : DSL.noCondition())
                .fetchMap(Record2::value1, Record2::value2);
    }

    public static Map<String, Country> countriesForQuery(DSLContext ctx, List<String> countryNames,
                                                   SelectionSet select) {
        return ctx
                .select(
                        COUNTRY.COUNTRY,
                        DSL.row(
                                COUNTRY.getId()
                        ).mapping(Functions.nullOnAllNull(Country::new))
                )
                .from(COUNTRY)
                .where(countryNames.size() > 0 ? COUNTRY.COUNTRY.in(countryNames) : DSL.noCondition())
                .fetchMap(Record2::value1, Record2::value2);
    }
}
