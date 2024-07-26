package fake.code.generated.queries.query;
import static no.sikt.graphitron.jooq.generated.testdata.Keys.*;
import static no.sikt.graphitron.jooq.generated.testdata.Tables.*;
import fake.graphql.example.model.FilmCategory;
import fake.graphql.example.model.OriginalCategoryInput;
import fake.graphql.example.model.OriginalCategoryInputNested;
import fake.graphql.example.model.OriginalCategoryInputWithOneField;
import java.lang.String;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.jooq.DSLContext;
import org.jooq.Functions;
import org.jooq.Record2;
import org.jooq.impl.DSL;
public class FilmDBQueries {
    public static Map<String, List<FilmCategory>> categoriesForFilm(DSLContext ctx,
            Set<String> filmIds, OriginalCategoryInput categoryIn, SelectionSet select) {
        var film_filmcategory_left = FILM.filmCategory().as("filmCategory_3258783538");
        var filmcategory_3258783538_category_left = film_filmcategory_left.category().as("category_4105583788");
        return ctx
                .select(
                        FILM.getId(),
                        DSL.row(
                                film_filmcategory_left.getId(),
                                select.optional("name", filmcategory_3258783538_category_left.NAME)
                        ).mapping(Functions.nullOnAllNull(FilmCategory::new))
                )
                .from(FILM)
                .leftJoin(film_filmcategory_left)
                .leftJoin(filmcategory_3258783538_category_left)
                .where(FILM.hasIds(filmIds))
                .and(film_filmcategory_left.LAST_UPDATED.eq(categoryIn.getLastUpdated()))
                .and(filmcategory_3258783538_category_left.NAME.eq(categoryIn.getName()))
                .orderBy(film_filmcategory_left.getIdFields())
                .fetchGroups(Record2::value1, Record2::value2);
    }
    public static Map<String, List<FilmCategory>> categoriesForInputListForFilm(DSLContext ctx,
            Set<String> filmIds, List<OriginalCategoryInput> categoryInList, SelectionSet select) {
        var film_filmcategory_left = FILM.filmCategory().as("filmCategory_3258783538");
        var filmcategory_3258783538_category_left = film_filmcategory_left.category().as("category_4105583788");
        return ctx
                .select(
                        FILM.getId(),
                        DSL.row(
                                film_filmcategory_left.getId(),
                                select.optional("name", filmcategory_3258783538_category_left.NAME)
                        ).mapping(Functions.nullOnAllNull(FilmCategory::new))
                )
                .from(FILM)
                .leftJoin(film_filmcategory_left)
                .leftJoin(filmcategory_3258783538_category_left)
                .where(FILM.hasIds(filmIds))
                .and(
                        categoryInList != null && categoryInList.size() > 0 ?
                                DSL.row(
                                        film_filmcategory_left.LAST_UPDATED,
                                        filmcategory_3258783538_category_left.NAME
                                ).in(
                                        categoryInList.stream().map(internal_it_ ->
                                                DSL.row(
                                                        DSL.inline(internal_it_.getLastUpdated()),
                                                        DSL.inline(internal_it_.getName())
                                                )
                                        ).collect(Collectors.toList())
                                ) : DSL.noCondition()
                )
                .orderBy(film_filmcategory_left.getIdFields())
                .fetchGroups(Record2::value1, Record2::value2);
    }
    public static Map<String, List<FilmCategory>> categoriesForMixOfListAndSingleInputForFilm(
            DSLContext ctx, Set<String> filmIds, OriginalCategoryInput categoryIn,
            List<OriginalCategoryInput> categoryInList, SelectionSet select) {
        var film_filmcategory_left = FILM.filmCategory().as("filmCategory_3258783538");
        var filmcategory_3258783538_category_left = film_filmcategory_left.category().as("category_4105583788");
        return ctx
                .select(
                        FILM.getId(),
                        DSL.row(
                                film_filmcategory_left.getId(),
                                select.optional("name", filmcategory_3258783538_category_left.NAME)
                        ).mapping(Functions.nullOnAllNull(FilmCategory::new))
                )
                .from(FILM)
                .leftJoin(film_filmcategory_left)
                .leftJoin(filmcategory_3258783538_category_left)
                .where(FILM.hasIds(filmIds))
                .and(film_filmcategory_left.LAST_UPDATED.eq(categoryIn.getLastUpdated()))
                .and(filmcategory_3258783538_category_left.NAME.eq(categoryIn.getName()))
                .and(
                        categoryInList != null && categoryInList.size() > 0 ?
                                DSL.row(
                                        film_filmcategory_left.LAST_UPDATED,
                                        filmcategory_3258783538_category_left.NAME
                                ).in(
                                        categoryInList.stream().map(internal_it_ ->
                                                DSL.row(
                                                        DSL.inline(internal_it_.getLastUpdated()),
                                                        DSL.inline(internal_it_.getName())
                                                )
                                        ).collect(Collectors.toList())
                                ) : DSL.noCondition()
                )
                .orderBy(film_filmcategory_left.getIdFields())
                .fetchGroups(Record2::value1, Record2::value2);
    }
    public static Map<String, List<FilmCategory>> categoriesForInputWithOneFieldListForFilm(
            DSLContext ctx, Set<String> filmIds,
            List<OriginalCategoryInputWithOneField> categoryInListOneField, SelectionSet select) {
        var film_filmcategory_left = FILM.filmCategory().as("filmCategory_3258783538");
        var filmcategory_3258783538_category_left = film_filmcategory_left.category().as("category_4105583788");
        return ctx
                .select(
                        FILM.getId(),
                        DSL.row(
                                film_filmcategory_left.getId(),
                                select.optional("name", filmcategory_3258783538_category_left.NAME)
                        ).mapping(Functions.nullOnAllNull(FilmCategory::new))
                )
                .from(FILM)
                .leftJoin(film_filmcategory_left)
                .leftJoin(filmcategory_3258783538_category_left)
                .where(FILM.hasIds(filmIds))
                .and(
                        categoryInListOneField != null && categoryInListOneField.size() > 0 ?
                                DSL.row(film_filmcategory_left.LAST_UPDATED).in(
                                        categoryInListOneField.stream().map(internal_it_ ->
                                                DSL.row(DSL.inline(internal_it_.getLastUpdated()))
                                        ).collect(Collectors.toList())
                                ) : DSL.noCondition()
                )
                .orderBy(film_filmcategory_left.getIdFields())
                .fetchGroups(Record2::value1, Record2::value2);
    }
    public static Map<String, List<FilmCategory>> categoriesForInputWithNestedFieldListForFilm(
            DSLContext ctx, Set<String> filmIds,
            List<OriginalCategoryInputNested> categoryInListNestedField, SelectionSet select) {
        var film_filmcategory_left = FILM.filmCategory().as("filmCategory_3258783538");
        var filmcategory_3258783538_category_left = film_filmcategory_left.category().as("category_4105583788");
        return ctx
                .select(
                        FILM.getId(),
                        DSL.row(
                                film_filmcategory_left.getId(),
                                select.optional("name", filmcategory_3258783538_category_left.NAME)
                        ).mapping(Functions.nullOnAllNull(FilmCategory::new))
                )
                .from(FILM)
                .leftJoin(film_filmcategory_left)
                .leftJoin(filmcategory_3258783538_category_left)
                .where(FILM.hasIds(filmIds))
                .and(
                        categoryInListNestedField != null && categoryInListNestedField.size() > 0 ?
                                DSL.row(
                                        film_filmcategory_left.LAST_UPDATED,
                                        filmcategory_3258783538_category_left.NAME
                                ).in(
                                        categoryInListNestedField.stream().map(internal_it_ ->
                                                DSL.row(
                                                        DSL.inline(internal_it_.getOriginalCategoryField().getLastUpdated()),
                                                        DSL.inline(internal_it_.getName())
                                                )
                                        ).collect(Collectors.toList())
                                ) : DSL.noCondition()
                )
                .orderBy(film_filmcategory_left.getIdFields())
                .fetchGroups(Record2::value1, Record2::value2);
    }
}
