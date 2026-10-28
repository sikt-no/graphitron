package no.sikt.graphitron.rewrite.test.internal;

import graphql.ErrorType;
import graphql.GraphQLContext;
import graphql.GraphQLError;
import graphql.Scalars;
import graphql.execution.ExecutionStepInfo;
import graphql.execution.MergedField;
import graphql.execution.ResultPath;
import graphql.language.Field;
import graphql.language.SourceLocation;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
import no.sikt.graphitron.generated.schema.ErrorRouter;
import no.sikt.graphitron.generated.schema.FetcherErrorResponse;
import no.sikt.graphitron.generated.schema.GraphitronClientException;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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
 * correlation id. A configured {@link FetcherErrorResponse} rewords the surfaced client error
 * through {@code clientErrorMessage} and nothing else.
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

    @Test
    void unclaimedClientException_withAResponseThatRewords_carriesItsMessageAndNothingElseChanges() {
        var clientError = new GraphitronClientException("Tenant '2' is not permitted for this request.");
        var thrown = new RuntimeException("wrapper", clientError);
        var received = new ArrayList<Throwable>();
        FetcherErrorResponse response = new FetcherErrorResponse() {
            @Override
            public String clientErrorMessage(GraphitronClientException error, DataFetchingEnvironment env) {
                received.add(error);
                return "You do not have access to data from institusjonsnrEier = 2";
            }
        };

        var plain = singleError(ErrorRouter.surfaceClientErrorOrRedact(thrown, environment()));
        var reworded = singleError(ErrorRouter.surfaceClientErrorOrRedact(thrown, environment(response)));

        assertThat(reworded.getMessage()).isEqualTo("You do not have access to data from institusjonsnrEier = 2");
        assertThat(received).as("the hook receives the client exception from the cause chain, not the wrapper")
            .singleElement().isSameAs(clientError);
        assertThat(reworded.getErrorType()).isEqualTo(plain.getErrorType());
        assertThat(reworded.getExtensions()).isEqualTo(plain.getExtensions());
        assertThat(reworded.getPath()).isEqualTo(plain.getPath());
        assertThat(reworded.getLocations()).isEqualTo(plain.getLocations());
    }

    @Test
    void unclaimedClientException_withAResponseThatThrows_carriesItsOwnMessage() {
        var clientError = new GraphitronClientException("Tenant '2' is not permitted for this request.");
        FetcherErrorResponse response = new FetcherErrorResponse() {
            @Override
            public String clientErrorMessage(GraphitronClientException error, DataFetchingEnvironment env) {
                throw new IllegalStateException("hook failure");
            }
        };

        assertThat(singleError(ErrorRouter.surfaceClientErrorOrRedact(clientError, environment(response)))
            .getMessage()).isEqualTo(clientError.getMessage());
    }

    @Test
    void unclaimedClientException_withAResponseThatAnswersNull_carriesItsOwnMessage() {
        var clientError = new GraphitronClientException("Tenant '2' is not permitted for this request.");
        FetcherErrorResponse response = new FetcherErrorResponse() {
            @Override
            public String clientErrorMessage(GraphitronClientException error, DataFetchingEnvironment env) {
                return null;
            }
        };

        assertThat(singleError(ErrorRouter.surfaceClientErrorOrRedact(clientError, environment(response)))
            .getMessage()).isEqualTo(clientError.getMessage());
    }

    @Test
    void unclaimedClientException_withAResponseThatOverridesOnlyMatchedMessage_carriesItsOwnMessage() {
        var clientError = new GraphitronClientException("Tenant '2' is not permitted for this request.");
        FetcherErrorResponse response = new FetcherErrorResponse() {
            @Override
            public String matchedMessage(Throwable source, String defaultMessage, String reference,
                                         DataFetchingEnvironment env) {
                return "rewritten";
            }
        };

        assertThat(singleError(ErrorRouter.surfaceClientErrorOrRedact(clientError, environment(response)))
            .getMessage()).isEqualTo(clientError.getMessage());
    }

    @Test
    void unclaimedInternalFault_withAResponse_isNotAClientErrorAndReadsUnmatchedMessage() {
        var asked = new ArrayList<String>();
        FetcherErrorResponse response = new FetcherErrorResponse() {
            @Override
            public String clientErrorMessage(GraphitronClientException error, DataFetchingEnvironment env) {
                asked.add("clientErrorMessage");
                return "client";
            }
            @Override
            public String unmatchedMessage(Throwable thrown, String reference, DataFetchingEnvironment env) {
                return "unmatched";
            }
        };

        var error = singleError(ErrorRouter.surfaceClientErrorOrRedact(
            new IllegalStateException("internal detail"), environment(response)));

        assertThat(error.getMessage()).isEqualTo("unmatched");
        assertThat(asked).isEmpty();
    }

    private static GraphQLError singleError(graphql.execution.DataFetcherResult<?> result) {
        assertThat(result.getData()).isNull();
        List<GraphQLError> errors = result.getErrors();
        assertThat(errors).hasSize(1);
        return errors.get(0);
    }

    private static DataFetchingEnvironment environment(FetcherErrorResponse response) {
        return DataFetchingEnvironmentImpl.newDataFetchingEnvironment(environment())
            .graphQLContext(GraphQLContext.newContext().of(FetcherErrorResponse.class, response).build())
            .build();
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
