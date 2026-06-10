package no.sikt.graphitron.rewrite.test.querydb;

import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R292 runtime-rebuild seam: the descriptions on synthesised Connection/Edge boilerplate must
 * reach the schema a consumer rebuilds via {@link Graphitron#buildSchema}, which is assembled
 * from the generated {@code <Type>} classes ({@code ObjectTypeGenerator} emits
 * {@code b.description(...)} per type and field). This is the second published seam, distinct
 * from the emitted {@code schema.graphqls} file pinned by
 * {@link SchemaSdlEmissionTest#synthesisedConnectionBoilerplateCarriesRelayDescriptions}.
 *
 * <p>Necessary as a dedicated pin because {@link FederationBuildSmokeTest#emittedSdlMatchesRuntimeSchema}
 * compares the two seams through graphql-java's {@code SchemaDiffing}, which walks the schema as a
 * type-element graph and does not treat descriptions as vertices: it stays green whether or not the
 * runtime seam emits descriptions. Reading the description straight off the runtime
 * {@link GraphQLObjectType} closes that gap.
 *
 * <p>Scope mirrors the file-seam test: only the genuinely-synthesised {@code QueryStoresConnection}
 * / {@code QueryStoresEdge} (driven by {@code stores: [Store!]! @asConnection} on the shared
 * fixture). {@code PageInfo} is declared structurally in that fixture, so the synthesis path reuses
 * the consumer object and stamps no description; synthesised-{@code PageInfo} descriptions are
 * pinned at the unit tier by
 * {@code ConnectionPromoterTest.directiveDrivenSynthesis_carriesRelayDescriptionsOnTypesAndFields}.
 */
@PipelineTier
class SynthesisedConnectionRuntimeDescriptionTest {

    @Test
    void runtimeRebuiltSchemaCarriesSynthesisedConnectionDescriptions() {
        GraphQLSchema schema = Graphitron.buildSchema(b -> {});

        GraphQLObjectType connection = schema.getObjectType("QueryStoresConnection");
        assertThat(connection)
            .as("synthesised QueryStoresConnection present in the runtime-rebuilt schema")
            .isNotNull();
        assertThat(connection.getDescription()).isEqualTo("A connection to a list of items.");
        assertThat(connection.getFieldDefinition("edges").getDescription()).isEqualTo("A list of edges.");
        assertThat(connection.getFieldDefinition("nodes").getDescription()).isEqualTo("A list of nodes.");
        assertThat(connection.getFieldDefinition("pageInfo").getDescription())
            .isEqualTo("Information to aid in pagination.");
        assertThat(connection.getFieldDefinition("totalCount").getDescription())
            .isEqualTo("Identifies the total count of items in the connection.");

        GraphQLObjectType edge = schema.getObjectType("QueryStoresEdge");
        assertThat(edge.getDescription()).isEqualTo("An edge in a connection.");
        assertThat(edge.getFieldDefinition("cursor").getDescription()).isEqualTo("A cursor for use in pagination.");
        assertThat(edge.getFieldDefinition("node").getDescription()).isEqualTo("The item at the end of the edge.");
    }
}
