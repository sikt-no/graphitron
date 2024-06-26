package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.model.Film;
import fake.graphql.example.model.FilmFields;
import fake.graphql.example.model.FilmNested;
import fake.graphql.example.model.FilmNestedList;
import fake.graphql.example.model.FilmNestedNoKey;
import fake.graphql.example.model.FilmNestedWithKeyList;
import fake.graphql.example.model.FilmWithListKeys;
import fake.graphql.example.model.FilmWithoutKeys;
import fake.graphql.example.model.FilmNullableNestedList;
import java.lang.Integer;
import java.lang.String;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.Record2;
import org.jooq.impl.DSL;

public class QueryDBQueries {
    public static Map<String, Film> filmsForQuery(DSLContext ctx, List<String> titles,
                                           List<String> releaseYears, List<Integer> durations, List<String> filmId,
                                           SelectionSet select) {
        return ctx
                .select(
                        DSL.concat(DSL.inlined(FILM.TITLE), DSL.inline(","), DSL.inlined(FILM.RELEASE_YEAR), DSL.inline(","), DSL.inlined(FILM.LENGTH), DSL.inline(","), DSL.inlined(FILM.FILM_ID)),
                        DSL.row(
                                FILM.getId().as("id")
                        ).mapping(Functions.nullOnAllNull(Film::new)).as("films")
                )
                .from(FILM)
                .where(titles.size() > 0 ? FILM.TITLE.in(titles) : DSL.noCondition())
                .and(releaseYears.size() > 0 ? FILM.RELEASE_YEAR.in(releaseYears) : DSL.noCondition())
                .and(durations.size() > 0 ? FILM.LENGTH.in(durations) : DSL.noCondition())
                .and(filmId.size() > 0 ? FILM.FILM_ID.in(filmId) : DSL.noCondition())
                .fetchMap(Record2::value1, Record2::value2);
    }

    public static Map<String, Film> filmsInputKeysForQuery(DSLContext ctx, FilmWithListKeys in,
                                                    SelectionSet select) {
        return ctx
                .select(
                        DSL.concat(DSL.inlined(FILM.TITLE), DSL.inline(","), DSL.inlined(FILM.RELEASE_YEAR), DSL.inline(","), DSL.inlined(FILM.LENGTH)),
                        DSL.row(
                                FILM.getId().as("id")
                        ).mapping(Functions.nullOnAllNull(Film::new)).as("filmsInputKeys")
                )
                .from(FILM)
                .where(in != null && in.getTitles().size() > 0 ? FILM.TITLE.in(in.getTitles()) : DSL.noCondition())
                .and(in != null && in.getReleaseYears().size() > 0 ? FILM.RELEASE_YEAR.in(in.getReleaseYears()) : DSL.noCondition())
                .and(in != null && in.getDurations().size() > 0 ? FILM.LENGTH.in(in.getDurations()) : DSL.noCondition())
                .fetchMap(Record2::value1, Record2::value2);
    }

    public static Map<String, Film> filmsListedInputForQuery(DSLContext ctx, List<FilmWithoutKeys> in,
                                                      SelectionSet select) {
        return ctx
                .select(
                        DSL.concat(DSL.inlined(FILM.TITLE), DSL.inline(","), DSL.inlined(FILM.RELEASE_YEAR), DSL.inline(","), DSL.inlined(FILM.LENGTH)),
                        DSL.row(
                                FILM.getId().as("id")
                        ).mapping(Functions.nullOnAllNull(Film::new)).as("filmsListedInput")
                )
                .from(FILM)
                .where(in.size() > 0 ?
                        DSL.row(
                                FILM.TITLE,
                                FILM.RELEASE_YEAR,
                                FILM.LENGTH
                        ).in(in.stream().map(input -> DSL.row(
                                input.getTitle(),
                                input.getReleaseYear(),
                                input.getDuration())
                        ).collect(Collectors.toList())) :
                        DSL.noCondition())
                .fetchMap(Record2::value1, Record2::value2);
    }

