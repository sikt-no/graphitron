package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;
import fake.code.generated.model.Film;
import fake.code.generated.model.Rating;
import java.lang.Integer;
import java.util.List;
import java.util.Map;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;


public class QueryDBQueries {
    public static List<Film> filmForQuery(DSLContext ctx, Integer releaseYear, SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                FILM.getId(),
                                select.optional("rating", FILM.RATING.convert(Rating.class, s -> s == null ? null : Map.of("G", Rating.G, "PG-13", Rating.PG_13, "R", Rating.R).getOrDefault(s, null), s -> s == null ? null : Map.of(Rating.G, "G", Rating.PG_13, "PG-13", Rating.R, "R").getOrDefault(s, null))),
                                select.optional("length", FILM.LENGTH)
                        ).mapping(Functions.nullOnAllNull(Film::new))
                )
                .from(FILM)
                .where(FILM.RELEASE_YEAR.eq(releaseYear))
                .orderBy(FILM.fields(FILM.getPrimaryKey().getFieldsArray()))
                .fetch(it -> it.into(Film.class));
    }
}
