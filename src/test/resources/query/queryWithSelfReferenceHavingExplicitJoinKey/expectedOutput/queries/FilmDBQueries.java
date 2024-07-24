package fake.code.generated.queries.query;
import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;
import fake.graphql.example.model.Film;
import java.lang.String;
import java.util.Map;
import java.util.Set;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.Record2;
import org.jooq.impl.DSL;
public class FilmDBQueries {
    public static Map<String, Film> sequelForFilm(DSLContext ctx, Set<String> filmIds,
            SelectionSet select) {
        var film_film_left = FILM.film().as("film_721703768");
        return ctx
                .select(
                        FILM.getId(),
                        DSL.row(film_film_left.getId()).mapping(Functions.nullOnAllNull(Film::new))
                )
                .from(FILM)
                .leftJoin(film_film_left)
                .where(FILM.hasIds(filmIds))
                .orderBy(film_film_left.getIdFields())
                .fetchMap(Record2::value1, Record2::value2);
    }
}
