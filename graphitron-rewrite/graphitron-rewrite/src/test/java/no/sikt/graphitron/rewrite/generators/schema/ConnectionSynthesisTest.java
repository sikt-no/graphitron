package no.sikt.graphitron.rewrite.generators.schema;

import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionSynthesisTest {

    // SDL with a directive-driven @asConnection field on a bare list.
    private static final String WITH_AS_CONNECTION = """
        type Query {
          films: [Film!]! @asConnection
        }
        type Film { id: ID! }
        """;

    // SDL where the Connection type is already hand-written (structural path).
    private static final String STRUCTURAL_CONNECTION = """
        type Query {
          films: FilmsConnection!
        }
        type FilmsConnection { edges: [FilmsEdge!]! nodes: [Film!]! pageInfo: PageInfo! }
        type FilmsEdge { cursor: String! node: Film! }
        type PageInfo { hasNextPage: Boolean! hasPreviousPage: Boolean! startCursor: String endCursor: String }
        type Film { id: ID! }
        """;

    // SDL with an already-declared PageInfo type alongside a synthesis field.
    private static final String WITH_EXISTING_PAGE_INFO = """
        type Query {
          films: [Film!]! @asConnection
        }
        type Film { id: ID! }
        type PageInfo { hasNextPage: Boolean! hasPreviousPage: Boolean! startCursor: String endCursor: String }
        """;

    // SDL with explicit connectionName override.
    private static final String WITH_EXPLICIT_NAME = """
        type Query {
          films: [Film!]! @asConnection(connectionName: "MyFilmsConnection")
        }
        type Film { id: ID! }
        """;

    // SDL with explicit defaultFirstValue override.
    private static final String WITH_DEFAULT_FIRST = """
        type Query {
          films: [Film!]! @asConnection(defaultFirstValue: 25)
        }
        type Film { id: ID! }
        """;

    // SDL with two fields sharing the same connection name via explicit connectionName.
    private static final String WITH_DUPLICATES = """
        type Query {
          films:  [Film!]! @asConnection(connectionName: "SharedConnection")
          films2: [Film!]! @asConnection(connectionName: "SharedConnection")
        }
        type Film { id: ID! }
        """;

    @Test
    void buildPlan_empty_forSchemaWithNoAsConnection() {
        var plan = buildPlan("type Query { x: String }");
        assertThat(plan.connections()).isEmpty();
        assertThat(plan.needPageInfo()).isFalse();
    }

    @Test
    void buildPlan_detectsDirectiveDrivenField() {
        var plan = buildPlan(WITH_AS_CONNECTION);
        assertThat(plan.connections()).containsKey("QueryFilmsConnection");
    }

    @Test
    void buildPlan_derivesConnectionName_fromParentAndFieldName() {
        var plan = buildPlan(WITH_AS_CONNECTION);
        var def = plan.connections().get("QueryFilmsConnection");
        assertThat(def).isNotNull();
        assertThat(def.elementTypeName()).isEqualTo("Film");
    }

    @Test
    void buildPlan_usesExplicitConnectionName() {
        var plan = buildPlan(WITH_EXPLICIT_NAME);
        assertThat(plan.connections()).containsKey("MyFilmsConnection");
        assertThat(plan.connections()).doesNotContainKey("QueryFilmsConnection");
    }

    @Test
    void buildPlan_usesExplicitDefaultFirstValue() {
        var plan = buildPlan(WITH_DEFAULT_FIRST);
        var def = plan.connections().get("QueryFilmsConnection");
        assertThat(def).isNotNull();
        assertThat(def.defaultPageSize()).isEqualTo(25);
    }

    @Test
    void buildPlan_defaultPageSizeFallback() {
        var plan = buildPlan(WITH_AS_CONNECTION);
        var def = plan.connections().get("QueryFilmsConnection");
        assertThat(def.defaultPageSize()).isEqualTo(no.sikt.graphitron.rewrite.model.FieldWrapper.DEFAULT_PAGE_SIZE);
    }

    @Test
    void buildPlan_skipsStructuralField() {
        var plan = buildPlan(STRUCTURAL_CONNECTION);
        assertThat(plan.connections()).isEmpty();
    }

    @Test
    void buildPlan_needsPageInfo_whenPageInfoAbsent() {
        var plan = buildPlan(WITH_AS_CONNECTION);
        assertThat(plan.needPageInfo()).isTrue();
    }

    @Test
    void buildPlan_noPageInfo_whenPageInfoAlreadyDeclared() {
        var plan = buildPlan(WITH_EXISTING_PAGE_INFO);
        assertThat(plan.needPageInfo()).isFalse();
    }

    @Test
    void buildPlan_deduplicatesFields_withSameConnectionName() {
        var plan = buildPlan(WITH_DUPLICATES);
        assertThat(plan.connections()).hasSize(1);
        assertThat(plan.connections()).containsKey("SharedConnection");
    }

    @Test
    void emitSupportingTypes_emptyForEmptyPlan() {
        var result = ConnectionSynthesis.emitSupportingTypes(ConnectionSynthesis.Plan.EMPTY, "");
        assertThat(result).isEmpty();
    }

    @Test
    void emitSupportingTypes_emitsConnectionAndEdgeTypeSpec() {
        var plan = buildPlan(WITH_AS_CONNECTION);
        var specs = ConnectionSynthesis.emitSupportingTypes(plan, "");
        var names = specs.stream().map(TypeSpec::name).toList();
        // QueryFilmsConnection → edge is QueryFilmsEdge (replace "Connection" with "Edge")
        assertThat(names).contains("QueryFilmsConnectionType", "QueryFilmsEdgeType");
    }

    @Test
    void emitSupportingTypes_emitsPageInfoWhenNeeded() {
        var plan = buildPlan(WITH_AS_CONNECTION);
        var specs = ConnectionSynthesis.emitSupportingTypes(plan, "");
        var names = specs.stream().map(TypeSpec::name).toList();
        assertThat(names).contains("PageInfoType");
    }

    @Test
    void emitSupportingTypes_omitsPageInfoWhenAlreadyDeclared() {
        var plan = buildPlan(WITH_EXISTING_PAGE_INFO);
        var specs = ConnectionSynthesis.emitSupportingTypes(plan, "");
        var names = specs.stream().map(TypeSpec::name).toList();
        assertThat(names).doesNotContain("PageInfoType");
    }

    @Test
    void emitSupportingTypes_connectionHasEdgesNodesPageInfo() {
        var plan = buildPlan(WITH_AS_CONNECTION);
        var connSpec = findByName(ConnectionSynthesis.emitSupportingTypes(plan, ""), "QueryFilmsConnectionType");
        var body = connSpec.methodSpecs().get(0).code().toString();
        assertThat(body).contains(".name(\"QueryFilmsConnection\")");
        assertThat(body).contains(".name(\"edges\")");
        assertThat(body).contains(".name(\"nodes\")");
        assertThat(body).contains(".name(\"pageInfo\")");
    }

    @Test
    void emitSupportingTypes_edgeHasCursorAndNode() {
        var plan = buildPlan(WITH_AS_CONNECTION);
        var edgeSpec = findByName(ConnectionSynthesis.emitSupportingTypes(plan, ""), "QueryFilmsEdgeType");
        var body = edgeSpec.methodSpecs().get(0).code().toString();
        assertThat(body).contains(".name(\"QueryFilmsEdge\")");
        assertThat(body).contains(".name(\"cursor\")");
        assertThat(body).contains(".name(\"node\")");
    }

    @Test
    void emitSupportingTypes_connectionEdgesAreNonNullList() {
        var plan = buildPlan(WITH_AS_CONNECTION);
        var connSpec = findByName(ConnectionSynthesis.emitSupportingTypes(plan, ""), "QueryFilmsConnectionType");
        var body = connSpec.methodSpecs().get(0).code().toString();
        // edges: [QueryFilmsEdge!]!
        assertThat(body).contains("nonNull(graphql.schema.GraphQLList.list(graphql.schema.GraphQLNonNull.nonNull(graphql.schema.GraphQLTypeReference.typeRef(\"QueryFilmsEdge\"))))");
    }

    @Test
    void emitSupportingTypes_nodesListReflectsItemNullability() {
        var plan = buildPlan(WITH_AS_CONNECTION);
        var def = plan.connections().get("QueryFilmsConnection");
        assertThat(def.itemNullable()).isFalse();  // [Film!]! → items are non-null
        var connSpec = findByName(ConnectionSynthesis.emitSupportingTypes(plan, ""), "QueryFilmsConnectionType");
        var body = connSpec.methodSpecs().get(0).code().toString();
        // nodes: [Film!]!
        assertThat(body).contains("nonNull(graphql.schema.GraphQLTypeReference.typeRef(\"Film\"))");
    }

    @Test
    void emitSupportingTypes_resultsAreSorted() {
        var plan = buildPlan(WITH_AS_CONNECTION);
        var names = ConnectionSynthesis.emitSupportingTypes(plan, "").stream().map(TypeSpec::name).toList();
        assertThat(names).isSorted();
    }

    @Test
    void emitSupportingTypes_connectionHasRegisterFetchers() {
        var plan = buildPlan(WITH_AS_CONNECTION);
        var connSpec = findByName(ConnectionSynthesis.emitSupportingTypes(plan, ""), "QueryFilmsConnectionType");
        assertThat(connSpec.methodSpecs()).extracting(m -> m.name()).contains("registerFetchers");
    }

    @Test
    void emitSupportingTypes_edgeHasRegisterFetchers() {
        var plan = buildPlan(WITH_AS_CONNECTION);
        var edgeSpec = findByName(ConnectionSynthesis.emitSupportingTypes(plan, ""), "QueryFilmsEdgeType");
        assertThat(edgeSpec.methodSpecs()).extracting(m -> m.name()).contains("registerFetchers");
    }

    @Test
    void resolveEdgeName_replacesConnectionWithEdge() {
        assertThat(ConnectionSynthesis.resolveEdgeName("FilmsConnection")).isEqualTo("FilmsEdge");
        assertThat(ConnectionSynthesis.resolveEdgeName("QueryStoresConnection")).isEqualTo("QueryStoresEdge");
    }

    private static ConnectionSynthesis.Plan buildPlan(String sdl) {
        return ConnectionSynthesis.buildPlan(TestSchemaHelper.buildBundle(sdl).assembled());
    }

    private static TypeSpec findByName(List<TypeSpec> specs, String name) {
        return specs.stream()
            .filter(s -> s.name().equals(name))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no TypeSpec named " + name + " in " + specs));
    }
}
