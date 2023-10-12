package fake.code.generated.queries.query;

import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;

import fake.graphql.example.package.model.FilmCategory;
import fake.graphql.example.package.model.OriginalCategoryInput;
import fake.graphql.example.package.model.OriginalCategoryInputNested;
import fake.graphql.example.package.model.OriginalCategoryInputWithOneField;
import java.lang.String;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Record2;
import org.jooq.Functions;
import org.jooq.impl.DSL;

public class FilmDBQueries {
    public Map<String, List<FilmCategory>> categoriesForFilm(DSLContext ctx, Set<String> filmIds,
                                                             OriginalCategoryInput categoryIn, SelectionSet select) {
        return ctx
                .select(
                        FILM_CATEGORY.getFilmId(),
                        DSL.row(
                                FILM_CATEGORY.getId().as("id"),
                                select.optional("name", FILM_CATEGORY.category().NAME).as("name")
                        ).mapping(Functions.nullOnAllNull(FilmCategory::new)).as("categories")
                )
                .from(FILM_CATEGORY)
                .where(FILM_CATEGORY.hasFilmIds(filmIds))
                .and(FILM_CATEGORY.LAST_UPDATED.eq(categoryIn.getLastUpdated()))
                .and(FILM_CATEGORY.category().NAME.eq(categoryIn.getName()))
                .fetchGroups(Record2::value1, Record2::value2);
    }

    public Map<String, List<FilmCategory>> categoriesForInputListForFilm(DSLContext ctx,
                                                                         Set<String> filmIds, List<OriginalCategoryInput> categoryInList, SelectionSet select) {
        return ctx
                .select(
                        FILM_CATEGORY.getFilmId(),
                        DSL.row(
                                FILM_CATEGORY.getId().as("id"),
                                select.optional("name", FILM_CATEGORY.category().NAME).as("name")
                        ).mapping(Functions.nullOnAllNull(FilmCategory::new)).as("categoriesForInputList")
                )
                .from(FILM_CATEGORY)
                .where(FILM_CATEGORY.hasFilmIds(filmIds))
                .and(categoryInList != null && categoryInList.size() > 0 ?
                        DSL.row(
                                FILM_CATEGORY.LAST_UPDATED,
                                FILM_CATEGORY.category().NAME
                        ).in(categoryInList.stream().map(input -> DSL.row(
                                input.getLastUpdated(),
                                input.getName())
                        ).collect(Collectors.toList())) :
                        DSL.noCondition())
                .fetchGroups(Record2::value1, Record2::value2);
    }

    public Map<String, List<FilmCategory>> categoriesForMixOfListAndSingleInputForFilm(
            DSLContext ctx, Set<String> filmIds, OriginalCategoryInput categoryIn,
            List<OriginalCategoryInput> categoryInList, SelectionSet select) {
        return ctx
                .select(
                        FILM_CATEGORY.getFilmId(),
                        DSL.row(
                                FILM_CATEGORY.getId().as("id"),
                                select.optional("name", FILM_CATEGORY.category().NAME).as("name")
                        ).mapping(Functions.nullOnAllNull(FilmCategory::new)).as("categoriesForMixOfListAndSingleInput")
                )
                .from(FILM_CATEGORY)
                .where(FILM_CATEGORY.hasFilmIds(filmIds))
                .and(FILM_CATEGORY.LAST_UPDATED.eq(categoryIn.getLastUpdated()))
                .and(FILM_CATEGORY.category().NAME.eq(categoryIn.getName()))
                .and(categoryInList != null && categoryInList.size() > 0 ?
                        DSL.row(
                                FILM_CATEGORY.LAST_UPDATED,
                                FILM_CATEGORY.category().NAME
                        ).in(categoryInList.stream().map(input -> DSL.row(
                                input.getLastUpdated(),
                                input.getName())
                        ).collect(Collectors.toList())) :
                        DSL.noCondition())
                .fetchGroups(Record2::value1, Record2::value2);
    }

    public Map<String, List<FilmCategory>> categoriesForInputWithOneFieldListForFilm(DSLContext ctx,
                                                                                     Set<String> filmIds, List<OriginalCategoryInputWithOneField> categoryInListOneField,
                                                                                     SelectionSet select) {
        return ctx
                .select(
                        FILM_CATEGORY.getFilmId(),
                        DSL.row(
                                FILM_CATEGORY.getId().as("id"),
                                select.optional("name", FILM_CATEGORY.category().NAME).as("name")
                        ).mapping(Functions.nullOnAllNull(FilmCategory::new)).as("categoriesForInputWithOneFieldList")
                )
                .from(FILM_CATEGORY)
                .where(FILM_CATEGORY.hasFilmIds(filmIds))
                .and(categoryInListOneField != null && categoryInListOneField.size() > 0 ?
                        DSL.row(
                                FILM_CATEGORY.LAST_UPDATED
                        ).in(categoryInListOneField.stream().map(input -> DSL.row(
                                input.getLastUpdated())
                        ).collect(Collectors.toList())) :
                        DSL.noCondition())
                .fetchGroups(Record2::value1, Record2::value2);
    }

    public Map<String, List<FilmCategory>> categoriesForInputWithNestedFieldListForFilm(
            DSLContext ctx, Set<String> filmIds,
            List<OriginalCategoryInputNested> categoryInListNestedField, SelectionSet select) {
        return ctx
                .select(
                        FILM_CATEGORY.getFilmId(),
                        DSL.row(
                                FILM_CATEGORY.getId().as("id"),
                                select.optional("name", FILM_CATEGORY.category().NAME).as("name")
                        ).mapping(Functions.nullOnAllNull(FilmCategory::new)).as("categoriesForInputWithNestedFieldList")
                )
                .from(FILM_CATEGORY)
                .where(FILM_CATEGORY.hasFilmIds(filmIds))
                .and(categoryInListNestedField != null && categoryInListNestedField.size() > 0 ?
                        DSL.row(
                                FILM_CATEGORY.LAST_UPDATED,
                                FILM_CATEGORY.category().NAME
                        ).in(categoryInListNestedField.stream().map(input -> DSL.row(
                                input.getOriginalCategoryField().getLastUpdated(),
                                input.getName())
                        ).collect(Collectors.toList())) :
                        DSL.noCondition())
                .fetchGroups(Record2::value1, Record2::value2);
    }
}