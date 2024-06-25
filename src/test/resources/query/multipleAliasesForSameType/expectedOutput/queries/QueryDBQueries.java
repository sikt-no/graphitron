package fake.code.generated.queries.query;
import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;
import fake.graphql.example.model.Film;
import fake.graphql.example.model.Language;
import java.lang.String;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;
public class QueryDBQueries {
    public Film filmForQuery(DSLContext ctx, String id, SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                FILM.getId().as("id"),
                                DSL.row(
                                        FILM.filmLanguageIdFkey().getId().as("id")
                                ).mapping(Functions.nullOnAllNull(Language::new)).as("filmLanguage"),
                                DSL.row(
                                        FILM.filmOriginalLanguageIdFkey().getId().as("id")
                                ).mapping(Functions.nullOnAllNull(Language::new)).as("originalLanguage")
                        ).mapping(Functions.nullOnAllNull(Film::new)).as("film")
                )
                .from(FILM)
                .where(FILM.ID.eq(id))
                .fetchOne(it -> it.into(Film.class));
    }
}
