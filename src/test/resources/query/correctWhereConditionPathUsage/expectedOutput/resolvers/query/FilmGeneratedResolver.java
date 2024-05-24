package fake.code.generated.resolvers.query;

import fake.code.generated.queries.query.FilmDBQueries;
import fake.graphql.example.api.FilmResolver;
import fake.graphql.example.model.Film;
import fake.graphql.example.model.In;
import fake.graphql.example.model.Language;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
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
    public CompletableFuture<List<Language>> languages(Film film, List<String> s,
            DataFetchingEnvironment env) throws Exception {
        return new DataFetcher(env, this.ctx).loadNonNullable("languagesForFilm", film.getId(), (ctx, ids, selectionSet) -> filmDBQueries.languagesForFilm(ctx, ids, s, selectionSet));
    }

    @Override
    public CompletableFuture<List<Language>> languagesInput(Film film, In s,
            DataFetchingEnvironment env) throws Exception {
        return new DataFetcher(env, this.ctx).loadNonNullable("languagesInputForFilm", film.getId(), (ctx, ids, selectionSet) -> filmDBQueries.languagesInputForFilm(ctx, ids, s, selectionSet));
    }
}