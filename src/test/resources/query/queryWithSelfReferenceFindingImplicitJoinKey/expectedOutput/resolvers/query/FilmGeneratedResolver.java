package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.FilmDBQueries;
import fake.graphql.example.api.FilmResolver;
import fake.graphql.example.model.Film;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.resolvers.DataFetcher;
import org.jooq.DSLContext;

public class FilmGeneratedResolver implements FilmResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<Film> sequel(Film film, DataFetchingEnvironment env) throws Exception {
        return new DataFetcher(env, this.ctx).load("sequelForFilm", film.getId(), (ctx, ids, selectionSet) -> FilmDBQueries.sequelForFilm(ctx, ids, selectionSet));
    }
}
