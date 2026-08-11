package no.sikt.graphitron.sakila.example.app;

import graphql.language.OperationDefinition.Operation;
import graphql.parser.InvalidSyntaxException;
import jakarta.ws.rs.core.Response;
import no.sikt.graphitron.jakarta.rest.GraphqlHttpHandler;
import no.sikt.graphitron.jakarta.rest.OperationPolicy;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The operation guard as a decision table: which operation a document resolves to
 * ({@link GraphqlHttpHandler#resolveOperation}) and whether a policy permits it
 * ({@link OperationPolicy#permits}). Named for the guard rather than either collaborator, because
 * the decision under test is the pair; resolution and permission are separately uninteresting.
 *
 * <p>Unit tier deliberately. The guard is a pure decision over {@code (query, operationName,
 * policy)}, so observing it through a Quarkus boot, live Postgres, JAX-RS routing, JSON-B, content
 * negotiation and the engine would be plumbing around three arguments.
 * {@link MountedEndpointTest} keeps the cases that genuinely need a container: that this decision
 * reaches the wire with the right status, media type and body.
 *
 * <p>It lives in this module rather than beside the code it covers because
 * {@code graphitron-jakarta-rest} is a runtime artifact carrying no {@code @Test} classes; adding
 * them would put test-scope JUnit and a dependency on {@code graphitron}'s tier-annotation test-jar
 * under a jar that today depends on graphql-java and four provided APIs and nothing else.
 * {@code ScatterSingleByIdxTest} is the standing precedent.
 */
@UnitTier
class OperationGuardTest {

    private static final OperationPolicy QUERIES_ONLY =
        OperationPolicy.queriesOnly(Response.Status.BAD_REQUEST);

    // ===== resolution feeding permission =====

    @Test
    @DisplayName("A mutation is refused by a queries-only policy.")
    void mutationIsRefused() {
        var operation = GraphqlHttpHandler.resolveOperation("mutation { __typename }", null);

        assertThat(operation).isEqualTo(Operation.MUTATION);
        assertThat(QUERIES_ONLY.permits(operation)).isFalse();
    }

    @Test
    @DisplayName("A subscription is refused too: the guard is 'not a query', not a mutation special case.")
    void subscriptionIsRefused() {
        var operation = GraphqlHttpHandler.resolveOperation("subscription { __typename }", null);

        assertThat(operation).isEqualTo(Operation.SUBSCRIPTION);
        assertThat(QUERIES_ONLY.permits(operation)).isFalse();
    }

    @Test
    @DisplayName("A plain query is permitted.")
    void queryIsPermitted() {
        var operation = GraphqlHttpHandler.resolveOperation("{ __typename }", null);

        assertThat(operation).isEqualTo(Operation.QUERY);
        assertThat(QUERIES_ONLY.permits(operation)).isTrue();
    }

    @Test
    @DisplayName("operationName selecting the mutation in a mixed document resolves to the mutation, so the policy refuses it.")
    void operationNameSelectsTheMutation() {
        // The smuggling case: the engine honours operationName, so judging anything but the
        // resolved operation would let this through.
        var operation = GraphqlHttpHandler.resolveOperation(
            "query A { __typename } mutation B { __typename }", "B");

        assertThat(operation).isEqualTo(Operation.MUTATION);
        assertThat(QUERIES_ONLY.permits(operation)).isFalse();
    }

    @Test
    @DisplayName("operationName selecting the query in the same document is permitted: refusing the whole document would break a legitimate request.")
    void operationNameSelectsTheQuery() {
        var operation = GraphqlHttpHandler.resolveOperation(
            "query A { __typename } mutation B { __typename }", "A");

        assertThat(operation).isEqualTo(Operation.QUERY);
        assertThat(QUERIES_ONLY.permits(operation)).isTrue();
    }

    @Test
    @DisplayName("No operationName resolves to the first operation in the document.")
    void noOperationNameResolvesToTheFirst() {
        assertThat(GraphqlHttpHandler.resolveOperation(
            "mutation B { __typename } query A { __typename }", null))
            .isEqualTo(Operation.MUTATION);
        assertThat(GraphqlHttpHandler.resolveOperation(
            "query A { __typename } mutation B { __typename }", null))
            .isEqualTo(Operation.QUERY);
    }

    // ===== the unresolvable cases, which belong to the engine =====

    @Test
    @DisplayName("An operationName matching nothing resolves to null: no rejection is possible, and graphql-java answers with a request error.")
    void unmatchedOperationNameResolvesToNull() {
        assertThat(GraphqlHttpHandler.resolveOperation(
            "query A { __typename } mutation B { __typename }", "X")).isNull();
    }

    @Test
    @DisplayName("A fragment-only document resolves to null: same fall-through to the engine.")
    void fragmentOnlyDocumentResolvesToNull() {
        assertThat(GraphqlHttpHandler.resolveOperation(
            "fragment F on Query { __typename }", null)).isNull();
    }

    @Test
    @DisplayName("An unparseable document propagates graphql-java's parse failure, which the pipeline turns into 400.")
    void unparseableDocumentPropagates() {
        assertThatThrownBy(() -> GraphqlHttpHandler.resolveOperation("{ customers ", null))
            .isInstanceOf(InvalidSyntaxException.class);
    }

    // ===== the policy value itself =====

    @Test
    @DisplayName("allowing({QUERY, MUTATION}) is the read-write endpoint: subscriptions refused, the rest permitted.")
    void allowingQueriesAndMutations() {
        var readWrite = OperationPolicy.allowing(
            EnumSet.of(Operation.QUERY, Operation.MUTATION),
            Response.Status.NOT_IMPLEMENTED,
            "This endpoint serves no subscription transport.");

        assertThat(readWrite.permits(Operation.QUERY)).isTrue();
        assertThat(readWrite.permits(Operation.MUTATION)).isTrue();
        assertThat(readWrite.permits(Operation.SUBSCRIPTION)).isFalse();
    }

    @Test
    @DisplayName("A later change to the caller's set does not change the policy: allowing copies it.")
    void allowedSetIsCopied() {
        var mutable = EnumSet.of(Operation.QUERY);
        var policy = OperationPolicy.allowing(mutable, Response.Status.BAD_REQUEST, null);

        mutable.add(Operation.MUTATION);

        assertThat(policy.permits(Operation.MUTATION)).isFalse();
    }

    @Test
    @DisplayName("Factory validation: an empty allowed set, and a status outside the client/server error families, are refused.")
    void factoriesValidateTheirArguments() {
        assertThatThrownBy(() -> OperationPolicy.allowing(
            EnumSet.noneOf(Operation.class), Response.Status.BAD_REQUEST, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("empty");

        // StatusType narrows the type but not the value: without the family check this compiles
        // into a refusal no client can read as one.
        assertThatThrownBy(() -> OperationPolicy.queriesOnly(Response.Status.OK))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("200");

        // Both error families stay open, so 501 remains available for "no subscription transport".
        assertThat(OperationPolicy.queriesOnly(Response.Status.NOT_IMPLEMENTED)).isNotNull();

        assertThatThrownBy(() -> OperationPolicy.queriesOnly(Response.Status.BAD_REQUEST, "  "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("blank");
    }
}
