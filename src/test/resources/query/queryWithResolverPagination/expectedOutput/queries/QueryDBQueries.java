package fake.code.generated.queries.query;
import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;
import fake.graphql.example.model.Film;
import java.lang.Integer;
import java.lang.String;
import java.util.List;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;
public class QueryDBQueries {
    public static List<Film> filmForQuery(DSLContext ctx, String releaseYear, Integer pageSize,
            String after, SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                FILM.getId().as("id"),
                                select.optional("title", FILM.TITLE).as("title")
                        ).mapping(Functions.nullOnAllNull(Film::new)).as("film")
                )
                .from(FILM)
                .where(FILM.RELEASE_YEAR.eq(releaseYear))
                .orderBy(FILM.getIdFields())
                .seek(FILM.getIdValues(after))
                .limit(pageSize + 1)
                .fetch(it -> it.into(Film.class));
    }
    public static List<Film> film2ForQuery(DSLContext ctx, String releaseYear, SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                FILM.getId().as("id"),
                                select.optional("title", FILM.TITLE).as("title")
                        ).mapping(Functions.nullOnAllNull(Film::new)).as("film2")
                )
                .from(FILM)
                .where(FILM.RELEASE_YEAR.eq(releaseYear))
                .orderBy(FILM.getIdFields())
                .fetch(it -> it.into(Film.class));
    }
    public static Integer countFilmForQuery(DSLContext ctx, String releaseYear) {
        return ctx
                .select(DSL.count().as("totalCount"))
                .from(FILM)
                .where(FILM.RELEASE_YEAR.eq(releaseYear))
                .fetchOne(0, Integer.class);
    }
}
