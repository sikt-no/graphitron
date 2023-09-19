package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.FilmDBQueries;
import fake.graphql.example.package.api.FilmResolver;
import fake.graphql.example.package.model.Film;
import fake.graphql.example.package.model.In;
import fake.graphql.example.package.model.Language;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.EnvironmentUtils;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import org.dataloader.DataLoader;
import org.dataloader.DataLoaderFactory;
import org.dataloader.MappedBatchLoaderWithContext;
import org.jooq.DSLContext;

public class FilmGeneratedResolver implements FilmResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private FilmDBQueries filmDBQueries;

    @Override
    public CompletableFuture<List<Language>> languages(Film film, List<String> s,
            DataFetchingEnvironment env) throws Exception {
        var ctx = env.getLocalContext() == null ? this.ctx : (DSLContext) env.getLocalContext();
        DataLoader<String, List<Language>> loader = env.getDataLoaderRegistry().computeIfAbsent("languagesForFilm", name -> {
            var batchLoader = (MappedBatchLoaderWithContext<String, List<Language>>) (keys, batchEnvLoader) -> {
                var keyToId = keys.stream().collect(
                        Collectors.toMap(s -> s, s -> s.substring(s.lastIndexOf("||") + 2)));
                var idSet = new HashSet<>(keyToId.values());
                var selectionSet = new SelectionSet(EnvironmentUtils.getSelectionSetsFromEnvironment(batchEnvLoader));
                var dbResult = filmDBQueries.languagesForFilm(ctx, idSet, s, selectionSet);
                var mapResult = keyToId.entrySet().stream()
                        .filter(it -> dbResult.get(it.getValue()) != null)
                        .collect(Collectors.toMap(Map.Entry::getKey, it -> dbResult.get(it.getValue())));
                return CompletableFuture.completedFuture(mapResult);
            } ;
            return DataLoaderFactory.newMappedDataLoader(batchLoader);
        } );
        return loader.load(env.getExecutionStepInfo().getPath().toString() + "||" + film.getId(), env).thenApply(data -> Optional.ofNullable(data).orElse(List.of()));
    }

    @Override
    public CompletableFuture<List<Language>> languagesInput(Film film, In s,
            DataFetchingEnvironment env) throws Exception {
        var ctx = env.getLocalContext() == null ? this.ctx : (DSLContext) env.getLocalContext();
        DataLoader<String, List<Language>> loader = env.getDataLoaderRegistry().computeIfAbsent("languagesInputForFilm", name -> {
            var batchLoader = (MappedBatchLoaderWithContext<String, List<Language>>) (keys, batchEnvLoader) -> {
                var keyToId = keys.stream().collect(
                        Collectors.toMap(s -> s, s -> s.substring(s.lastIndexOf("||") + 2)));
                var idSet = new HashSet<>(keyToId.values());
                var selectionSet = new SelectionSet(EnvironmentUtils.getSelectionSetsFromEnvironment(batchEnvLoader));
                var dbResult = filmDBQueries.languagesInputForFilm(ctx, idSet, s, selectionSet);
                var mapResult = keyToId.entrySet().stream()
                        .filter(it -> dbResult.get(it.getValue()) != null)
                        .collect(Collectors.toMap(Map.Entry::getKey, it -> dbResult.get(it.getValue())));
                return CompletableFuture.completedFuture(mapResult);
            } ;
            return DataLoaderFactory.newMappedDataLoader(batchLoader);
        } );
        return loader.load(env.getExecutionStepInfo().getPath().toString() + "||" + film.getId(), env).thenApply(data -> Optional.ofNullable(data).orElse(List.of()));
    }
}