package fake.code.generated.queries.query;
import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;
import fake.graphql.example.model.Rental;
import java.util.List;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;
public class QueryDBQueries {
    public static List<Rental> inventoryForQuery(DSLContext ctx, SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                RENTAL.getId()
                        ).mapping(Functions.nullOnAllNull(Rental::new))
                )
                .from(RENTAL)
                .orderBy(RENTAL.getIdFields())
                .fetch(it -> it.into(Rental.class));
    }
}
