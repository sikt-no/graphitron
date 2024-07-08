package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.EditFilmRatingNoConverterIterableDBQueries;
import fake.code.generated.transform.RecordTransformer;
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
import org.jooq.DSLContext;

public class EditFilmRatingNoConverterIterableGeneratedResolver implements EditFilmRatingNoConverterIterableMutationResolver {
    @Inject
    DSLContext ctx;

    @Override
    public CompletableFuture<ListedResponse> editFilmRatingNoConverterIterable(
            List<FilmInput1> input, DataFetchingEnvironment env) throws Exception {
        var transform = new RecordTransformer(env, this.ctx);

        var inputRecordList = transform.filmInput1ToJOOQRecord(input, "input");

        var rowsUpdated = EditFilmRatingNoConverterIterableDBQueries.editFilmRatingNoConverterIterable(transform.getCtx(), inputRecordList);

        var listedResponse = new ListedResponse();
        listedResponse.setIds(inputRecordList.stream().map(it -> it.getId()).collect(Collectors.toList()));

        return CompletableFuture.completedFuture(listedResponse);
    }
}
