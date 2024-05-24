package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.EditFilmRatingWithConverterIterableDBQueries;
import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.api.EditFilmRatingWithConverterIterableMutationResolver;
import fake.graphql.example.model.FilmInput2;
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

public class EditFilmRatingWithConverterIterableGeneratedResolver implements EditFilmRatingWithConverterIterableMutationResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private EditFilmRatingWithConverterIterableDBQueries editFilmRatingWithConverterIterableDBQueries;

    @Override
    public CompletableFuture<ListedResponse> editFilmRatingWithConverterIterable(
            List<FilmInput2> input, DataFetchingEnvironment env) throws Exception {
        var ctx = ResolverHelpers.selectContext(env, this.ctx);

        var transform = new RecordTransformer(env, this.ctx);

        var inputRecordList = transform.filmInput2ToJOOQRecord(input, "input");

        var rowsUpdated = editFilmRatingWithConverterIterableDBQueries.editFilmRatingWithConverterIterable(ctx, inputRecordList);

        var listedResponse = new ListedResponse();
        listedResponse.setIds(inputRecordList.stream().map(it -> it.getId()).collect(Collectors.toList()));

        return CompletableFuture.completedFuture(listedResponse);
    }
}