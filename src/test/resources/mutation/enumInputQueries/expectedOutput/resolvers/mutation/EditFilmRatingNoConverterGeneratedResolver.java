package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.EditFilmRatingNoConverterDBQueries;
import fake.graphql.example.package.api.EditFilmRatingNoConverterMutationResolver;
import fake.graphql.example.package.model.FilmInput1;
import fake.graphql.example.package.model.RatingNoConverter;
import fake.graphql.example.package.model.Response;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphql.helpers.arguments.Arguments;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.FilmRecord;
import org.jooq.DSLContext;

public class EditFilmRatingNoConverterGeneratedResolver implements EditFilmRatingNoConverterMutationResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private EditFilmRatingNoConverterDBQueries editFilmRatingNoConverterDBQueries;

    @Override
    public CompletableFuture<Response> editFilmRatingNoConverter(FilmInput1 input,
            DataFetchingEnvironment env) throws Exception {
        var ctx = env.getLocalContext() == null ? this.ctx : (DSLContext) env.getLocalContext();
        var flatArguments = Arguments.flattenArgumentKeys(env.getArguments());

        var inputRecord = new FilmRecord();
        inputRecord.attach(ctx.configuration());

        if (input != null) {
            if (flatArguments.contains("input/rating")) {
                inputRecord.setRating(input.getRating() == null ? null : Map.of(RatingNoConverter.G, "G", RatingNoConverter.PG, "PG", RatingNoConverter.R, "R").getOrDefault(input.getRating(), null));
            }
            if (flatArguments.contains("input/id")) {
                inputRecord.setId(input.getId());
            }
        }

        var rowsUpdated = editFilmRatingNoConverterDBQueries.editFilmRatingNoConverter(ctx, inputRecord);

        var response = new Response();
        response.setId(inputRecord.getId());

        return CompletableFuture.completedFuture(response);
    }
}