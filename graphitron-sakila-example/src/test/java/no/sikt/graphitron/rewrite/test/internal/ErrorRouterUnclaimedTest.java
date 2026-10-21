package no.sikt.graphitron.rewrite.test.internal;

import graphql.ErrorType;
import graphql.Scalars;
import graphql.execution.ExecutionStepInfo;
import graphql.execution.MergedField;
import graphql.execution.ResultPath;
import graphql.language.Field;
import graphql.language.SourceLocation;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
import no.sikt.graphitron.generated.schema.ErrorRouter;
import no.sikt.graphitron.generated.schema.GraphitronClientException;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;


import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct coverage for the unclaimed tail of an outcome's catch arm: {@link ErrorRouter#claim}
 * answering no mapping, then {@link ErrorRouter#surfaceClientErrorOrRedact}. No example-schema
 * field on that transport can be made to throw a {@link GraphitronClientException} from client
 * input, so the router is called here with hand-built throwables and mapping tables.
 *
 * <p>The contract: authored mappings are tried first; when none claims the throw, a client
 * exception anywhere in the cause chain surfaces its message as the field's error, at the field's
 * path and location, exactly as on a fetcher without a channel, and anything else redacts to a
 * correlation id.
 */
@UnitTier
class ErrorRouterUnclaimedTest {

    private static final ErrorRouter.Mapping[] UNRELATED_MAPPINGS = {
        new ErrorRouter.ExceptionMapping(UnsupportedOperationException.class, null, null)
    };

    private static final SourceLocation FIELD_LOCATION = new SourceLocation(3, 5);

    @Test
    void unclaimedClientException_inTheCauseChain_surfacesItsOwnMessage() {
        var clientError = new GraphitronClientException("Tenant '2' is not permitted for this request.");

        var thrown = new RuntimeException("wrapper", clientError);
        assertThat(ErrorRouter.claim(thrown, UNRELATED_MAPPINGS, environment())).isNull();
        var result = ErrorRouter.surfaceClientErrorOrRedact(thrown, environment());

        assertThat(result.getData()).isNull();
        assertThat(result.getErrors())
            .singleElement()
            .satisfies(error -> {
                assertThat(error.getMessage()).isEqualTo(clientError.getMessage());
                assertThat(error.getErrorType()).isEqualTo(ErrorType.DataFetchingException);
                assertThat(error.getPath()).containsExactly("film");
                assertThat(error.getLocations()).containsExactly(FIELD_LOCATION);
            });
    }

    @Test
    void unclaimedInternalFault_redactsToACorrelationId() {
        var thrown = new IllegalStateException("internal detail");
        assertThat(ErrorRouter.claim(thrown, UNRELATED_MAPPINGS, environment())).isNull();
        var result = ErrorRouter.surfaceClientErrorOrRedact(thrown, environment());

        assertThat(result.getData()).isNull();
        assertThat(result.getErrors())
            .singleElement()
            .satisfies(error -> assertThat(error.getMessage())
                .startsWith("An error occurred. Reference: ")
                .doesNotContain("internal detail"));
    }

    @Test
    void anAuthoredMappingThatMatchesTheClientException_stillClaimsIt() {
        var clientError = new GraphitronClientException("Tenant '2' is not permitted for this request.");
        ErrorRouter.Mapping[] claiming = {
            new ErrorRouter.ExceptionMapping(graphql.GraphqlErrorException.class, null, null)
        };

        assertThat(ErrorRouter.claim(new RuntimeException("wrapper", clientError), claiming, environment()))
            .isSameAs(clientError);
    }

    private static DataFetchingEnvironment environment() {
        return DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
            .mergedField(MergedField.newMergedField(Field.newField("film").sourceLocation(FIELD_LOCATION).build()).build())
            .executionStepInfo(ExecutionStepInfo.newExecutionStepInfo()
                .type(Scalars.GraphQLString)
                .path(ResultPath.rootPath().segment("film"))
                .build())
            .build();
    }
}
