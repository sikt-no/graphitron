package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.EditFilmRatingWithConverterDBQueries;
import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.EditFilmRatingWithConverterMutationResolver;
import fake.graphql.example.model.FilmInput2;
import fake.graphql.example.model.Response;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import org.jooq.DSLContext;

public class EditFilmRatingWithConverterGeneratedResolver implements EditFilmRatingWithConverterMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<Response> editFilmRatingWithConverter(FilmInput2 input,
            DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);

        var transform = new RecordTransformer(env, this.ctx);

        var inputRecord = transform.filmInput2ToJOOQRecord(input, "input");

        var rowsUpdated = EditFilmRatingWithConverterDBQueries.editFilmRatingWithConverter(ctx, inputRecord);

        var response = new Response();
        response.setId(inputRecord.getId());

        return CompletableFuture.completedFuture(response);
    }
}
