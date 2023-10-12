package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.EditFilmRatingNoConverterIterableDBQueries;
import fake.graphql.example.package.api.EditFilmRatingNoConverterIterableMutationResolver;
import fake.graphql.example.package.model.FilmInput1;
import fake.graphql.example.package.model.ListedResponse;
import fake.graphql.example.package.model.RatingNoConverter;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.arguments.Arguments;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.FilmRecord;
import org.jooq.DSLContext;

public class EditFilmRatingNoConverterIterableGeneratedResolver implements EditFilmRatingNoConverterIterableMutationResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private EditFilmRatingNoConverterIterableDBQueries editFilmRatingNoConverterIterableDBQueries;

    @Override
    public CompletableFuture<ListedResponse> editFilmRatingNoConverterIterable(
            List<FilmInput1> input, DataFetchingEnvironment env) throws Exception {
        var ctx = env.getLocalContext() == null ? this.ctx : (DSLContext) env.getLocalContext();
        var flatArguments = Arguments.flattenArgumentKeys(env.getArguments());

        List<FilmRecord> inputRecordList = new ArrayList<FilmRecord>();


        if (input != null) {
            for (int itInputIndex = 0; itInputIndex < input.size(); itInputIndex++) {
                var itInput = input.get(itInputIndex);
                if (itInput == null) continue;
                var inputRecord = new FilmRecord();
                inputRecord.attach(ctx.configuration());
                if (flatArguments.contains("input/rating")) {
                    inputRecord.setRating(itInput.getRating() == null ? null : Map.of(RatingNoConverter.G, "G", RatingNoConverter.PG, "PG", RatingNoConverter.R, "R").getOrDefault(itInput.getRating(), null));
                }
                if (flatArguments.contains("input/id")) {
                    inputRecord.setId(itInput.getId());
                }
                inputRecordList.add(inputRecord);
            }
        }

        var rowsUpdated = editFilmRatingNoConverterIterableDBQueries.editFilmRatingNoConverterIterable(ctx, inputRecordList);

        var listedResponse = new ListedResponse();
        listedResponse.setIds(inputRecordList.stream().map(it -> it.getId()).collect(Collectors.toList()));

        return CompletableFuture.completedFuture(listedResponse);
    }
}