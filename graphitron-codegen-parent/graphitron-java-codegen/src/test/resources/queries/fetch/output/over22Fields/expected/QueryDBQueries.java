package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.public_.Tables.*;
import static no.sikt.graphitron.jooq.generated.testdata.pg_catalog.Tables.*;

import fake.graphql.example.model.Film;
import java.util.Arrays;
import java.util.Objects;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public static Film queryForQuery(DSLContext _iv_ctx, SelectionSet _iv_select) {
        var _a_film = FILM.as("film_2185543202");
        return _iv_ctx
                .select(
                        DSL.row(
                                _a_film.TITLE,
                                _a_film.TITLE,
                                _a_film.TITLE,
                                _a_film.TITLE,
                                _a_film.TITLE,
                                _a_film.TITLE,
                                _a_film.TITLE,
                                _a_film.TITLE,
                                _a_film.TITLE,
                                _a_film.TITLE,
                                _a_film.TITLE,
                                _a_film.TITLE,
                                _a_film.TITLE,
                                _a_film.TITLE,
                                _a_film.TITLE,
                                _a_film.TITLE,
                                _a_film.TITLE,
                                _a_film.TITLE,
                                _a_film.TITLE,
                                _a_film.TITLE,
                                _a_film.TITLE,
                                _a_film.TITLE,
                                _a_film.TITLE
                        ).mapping(Film.class, _iv_r ->
                                Arrays.stream(_iv_r).allMatch(Objects::isNull) ? null : new Film(
                                        _a_film.TITLE.getDataType().convert(_iv_r[0]),
                                        _a_film.TITLE.getDataType().convert(_iv_r[1]),
                                        _a_film.TITLE.getDataType().convert(_iv_r[2]),
                                        _a_film.TITLE.getDataType().convert(_iv_r[3]),
                                        _a_film.TITLE.getDataType().convert(_iv_r[4]),
                                        _a_film.TITLE.getDataType().convert(_iv_r[5]),
                                        _a_film.TITLE.getDataType().convert(_iv_r[6]),
                                        _a_film.TITLE.getDataType().convert(_iv_r[7]),
                                        _a_film.TITLE.getDataType().convert(_iv_r[8]),
                                        _a_film.TITLE.getDataType().convert(_iv_r[9]),
                                        _a_film.TITLE.getDataType().convert(_iv_r[10]),
                                        _a_film.TITLE.getDataType().convert(_iv_r[11]),
                                        _a_film.TITLE.getDataType().convert(_iv_r[12]),
                                        _a_film.TITLE.getDataType().convert(_iv_r[13]),
                                        _a_film.TITLE.getDataType().convert(_iv_r[14]),
                                        _a_film.TITLE.getDataType().convert(_iv_r[15]),
                                        _a_film.TITLE.getDataType().convert(_iv_r[16]),
                                        _a_film.TITLE.getDataType().convert(_iv_r[17]),
                                        _a_film.TITLE.getDataType().convert(_iv_r[18]),
                                        _a_film.TITLE.getDataType().convert(_iv_r[19]),
                                        _a_film.TITLE.getDataType().convert(_iv_r[20]),
                                        _a_film.TITLE.getDataType().convert(_iv_r[21]),
                                        _a_film.TITLE.getDataType().convert(_iv_r[22])
                                )
                        )
                )
                .from(_a_film)
                .fetchOne(_iv_it -> _iv_it.into(Film.class));
    }
}
