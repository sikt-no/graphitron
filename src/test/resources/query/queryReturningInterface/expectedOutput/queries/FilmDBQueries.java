package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.model.Film;
import java.lang.String;
import java.util.Map;
import java.util.Set;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Record2;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class FilmDBQueries {
    public Map<String, Film> loadFilmByIdsAsNode(DSLContext ctx, Set<String> ids,
            SelectionSet select) {
        return ctx
                .select(
                        FILM.getId(),
                        DSL.row(
                                FILM.getId().as("id"),
                                select.optional("title", FILM.TITLE).as("title")
                        ).mapping(Functions.nullOnAllNull(Film::new)).as("id")
                )
                .from(FILM)
                .where(FILM.hasIds(ids))
                .fetchMap(Record2::value1, Record2::value2);
    }

    public Map<String, Film> loadFilmByTitlesAsTitled(DSLContext ctx, Set<String> titles,
            SelectionSet select) {
        return ctx
                .select(
                        FILM.getId(),
                        DSL.row(
                                FILM.getId().as("id"),
                                select.optional("title", FILM.TITLE).as("title")
                        ).mapping(Functions.nullOnAllNull(Film::new)).as("id")
                )
                .from(FILM)
                .where(FILM.hasTitles(titles))
                .fetchMap(Record2::value1, Record2::value2);
    }
}