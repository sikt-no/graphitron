package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.model.Film;
import fake.graphql.example.model.Language;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public static Film filmForQuery(DSLContext ctx, SelectionSet select) {
        var film_filmlanguageidfkey_left = FILM.filmLanguageIdFkey().as("filmLanguageIdFkey_2782157680");
        var film_filmoriginallanguageidfkey_left = FILM.filmOriginalLanguageIdFkey().as("filmOriginalLanguageIdFkey_1523086221");
        return ctx
                .select(
                        DSL.row(
                                FILM.getId(),
                                DSL.row(film_filmlanguageidfkey_left.getId()).mapping(Functions.nullOnAllNull(Language::new)),
                                DSL.row(film_filmoriginallanguageidfkey_left.getId()).mapping(Functions.nullOnAllNull(Language::new))
                        ).mapping(Functions.nullOnAllNull(Film::new))
                )
                .from(FILM)
                .leftJoin(film_filmlanguageidfkey_left)
                .leftJoin(film_filmoriginallanguageidfkey_left)
                .fetchOne(it -> it.into(Film.class));
    }
}
