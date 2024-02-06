package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.EditFilmRatingNoConverterIterableDBQueries;
import fake.code.generated.transform.InputTransformer;
import fake.graphql.example.api.EditFilmRatingNoConverterIterableMutationResolver;
import fake.graphql.example.model.FilmInput1;
import fake.graphql.example.model.ListedResponse;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.resolvers.ResolverHelpers;
import org.jooq.DSLContext;

public class EditFilmRatingNoConverterIterableGeneratedResolver implements EditFilmRatingNoConverterIterableMutationResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private EditFilmRatingNoConverterIterableDBQueries editFilmRatingNoConverterIterableDBQueries;

    @Override
    public CompletableFuture<ListedResponse> editFilmRatingNoConverterIterable(
            List<FilmInput1> input, DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);

        var transform = new InputTransformer(env, ctx);

        var inputRecordList = transform.filmInput1ToJOOQRecord(input, "input");

        var rowsUpdated = editFilmRatingNoConverterIterableDBQueries.editFilmRatingNoConverterIterable(ctx, inputRecordList);

        var listedResponse = new ListedResponse();
        listedResponse.setIds(inputRecordList.stream().map(it -> it.getId()).collect(Collectors.toList()));

        return CompletableFuture.completedFuture(listedResponse);
    }
}