    public static Map<String, Film> filmsInputForQuery(DSLContext ctx, FilmFields in,
                                                SelectionSet select) {
        return ctx
                .select(
                        DSL.concat(DSL.inlined(FILM.TITLE), DSL.inline(","), DSL.inlined(FILM.RELEASE_YEAR), DSL.inline(","), DSL.inlined(FILM.LENGTH)),
                        DSL.row(
                                FILM.getId().as("id")
                        ).mapping(Functions.nullOnAllNull(Film::new)).as("filmsInput")
                )
                .from(FILM)
                .where(in != null && in.getTitles().size() > 0 ? FILM.TITLE.in(in.getTitles()) : DSL.noCondition())
                .and(in != null && in.getReleaseYears().size() > 0 ? FILM.RELEASE_YEAR.in(in.getReleaseYears()) : DSL.noCondition())
                .and(in != null && in.getDurations().size() > 0 ? FILM.LENGTH.in(in.getDurations()) : DSL.noCondition())
                .fetchMap(Record2::value1, Record2::value2);
    }

    public static Map<String, Film> filmsNestedInputsForQuery(DSLContext ctx, FilmNestedNoKey in,
                                                       SelectionSet select) {
        return ctx
                .select(
                        DSL.concat(DSL.inlined(FILM.FILM_ID), DSL.inline(","), DSL.inlined(FILM.TITLE), DSL.inline(","), DSL.inlined(FILM.RELEASE_YEAR), DSL.inline(","), DSL.inlined(FILM.LENGTH)),
                        DSL.row(
                                FILM.getId().as("id")
                        ).mapping(Functions.nullOnAllNull(Film::new)).as("filmsNestedInputs")
                )
                .from(FILM)
                .where(in != null && in.getFields() != null && in.getFields().getTitles().size() > 0 ? FILM.TITLE.in(in.getFields().getTitles()) : DSL.noCondition())
                .and(in != null && in.getFields() != null && in.getFields().getReleaseYears().size() > 0 ? FILM.RELEASE_YEAR.in(in.getFields().getReleaseYears()) : DSL.noCondition())
                .and(in != null && in.getFields() != null && in.getFields().getDurations().size() > 0 ? FILM.LENGTH.in(in.getFields().getDurations()) : DSL.noCondition())
                .and(in != null && in.getFilmIds().size() > 0 ? FILM.FILM_ID.in(in.getFilmIds()) : DSL.noCondition())
                .fetchMap(Record2::value1, Record2::value2);
    }

    public static Map<String, Film> filmsNestedInputsAndKeysForQuery(DSLContext ctx, FilmNested in,
                                                              SelectionSet select) {
        return ctx
                .select(
                        DSL.concat(DSL.inlined(FILM.FILM_ID), DSL.inline(","), DSL.inlined(FILM.TITLE), DSL.inline(","), DSL.inlined(FILM.RELEASE_YEAR), DSL.inline(","), DSL.inlined(FILM.LENGTH)),
                        DSL.row(
                                FILM.getId().as("id")
                        ).mapping(Functions.nullOnAllNull(Film::new)).as("filmsNestedInputsAndKeys")
                )
                .from(FILM)
                .where(in != null && in.getFields() != null && in.getFields().getTitles().size() > 0 ? FILM.TITLE.in(in.getFields().getTitles()) : DSL.noCondition())
                .and(in != null && in.getFields() != null && in.getFields().getReleaseYears().size() > 0 ? FILM.RELEASE_YEAR.in(in.getFields().getReleaseYears()) : DSL.noCondition())
                .and(in != null && in.getFields() != null && in.getFields().getDurations().size() > 0 ? FILM.LENGTH.in(in.getFields().getDurations()) : DSL.noCondition())
                .and(in != null && in.getFilmIds().size() > 0 ? FILM.FILM_ID.in(in.getFilmIds()) : DSL.noCondition())
                .fetchMap(Record2::value1, Record2::value2);
    }

