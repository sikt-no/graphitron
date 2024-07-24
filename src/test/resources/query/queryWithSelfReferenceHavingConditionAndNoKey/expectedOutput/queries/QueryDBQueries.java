package fake.code.generated.queries.query;
import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;
import fake.graphql.example.model.Store;
import java.util.List;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;
public class QueryDBQueries {
    public static List<Store> storesForQuery(DSLContext ctx, SelectionSet select) {
        return ctx
                .select(DSL.row(STORE.getId()).mapping(Functions.nullOnAllNull(Store::new)))
                .from(STORE)
                .orderBy(STORE.getIdFields())
                .fetch(it -> it.into(Store.class));
    }
}
