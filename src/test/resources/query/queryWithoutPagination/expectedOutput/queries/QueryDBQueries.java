package fake.code.generated.queries.query;
import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;
import fake.graphql.example.model.Film;
import java.lang.String;
import java.util.List;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;
public class QueryDBQueries {
    public List<Film> filmsForQuery(DSLContext ctx, String releaseYear, SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                FILM.getId().as("id"),
                                select.optional("title", FILM.TITLE).as("title")
                        ).mapping(Functions.nullOnAllNull(Film::new)).as("films")
                )
                .from(FILM)
                .where(FILM.RELEASE_YEAR.eq(releaseYear))
                .orderBy(FILM.getIdFields())
                .fetch(it -> it.into(Film.class));
    }
}
