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
    public Map<String, List<Language>> languagesForFilm(DSLContext ctx, Set<String> filmIder,
            List<String> s, SelectionSet select) {
        var film_languages = LANGUAGE.as("FILM_317255161");
        return ctx
                .select(
                        FILM.getId(),
                        DSL.row(
                                film_languages.getId().as("id")
                        ).mapping(Functions.nullOnAllNull(Language::new)).as("languages")
                )
                .from(FILM)
                .join(film_languages)
                .onKey(FILM__FILM_LANGUAGE_ID_FKEY,)
                .where(FILM.hasIds(filmIder))
                .and(s != null && s.size() > 0 ? film_languages.NAME.in(s) : DSL.noCondition())
                .fetchGroups(Record2::value1, Record2::value2);
    }

    public Map<String, List<Language>> languagesInputForFilm(DSLContext ctx, Set<String> filmIder,
            In s, SelectionSet select) {
        var film_languagesinput = LANGUAGE.as("FILM_317255161");
        return ctx
                .select(
                        FILM.getId(),
                        DSL.row(
                                film_languagesinput.getId().as("id")
                        ).mapping(Functions.nullOnAllNull(Language::new)).as("languagesInput")
                )
                .from(FILM)
                .join(film_languagesinput)
                .onKey(FILM__FILM_LANGUAGE_ID_FKEY,)
                .where(FILM.hasIds(filmIder))
                .and(s != null && s.getName() != null && s.getName().size() > 0 ? film_languagesinput.NAME.in(s.getName()) : DSL.noCondition())
                .fetchGroups(Record2::value1, Record2::value2);
    }
}