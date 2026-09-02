package no.sikt.graphitron.rewrite.validation;

import no.sikt.graphitron.rewrite.GraphitronSchemaValidator;
import no.sikt.graphitron.model.diagnostics.RejectionKind;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.model.diagnostics.ValidationError;
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

    // ===== structural edge naming =====

    @Test
    void structuralConnection_withNonConventionEdgeName_classifiesAndValidatesClean() {
        // The classifier reads a structural connection's edge type off the edges field's actual
        // element type, so the author owns the name; no <Name>Connection to <Name>Edge naming
        // convention applies. (The old convention-derived lookup registered a phantom EdgeType
        // with no schema form for this shape, propped up by a validator rejection; both retired
        // when connection synthesis became a coordinate-keyed relation.)
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { id: ID }
            type FilmsConnection { edges: [FilmEdge!]! nodes: [Film!]! pageInfo: PageInfo! }
            type FilmEdge { cursor: String! node: Film! }
            type PageInfo { hasNextPage: Boolean! hasPreviousPage: Boolean! startCursor: String endCursor: String }
            type Query { films: FilmsConnection }
            """);
        assertThat(schema.types().get("FilmEdge"))
            .isInstanceOfSatisfying(no.sikt.graphitron.rewrite.model.GraphitronType.EdgeType.class,
                edge -> assertThat(edge.schemaType()).isNotNull());
        assertThat(schema.types().get("FilmsEdge")).isNull();
        var errors = new GraphitronSchemaValidator().validate(schema);
        assertThat(errors)
            .as("no edge-related validation error may surface for an author-named edge type")
            .noneMatch(e -> e.coordinate().contains("Edge") || e.message().contains("Edge"));
    }

    private static boolean noTotalCountErrors(no.sikt.graphitron.rewrite.GraphitronSchema schema) {
        List<ValidationError> errors = new GraphitronSchemaValidator().validate(schema);
        return errors.stream().noneMatch(e -> e.message().contains("totalCount"));
    }
}
