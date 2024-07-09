package fake.code.example.package.queries.query;

import static no.fellesstudentsystem.kjerneapi.Keys.*;
import static no.fellesstudentsystem.kjerneapi.Tables.*;

import java.lang.String;
import java.util.Map;
import java.util.Set;
import fake.graphql.example.model.Kull;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Record2;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class KullDBQueries {
    public static Map<String, Kull> loadKullByIdsAsReferertNode(DSLContext ctx, Set<String> ids, SelectionSet select) {
        return ctx
                .select(
                        KULL.getId(),
                        DSL.row(
                                KULL.getId()
                        ).mapping(Functions.nullOnAllNull(Kull::new))
                )
                .from(KULL)
                .where(KULL.hasIds(ids))
                .fetchMap(Record2::value1, Record2::value2);
    }
}
