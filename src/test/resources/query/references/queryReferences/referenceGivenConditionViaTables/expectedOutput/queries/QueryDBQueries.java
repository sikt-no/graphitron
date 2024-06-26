package fake.code.generated.queries.query;
import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;
import fake.graphql.example.model.Rental;
import java.lang.String;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;
public class QueryDBQueries {
    public static Rental rentalForQuery(DSLContext ctx, String id, SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                RENTAL.getId().as("id")
                        ).mapping(Functions.nullOnAllNull(Rental::new)).as("rental")
                )
                .from(RENTAL)
                .where(RENTAL.ID.eq(id))
                .fetchOne(it -> it.into(Rental.class));
    }
}
