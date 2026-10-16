package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.FacetSpec;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.model.diagnostics.RejectionKind;
import no.sikt.graphitron.model.diagnostics.ValidationError;

/**
 * {@code @asFacet} through the full classification pipeline. The synthesis half
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
        input FilmFilter {
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
                    new FacetSpec("filter", "title", "title", "String", false, "StringFacetValue", false),
                    new FacetSpec("filter", "length", "length", "Int", true, "IntFacetValueOrNull", false)));
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
    void nonListFacetField_classifiesWithTheFieldsOwnNullability() {
        // A non-list facet field is legal: the element type is the field itself, and the field
        // is nullable by the non-null rejection, so the value is always nullable here.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmFilter {
                title: String @field(name: "title") @asFacet
            }
            type Query {
                films(filter: FilmFilter): [Film!]! @asConnection @defaultOrder(primaryKey: true)
            }
            """);

        assertThat(schema.diagnostics()).noneMatch(e -> e.message().contains("@asFacet"));
        assertThat(schema.types().get("QueryFilmsConnection"))
            .isInstanceOfSatisfying(GraphitronType.ConnectionType.class, conn ->
                assertThat(conn.facets()).containsExactly(
                    new FacetSpec("filter", "title", "title", "String", true, "StringFacetValueOrNull", false)));
    }

    @Test
    void connectionWithoutAsFacet_hasEmptyFacets() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmFilter {
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
            input FilmFilter {
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
            input FilmFilter {
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
                && e.message().contains("remove @condition / @nodeId from the facet field"));
    }

    @Test
    void asFacetOnReferenceBoundField_synthesisesTheFacet() {
        // @reference names the table the @field column is on; the facet surface depends only on
        // the value type and nullability, so the carrier synthesises the facet like a direct one,
        // flagged reference-bound for the planner.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmFilter {
                languageName: [String!] @field(name: "name")
                    @reference(path: [{key: "film_language_id_fkey"}]) @asFacet
            }
            type Query {
                films(filter: FilmFilter): [Film!]! @asConnection @defaultOrder(primaryKey: true)
            }
            """);

        assertThat(schema.diagnostics()).noneMatch(e -> e.message().contains("@asFacet")
            || e.message().contains("@reference facet"));
        assertThat(schema.types().get("QueryFilmsConnection"))
            .isInstanceOfSatisfying(GraphitronType.ConnectionType.class, conn ->
                assertThat(conn.facets()).containsExactly(
                    new FacetSpec("filter", "languageName", "name", "String", false, "StringFacetValue", true)));
        assertThat(schema.types().get("StringFacetValue"))
            .isInstanceOf(GraphitronType.FacetValueType.class);
    }

    @Test
    void asFacetOnIdField_rejected() {
        // Rejecting the ID type outright, not just @nodeId co-occurrence, keeps the rule readable
        // off the SDL: ID is where a node-id reading can arise without a directive to point at, and
        // a facet groups on a plain column.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmFilter {
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
    void asFacetWithNodeIdCoOccurrence_rejected() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmFilter {
                ids: [ID!] @field(name: "film_id") @nodeId(typeName: "Film") @asFacet
            }
            type Query {
                films(filter: FilmFilter): [Film!]! @asConnection @defaultOrder(primaryKey: true)
            }
            """);

        assertThat(schema.diagnostics())
            .anyMatch(e -> e.kind() == RejectionKind.INVALID_SCHEMA
                && e.message().contains("FilmFilter.ids")
                && e.message().contains("remove @condition / @nodeId from the facet field"));
    }

    @Test
    void asFacetOnInputObjectField_rejected() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input TitleBox { title: String @field(name: "title") }
            input FilmFilter {
                nested: TitleBox @field(name: "title") @asFacet
            }
            type Query {
                films(filter: FilmFilter): [Film!]! @asConnection @defaultOrder(primaryKey: true)
            }
            """);

        assertThat(schema.diagnostics())
            .anyMatch(e -> e.kind() == RejectionKind.INVALID_SCHEMA
                && e.message().contains("FilmFilter.nested")
                && e.message().contains("must mark a scalar or enum field"));
    }

    @Test
    void asFacetOnInterfaceConnection_rejected() {
        // The reference page advertises interface/union rejection alongside the @splitQuery
        // child case; this pins the interface arm of the carrier-scope check.
        var schema = TestSchemaHelper.buildSchema("""
            interface Watchable { title: String }
            type Film implements Watchable @table(name: "film") { title: String }
            input WatchableFilter {
                title: [String!] @field(name: "title") @asFacet
            }
            type Query {
                watchables(filter: WatchableFilter): [Watchable!]!
                    @asConnection @defaultOrder(primaryKey: true)
            }
            """);

        assertThat(schema.diagnostics())
            .anyMatch(e -> e.kind() == RejectionKind.INVALID_SCHEMA
                && e.message().contains("WatchableFilter.title")
                && e.message().contains("root Query connections")
                && e.message().contains("@table-backed object element"));
    }

    @Test
    void asFacetInputSharedWithStructuralConnection_notRejected() {
        // A structural connection carrier (declared Connection return type, @asConnection
        // alongside) never gains facets, so @asFacet is inert there per the spec's "inert at
        // the others" rule; sharing the input with a served root carrier stays legal.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type FilmsConnection { edges: [FilmsEdge!]! nodes: [Film!]! pageInfo: PageInfo! }
            type FilmsEdge { cursor: String! node: Film! }
            type PageInfo { hasNextPage: Boolean! hasPreviousPage: Boolean! startCursor: String endCursor: String }
            input FilmFilter {
                title: [String!] @field(name: "title") @asFacet
            }
            type Query {
                films(filter: FilmFilter): [Film!]! @asConnection @defaultOrder(primaryKey: true)
                filmsStructural(filter: FilmFilter): FilmsConnection @asConnection @defaultOrder(primaryKey: true)
            }
            """);

        assertThat(schema.diagnostics())
            .noneMatch(e -> e.message().contains("@asFacet"));
    }

    @Test
    void asFacetOnNonNullField_rejected() {
        // Phase 4 rationale: the generated filter-minus-self fragments suppress a facet's own
        // predicate by leaving its argument unset, which requires the binding to be optional; and
        // an always-active filter value could never show unfiltered pivot counts anyway.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmFilter {
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
    void asFacetWithConnectionNameOverride_accepted() {
        // Acceptance pin for the dissolved rejection: the facet producers resolve the carrier's
        // ConnectionType by coordinate through the connection-synthesis relation, so the
        // deprecated connectionName: override no longer forfeits facets. The named connection
        // carries the facet specs and the facet types are synthesised under the overridden name.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmFilter {
                title: [String!] @field(name: "title") @asFacet
            }
            type Query {
                films(filter: FilmFilter): [Film!]!
                    @asConnection(connectionName: "LegacyFilmsConnection")
                    @defaultOrder(primaryKey: true)
            }
            """);

        assertThat(schema.diagnostics())
            .as("a named faceted connection is legal; no facet diagnostics may surface")
            .noneMatch(e -> e.message().contains("@asFacet") || e.message().contains("connectionName"));
        assertThat(schema.types().get("LegacyFilmsConnection"))
            .isInstanceOfSatisfying(GraphitronType.ConnectionType.class, conn ->
                assertThat(conn.facets()).containsExactly(
                    new FacetSpec("filter", "title", "title", "String", false, "StringFacetValue", false)));
        assertThat(schema.types().get("LegacyFilmsConnectionFacets"))
            .isInstanceOfSatisfying(GraphitronType.FacetsType.class, ft ->
                assertThat(ft.connectionName()).isEqualTo("LegacyFilmsConnection"));
        // The plan-side lookup is coordinate-keyed, so the overridden name resolves identically.
        assertThat(schema.connectionSynthesis().facetsAt("Query", "films"))
            .containsExactly(new FacetSpec("filter", "title", "title", "String", false, "StringFacetValue", false));
    }

    @Test
    void asFacetOnChildConnection_rejected() {
        // The v1 facet plan is built only by the root Query connection fetcher; a faceted
        // @splitQuery child carrier would expose a facets field whose resolver always returns
        // null, so the combination is rejected rather than shipped dead.
        var schema = TestSchemaHelper.buildSchema("""
            type Customer @table(name: "customer") { firstName: String }
            input CustomerFilter {
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
    void facetNameCollidingWithSiblingArgFilterField_rejected() {
        // A non-facet filter field on a sibling input arg shares the facet's name. The facet
        // fragments and the generated condition method both
        // key parameters by name (the condition method cannot even declare two same-named
        // parameters), so the collision is rejected rather than resolved by position.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmFilter {
                length: [Int] @field(name: "length") @asFacet
            }
            input FilmExtra {
                length: [Int!] @field(name: "length")
            }
            type Query {
                films(filter: FilmFilter, extra: FilmExtra): [Film!]!
                    @asConnection @defaultOrder(primaryKey: true)
            }
            """);

        assertThat(schema.diagnostics())
            .anyMatch(e -> e.kind() == RejectionKind.INVALID_SCHEMA
                && e.message().contains("Query.films")
                && e.message().contains("facet 'length' shares its name"));
    }

    @Test
    void duplicateFacetNameAcrossFilterInputs_rejected() {
        // Each facet becomes one field on the synthesised <Conn>Facets object, so two filter
        // inputs on the same carrier cannot both facet a field named 'title'.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmFilterA {
                title: [String!] @field(name: "title") @asFacet
            }
            input FilmFilterB {
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
            input FilmFilter {
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
        // The definition-keyed / use-keyed split: the directive is authored at the input type's
        // member coordinate; a non-connection consumer leaves it inert rather than invalid. The
        // rejection fires only when NO use site is an @asConnection field.
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmFilter {
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

    // ===== Rejections (use-keyed, @reference facets) =====

    @Test
    void referenceFacetOnRoutineCarrier_rejected() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmFilter {
                languageName: [String!] @field(name: "name")
                    @reference(path: [{key: "film_language_id_fkey"}]) @asFacet
            }
            type Query {
                recent(actorId: Int!, minLength: Int!, filter: FilmFilter): [Film!]! @asConnection
                    @routine(name: "films_for_actor", argMapping: "pActorId: actorId, pMinLength: minLength")
                    @reference(path: [{table: "film"}])
            }
            """);

        assertThat(schema.diagnostics())
            .anyMatch(e -> e.kind() == RejectionKind.INVALID_SCHEMA
                && e.message().contains("FilmFilter.languageName")
                && e.message().contains("consumer 'Query.recent' reads from a @routine"));
    }

    @Test
    void referenceFacetOnCarrierWithoutPrimaryKey_rejected() {
        var schema = TestSchemaHelper.buildSchema("""
            type RoleAssignment @table(name: "role_assignment") { roleCode: String @field(name: "role_code") }
            input RoleAssignmentFilter {
                holderName: [String!] @field(name: "holder_name")
                    @reference(path: [{table: "role_holder"}]) @asFacet
            }
            type Query {
                assignments(filter: RoleAssignmentFilter): [RoleAssignment!]! @asConnection
                    @defaultOrder(fields: [{name: "role_code"}])
            }
            """);

        assertThat(schema.diagnostics())
            .anyMatch(e -> e.kind() == RejectionKind.INVALID_SCHEMA
                && e.message().contains("RoleAssignmentFilter.holderName")
                && e.message().contains("returns rows of table 'role_assignment', which has no primary key"));
    }

    @Test
    void referenceFacetUnderFieldOverride_rejected() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmFilter {
                languageName: [String!] @field(name: "name")
                    @reference(path: [{key: "film_language_id_fkey"}]) @asFacet
            }
            type Query {
                films(filter: FilmFilter): [Film!]! @asConnection @defaultOrder(primaryKey: true)
                    @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "lifterFieldCondition"}, override: true)
            }
            """);

        assertThat(schema.diagnostics())
            .anyMatch(e -> e.kind() == RejectionKind.INVALID_SCHEMA
                && e.message().contains("FilmFilter.languageName")
                && e.message().contains("carries @condition(override: true) on the field"));
    }

    @Test
    void referenceFacetUnderArgumentOverride_rejected() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            input FilmFilter {
                languageName: [String!] @field(name: "name")
                    @reference(path: [{key: "film_language_id_fkey"}]) @asFacet
            }
            type Query {
                films(filter: FilmFilter
                        @condition(condition: {className: "no.sikt.graphitron.rewrite.TestConditionStub", method: "inputColumnCondition"}, override: true)
                ): [Film!]! @asConnection @defaultOrder(primaryKey: true)
            }
            """);

        assertThat(schema.diagnostics())
            .anyMatch(e -> e.kind() == RejectionKind.INVALID_SCHEMA
                && e.message().contains("FilmFilter.languageName")
                && e.message().contains("carries @condition(override: true) on its argument 'filter'"));
    }
}
