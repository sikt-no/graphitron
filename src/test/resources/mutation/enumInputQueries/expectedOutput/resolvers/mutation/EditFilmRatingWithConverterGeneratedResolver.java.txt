package fake.code.generated.resolvers.mutation;

import fake.code.generated.queries.mutation.EditFilmRatingWithConverterDBQueries;
import fake.graphql.example.package.api.EditFilmRatingWithConverterMutationResolver;
import fake.graphql.example.package.model.FilmInput2;
import fake.graphql.example.package.model.Rating;
import fake.graphql.example.package.model.Response;
import graphql.schema.DataFetchingEnvironment;
import java.lang.Exception;
import java.lang.Override;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import no.fellesstudentsystem.graphitron.enums.RatingTest;
import no.fellesstudentsystem.graphql.helpers.arguments.Arguments;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.FilmRecord;
import org.jooq.DSLContext;

public class EditFilmRatingWithConverterGeneratedResolver implements EditFilmRatingWithConverterMutationResolver {
    @Inject
    DSLContext ctx;

    @Inject
    private EditFilmRatingWithConverterDBQueries editFilmRatingWithConverterDBQueries;

    @Override
    public CompletableFuture<Response> editFilmRatingWithConverter(FilmInput2 input,
            DataFetchingEnvironment env) throws Exception {
        var ctx = env.getLocalContext() == null ? this.ctx : (DSLContext) env.getLocalContext();
        var flatArguments = Arguments.flattenArgumentKeys(env.getArguments());

        var inputRecord = new FilmRecord();
        inputRecord.attach(ctx.configuration());

        if (input != null) {
            if (flatArguments.contains("input/rating")) {
                inputRecord.setRating(input.getRating() == null ? null : Map.of(Rating.R, RatingTest.R, Rating.G, RatingTest.G, Rating.PG, RatingTest.PG).getOrDefault(input.getRating(), null));
            }
            if (flatArguments.contains("input/id")) {
                inputRecord.setId(input.getId());
            }
        }

        var rowsUpdated = editFilmRatingWithConverterDBQueries.editFilmRatingWithConverter(ctx, inputRecord);

        var response = new Response();
        response.setId(inputRecord.getId());

        return CompletableFuture.completedFuture(response);
    }
}