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
        var _film = FILM.as("film_3747728953");
        return ctx
                .select(
                        DSL.row(
                                select.optional("title0", _film.TITLE),
                                select.optional("title1", _film.TITLE),
                                select.optional("title2", _film.TITLE),
                                select.optional("title3", _film.TITLE),
                                select.optional("title4", _film.TITLE),
                                select.optional("title5", _film.TITLE),
                                select.optional("title6", _film.TITLE),
                                select.optional("title7", _film.TITLE),
                                select.optional("title8", _film.TITLE),
                                select.optional("title9", _film.TITLE),
                                select.optional("title10", _film.TITLE),
                                select.optional("title11", _film.TITLE),
                                select.optional("title12", _film.TITLE),
                                select.optional("title13", _film.TITLE),
                                select.optional("title14", _film.TITLE),
                                select.optional("title15", _film.TITLE),
                                select.optional("title16", _film.TITLE),
                                select.optional("title17", _film.TITLE),
                                select.optional("title18", _film.TITLE),
                                select.optional("title19", _film.TITLE),
                                select.optional("title20", _film.TITLE),
                                select.optional("title21", _film.TITLE),
                                select.optional("title22", _film.TITLE)
                        ).mapping(Film.class, r ->
                                Arrays.stream(r).allMatch(Objects::isNull) ? null : new Film(
                                        _film.TITLE.getDataType().convert(r[0]),
                                        _film.TITLE.getDataType().convert(r[1]),
                                        _film.TITLE.getDataType().convert(r[2]),
                                        _film.TITLE.getDataType().convert(r[3]),
                                        _film.TITLE.getDataType().convert(r[4]),
                                        _film.TITLE.getDataType().convert(r[5]),
                                        _film.TITLE.getDataType().convert(r[6]),
                                        _film.TITLE.getDataType().convert(r[7]),
                                        _film.TITLE.getDataType().convert(r[8]),
                                        _film.TITLE.getDataType().convert(r[9]),
                                        _film.TITLE.getDataType().convert(r[10]),
                                        _film.TITLE.getDataType().convert(r[11]),
                                        _film.TITLE.getDataType().convert(r[12]),
                                        _film.TITLE.getDataType().convert(r[13]),
                                        _film.TITLE.getDataType().convert(r[14]),
                                        _film.TITLE.getDataType().convert(r[15]),
                                        _film.TITLE.getDataType().convert(r[16]),
                                        _film.TITLE.getDataType().convert(r[17]),
                                        _film.TITLE.getDataType().convert(r[18]),
                                        _film.TITLE.getDataType().convert(r[19]),
                                        _film.TITLE.getDataType().convert(r[20]),
                                        _film.TITLE.getDataType().convert(r[21]),
                                        _film.TITLE.getDataType().convert(r[22])
                                )
                        )
                )
                .from(_film)
                .fetchOne(it -> it.into(Film.class));
    }
}
