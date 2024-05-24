package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.FilmDBQueries;
import fake.graphql.example.api.FilmResolver;
import fake.graphql.example.model.Film;
import fake.graphql.example.model.FilmCategory;
import fake.graphql.example.model.OriginalCategoryInput;
import fake.graphql.example.model.OriginalCategoryInputNested;
import fake.graphql.example.model.OriginalCategoryInputWithOneField;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.resolvers.DataFetcher;
import org.jooq.DSLContext;

public class FilmGeneratedResolver implements FilmResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private FilmDBQueries filmDBQueries;

    @Override
    public CompletableFuture<List<FilmCategory>> categories(Film film,
            OriginalCategoryInput categoryIn, DataFetchingEnvironment env) throws Exception {
        return new DataFetcher(env, this.ctx).loadNonNullable("categoriesForFilm", film.getId(), (ctx, ids, selectionSet) -> filmDBQueries.categoriesForFilm(ctx, ids, categoryIn, selectionSet));
    }

    @Override
    public CompletableFuture<List<FilmCategory>> categoriesForInputList(Film film,
            List<OriginalCategoryInput> categoryInList, DataFetchingEnvironment env) throws
            Exception {
        return new DataFetcher(env, this.ctx).loadNonNullable("categoriesForInputListForFilm", film.getId(), (ctx, ids, selectionSet) -> filmDBQueries.categoriesForInputListForFilm(ctx, ids, categoryInList, selectionSet));
    }

    @Override
    public CompletableFuture<List<FilmCategory>> categoriesForMixOfListAndSingleInput(Film film,
            OriginalCategoryInput categoryIn, List<OriginalCategoryInput> categoryInList,
            DataFetchingEnvironment env) throws Exception {
        return new DataFetcher(env, this.ctx).loadNonNullable("categoriesForMixOfListAndSingleInputForFilm", film.getId(), (ctx, ids, selectionSet) -> filmDBQueries.categoriesForMixOfListAndSingleInputForFilm(ctx, ids, categoryIn, categoryInList, selectionSet));
    }

    @Override
    public CompletableFuture<List<FilmCategory>> categoriesForInputWithOneFieldList(Film film,
            List<OriginalCategoryInputWithOneField> categoryInListOneField,
            DataFetchingEnvironment env) throws Exception {
        return new DataFetcher(env, this.ctx).loadNonNullable("categoriesForInputWithOneFieldListForFilm", film.getId(), (ctx, ids, selectionSet) -> filmDBQueries.categoriesForInputWithOneFieldListForFilm(ctx, ids, categoryInListOneField, selectionSet));
    }

    @Override
    public CompletableFuture<List<FilmCategory>> categoriesForInputWithNestedFieldList(Film film,
            List<OriginalCategoryInputNested> categoryInListNestedField,
            DataFetchingEnvironment env) throws Exception {
        return new DataFetcher(env, this.ctx).loadNonNullable("categoriesForInputWithNestedFieldListForFilm", film.getId(), (ctx, ids, selectionSet) -> filmDBQueries.categoriesForInputWithNestedFieldListForFilm(ctx, ids, categoryInListNestedField, selectionSet));
    }
}