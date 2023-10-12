package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.package.model.In;
import fake.graphql.example.package.model.Language;
import java.lang.String;
import java.util.List;
import java.util.Map;
import java.util.Set;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Record2;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class FilmDBQueries {
    public Map<String, List<Language>> languagesForFilm(DSLContext ctx, Set<String> filmIds,
                                                        List<String> s, SelectionSet select) {
        return ctx
                .select(
                        FILM.getId(),
                        DSL.row(
                                FILM.filmLanguageIdFkey().getId().as("id")
                        ).mapping(Functions.nullOnAllNull(Language::new)).as("languages")
                )
                .from(FILM)
                .where(FILM.hasIds(filmIds))
                .and(s != null && s.size() > 0 ? FILM.filmLanguageIdFkey().NAME.in(s) : DSL.noCondition())
                .fetchGroups(Record2::value1, Record2::value2);
    }

    public Map<String, List<Language>> languagesInputForFilm(DSLContext ctx, Set<String> filmIds,
                                                             In s, SelectionSet select) {
        return ctx
                .select(
                        FILM.getId(),
                        DSL.row(
                                FILM.filmLanguageIdFkey().getId().as("id")
                        ).mapping(Functions.nullOnAllNull(Language::new)).as("languagesInput")
                )
                .from(FILM)
                .where(FILM.hasIds(filmIds))
                .and(s != null && s.getName() != null && s.getName().size() > 0 ? FILM.filmLanguageIdFkey().NAME.in(s.getName()) : DSL.noCondition())
                .fetchGroups(Record2::value1, Record2::value2);
    }
}