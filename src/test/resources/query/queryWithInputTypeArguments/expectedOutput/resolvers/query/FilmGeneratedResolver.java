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
import java.lang.String;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.resolvers.DataLoaders;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import org.dataloader.DataLoader;
import org.jooq.DSLContext;

public class FilmGeneratedResolver implements FilmResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private FilmDBQueries filmDBQueries;

    @Override
    public CompletableFuture<List<FilmCategory>> categories(Film film,
            OriginalCategoryInput categoryIn, DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        DataLoader<String, List<FilmCategory>> loader = DataLoaders.getDataLoader(env, "categoriesForFilm", (ids, selectionSet) -> filmDBQueries.categoriesForFilm(ctx, ids, categoryIn, selectionSet));
        return DataLoaders.loadNonNullable(loader, film.getId(), env);
    }

    @Override
    public CompletableFuture<List<FilmCategory>> categoriesForInputList(Film film,
            List<OriginalCategoryInput> categoryInList, DataFetchingEnvironment env) throws
            Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        DataLoader<String, List<FilmCategory>> loader = DataLoaders.getDataLoader(env, "categoriesForInputListForFilm", (ids, selectionSet) -> filmDBQueries.categoriesForInputListForFilm(ctx, ids, categoryInList, selectionSet));
        return DataLoaders.loadNonNullable(loader, film.getId(), env);
    }

    @Override
    public CompletableFuture<List<FilmCategory>> categoriesForMixOfListAndSingleInput(Film film,
            OriginalCategoryInput categoryIn, List<OriginalCategoryInput> categoryInList,
            DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        DataLoader<String, List<FilmCategory>> loader = DataLoaders.getDataLoader(env, "categoriesForMixOfListAndSingleInputForFilm", (ids, selectionSet) -> filmDBQueries.categoriesForMixOfListAndSingleInputForFilm(ctx, ids, categoryIn, categoryInList, selectionSet));
        return DataLoaders.loadNonNullable(loader, film.getId(), env);
    }

    @Override
    public CompletableFuture<List<FilmCategory>> categoriesForInputWithOneFieldList(Film film,
            List<OriginalCategoryInputWithOneField> categoryInListOneField,
            DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        DataLoader<String, List<FilmCategory>> loader = DataLoaders.getDataLoader(env, "categoriesForInputWithOneFieldListForFilm", (ids, selectionSet) -> filmDBQueries.categoriesForInputWithOneFieldListForFilm(ctx, ids, categoryInListOneField, selectionSet));
        return DataLoaders.loadNonNullable(loader, film.getId(), env);
    }

    @Override
    public CompletableFuture<List<FilmCategory>> categoriesForInputWithNestedFieldList(Film film,
            List<OriginalCategoryInputNested> categoryInListNestedField,
            DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        DataLoader<String, List<FilmCategory>> loader = DataLoaders.getDataLoader(env, "categoriesForInputWithNestedFieldListForFilm", (ids, selectionSet) -> filmDBQueries.categoriesForInputWithNestedFieldListForFilm(ctx, ids, categoryInListNestedField, selectionSet));
        return DataLoaders.loadNonNullable(loader, film.getId(), env);
    }
}