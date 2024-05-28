package fake.code.generated.resolvers.mutation;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.EditFilmMutationResolver;
import fake.graphql.example.model.EditFilmResponseLevel1;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.lang.String;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphitron.services.TestFilmService;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import org.jooq.DSLContext;

public class EditFilmGeneratedResolver implements EditFilmMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<EditFilmResponseLevel1> editFilm(String id,
                                                              DataFetchingEnvironment env) throws Exception {
        var testFilmService = new TestFilmService(ResolverHelpers.selectContext(env, this.ctx));
        var transform = new RecordTransformer(env, this.ctx);

        var editFilm = testFilmService.editFilm(id);

        var editFilmResponseLevel1 = transform.editFilmResponseLevel1ToGraphType(editFilm, "");

        return CompletableFuture.completedFuture(editFilmResponseLevel1);
    }
}
