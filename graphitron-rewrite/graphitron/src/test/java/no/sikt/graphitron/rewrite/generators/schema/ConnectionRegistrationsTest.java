package no.sikt.graphitron.rewrite.generators.schema;

import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pipeline tests for the connection arm of {@link FetcherRegistrationsEmitter}: verifies that
 * synthesised and structural connection types emit the right set of {@code dataFetcher(…)}
 * registrations, including the {@code totalCount} arm gated on the SDL field's presence.
 */
class ConnectionRegistrationsTest {

    @Test
    void synthesisedConnection_registersTotalCount() {
        var body = bodyFor("""
            type Film @table(name: "film") { id: ID }
            type Query { films: [Film!]! @asConnection @defaultOrder(primaryKey: true) }
            """, "QueryFilmsConnection");
        assertThat(body).contains("\"edges\"");
        assertThat(body).contains("\"nodes\"");
        assertThat(body).contains("\"pageInfo\"");
        assertThat(body).contains("\"totalCount\"");
        assertThat(body).contains("ConnectionHelper::totalCount");
    }

    @Test
    void structuralConnection_withTotalCount_registersTotalCount() {
        var body = bodyFor("""
            type Film @table(name: "film") { id: ID }
            type FilmsConnection { edges: [FilmsEdge!]! nodes: [Film!]! pageInfo: PageInfo! totalCount: Int }
            type FilmsEdge { cursor: String! node: Film! }
            type PageInfo { hasNextPage: Boolean! hasPreviousPage: Boolean! startCursor: String endCursor: String }
            type Query { films: FilmsConnection }
            """, "FilmsConnection");
        assertThat(body).contains("\"totalCount\"");
        assertThat(body).contains("ConnectionHelper::totalCount");
    }

    @Test
    void structuralConnection_withoutTotalCount_doesNotRegisterTotalCount() {
        var body = bodyFor("""
            type Film @table(name: "film") { id: ID }
            type FilmsConnection { edges: [FilmsEdge!]! nodes: [Film!]! pageInfo: PageInfo! }
            type FilmsEdge { cursor: String! node: Film! }
            type PageInfo { hasNextPage: Boolean! hasPreviousPage: Boolean! startCursor: String endCursor: String }
            type Query { films: FilmsConnection }
            """, "FilmsConnection");
        assertThat(body).contains("\"edges\"");
        assertThat(body).contains("\"pageInfo\"");
        assertThat(body).doesNotContain("\"totalCount\"");
        assertThat(body).doesNotContain("::totalCount");
    }

    private static String bodyFor(String sdl, String connectionTypeName) {
        GraphitronSchema schema = TestSchemaHelper.buildSchema(sdl);
        Map<String, CodeBlock> bodies = FetcherRegistrationsEmitter.emit(
            schema, DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);
        var block = bodies.get(connectionTypeName);
        if (block == null) {
            throw new AssertionError("no registration body for connection type '"
                + connectionTypeName + "' in " + bodies.keySet());
        }
        return block.toString();
    }
}
