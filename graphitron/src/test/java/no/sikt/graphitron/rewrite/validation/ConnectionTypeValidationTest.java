package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.GraphitronSchemaValidator;
import no.sikt.graphitron.rewrite.RejectionKind;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.ValidationError;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;

/**
 * Validates the {@code totalCount} field-shape check in
 * {@link GraphitronSchemaValidator#validateConnectionType}: structural connections that declare
 * {@code totalCount} with a non-{@code Int} type fail the build with an
 * {@link RejectionKind#INVALID_SCHEMA} error. Synthesised connections always carry
 * {@code totalCount: Int}, so they never trip the check.
 */
@UnitTier
class ConnectionTypeValidationTest {

    @Test
    void synthesisedConnection_passesValidation() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { id: ID }
            type Query { films: [Film!]! @asConnection }
            """);
        assertThat(noTotalCountErrors(schema))
            .as("synthesised totalCount: Int should not trip the validator")
            .isTrue();
    }

    @Test
    void structuralConnection_withTotalCountInt_passesValidation() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { id: ID }
            type FilmsConnection { edges: [FilmsEdge!]! nodes: [Film!]! pageInfo: PageInfo! totalCount: Int }
            type FilmsEdge { cursor: String! node: Film! }
            type PageInfo { hasNextPage: Boolean! hasPreviousPage: Boolean! startCursor: String endCursor: String }
            type Query { films: FilmsConnection }
            """);
        assertThat(noTotalCountErrors(schema))
            .as("structural totalCount: Int should not trip the validator")
            .isTrue();
    }

    @Test
    void structuralConnection_withTotalCountIntNonNull_passesValidation() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { id: ID }
            type FilmsConnection { edges: [FilmsEdge!]! nodes: [Film!]! pageInfo: PageInfo! totalCount: Int! }
            type FilmsEdge { cursor: String! node: Film! }
            type PageInfo { hasNextPage: Boolean! hasPreviousPage: Boolean! startCursor: String endCursor: String }
            type Query { films: FilmsConnection }
            """);
        assertThat(noTotalCountErrors(schema))
            .as("Int! unwraps to Int; should not trip the validator")
            .isTrue();
    }

    @Test
    void structuralConnection_withoutTotalCount_passesValidation() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { id: ID }
            type FilmsConnection { edges: [FilmsEdge!]! nodes: [Film!]! pageInfo: PageInfo! }
            type FilmsEdge { cursor: String! node: Film! }
            type PageInfo { hasNextPage: Boolean! hasPreviousPage: Boolean! startCursor: String endCursor: String }
            type Query { films: FilmsConnection }
            """);
        assertThat(noTotalCountErrors(schema))
            .as("absent totalCount field is allowed")
            .isTrue();
    }

    @Test
    void structuralConnection_withTotalCountString_failsValidation() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { id: ID }
            type FilmsConnection { edges: [FilmsEdge!]! nodes: [Film!]! pageInfo: PageInfo! totalCount: String }
            type FilmsEdge { cursor: String! node: Film! }
            type PageInfo { hasNextPage: Boolean! hasPreviousPage: Boolean! startCursor: String endCursor: String }
            type Query { films: FilmsConnection }
            """);
        var errors = new GraphitronSchemaValidator().validate(schema);
        assertThat(errors)
            .filteredOn(e -> e.coordinate().equals("FilmsConnection.totalCount"))
            .singleElement()
            .satisfies(e -> {
                assertThat(e.kind()).isEqualTo(RejectionKind.INVALID_SCHEMA);
                assertThat(e.message()).contains("must be of type 'Int'");
                assertThat(e.message()).contains("String");
                // Coordinate and location both point at the field, not the type.
                assertThat(e.location()).isNotNull();
            });
    }

    @Test
    void structuralConnection_withTotalCountListInt_failsValidation() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { id: ID }
            type FilmsConnection { edges: [FilmsEdge!]! nodes: [Film!]! pageInfo: PageInfo! totalCount: [Int!] }
            type FilmsEdge { cursor: String! node: Film! }
            type PageInfo { hasNextPage: Boolean! hasPreviousPage: Boolean! startCursor: String endCursor: String }
            type Query { films: FilmsConnection }
            """);
        var errors = new GraphitronSchemaValidator().validate(schema);
        assertThat(errors)
            .filteredOn(e -> e.coordinate().equals("FilmsConnection.totalCount"))
            .singleElement()
            .satisfies(e -> assertThat(e.kind()).isEqualTo(RejectionKind.INVALID_SCHEMA));
    }

    // ===== structural edge naming convention =====

    @Test
    void structuralConnection_withConventionNamedEdge_hasNoEdgeNamingError() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { id: ID }
            type FilmsConnection { edges: [FilmsEdge!]! nodes: [Film!]! pageInfo: PageInfo! }
            type FilmsEdge { cursor: String! node: Film! }
            type PageInfo { hasNextPage: Boolean! hasPreviousPage: Boolean! startCursor: String endCursor: String }
            type Query { films: FilmsConnection }
            """);
        var errors = new GraphitronSchemaValidator().validate(schema);
        assertThat(errors).noneMatch(e -> e.message().contains("naming convention"));
    }

    @Test
    void structuralConnection_withMisnamedEdgeType_failsValidation() {
        // The classifier derives a structural connection's edge type by naming convention
        // (FilmsConnection pairs with FilmsEdge). An edge declared under any other name leaves
        // the derived EdgeType without a schema form; before this rejection the emitted
        // GraphitronSchema referenced a FilmsEdgeType class that was never generated, surfacing
        // as a missing-symbol error at the consumer's javac.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { id: ID }
            type FilmsConnection { edges: [FilmEdge!]! nodes: [Film!]! pageInfo: PageInfo! }
            type FilmEdge { cursor: String! node: Film! }
            type PageInfo { hasNextPage: Boolean! hasPreviousPage: Boolean! startCursor: String endCursor: String }
            type Query { films: FilmsConnection }
            """);
        var errors = new GraphitronSchemaValidator().validate(schema);
        assertThat(errors)
            .filteredOn(e -> e.coordinate().equals("FilmsEdge"))
            .singleElement()
            .satisfies(e -> {
                assertThat(e.message()).contains("naming convention");
                assertThat(e.message()).contains("FilmsEdge");
            });
    }

    private static boolean noTotalCountErrors(no.sikt.graphitron.rewrite.GraphitronSchema schema) {
        List<ValidationError> errors = new GraphitronSchemaValidator().validate(schema);
        return errors.stream().noneMatch(e -> e.message().contains("totalCount"));
    }
}
