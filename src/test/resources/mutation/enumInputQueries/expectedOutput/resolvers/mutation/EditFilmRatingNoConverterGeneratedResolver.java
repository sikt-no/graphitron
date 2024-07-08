package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.EditFilmRatingNoConverterDBQueries;
import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.EditFilmRatingNoConverterMutationResolver;
import fake.graphql.example.model.FilmInput1;
import fake.graphql.example.model.Response;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import org.jooq.DSLContext;

public class EditFilmRatingNoConverterGeneratedResolver implements EditFilmRatingNoConverterMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<Response> editFilmRatingNoConverter(FilmInput1 input,
            DataFetchingEnvironment env) throws Exception {
        var transform = new RecordTransformer(env, this.ctx);

        var inputRecord = transform.filmInput1ToJOOQRecord(input, "input");

        var rowsUpdated = EditFilmRatingNoConverterDBQueries.editFilmRatingNoConverter(transform.getCtx(), inputRecord);

        var response = new Response();
        response.setId(inputRecord.getId());

        return CompletableFuture.completedFuture(response);
    }
}
