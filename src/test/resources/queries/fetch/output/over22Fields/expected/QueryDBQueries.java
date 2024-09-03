package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.public_.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;

import fake.graphql.example.model.Film;
import java.util.Arrays;
import java.util.Objects;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public static Film queryForQuery(DSLContext ctx, SelectionSet select) {
        return ctx
                .select(
                        DSL.row(
                                select.optional("title0", FILM.TITLE),
                                select.optional("title1", FILM.TITLE),
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
                                select.optional("title21", FILM.TITLE),
                                select.optional("title22", FILM.TITLE)
                        ).mapping(Film.class, r ->
                                Arrays.stream(r).allMatch(Objects::isNull) ? null : new Film(
                                        FILM.TITLE.getDataType().convert(r[0]),
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
                                        FILM.TITLE.getDataType().convert(r[21]),
                                        FILM.TITLE.getDataType().convert(r[22])
                                )
                        )
                )
                .from(FILM)
                .fetchOne(it -> it.into(Film.class));
    }
}
