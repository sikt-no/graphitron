package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.QueryDBQueries;
import fake.graphql.example.api.QueryResolver;
import fake.graphql.example.model.Film;
import fake.graphql.example.model.FilmFields;
import fake.graphql.example.model.FilmNested;
import fake.graphql.example.model.FilmNestedList;
import fake.graphql.example.model.FilmNestedNoKey;
import fake.graphql.example.model.FilmNestedWithKeyList;
import fake.graphql.example.model.FilmWithListKeys;
import fake.graphql.example.model.FilmWithoutKeys;
import fake.graphql.example.model.FilmNullableNestedList;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Integer;
import java.lang.Override;
import java.lang.String;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import javax.inject.Inject;

import no.fellesstudentsystem.graphql.helpers.resolvers.DataFetcher;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import org.jooq.DSLContext;

public class QueryGeneratedResolver implements QueryResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<List<Film>> films(List<String> titles, List<String> releaseYears,
                                               List<Integer> durations, List<String> filmId, DataFetchingEnvironment env) throws
            Exception {
        var keys = List.of(titles, releaseYears, ResolverHelpers.formatString(durations), filmId);
        return new DataFetcher(env, this.ctx).loadLookup(keys, (ctx, ids, selectionSet) -> QueryDBQueries.filmsForQuery(ctx, titles, releaseYears, durations, filmId, selectionSet));
    }

    @Override
    public CompletableFuture<List<Film>> filmsInputKeys(FilmWithListKeys in,
                                                        DataFetchingEnvironment env) throws Exception {
        var keys = List.of(in.getTitles(), in.getReleaseYears(), ResolverHelpers.formatString(in.getDurations()));
        return new DataFetcher(env, this.ctx).loadLookup(keys, (ctx, ids, selectionSet) -> QueryDBQueries.filmsInputKeysForQuery(ctx, in, selectionSet));
    }

    @Override
    public CompletableFuture<List<Film>> filmsListedInput(List<FilmWithoutKeys> in,
                                                          DataFetchingEnvironment env) throws Exception {
        var keys = List.of(in.stream().map(itIn -> itIn != null ? itIn.getTitle() : null).collect(Collectors.toList()), in.stream().map(itIn -> itIn != null ? itIn.getReleaseYear() : null).collect(Collectors.toList()), ResolverHelpers.formatString(in.stream().map(itIn -> itIn != null ? itIn.getDuration() : null).collect(Collectors.toList())));
        return new DataFetcher(env, this.ctx).loadLookup(keys, (ctx, ids, selectionSet) -> QueryDBQueries.filmsListedInputForQuery(ctx, in, selectionSet));
    }

    @Override
    public CompletableFuture<List<Film>> filmsInput(FilmFields in, DataFetchingEnvironment env)
            throws Exception {
        var keys = List.of(in.getTitles(), in.getReleaseYears(), ResolverHelpers.formatString(in.getDurations()));
        return new DataFetcher(env, this.ctx).loadLookup(keys, (ctx, ids, selectionSet) -> QueryDBQueries.filmsInputForQuery(ctx, in, selectionSet));
    }

    @Override
    public CompletableFuture<List<Film>> filmsNestedInputs(FilmNestedNoKey in,
                                                           DataFetchingEnvironment env) throws Exception {
        var keys = List.of(in.getFilmIds(), in.getFields().getTitles(), in.getFields().getReleaseYears(), ResolverHelpers.formatString(in.getFields().getDurations()));
        return new DataFetcher(env, this.ctx).loadLookup(keys, (ctx, ids, selectionSet) -> QueryDBQueries.filmsNestedInputsForQuery(ctx, in, selectionSet));
    }

    @Override
    public CompletableFuture<List<Film>> filmsNestedInputsAndKeys(FilmNested in,
                                                                  DataFetchingEnvironment env) throws Exception {
        var keys = List.of(in.getFilmIds(), in.getFields().getTitles(), in.getFields().getReleaseYears(), ResolverHelpers.formatString(in.getFields().getDurations()));
        return new DataFetcher(env, this.ctx).loadLookup(keys, (ctx, ids, selectionSet) -> QueryDBQueries.filmsNestedInputsAndKeysForQuery(ctx, in, selectionSet));
    }

    @Override
    public CompletableFuture<List<Film>> filmsNestedList(FilmNestedList in,
                                                         DataFetchingEnvironment env) throws Exception {
        var keys = List.of(in.getFilmIds(), in.getFields().stream().map(itFields -> itFields != null ? itFields.getTitle() : null).collect(Collectors.toList()), in.getFields().stream().map(itFields -> itFields != null ? itFields.getReleaseYear() : null).collect(Collectors.toList()), ResolverHelpers.formatString(in.getFields().stream().map(itFields -> itFields != null ? itFields.getDuration() : null).collect(Collectors.toList())));
        return new DataFetcher(env, this.ctx).loadLookup(keys, (ctx, ids, selectionSet) -> QueryDBQueries.filmsNestedListForQuery(ctx, in, selectionSet));
    }

    @Override
    public CompletableFuture<List<Film>> filmsNestedListWithKeys(FilmNestedWithKeyList in,
                                                                 DataFetchingEnvironment env) throws Exception {
        var keys = List.of(in.getFilmIds(), in.getFields().stream().map(itFields -> itFields != null ? itFields.getTitle() : null).collect(Collectors.toList()), in.getFields().stream().map(itFields -> itFields != null ? itFields.getReleaseYear() : null).collect(Collectors.toList()), ResolverHelpers.formatString(in.getFields().stream().map(itFields -> itFields != null ? itFields.getDuration() : null).collect(Collectors.toList())));
        return new DataFetcher(env, this.ctx).loadLookup(keys, (ctx, ids, selectionSet) -> QueryDBQueries.filmsNestedListWithKeysForQuery(ctx, in, selectionSet));
    }

    @Override
    public CompletableFuture<List<Film>> filmsNullableNestedList(FilmNullableNestedList in,
                                                                 DataFetchingEnvironment env) throws Exception {
        var keys = List.of(in.getFields().stream().map(itFields -> itFields != null ? itFields.getTitle() : null).collect(Collectors.toList()), in.getFields().stream().map(itFields -> itFields != null ? itFields.getReleaseYear() : null).collect(Collectors.toList()), ResolverHelpers.formatString(in.getFields().stream().map(itFields -> itFields != null ? itFields.getDuration() : null).collect(Collectors.toList())));
        return new DataFetcher(env, this.ctx).loadLookup(keys, (ctx, ids, selectionSet) -> QueryDBQueries.filmsNullableNestedListForQuery(ctx, in, selectionSet));
    }
}
