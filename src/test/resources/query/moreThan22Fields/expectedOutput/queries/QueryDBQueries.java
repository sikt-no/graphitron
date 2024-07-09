package fake.code.generated.queries.query;
import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;
import fake.graphql.example.model.Duration1;
import fake.graphql.example.model.Duration2;
import fake.graphql.example.model.Film;
import fake.graphql.example.model.Rating;
import java.lang.String;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.impl.DSL;
public class QueryDBQueries {
    public static List<Film> filmsForQuery(DSLContext ctx, List<String> ids, SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                FILM.getId(),
                                select.optional("title", FILM.TITLE),
                                select.optional("title2", FILM.TITLE),
                                select.optional("title3", FILM.TITLE),
                                select.optional("title4", FILM.TITLE),
                                select.optional("title5", FILM.TITLE),
                                select.optional("title6", FILM.TITLE),
                                select.optional("title7", FILM.TITLE),
                                select.optional("title8", FILM.TITLE),
                                select.optional("title9", FILM.TITLE),
                                select.optional("title10", FILM.TITLE),
                                select.optional("title11", FILM.TITLE),
                                select.optional("title12", FILM.TITLE),
                                select.optional("title13", FILM.TITLE),
                                select.optional("title14", FILM.TITLE),
                                select.optional("title15", FILM.TITLE),
                                select.optional("title16", FILM.TITLE),
                                select.optional("title17", FILM.TITLE),
                                select.optional("title18", FILM.TITLE),
                                select.optional("title19", FILM.TITLE),
                                select.optional("title20", FILM.TITLE),
                                select.optional("description", FILM.DESCRIPTION),
                                select.optional("length", FILM.LENGTH),
                                DSL.row(
                                        select.optional("duration1/duration", FILM.RENTAL_DURATION)
                                ).mapping(Functions.nullOnAllNull(Duration1::new)),
                                DSL.row(
                                        select.optional("duration2/duration", FILM.RENTAL_DURATION),
                                        select.optional("duration2/duration2", FILM.RENTAL_DURATION),
                                        select.optional("duration2/duration3", FILM.RENTAL_DURATION),
                                        select.optional("duration2/duration4", FILM.RENTAL_DURATION),
                                        select.optional("duration2/duration5", FILM.RENTAL_DURATION),
                                        select.optional("duration2/duration6", FILM.RENTAL_DURATION),
                                        select.optional("duration2/duration7", FILM.RENTAL_DURATION),
                                        select.optional("duration2/duration8", FILM.RENTAL_DURATION),
                                        select.optional("duration2/duration9", FILM.RENTAL_DURATION),
                                        select.optional("duration2/duration10", FILM.RENTAL_DURATION),
                                        select.optional("duration2/duration11", FILM.RENTAL_DURATION),
                                        select.optional("duration2/duration12", FILM.RENTAL_DURATION),
                                        select.optional("duration2/duration13", FILM.RENTAL_DURATION),
                                        select.optional("duration2/duration14", FILM.RENTAL_DURATION),
                                        select.optional("duration2/duration15", FILM.RENTAL_DURATION),
                                        select.optional("duration2/duration16", FILM.RENTAL_DURATION),
                                        select.optional("duration2/duration17", FILM.RENTAL_DURATION),
                                        select.optional("duration2/duration18", FILM.RENTAL_DURATION),
                                        select.optional("duration2/duration19", FILM.RENTAL_DURATION),
                                        select.optional("duration2/duration20", FILM.RENTAL_DURATION),
                                        select.optional("duration2/duration21", FILM.RENTAL_DURATION),
                                        select.optional("duration2/duration22", FILM.RENTAL_DURATION),
                                        select.optional("duration2/duration23", FILM.RENTAL_DURATION),
                                        select.optional("duration2/duration24", FILM.RENTAL_DURATION),
                                        select.optional("duration2/duration25", FILM.RENTAL_DURATION)
                                ).mapping(Duration2.class, r ->
                                        Arrays.stream(r).allMatch(Objects::isNull) ? null : new Duration2(
                                                FILM.RENTAL_DURATION.getDataType().convert(r[0]),
                                                FILM.RENTAL_DURATION.getDataType().convert(r[1]),
                                                FILM.RENTAL_DURATION.getDataType().convert(r[2]),
                                                FILM.RENTAL_DURATION.getDataType().convert(r[3]),
                                                FILM.RENTAL_DURATION.getDataType().convert(r[4]),
                                                FILM.RENTAL_DURATION.getDataType().convert(r[5]),
                                                FILM.RENTAL_DURATION.getDataType().convert(r[6]),
                                                FILM.RENTAL_DURATION.getDataType().convert(r[7]),
                                                FILM.RENTAL_DURATION.getDataType().convert(r[8]),
                                                FILM.RENTAL_DURATION.getDataType().convert(r[9]),
                                                FILM.RENTAL_DURATION.getDataType().convert(r[10]),
                                                FILM.RENTAL_DURATION.getDataType().convert(r[11]),
                                                FILM.RENTAL_DURATION.getDataType().convert(r[12]),
                                                FILM.RENTAL_DURATION.getDataType().convert(r[13]),
                                                FILM.RENTAL_DURATION.getDataType().convert(r[14]),
                                                FILM.RENTAL_DURATION.getDataType().convert(r[15]),
                                                FILM.RENTAL_DURATION.getDataType().convert(r[16]),
                                                FILM.RENTAL_DURATION.getDataType().convert(r[17]),
                                                FILM.RENTAL_DURATION.getDataType().convert(r[18]),
                                                FILM.RENTAL_DURATION.getDataType().convert(r[19]),
                                                FILM.RENTAL_DURATION.getDataType().convert(r[20]),
                                                FILM.RENTAL_DURATION.getDataType().convert(r[21]),
                                                FILM.RENTAL_DURATION.getDataType().convert(r[22]),
                                                FILM.RENTAL_DURATION.getDataType().convert(r[23]),
                                                FILM.RENTAL_DURATION.getDataType().convert(r[24])
                                        )
                                ),
                                select.optional("rate", FILM.RENTAL_RATE),
                                select.optional("languageName", FILM.filmLanguageIdFkey().NAME),
                                select.optional("rating", FILM.RATING.convert(Rating.class, s -> s == null ? null : Map.of("G", Rating.G, "PG", Rating.PG, "R", Rating.R).getOrDefault(s, null), s -> s == null ? null : Map.of(Rating.G, "G", Rating.PG, "PG", Rating.R, "R").getOrDefault(s, null)))
                        ).mapping(Film.class, r ->
                                Arrays.stream(r).allMatch(Objects::isNull) ? null : new Film(
                                        (String) r[0],
                                        FILM.TITLE.getDataType().convert(r[1]),
                                        FILM.TITLE.getDataType().convert(r[2]),
                                        FILM.TITLE.getDataType().convert(r[3]),
                                        FILM.TITLE.getDataType().convert(r[4]),
                                        FILM.TITLE.getDataType().convert(r[5]),
                                        FILM.TITLE.getDataType().convert(r[6]),
                                        FILM.TITLE.getDataType().convert(r[7]),
                                        FILM.TITLE.getDataType().convert(r[8]),
                                        FILM.TITLE.getDataType().convert(r[9]),
                                        FILM.TITLE.getDataType().convert(r[10]),
                                        FILM.TITLE.getDataType().convert(r[11]),
                                        FILM.TITLE.getDataType().convert(r[12]),
                                        FILM.TITLE.getDataType().convert(r[13]),
                                        FILM.TITLE.getDataType().convert(r[14]),
                                        FILM.TITLE.getDataType().convert(r[15]),
                                        FILM.TITLE.getDataType().convert(r[16]),
                                        FILM.TITLE.getDataType().convert(r[17]),
                                        FILM.TITLE.getDataType().convert(r[18]),
                                        FILM.TITLE.getDataType().convert(r[19]),
                                        FILM.TITLE.getDataType().convert(r[20]),
                                        FILM.DESCRIPTION.getDataType().convert(r[21]),
                                        FILM.LENGTH.getDataType().convert(r[22]),
                                        (Duration1) r[23],
                                        (Duration2) r[24],
                                        FILM.RENTAL_RATE.getDataType().convert(r[25]),
                                        FILM.filmLanguageIdFkey().NAME.getDataType().convert(r[26]),
                                        (Rating) r[27]
                                )
                        )
                )
                .from(FILM)
                .where(ids.size() > 0 ? FILM.FILM_ID.in(ids) : DSL.noCondition())
                .orderBy(FILM.getIdFields())
                .fetch(it -> it.into(Film.class));
    }
}
