package fake.code.generated.queries.query;
import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;
import fake.graphql.example.model.In;
import fake.graphql.example.model.Language;
import java.lang.String;
import java.util.List;
import java.util.Map;
import java.util.Set;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.Record2;
import org.jooq.impl.DSL;
public class FilmDBQueries {
    public static Map<String, List<Language>> languagesForFilm(DSLContext ctx, Set<String> filmIds,
            List<String> s, SelectionSet select) {
        var film_filmlanguageidfkey = FILM.filmLanguageIdFkey().as("filmLanguageIdFkey_2832997198");
        return ctx
                .select(
                        FILM.getId(),
                        DSL.row(film_filmlanguageidfkey.getId()).mapping(Functions.nullOnAllNull(Language::new))
                )
                .from(FILM)
                .join(film_filmlanguageidfkey)
                .where(FILM.hasIds(filmIds))
                .and(s != null && s.size() > 0 ? film_filmlanguageidfkey.NAME.in(s) : DSL.noCondition())
                .orderBy(film_filmlanguageidfkey.getIdFields())
                .fetchGroups(Record2::value1, Record2::value2);
    }
    public static Map<String, List<Language>> languagesInputForFilm(DSLContext ctx,
            Set<String> filmIds, In s, SelectionSet select) {
        var film_filmlanguageidfkey = FILM.filmLanguageIdFkey().as("filmLanguageIdFkey_2832997198");
        return ctx
                .select(
                        FILM.getId(),
                        DSL.row(film_filmlanguageidfkey.getId()).mapping(Functions.nullOnAllNull(Language::new))
                )
                .from(FILM)
                .join(film_filmlanguageidfkey)
                .where(FILM.hasIds(filmIds))
                .and(s != null && s.getName() != null && s.getName().size() > 0 ? film_filmlanguageidfkey.NAME.in(s.getName()) : DSL.noCondition())
                .orderBy(film_filmlanguageidfkey.getIdFields())
                .fetchGroups(Record2::value1, Record2::value2);
    }
}
