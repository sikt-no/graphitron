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
import org.jooq.DSLContext;

public class EditFilmGeneratedResolver implements EditFilmMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<Film> editFilm(EditInputLevel1 input, DataFetchingEnvironment env)
            throws Exception {
        var transform = new RecordTransformer(env, this.ctx);

        var inputRecord = transform.editInputLevel1ToJavaRecord(input, "input");

        var testFilmService = new TestFilmService(transform.getCtx());
        var editFilm = testFilmService.editFilm(inputRecord);

        var film = transform.filmRecordToGraphType(editFilm, "");

        return CompletableFuture.completedFuture(film);
    }
}