    public static Map<String, Film> filmsNestedListForQuery(DSLContext ctx, FilmNestedList in,
                                                     SelectionSet select) {
        return ctx
                .select(
                        DSL.concat(DSL.inlined(FILM.FILM_ID), DSL.inline(","), DSL.inlined(FILM.TITLE), DSL.inline(","), DSL.inlined(FILM.RELEASE_YEAR), DSL.inline(","), DSL.inlined(FILM.LENGTH)),
                        DSL.row(
                                FILM.getId().as("id")
                        ).mapping(Functions.nullOnAllNull(Film::new)).as("filmsNestedList")
                )
                .from(FILM)
                .where(in != null && in.getFilmIds().size() > 0 ? FILM.FILM_ID.in(in.getFilmIds()) : DSL.noCondition())
                .and(in != null && in.getFields().size() > 0 ?
                        DSL.row(
                                FILM.TITLE,
                                FILM.RELEASE_YEAR,
                                FILM.LENGTH
                        ).in(in.getFields().stream().map(input -> DSL.row(
                                input.getTitle(),
                                input.getReleaseYear(),
                                input.getDuration())
                        ).collect(Collectors.toList())) :
                        DSL.noCondition())
                .fetchMap(Record2::value1, Record2::value2);
    }

    public static Map<String, Film> filmsNestedListWithKeysForQuery(DSLContext ctx,
                                                             FilmNestedWithKeyList in, SelectionSet select) {
        return ctx
                .select(
                        DSL.concat(DSL.inlined(FILM.FILM_ID), DSL.inline(","), DSL.inlined(FILM.TITLE), DSL.inline(","), DSL.inlined(FILM.RELEASE_YEAR), DSL.inline(","), DSL.inlined(FILM.LENGTH)),
                        DSL.row(
                                FILM.getId().as("id")
                        ).mapping(Functions.nullOnAllNull(Film::new)).as("filmsNestedListWithKeys")
                )
                .from(FILM)
                .where(in != null && in.getFilmIds().size() > 0 ? FILM.FILM_ID.in(in.getFilmIds()) : DSL.noCondition())
                .and(in != null && in.getFields().size() > 0 ?
                        DSL.row(
                                FILM.TITLE,
                                FILM.RELEASE_YEAR,
                                FILM.LENGTH
                        ).in(in.getFields().stream().map(input -> DSL.row(
                                input.getTitle(),
                                input.getReleaseYear(),
                                input.getDuration())
                        ).collect(Collectors.toList())) :
                        DSL.noCondition())
                .fetchMap(Record2::value1, Record2::value2);
    }

    public static Map<String, Film> filmsNullableNestedListForQuery(DSLContext ctx,
                                                             FilmNullableNestedList in, SelectionSet select) {
        return ctx
                .select(
                        DSL.concat(DSL.inlined(FILM.TITLE), DSL.inline(","), DSL.inlined(FILM.RELEASE_YEAR), DSL.inline(","), DSL.inlined(FILM.LENGTH)),
                        DSL.row(
                                FILM.getId().as("id")
                        ).mapping(Functions.nullOnAllNull(Film::new)).as("filmsNullableNestedList")
                )
                .from(FILM)
                .where(in != null && in.getFields() != null && in.getFields().size() > 0 ?
                        DSL.row(
                                FILM.TITLE,
                                FILM.RELEASE_YEAR,
                                FILM.LENGTH
                        ).in(in.getFields().stream().map(input -> DSL.row(
                                input.getTitle(),
                                input.getReleaseYear(),
                                input.getDuration())
                        ).collect(Collectors.toList())) :
                        DSL.noCondition())
                .fetchMap(Record2::value1, Record2::value2);
    }
}
