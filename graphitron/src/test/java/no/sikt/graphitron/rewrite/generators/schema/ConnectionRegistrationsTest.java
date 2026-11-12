package no.sikt.graphitron.rewrite.generators.schema;

import no.sikt.graphitron.rewrite.SchemaShapeRenderTestSupport;
import com.palantir.javapoet.CodeBlock;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;

/**
 * Which connection-shaped types the schema-shape classes register: a connection and its edge none,
 * their fields being registered by the store-rendered {@code Connections} class (pinned in
 * {@code ConnectionsEmitterTest}), and the shared page info exactly when it declares a field of the
 * specification.
 */
@UnitTier
class ConnectionRegistrationsTest {

    @Test
    void connectionAndEdge_carryNoRegistrationBody() {
        GraphitronSchema schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { id: ID }
            type Query { films: [Film!]! @asConnection @defaultOrder(primaryKey: true) }
            """);
        assertThat(registersFetchers(schema, "QueryFilmsConnection")).isFalse();
        assertThat(registersFetchers(schema, "QueryFilmsConnectionEdge")).isFalse();
        Map<String, CodeBlock> bodies = SchemaShapeRenderTestSupport.fetcherRegistrations(
            schema, DEFAULT_OUTPUT_PACKAGE);
        assertThat(bodies).doesNotContainKeys("QueryFilmsConnection", "QueryFilmsConnectionEdge");
    }

    @Test
    void pageInfo_registersExactlyWhenItDeclaresASpecificationField() {
        var declared = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { id: ID }
            type FilmsConnection { edges: [FilmsEdge!]! pageInfo: PageInfo! }
            type FilmsEdge { cursor: String! node: Film! }
            type PageInfo { hasNextPage: Boolean! endCursor: String }
            type Query { films: FilmsConnection }
            """);
        var pageInfo = (no.sikt.graphitron.rewrite.model.GraphitronType.PageInfoType) declared.type("PageInfo");
        assertThat(pageInfo.declaredSpecFields()).containsExactly("hasNextPage", "endCursor");
        assertThat(registersFetchers(declared, "PageInfo")).isTrue();

        var synthesised = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { id: ID }
            type Query { films: [Film!]! @asConnection @defaultOrder(primaryKey: true) }
            """);
        var minted = (no.sikt.graphitron.rewrite.model.GraphitronType.PageInfoType) synthesised.type("PageInfo");
        assertThat(minted.declaredSpecFields())
            .containsExactlyElementsOf(no.sikt.graphitron.rewrite.model.GraphitronType.PageInfoType.SPEC_FIELDS);
        assertThat(registersFetchers(synthesised, "PageInfo")).isTrue();
    }

    private static boolean registersFetchers(GraphitronSchema schema, String typeName) {
        return no.sikt.graphitron.plan.SchemaShapePlanner.produce(schema, DEFAULT_OUTPUT_PACKAGE).stream()
            .filter(row -> row.typeName().equals(typeName))
            .findFirst().orElseThrow()
            .registersFetchers();
    }
}
