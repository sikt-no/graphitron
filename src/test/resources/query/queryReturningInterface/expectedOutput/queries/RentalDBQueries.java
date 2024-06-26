package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.model.Rental;
import java.lang.String;
import java.util.Map;
import java.util.Set;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Record2;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class RentalDBQueries {
    public static Map<String, Rental> loadRentalByIdsAsNode(DSLContext ctx, Set<String> ids,
            SelectionSet select) {
        return ctx
                .select(
                        RENTAL.getId(),
                        DSL.row(
                                RENTAL.getId().as("id")
                        ).mapping(Functions.nullOnAllNull(Rental::new)).as("id")
                )
                .from(RENTAL)
                .where(RENTAL.hasIds(ids))
                .fetchMap(Record2::value1, Record2::value2);
    }
}