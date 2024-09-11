package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.code.generated.model.Film;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public static Film filmForQuery(DSLContext ctx, SelectionSet select) {
        return ctx
                .select(DSL.row(FILM.getId()).mapping(Functions.nullOnAllNull(Film::new)))
                .from(FILM)
                .fetchOne(it -> it.into(Film.class));
    }
}
