package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.package.model.Duration1;
import fake.graphql.example.package.model.Duration2;
import fake.graphql.example.package.model.Film;
import fake.graphql.example.package.model.Rating;
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
    public List<Film> filmsForQuery(DSLContext ctx, List<String> ids, SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                FILM.getId().as("id"),
                                select.optional("title", FILM.TITLE).as("title"),
                                select.optional("title2", FILM.TITLE).as("title2"),
                                select.optional("title3", FILM.TITLE).as("title3"),
                                select.optional("title4", FILM.TITLE).as("title4"),
                                select.optional("title5", FILM.TITLE).as("title5"),
                                select.optional("title6", FILM.TITLE).as("title6"),
                                select.optional("title7", FILM.TITLE).as("title7"),
                                select.optional("title8", FILM.TITLE).as("title8"),
                                select.optional("title9", FILM.TITLE).as("title9"),
                                select.optional("title10", FILM.TITLE).as("title10"),
                                select.optional("title11", FILM.TITLE).as("title11"),
                                select.optional("title12", FILM.TITLE).as("title12"),
                                select.optional("title13", FILM.TITLE).as("title13"),
                                select.optional("title14", FILM.TITLE).as("title14"),
                                select.optional("title15", FILM.TITLE).as("title15"),
                                select.optional("title16", FILM.TITLE).as("title16"),
                                select.optional("title17", FILM.TITLE).as("title17"),
                                select.optional("title18", FILM.TITLE).as("title18"),
                                select.optional("title19", FILM.TITLE).as("title19"),
                                select.optional("title20", FILM.TITLE).as("title20"),
                                select.optional("description", FILM.DESCRIPTION).as("description"),
                                select.optional("length", FILM.LENGTH).as("length"),
                                DSL.row(
                                        select.optional("duration1/duration", FILM.RENTAL_DURATION).as("duration")
                                ).mapping(Functions.nullOnAllNull(Duration1::new)).as("duration1"),
                                DSL.row(
                                        select.optional("duration2/duration", FILM.RENTAL_DURATION).as("duration"),
                                        select.optional("duration2/duration2", FILM.RENTAL_DURATION).as("duration2"),
                                        select.optional("duration2/duration3", FILM.RENTAL_DURATION).as("duration3"),
                                        select.optional("duration2/duration4", FILM.RENTAL_DURATION).as("duration4"),
                                        select.optional("duration2/duration5", FILM.RENTAL_DURATION).as("duration5"),
                                        select.optional("duration2/duration6", FILM.RENTAL_DURATION).as("duration6"),
                                        select.optional("duration2/duration7", FILM.RENTAL_DURATION).as("duration7"),
                                        select.optional("duration2/duration8", FILM.RENTAL_DURATION).as("duration8"),
                                        select.optional("duration2/duration9", FILM.RENTAL_DURATION).as("duration9"),
                                        select.optional("duration2/duration10", FILM.RENTAL_DURATION).as("duration10"),
                                        select.optional("duration2/duration11", FILM.RENTAL_DURATION).as("duration11"),
                                        select.optional("duration2/duration12", FILM.RENTAL_DURATION).as("duration12"),
                                        select.optional("duration2/duration13", FILM.RENTAL_DURATION).as("duration13"),
                                        select.optional("duration2/duration14", FILM.RENTAL_DURATION).as("duration14"),
                                        select.optional("duration2/duration15", FILM.RENTAL_DURATION).as("duration15"),
                                        select.optional("duration2/duration16", FILM.RENTAL_DURATION).as("duration16"),
                                        select.optional("duration2/duration17", FILM.RENTAL_DURATION).as("duration17"),
                                        select.optional("duration2/duration18", FILM.RENTAL_DURATION).as("duration18"),
                                        select.optional("duration2/duration19", FILM.RENTAL_DURATION).as("duration19"),
                                        select.optional("duration2/duration20", FILM.RENTAL_DURATION).as("duration20"),
                                        select.optional("duration2/duration21", FILM.RENTAL_DURATION).as("duration21"),
                                        select.optional("duration2/duration22", FILM.RENTAL_DURATION).as("duration22"),
                                        select.optional("duration2/duration23", FILM.RENTAL_DURATION).as("duration23"),
                                        select.optional("duration2/duration24", FILM.RENTAL_DURATION).as("duration24"),
                                        select.optional("duration2/duration25", FILM.RENTAL_DURATION).as("duration25")
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
                                ).as("duration2"),
                                select.optional("rate", FILM.RENTAL_RATE).as("rate"),
                                select.optional("languageName", FILM.filmLanguageIdFkey().NAME).as("languageName"),
                                select.optional("rating", FILM.RATING.convert(Rating.class, s -> s == null ? null : Map.of("G", Rating.G, "PG", Rating.PG, "R", Rating.R).getOrDefault(s, null), s -> s == null ? null : Map.of(Rating.G, "G", Rating.PG, "PG", Rating.R, "R").getOrDefault(s, null))).as("rating")
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
                        ).as("films")
                )
                .from(FILM)
                .where(ids.size() > 0 ? FILM.FILM_ID.in(ids) : DSL.noCondition())
                .orderBy(FILM.getIdFields())
                .fetch(0, Film.class);
    }
}