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
    public static List<Film> filmTwoArgumentsForQuery(DSLContext ctx, String releaseYear,
            List<Integer> languageID, Integer pageSize, String after, SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                FILM.getId(),
                                select.optional("title", FILM.TITLE),
                                select.optional("description", FILM.DESCRIPTION)
                        ).mapping(Functions.nullOnAllNull(Film::new))
                )
                .from(FILM)
                .where(FILM.RELEASE_YEAR.eq(releaseYear))
                .and(languageID != null && languageID.size() > 0 ? FILM.LANGUAGE_ID.in(languageID) : DSL.noCondition())
                .orderBy(FILM.getIdFields())
                .seek(FILM.getIdValues(after))
                .limit(pageSize + 1)
                .fetch(it -> it.into(Film.class));
    }
    public static List<Film> filmFiveArgumentsForQuery(DSLContext ctx, String releaseYear,
            List<Integer> languageID, String description, String title, Integer length,
            Integer pageSize, String after, SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                FILM.getId(),
                                select.optional("title", FILM.TITLE),
                                select.optional("description", FILM.DESCRIPTION)
                        ).mapping(Functions.nullOnAllNull(Film::new))
                )
                .from(FILM)
                .where(FILM.RELEASE_YEAR.eq(releaseYear))
                .and(languageID != null && languageID.size() > 0 ? FILM.LANGUAGE_ID.in(languageID) : DSL.noCondition())
                .and(description != null ? FILM.DESCRIPTION.eq(description) : DSL.noCondition())
                .and(FILM.TITLE.eq(title))
                .and(length != null ? FILM.LENGTH.eq(length) : DSL.noCondition())
                .orderBy(FILM.getIdFields())
                .seek(FILM.getIdValues(after))
                .limit(pageSize + 1)
                .fetch(it -> it.into(Film.class));
    }
    public static Integer countFilmTwoArgumentsForQuery(DSLContext ctx, String releaseYear,
            List<Integer> languageID) {
        return ctx
                .select(DSL.count())
                .from(FILM)
                .where(FILM.RELEASE_YEAR.eq(releaseYear))
                .and(languageID != null && languageID.size() > 0 ? FILM.LANGUAGE_ID.in(languageID) : DSL.noCondition())
                .fetchOne(0, Integer.class);
    }
    public static Integer countFilmFiveArgumentsForQuery(DSLContext ctx, String releaseYear,
            List<Integer> languageID, String description, String title, Integer length) {
        return ctx
                .select(DSL.count())
                .from(FILM)
                .where(FILM.RELEASE_YEAR.eq(releaseYear))
                .and(languageID != null && languageID.size() > 0 ? FILM.LANGUAGE_ID.in(languageID) : DSL.noCondition())
                .and(description != null ? FILM.DESCRIPTION.eq(description) : DSL.noCondition())
                .and(FILM.TITLE.eq(title))
                .and(length != null ? FILM.LENGTH.eq(length) : DSL.noCondition())
                .fetchOne(0, Integer.class);
    }
}
