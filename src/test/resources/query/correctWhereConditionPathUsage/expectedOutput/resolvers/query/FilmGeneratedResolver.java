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
    public CompletableFuture<List<Language>> languages(Film film, List<String> s,
            DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        DataLoader<String, List<Language>> loader = DataLoaders.getDataLoader(env, "languagesForFilm", (ids, selectionSet) -> filmDBQueries.languagesForFilm(ctx, ids, s, selectionSet));
        return DataLoaders.loadNonNullable(loader, film.getId(), env);
    }

    @Override
    public CompletableFuture<List<Language>> languagesInput(Film film, In s,
            DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        DataLoader<String, List<Language>> loader = DataLoaders.getDataLoader(env, "languagesInputForFilm", (ids, selectionSet) -> filmDBQueries.languagesInputForFilm(ctx, ids, s, selectionSet));
        return DataLoaders.loadNonNullable(loader, film.getId(), env);
    }
}