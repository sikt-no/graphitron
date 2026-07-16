package no.sikt.graphitron.rewrite;

import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLTypeUtil;
import no.sikt.graphitron.rewrite.model.GraphitronType.ConnectionType;
import no.sikt.graphitron.rewrite.model.GraphitronType.EdgeType;
import no.sikt.graphitron.rewrite.model.GraphitronType.PageInfoType;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the assembled-schema delta the connection rebuild produces, the one surface
 * the projection-snapshot differential is blind to (it flattens assembled-schema identity). Folding
 * connection synthesis into the field-first walk made the registry the single producer of the
 * synthesised-type set; {@code rebuildAssembledForConnections} consumes that set rather than
 * re-deriving it, so the registry and the assembled schema cannot drift. This test asserts both
 * sides of that delta agree: the synthesised Connection / Edge / PageInfo appear on the rebuilt
 * assembled schema and are classified on the model, and the carrier field is rewritten to point at
 * the Connection with {@code first} / {@code after} present.
 */
@PipelineTier
class ConnectionAssembledDeltaPipelineTest {

    @Test
    void directiveDrivenCarrier_assembledSchemaAndModelAgreeOnSynthesisedTypes() {
        String sdl = """
            type Film @table(name: "film") { id: ID }
            type Query {
                films: [Film!]! @asConnection @defaultOrder(primaryKey: true)
            }
            """;
        var bundle = TestSchemaHelper.buildBundle(sdl);
        GraphQLSchema assembled = bundle.assembled();

        // The synthesised types are absent from the SDL but present on the rebuilt assembled schema.
        assertThat(assembled.getType("QueryFilmsConnection")).isInstanceOf(GraphQLObjectType.class);
        assertThat(assembled.getType("QueryFilmsEdge")).isInstanceOf(GraphQLObjectType.class);
        assertThat(assembled.getType("PageInfo")).isInstanceOf(GraphQLObjectType.class);

        // The carrier field's return type is rewritten to the Connection, with first/after added.
        var carrier = ((GraphQLObjectType) assembled.getType("Query")).getFieldDefinition("films");
        assertThat(((graphql.schema.GraphQLNamedType) GraphQLTypeUtil.unwrapAll(carrier.getType())).getName())
            .isEqualTo("QueryFilmsConnection");
        assertThat(carrier.getArgument("first")).isNotNull();
        assertThat(carrier.getArgument("after")).isNotNull();

        // No drift: the model registry classifies exactly those synthesised names as the connection arms.
        assertThat(bundle.model().types().get("QueryFilmsConnection")).isInstanceOf(ConnectionType.class);
        assertThat(bundle.model().types().get("QueryFilmsEdge")).isInstanceOf(EdgeType.class);
        assertThat(bundle.model().types().get("PageInfo")).isInstanceOf(PageInfoType.class);
    }
}
