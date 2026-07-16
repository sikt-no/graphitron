package no.sikt.graphitron.rewrite;

import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLTypeReference;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.rewrite.model.FacetSpec;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronType.ConnectionType;
import no.sikt.graphitron.rewrite.model.GraphitronType.EdgeType;
import no.sikt.graphitron.rewrite.model.GraphitronType.FacetsType;
import no.sikt.graphitron.rewrite.model.GraphitronType.FacetValueType;
import no.sikt.graphitron.rewrite.model.GraphitronType.PageInfoType;
import no.sikt.graphitron.rewrite.schema.RewriteSchemaLoader;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Resolver-tier coverage for {@link ConnectionPromoter}: asserts that promotion synthesises
 * {@link ConnectionType} / {@link EdgeType} / {@link PageInfoType} entries on the registry and
 * returns the appropriate carrier-rewrite list, across the directive-driven and structural
 * arms plus the dedup, item-nullability, and SDL-PageInfo enrich edges.
 *
 * <p>Wiring: {@link GraphitronSchemaBuilder#buildContextForTests} runs the schema generator and
 * {@code TypeBuilder} but stops before field classification, returning the same fully-wired
 * {@link BuildContext} the orchestrator hands to {@link FieldBuilder}. The {@link #promoteAll} helper
 * drives {@link ConnectionPromoter#synthesiseForField} over every object field of that context,
 * standing in for the field-first walk so each carrier is promoted exactly once.
 */
@UnitTier
class ConnectionPromoterTest {

    private static final String FIXTURE_JOOQ_PACKAGE = "no.sikt.graphitron.rewrite.nodeidfixture";
    private static final RewriteContext FIXTURE_CTX = new RewriteContext(
        List.of(), Path.of(""), Path.of(""),
        DEFAULT_OUTPUT_PACKAGE, FIXTURE_JOOQ_PACKAGE,
        Map.of()
    );

    @Test
    void directiveDrivenBareList_emitsCarrierAndSynthesisesTypes() {
        String sdl = """
            type Customer { id: ID! }
            type Query {
                customers: [Customer!]! @asConnection
            }
            """;
        var bctx = buildBuildContext(sdl);

        var rewrites = promoteAll(bctx);

        assertThat(rewrites).singleElement().satisfies(r -> {
            assertThat(r.parentTypeName()).isEqualTo("Query");
            assertThat(r.fieldName()).isEqualTo("customers");
            assertThat(r.connectionName()).isEqualTo("QueryCustomersConnection");
            assertThat(r.defaultPageSize()).isEqualTo(FieldWrapper.DEFAULT_PAGE_SIZE);
            assertThat(r.outerNonNull()).isTrue();
        });
        assertThat(bctx.types.get("QueryCustomersConnection")).isInstanceOf(ConnectionType.class);
        assertThat(bctx.types.get("QueryCustomersEdge")).isInstanceOf(EdgeType.class);
        assertThat(bctx.types.get("PageInfo")).isInstanceOf(PageInfoType.class);
    }

    @Test
    void directiveDrivenSynthesis_carriesRelayDescriptionsOnTypesAndFields() {
        String sdl = """
            type Customer { id: ID! }
            type Query {
                customers: [Customer!]! @asConnection
            }
            """;
        var bctx = buildBuildContext(sdl);

        promoteAll(bctx);

        var connection = ((ConnectionType) bctx.types.get("QueryCustomersConnection")).schemaType();
        assertThat(connection.getDescription()).isEqualTo("A connection to a list of items.");
        assertThat(connection.getFieldDefinition("edges").getDescription()).isEqualTo("A list of edges.");
        assertThat(connection.getFieldDefinition("nodes").getDescription()).isEqualTo("A list of nodes.");
        assertThat(connection.getFieldDefinition("pageInfo").getDescription()).isEqualTo("Information to aid in pagination.");
        assertThat(connection.getFieldDefinition("totalCount").getDescription())
            .isEqualTo("Identifies the total count of items in the connection.");

        var edge = ((EdgeType) bctx.types.get("QueryCustomersEdge")).schemaType();
        assertThat(edge.getDescription()).isEqualTo("An edge in a connection.");
        assertThat(edge.getFieldDefinition("cursor").getDescription()).isEqualTo("A cursor for use in pagination.");
        assertThat(edge.getFieldDefinition("node").getDescription()).isEqualTo("The item at the end of the edge.");

        var pageInfo = ((PageInfoType) bctx.types.get("PageInfo")).schemaType();
        assertThat(pageInfo.getDescription()).isEqualTo("Information about pagination in a connection.");
        assertThat(pageInfo.getFieldDefinition("hasNextPage").getDescription())
            .isEqualTo("When paginating forwards, are there more items?");
        assertThat(pageInfo.getFieldDefinition("hasPreviousPage").getDescription())
            .isEqualTo("When paginating backwards, are there more items?");
        assertThat(pageInfo.getFieldDefinition("startCursor").getDescription())
            .isEqualTo("When paginating backwards, the cursor to continue.");
        assertThat(pageInfo.getFieldDefinition("endCursor").getDescription())
            .isEqualTo("When paginating forwards, the cursor to continue.");
    }

    @Test
    void directiveDrivenWithExplicitConnectionName_overridesAutoDerivedName() {
        String sdl = """
            type Customer { id: ID! }
            type Query {
                customers: [Customer!]! @asConnection(connectionName: "MyCustomerConnection")
            }
            """;
        var bctx = buildBuildContext(sdl);

        var rewrites = promoteAll(bctx);

        assertThat(rewrites).singleElement().satisfies(r ->
            assertThat(r.connectionName()).isEqualTo("MyCustomerConnection"));
        assertThat(bctx.types.get("MyCustomerConnection")).isInstanceOf(ConnectionType.class);
        assertThat(bctx.types.get("MyCustomerEdge")).isInstanceOf(EdgeType.class);
    }

    @Test
    void directiveDrivenWithExplicitDefaultFirstValue_overridesDefault() {
        String sdl = """
            type Customer { id: ID! }
            type Query {
                customers: [Customer!]! @asConnection(defaultFirstValue: 42)
            }
            """;
        var bctx = buildBuildContext(sdl);

        var rewrites = promoteAll(bctx);

        assertThat(rewrites).singleElement().satisfies(r ->
            assertThat(r.defaultPageSize()).isEqualTo(42));
    }

    @Test
    void structuralConnectionTypedReturn_enrichesPlainObjectEntries_noCarrierRewrite() {
        // SDL declares the Connection / Edge object types itself; the carrier field returns
        // CustomerConnection without @asConnection. Promotion should enrich the
        // NestingType entries to typed ConnectionType / EdgeType, but emit no
        // CarrierRewrite (the return type already names the Connection).
        String sdl = """
            type Customer { id: ID! }
            type CustomerEdge {
                cursor: String!
                node: Customer
            }
            type CustomerConnection {
                edges: [CustomerEdge!]!
                nodes: [Customer]!
                pageInfo: PageInfo!
                totalCount: Int
            }
            type PageInfo {
                hasNextPage: Boolean!
                hasPreviousPage: Boolean!
                startCursor: String
                endCursor: String
            }
            type Query {
                customers: CustomerConnection!
            }
            """;
        var bctx = buildBuildContext(sdl);

        var rewrites = promoteAll(bctx);

        assertThat(rewrites).isEmpty();
        assertThat(bctx.types.get("CustomerConnection")).isInstanceOf(ConnectionType.class);
        assertThat(bctx.types.get("CustomerEdge")).isInstanceOf(EdgeType.class);
        assertThat(bctx.types.get("PageInfo")).isInstanceOf(PageInfoType.class);
    }

    @Test
    void sdlDeclaredPageInfoWithShareable_enrichesAndPreservesShareableFlag() {
        String sdl = """
            directive @shareable on OBJECT | FIELD_DEFINITION
            type Customer { id: ID! }
            type PageInfo @shareable {
                hasNextPage: Boolean!
                hasPreviousPage: Boolean!
                startCursor: String
                endCursor: String
            }
            type Query {
                customers: [Customer!]! @asConnection
            }
            """;
        var bctx = buildBuildContext(sdl);

        var rewrites = promoteAll(bctx);

        assertThat(rewrites).hasSize(1);
        var pageInfo = bctx.types.get("PageInfo");
        assertThat(pageInfo).isInstanceOf(PageInfoType.class);
        assertThat(((PageInfoType) pageInfo).shareable()).isTrue();
    }

    @Test
    void twoCarriersSameConnectionName_dedupsToOneSynthesisedEntry() {
        String sdl = """
            type Customer { id: ID! }
            type Query {
                first: [Customer!]! @asConnection(connectionName: "CustomerConnection")
                second: [Customer!]! @asConnection(connectionName: "CustomerConnection")
            }
            """;
        var bctx = buildBuildContext(sdl);

        var rewrites = promoteAll(bctx);

        assertThat(rewrites).hasSize(2);
        assertThat(rewrites).allSatisfy(r -> assertThat(r.connectionName()).isEqualTo("CustomerConnection"));
        assertThat(bctx.types.get("CustomerConnection")).isInstanceOf(ConnectionType.class);
        assertThat(bctx.types.get("CustomerEdge")).isInstanceOf(EdgeType.class);
    }

    @Test
    void asConnectionOnAlreadyConnectionTypedReturn_emitsNoCarrierRewrite() {
        // The currentBaseName.equals(connectionName) guard: when the SDL return type already
        // names the Connection (here "CustomerConnection"), promotion must not emit a
        // CarrierRewrite — there is no return-type swap to make. The synthesised type
        // entries still get registered (enrich path).
        String sdl = """
            type Customer { id: ID! }
            type CustomerEdge {
                cursor: String!
                node: Customer
            }
            type CustomerConnection {
                edges: [CustomerEdge!]!
                nodes: [Customer]!
                pageInfo: PageInfo!
                totalCount: Int
            }
            type PageInfo {
                hasNextPage: Boolean!
                hasPreviousPage: Boolean!
                startCursor: String
                endCursor: String
            }
            type Query {
                customers: CustomerConnection! @asConnection
            }
            """;
        var bctx = buildBuildContext(sdl);

        var rewrites = promoteAll(bctx);

        assertThat(rewrites).isEmpty();
        assertThat(bctx.types.get("CustomerConnection")).isInstanceOf(ConnectionType.class);
    }

    @Test
    void itemNullabilityPropagation_matchesCarrierShape() {
        String sdl = """
            type Customer { id: ID! }
            type Query {
                nullable: [Customer]! @asConnection(connectionName: "NullableCustomerConnection")
                nonNull:  [Customer!]! @asConnection(connectionName: "NonNullCustomerConnection")
            }
            """;
        var bctx = buildBuildContext(sdl);

        promoteAll(bctx);

        assertThat(((ConnectionType) bctx.types.get("NullableCustomerConnection")).itemNullable()).isTrue();
        assertThat(((ConnectionType) bctx.types.get("NonNullCustomerConnection")).itemNullable()).isFalse();
    }

    @Test
    void rebuildAssembledForConnections_shortCircuitsWhenNoRewritesAndNoSynthesisedTypes() {
        // The empty-set short-circuit: no synthesised types and no rewrites must return the original
        // GraphQLSchema instance by reference.
        String sdl = """
            type Foo { id: ID! }
            type Query { foo: Foo }
            """;
        var bctx = buildBuildContext(sdl);

        var rebuilt = ConnectionPromoter.rebuildAssembledForConnections(
            bctx.schema, List.of(), List.of());

        assertThat(rebuilt).isSameAs(bctx.schema);
    }

    // ---- federation @tag inheritance on synthesised connection types ----

    private static final String TAG_DIRECTIVE_DECL =
        "directive @tag(name: String!) repeatable on FIELD_DEFINITION | OBJECT\n";

    @Test
    void directiveDrivenWithTag_synthesisedConnectionEdgePageInfoInheritTag() {
        String sdl = TAG_DIRECTIVE_DECL + """
            type Customer { id: ID! }
            type Query {
                customers: [Customer!]! @asConnection @tag(name: "x")
            }
            """;
        var bctx = buildBuildContext(sdl);

        promoteAll(bctx);

        assertThat(tagNames(connSchema(bctx, "QueryCustomersConnection"))).containsExactly("x");
        assertThat(tagNames(edgeSchema(bctx, "QueryCustomersEdge"))).containsExactly("x");
        assertThat(tagNames(pageInfoSchema(bctx))).containsExactly("x");
        // The carrier field on the original schema keeps its own @tag (promotion does not strip it).
        var carrier = ((GraphQLObjectType) bctx.schema.getType("Query")).getFieldDefinition("customers");
        assertThat(carrier.getAppliedDirectives("tag")).hasSize(1);
    }

    @Test
    void repeatableTags_allValuesAppearOnSynthesisedTypes() {
        String sdl = TAG_DIRECTIVE_DECL + """
            type Customer { id: ID! }
            type Query {
                customers: [Customer!]! @asConnection @tag(name: "a") @tag(name: "b")
            }
            """;
        var bctx = buildBuildContext(sdl);

        promoteAll(bctx);

        assertThat(tagNames(connSchema(bctx, "QueryCustomersConnection"))).containsExactlyInAnyOrder("a", "b");
        assertThat(tagNames(edgeSchema(bctx, "QueryCustomersEdge"))).containsExactlyInAnyOrder("a", "b");
        assertThat(tagNames(pageInfoSchema(bctx))).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void sharedConnectionName_synthesisedTypesCarryTagUnion() {
        String sdl = TAG_DIRECTIVE_DECL + """
            type Customer { id: ID! }
            type Query {
                first: [Customer!]! @asConnection(connectionName: "CustomerConnection") @tag(name: "a")
                second: [Customer!]! @asConnection(connectionName: "CustomerConnection") @tag(name: "b")
            }
            """;
        var bctx = buildBuildContext(sdl);

        promoteAll(bctx);

        assertThat(tagNames(connSchema(bctx, "CustomerConnection"))).containsExactlyInAnyOrder("a", "b");
        assertThat(tagNames(edgeSchema(bctx, "CustomerEdge"))).containsExactlyInAnyOrder("a", "b");
        assertThat(tagNames(pageInfoSchema(bctx))).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void structuralTaggedConnectionWithNoSdlPageInfo_synthesisedPageInfoCarriesTag() {
        // isConnectionType only requires edges -> node; a structural Connection without a
        // pageInfo field builds (no unresolved PageInfo reference) and still triggers PageInfo
        // synthesis. The structural arm reads the Connection type's own @tag and feeds it into
        // the synthesised PageInfo union.
        String sdl = TAG_DIRECTIVE_DECL + """
            type Customer { id: ID! }
            type CustomerEdge {
                cursor: String!
                node: Customer
            }
            type CustomerConnection @tag(name: "x") {
                edges: [CustomerEdge!]!
                nodes: [Customer]!
                totalCount: Int
            }
            type Query {
                customers: CustomerConnection!
            }
            """;
        var bctx = buildBuildContext(sdl);

        promoteAll(bctx);

        // The author-declared Connection keeps its own tag; the synthesised PageInfo inherits it.
        assertThat(tagNames(connSchema(bctx, "CustomerConnection"))).containsExactly("x");
        assertThat(tagNames(pageInfoSchema(bctx))).containsExactly("x");
    }

    @Test
    void sdlDeclaredPageInfo_isNeverTaggedByPromotion() {
        // Negative pin: an author-declared PageInfo is "not touched". Even though the structural
        // Connection is tagged @tag(name: "conn"), that tag does not leak onto the SDL-declared
        // PageInfo — the author owns it.
        String sdl = TAG_DIRECTIVE_DECL + """
            type Customer { id: ID! }
            type CustomerEdge {
                cursor: String!
                node: Customer
            }
            type CustomerConnection @tag(name: "conn") {
                edges: [CustomerEdge!]!
                nodes: [Customer]!
                pageInfo: PageInfo!
                totalCount: Int
            }
            type PageInfo @tag(name: "author") {
                hasNextPage: Boolean!
                hasPreviousPage: Boolean!
                startCursor: String
                endCursor: String
            }
            type Query {
                customers: CustomerConnection!
            }
            """;
        var bctx = buildBuildContext(sdl);

        promoteAll(bctx);

        // The Connection keeps its author tag; the SDL PageInfo keeps only its own, unchanged.
        assertThat(tagNames(connSchema(bctx, "CustomerConnection"))).containsExactly("conn");
        assertThat(tagNames(pageInfoSchema(bctx))).containsExactly("author");
    }

    // ===== @asFacet synthesis =====

    @Test
    void asFacetOnFilterInput_synthesisesFacetSpecsAndTypes() {
        String sdl = """
            enum Rating { G PG }
            type Film { id: ID! }
            input FilmFilter {
                rating: [Rating!] @field(name: "rating") @asFacet
                length: [Int] @field(name: "length") @asFacet
                title: String @field(name: "title")
            }
            type Query {
                films(filter: FilmFilter): [Film!]! @asConnection
            }
            """;
        var bctx = buildBuildContext(sdl);

        promoteAll(bctx);

        var conn = (ConnectionType) bctx.types.get("QueryFilmsConnection");
        assertThat(conn.facets()).containsExactly(
            new FacetSpec("filter", "rating", "rating", "Rating", false, "RatingFacetValue"),
            new FacetSpec("filter", "length", "length", "Int", true, "IntFacetValueOrNull"));
        assertThat(bctx.types.get("QueryFilmsConnectionFacets")).isInstanceOf(FacetsType.class);
        assertThat(bctx.types.get("RatingFacetValue")).isInstanceOf(FacetValueType.class);
        assertThat(bctx.types.get("IntFacetValueOrNull")).isInstanceOf(FacetValueType.class);
    }

    @Test
    void facetFieldNullability_isTheFailureFirewall() {
        // Pins the "Facet failure semantics" contract structurally: the facets field on the
        // Connection and every per-facet field on <Conn>Facets are nullable, so a facet failure
        // can never propagate through GraphQL non-null bubbling; only the list elements and the
        // inner count stay non-null.
        String sdl = """
            enum Rating { G PG }
            type Film { id: ID! }
            input FilmFilter {
                rating: [Rating!] @field(name: "rating") @asFacet
                length: [Int] @field(name: "length") @asFacet
            }
            type Query {
                films(filter: FilmFilter): [Film!]! @asConnection
            }
            """;
        var bctx = buildBuildContext(sdl);

        promoteAll(bctx);

        var facetsField = connSchema(bctx, "QueryFilmsConnection").getFieldDefinition("facets");
        assertThat(facetsField).isNotNull();
        assertThat(facetsField.getType())
            .as("the facets field itself must be nullable")
            .isInstanceOfSatisfying(GraphQLTypeReference.class,
                ref -> assertThat(ref.getName()).isEqualTo("QueryFilmsConnectionFacets"));

        var facetsSchema = ((FacetsType) bctx.types.get("QueryFilmsConnectionFacets")).schemaType();
        for (var fieldName : List.of("rating", "length")) {
            var perFacet = facetsSchema.getFieldDefinition(fieldName);
            assertThat(perFacet.getType())
                .as("per-facet field '%s' must be a nullable list (no field-level NonNull)", fieldName)
                .isInstanceOf(GraphQLList.class);
            assertThat(((GraphQLList) perFacet.getType()).getWrappedType())
                .as("per-facet list elements stay non-null")
                .isInstanceOf(GraphQLNonNull.class);
        }
    }

    @Test
    void facetValueTypes_mirrorTheFilterElementScalarAndNullability() {
        String sdl = """
            enum Rating { G PG }
            type Film { id: ID! }
            input FilmFilter {
                rating: [Rating!] @field(name: "rating") @asFacet
                length: [Int] @field(name: "length") @asFacet
            }
            type Query {
                films(filter: FilmFilter): [Film!]! @asConnection
            }
            """;
        var bctx = buildBuildContext(sdl);

        promoteAll(bctx);

        var nonNullValue = (FacetValueType) bctx.types.get("RatingFacetValue");
        assertThat(nonNullValue.valueTypeName()).isEqualTo("Rating");
        assertThat(nonNullValue.valueNullable()).isFalse();
        assertThat(nonNullValue.schemaType().getFieldDefinition("value").getType())
            .as("a non-null filter element yields a non-null facet value")
            .isInstanceOf(GraphQLNonNull.class);
        assertThat(nonNullValue.schemaType().getFieldDefinition("count").getType())
            .isInstanceOf(GraphQLNonNull.class);

        var nullableValue = (FacetValueType) bctx.types.get("IntFacetValueOrNull");
        assertThat(nullableValue.valueTypeName()).isEqualTo("Int");
        assertThat(nullableValue.valueNullable()).isTrue();
        assertThat(nullableValue.schemaType().getFieldDefinition("value").getType())
            .as("a nullable filter element yields a nullable facet value (the NULL bucket round-trips)")
            .isInstanceOf(GraphQLTypeReference.class);
    }

    @Test
    void filterWithoutAsFacet_synthesisesNoFacetSurface() {
        String sdl = """
            type Film { id: ID! }
            input FilmFilter {
                title: String @field(name: "title")
            }
            type Query {
                films(filter: FilmFilter): [Film!]! @asConnection
            }
            """;
        var bctx = buildBuildContext(sdl);

        promoteAll(bctx);

        var conn = (ConnectionType) bctx.types.get("QueryFilmsConnection");
        assertThat(conn.facets()).isEmpty();
        assertThat(conn.schemaType().getFieldDefinition("facets")).isNull();
        assertThat(bctx.types.get("QueryFilmsConnectionFacets")).isNull();
    }

    @Test
    void malformedAsFacet_isSkippedByTheSynthesisWalk() {
        // The walk projects only well-formed facets; each malformed application is rejected with a
        // named build diagnostic by GraphitronSchemaBuilder's facet-misuse reduction (pipeline-tier
        // coverage in FacetedConnectionPipelineTest), so nothing half-synthesised reaches the model.
        String sdl = """
            type Film { id: ID! }
            input FilmFilter {
                noColumn: [String!] @asFacet
                conditionBound: [String!] @field(name: "title") @condition @asFacet
            }
            type Query {
                films(filter: FilmFilter): [Film!]! @asConnection
            }
            """;
        var bctx = buildBuildContext(sdl);

        promoteAll(bctx);

        var conn = (ConnectionType) bctx.types.get("QueryFilmsConnection");
        assertThat(conn.facets()).isEmpty();
        assertThat(conn.schemaType().getFieldDefinition("facets")).isNull();
        assertThat(bctx.types.get("QueryFilmsConnectionFacets")).isNull();
    }

    private static GraphQLObjectType connSchema(BuildContext bctx, String name) {
        return ((ConnectionType) bctx.types.get(name)).schemaType();
    }

    private static GraphQLObjectType edgeSchema(BuildContext bctx, String name) {
        return ((EdgeType) bctx.types.get(name)).schemaType();
    }

    private static GraphQLObjectType pageInfoSchema(BuildContext bctx) {
        return ((PageInfoType) bctx.types.get("PageInfo")).schemaType();
    }

    private static List<String> tagNames(GraphQLObjectType type) {
        return type.getAppliedDirectives("tag").stream()
            .map(d -> (String) d.getArgument("name").getValue())
            .toList();
    }

    /**
     * Drives {@link ConnectionPromoter#synthesiseForField} over every object-type field of the
     * context, standing in for the field-first walk that the real builder runs, and returns the
     * accumulated carrier rewrites. The synthesised-name set is not asserted on here (the tests read
     * {@code bctx.types} directly), so it is discarded.
     */
    private static List<ConnectionPromoter.CarrierRewrite> promoteAll(BuildContext bctx) {
        var rewrites = new ArrayList<ConnectionPromoter.CarrierRewrite>();
        var synthesisedNames = new LinkedHashSet<String>();
        for (var t : bctx.schema.getAllTypesAsList()) {
            if (t.getName().startsWith("_")) continue;
            if (!(t instanceof GraphQLObjectType objType)) continue;
            for (var fieldDef : objType.getFieldDefinitions()) {
                ConnectionPromoter.synthesiseForField(bctx, objType, fieldDef, rewrites, synthesisedNames);
            }
        }
        return rewrites;
    }

    private static BuildContext buildBuildContext(String sdl) {
        TypeDefinitionRegistry registry = new SchemaParser().parse(prelude() + sdl);
        return GraphitronSchemaBuilder.buildContextForTests(AttributedRegistry.from(registry), FIXTURE_CTX);
    }

    private static String prelude() {
        try (InputStream is = RewriteSchemaLoader.class.getResourceAsStream("directives.graphqls")) {
            if (is == null) throw new IllegalStateException("directives.graphqls not found on classpath");
            return new String(is.readAllBytes(), StandardCharsets.UTF_8) + "\n";
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
