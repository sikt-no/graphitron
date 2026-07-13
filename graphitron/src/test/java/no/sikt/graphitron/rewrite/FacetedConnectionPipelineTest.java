package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.FacetSpec;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R13 Phases 2+3: {@code @asFacet} through the full classification pipeline. The synthesis half
 * asserts that a faceted {@code @asConnection} carrier lands a populated
 * {@link GraphitronType.ConnectionType#facets()} plus the synthesised
 * {@link GraphitronType.FacetsType} / {@link GraphitronType.FacetValueType} entries; the rejection
 * half pins each misuse arm of {@code GraphitronSchemaBuilder.rejectFacetMisuse}.
 *
 * <p>Like {@link RejectNonIdNodeIdPipelineTest}, the rejection cases assert on the typed
 * {@link ValidationError} draining the schema's diagnostic channel: the misuse leaves no trace on
 * the classified model (the promoter's facet walk skips malformed applications), so the diagnostic
 * is the only surface.
 */
@PipelineTier
class FacetedConnectionPipelineTest {

    private static final String FACETED_CONNECTION = """
        type Film @table(name: "film") { title: String }
        input FilmFilter @table(name: "film") {
            title: [String!] @field(name: "title") @asFacet
            length: [Int] @field(name: "length") @asFacet
        }
        type Query {
            films(filter: FilmFilter): [Film!]! @asConnection @defaultOrder(primaryKey: true)
        }
        """;

    // ===== Synthesis through the pipeline =====

    @Test
    void asFacetOnConnectionFilter_populatesConnectionTypeFacets() {
        var schema = TestSchemaHelper.buildSchema(FACETED_CONNECTION);

        assertThat(schema.diagnostics())
            .as("a well-formed @asFacet schema must produce no facet diagnostics")
            .noneMatch(e -> e.message().contains("@asFacet"));
        assertThat(schema.types().get("QueryFilmsConnection"))
            .isInstanceOfSatisfying(GraphitronType.ConnectionType.class, conn ->
                assertThat(conn.facets()).containsExactly(
                    new FacetSpec("title", "title", "String", false, "StringFacetValue"),
                    new FacetSpec("length", "length", "Int", true, "IntFacetValueOrNull")));
        assertThat(schema.types().get("QueryFilmsConnectionFacets"))
            .isInstanceOfSatisfying(GraphitronType.FacetsType.class, ft ->
                assertThat(ft.connectionName()).isEqualTo("QueryFilmsConnection"));
        assertThat(schema.types().get("StringFacetValue"))
            .isInstanceOfSatisfying(GraphitronType.FacetValueType.class, fv -> {
                assertThat(fv.valueTypeName()).isEqualTo("String");
                assertThat(fv.valueNullable()).isFalse();
            });
        assertThat(schema.types().get("IntFacetValueOrNull"))
            .isInstanceOfSatisfying(GraphitronType.FacetValueType.class, fv -> {
                assertThat(fv.valueTypeName()).isEqualTo("Int");
                assertThat(fv.valueNullable()).isTrue();
            });
    }

    @Test
    void connectionWithoutAsFacet_hasEmptyFacets() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmFilter @table(name: "film") {
                title: [String!] @field(name: "title")
            }
            type Query {
                films(filter: FilmFilter): [Film!]! @asConnection @defaultOrder(primaryKey: true)
            }
            """);

        assertThat(schema.types().get("QueryFilmsConnection"))
            .isInstanceOfSatisfying(GraphitronType.ConnectionType.class, conn ->
                assertThat(conn.facets()).isEmpty());
        assertThat(schema.types().get("QueryFilmsConnectionFacets")).isNull();
    }

    // ===== Rejections (definition-keyed binding checks) =====

    @Test
    void asFacetWithoutFieldDirective_rejected() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmFilter @table(name: "film") {
                title: [String!] @asFacet
            }
            type Query {
                films(filter: FilmFilter): [Film!]! @asConnection @defaultOrder(primaryKey: true)
            }
            """);

        assertThat(schema.diagnostics())
            .anyMatch(e -> e.kind() == RejectionKind.INVALID_SCHEMA
                && e.message().contains("FilmFilter.title")
                && e.message().contains("@asFacet requires @field(name:)"));
    }

    @Test
    void asFacetOnConditionBoundField_rejected() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmFilter @table(name: "film") {
                title: [String!] @field(name: "title") @asFacet
                    @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "inputColumnCondition"})
            }
            type Query {
                films(filter: FilmFilter): [Film!]! @asConnection @defaultOrder(primaryKey: true)
            }
            """);

        assertThat(schema.diagnostics())
            .anyMatch(e -> e.kind() == RejectionKind.INVALID_SCHEMA
                && e.message().contains("FilmFilter.title")
                && e.message().contains("@asFacet supports only direct-column bindings"));
    }

    @Test
    void asFacetOnReferenceBoundField_rejected() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmFilter @table(name: "film") {
                languageName: [String!] @field(name: "name")
                    @reference(path: [{key: "film_language_id_fkey"}]) @asFacet
            }
            type Query {
                films(filter: FilmFilter): [Film!]! @asConnection @defaultOrder(primaryKey: true)
            }
            """);

        assertThat(schema.diagnostics())
            .anyMatch(e -> e.kind() == RejectionKind.INVALID_SCHEMA
                && e.message().contains("FilmFilter.languageName")
                && e.message().contains("@asFacet supports only direct-column bindings"));
    }

    @Test
    void asFacetOnIdField_rejected() {
        // Rejecting the ID type outright (not just @nodeId co-occurrence) also closes the
        // node-reference synthesis shim, which classifies a bare ID column-hit as a reference
        // carrier with no directive trace.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmFilter @table(name: "film") {
                filmId: [ID!] @field(name: "film_id") @asFacet
            }
            type Query {
                films(filter: FilmFilter): [Film!]! @asConnection @defaultOrder(primaryKey: true)
            }
            """);

        assertThat(schema.diagnostics())
            .anyMatch(e -> e.kind() == RejectionKind.INVALID_SCHEMA
                && e.message().contains("FilmFilter.filmId")
                && e.message().contains("@asFacet on an ID field is not supported"));
    }

    @Test
    void asFacetOnNonNullField_rejected() {
        // Phase 4 rationale: the generated filter-minus-self fragments suppress a facet's own
        // predicate by leaving its argument unset, which requires the binding to be optional; and
        // an always-active filter value could never show unfiltered pivot counts anyway.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmFilter @table(name: "film") {
                title: [String!]! @field(name: "title") @asFacet
            }
            type Query {
                films(filter: FilmFilter): [Film!]! @asConnection @defaultOrder(primaryKey: true)
            }
            """);

        assertThat(schema.diagnostics())
            .anyMatch(e -> e.kind() == RejectionKind.INVALID_SCHEMA
                && e.message().contains("FilmFilter.title")
                && e.message().contains("@asFacet requires a nullable"));
    }

    @Test
    void asFacetWithConnectionNameOverride_rejected() {
        // The facet emitters resolve the carrier's ConnectionType through the derived
        // ConnectionNaming.defaultConnectionName; the deprecated connectionName: override would
        // silently miss that lookup, so the combination is rejected.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmFilter @table(name: "film") {
                title: [String!] @field(name: "title") @asFacet
            }
            type Query {
                films(filter: FilmFilter): [Film!]!
                    @asConnection(connectionName: "LegacyFilmsConnection")
                    @defaultOrder(primaryKey: true)
            }
            """);

        assertThat(schema.diagnostics())
            .anyMatch(e -> e.kind() == RejectionKind.INVALID_SCHEMA
                && e.message().contains("FilmFilter.title")
                && e.message().contains("connectionName"));
    }

    @Test
    void asFacetOnChildConnection_rejected() {
        // The v1 facet plan is built only by the root Query connection fetcher; a faceted
        // @splitQuery child carrier would expose a facets field whose resolver always returns
        // null, so the combination is rejected rather than shipped dead.
        var schema = TestSchemaHelper.buildSchema("""
            type Customer @table(name: "customer") { firstName: String }
            input CustomerFilter @table(name: "customer") {
                firstName: [String!] @field(name: "first_name") @asFacet
            }
            type Store @table(name: "store") {
                customers(filter: CustomerFilter): [Customer!]!
                    @asConnection @splitQuery @defaultOrder(primaryKey: true)
            }
            type Query { store: Store }
            """);

        assertThat(schema.diagnostics())
            .anyMatch(e -> e.kind() == RejectionKind.INVALID_SCHEMA
                && e.message().contains("CustomerFilter.firstName")
                && e.message().contains("root Query connections")
                && e.message().contains("Store.customers"));
    }

    @Test
    void duplicateFacetNameAcrossFilterInputs_rejected() {
        // Each facet becomes one field on the synthesised <Conn>Facets object, so two filter
        // inputs on the same carrier cannot both facet a field named 'title'.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmFilterA @table(name: "film") {
                title: [String!] @field(name: "title") @asFacet
            }
            input FilmFilterB @table(name: "film") {
                title: [String!] @field(name: "title") @asFacet
            }
            type Query {
                films(a: FilmFilterA, b: FilmFilterB): [Film!]!
                    @asConnection @defaultOrder(primaryKey: true)
            }
            """);

        assertThat(schema.diagnostics())
            .anyMatch(e -> e.kind() == RejectionKind.INVALID_SCHEMA
                && e.message().contains("Query.films")
                && e.message().contains("duplicate facet field name 'title'"));
    }

    // ===== Rejection (use-keyed reachability check) =====

    @Test
    void asFacetOnInputNotReachedViaAnyConnection_rejected() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmFilter @table(name: "film") {
                title: [String!] @field(name: "title") @asFacet
            }
            type Query {
                films(filter: FilmFilter): [Film!]!
            }
            """);

        assertThat(schema.diagnostics())
            .anyMatch(e -> e.kind() == RejectionKind.INVALID_SCHEMA
                && e.message().contains("FilmFilter.title")
                && e.message().contains("dead schema"));
    }

    @Test
    void asFacetOnInputSharedWithNonConnectionConsumer_notRejected() {
        // R333's definition-keyed / use-keyed split: the directive is authored at the input type's
        // member coordinate; a non-connection consumer leaves it inert rather than invalid. The
        // rejection fires only when NO use site is an @asConnection field.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmFilter @table(name: "film") {
                title: [String!] @field(name: "title") @asFacet
            }
            type Query {
                films(filter: FilmFilter): [Film!]! @asConnection @defaultOrder(primaryKey: true)
                filmList(filter: FilmFilter): [Film!]!
            }
            """);

        assertThat(schema.diagnostics())
            .noneMatch(e -> e.message().contains("@asFacet"));
    }
}
