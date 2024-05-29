package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.model.Film;
import fake.graphql.example.model.FilmTitle;
import java.lang.String;
import java.util.Map;
import java.util.Set;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.Record2;
import org.jooq.impl.DSL;

public class FilmDBQueries {
    public Map<String, Film> sequelForFilm(DSLContext ctx, Set<String> filmIds,
                                           SelectionSet select) {
        return ctx
                .select(
                        FILM.getId(),
                        DSL.row(
                                FILM.film().getId().as("id"),
                                DSL.row(
                                        select.optional("title/title", FILM.film().TITLE).as("title")
                                ).mapping(Functions.nullOnAllNull(FilmTitle::new)).as("title")
                        ).mapping(Functions.nullOnAllNull(Film::new)).as("sequel")
                )
                .from(FILM)
                .where(FILM.hasIds(filmIds))
                .orderBy(FILM.film().getIdFields())
                .fetchMap(Record2::value1, Record2::value2);
    }
}