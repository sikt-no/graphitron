package fake.code.generated.resolvers.mutation;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.EditFilmMutationResolver;
import fake.graphql.example.model.EditInputLevel1;
import fake.graphql.example.model.Film;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphitron.services.TestFilmService;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import org.jooq.DSLContext;

public class EditFilmGeneratedResolver implements EditFilmMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<Film> editFilm(EditInputLevel1 input, DataFetchingEnvironment env)
            throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);
        var testFilmService = new TestFilmService(ctx);
        var transform = new RecordTransformer(env, ctx);

        var inputRecord = transform.editInputLevel1ToJavaRecord(input, "input");

        var editFilm = testFilmService.editFilm(inputRecord);

        var film = transform.filmRecordToGraphType(editFilm, "");

        return CompletableFuture.completedFuture(film);
    }
}
