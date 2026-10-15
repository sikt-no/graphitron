package no.sikt.graphitron.rewrite.test.internal;

import graphql.Scalars;
import graphql.execution.ExecutionStepInfo;
import graphql.execution.MergedField;
import graphql.execution.ResultPath;
import graphql.language.Field;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
import no.sikt.graphitron.generated.schema.ErrorRouter;
import no.sikt.graphitron.generated.schema.GraphitronClientException;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Direct coverage for the unmatched tail of the generated {@link ErrorRouter#dispatch}, the
 * catch arm of every fetcher on the developer-payload-class error channel. No example-schema field
 * on that transport can be made to throw a {@link GraphitronClientException} from client input,
 * so the router is called here with hand-built throwables and mapping tables.
 *
 * <p>The contract: authored mappings are tried first; when none claims the throw, a client
 * exception anywhere in the cause chain surfaces as the field's error, exactly as on a fetcher
 * without a channel, and anything else redacts to a correlation id.
 */
@UnitTier
class ErrorRouterDispatchUnmatchedTest {

    private static final ErrorRouter.Mapping[] UNRELATED_MAPPINGS = {
        new ErrorRouter.ExceptionMapping(UnsupportedOperationException.class, null, null)
    };

    private static final Function<List<?>, Object> PAYLOAD_FACTORY_MUST_NOT_RUN =
        errors -> fail("no mapping claims the throw, so no payload is built: " + errors);

    @Test
    void unclaimedClientException_inTheCauseChain_surfacesItsOwnMessage() {
        var clientError = new GraphitronClientException("Tenant '2' is not permitted for this request.");

        var result = ErrorRouter.dispatch(new RuntimeException("wrapper", clientError),
            UNRELATED_MAPPINGS, environment(), PAYLOAD_FACTORY_MUST_NOT_RUN);

        assertThat(result.getData()).isNull();
        assertThat(result.getErrors()).containsExactly(clientError);
    }

    @Test
    void unclaimedInternalFault_redactsToACorrelationId() {
        var result = ErrorRouter.dispatch(new IllegalStateException("internal detail"),
            UNRELATED_MAPPINGS, environment(), PAYLOAD_FACTORY_MUST_NOT_RUN);

        assertThat(result.getData()).isNull();
        assertThat(result.getErrors())
            .singleElement()
            .satisfies(error -> assertThat(error.getMessage())
                .startsWith("An error occurred. Reference: ")
                .doesNotContain("internal detail"));
    }

    @Test
    void anAuthoredMappingThatMatchesTheClientException_stillPlacesItIntoThePayload() {
        var clientError = new GraphitronClientException("Tenant '2' is not permitted for this request.");
        ErrorRouter.Mapping[] claiming = {
            new ErrorRouter.ExceptionMapping(graphql.GraphqlErrorException.class, null, null)
        };

        var result = ErrorRouter.dispatch(new RuntimeException("wrapper", clientError),
            claiming, environment(), errors -> errors);

        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getData()).isEqualTo(List.of(clientError));
    }

    private static DataFetchingEnvironment environment() {
        return DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
            .mergedField(MergedField.newMergedField(Field.newField("film").build()).build())
            .executionStepInfo(ExecutionStepInfo.newExecutionStepInfo()
                .type(Scalars.GraphQLString)
                .path(ResultPath.rootPath().segment("film"))
                .build())
            .build();
    }
}
