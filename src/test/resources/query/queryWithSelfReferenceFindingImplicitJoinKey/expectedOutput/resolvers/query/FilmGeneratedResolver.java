package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.FilmDBQueries;
import fake.graphql.example.api.FilmResolver;
import fake.graphql.example.model.Film;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
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
    public CompletableFuture<Film> sequel(Film film, DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        DataLoader<String, Film> loader = DataLoaders.getDataLoader(env, "sequelForFilm", (ids, selectionSet) -> filmDBQueries.sequelForFilm(ctx, ids, selectionSet));
        return DataLoaders.load(loader, film.getId(), env);
    }
}