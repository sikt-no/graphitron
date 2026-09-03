package no.sikt.graphitron.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The server answers that only a real build can produce: a conflicted coordinate, and the three
 * diagnostics families a round writes once its rules have run.
 *
 * <p>These four cases came out of {@code graphitron-mcp}'s own {@code GraphitronMcpServerTest},
 * whose other fifty-odd cases read a captured store and stayed there. They are here because their
 * subject is two tiers agreeing: the rows they assert on are written by loaders that consume the
 * walk's own pre-fuse streams, so no fixture on either side can stand in for them, and a test that
 * needs the build and the server at once belongs above both rather than inside one reaching up.
 *
 * <p>Declared in the client's package so the relocation keeps the access the cases had, and driven
 * through the live server over the wire, which is how an agent reaches these tools.
 */
class BuildBackedDiagnosticsTest {

    /**
     * One coordinate carrying two mutually exclusive claims, so the schema tool has a conflict to
     * report and both rival claims to keep provenance for.
     */
    private static final String CONFLICTED_SDL = """
        type Film @table(name: "film") {
          title: String
        }
        type Query { film: Film }
        type Mutation {
          deleteFilm(id: ID!): Boolean
            @mutation(typeName: DELETE)
            @service(service: {className: "com.example.FilmService", method: "delete"})
        }
        """;

    /**
     * An unresolved column (the error), snake_case names (lint findings), and {@code @table} on an
     * input type (the rule-less advisory through a real producer). The loaders read the walk's own
     * pre-fuse streams, so the rows these cases assert on come from a real pipeline run rather than
     * a hand-built report.
     */
    private static final String DIAGNOSTICS_SDL = """
        type Film @table(name: "film") {
          original_language_id: Int
          badColumn: Int
        }
        input FilmInput @table(name: "film") {
          film_id: Int
        }
        type Query {
          film(where: FilmInput): Film
        }
        """;

    @Test
    @SuppressWarnings("unchecked")
    void schemaReportsAConflictedCoordinatesDirectivesAndMessage(@TempDir Path tmp) throws Exception {
        // A build rather than a capture, so the conflicted coordinate arrives through the same
        // pass a consumer's build runs; the view itself is total over the authored claims.
        try (var build = StoreBackedBuild.run(tmp, "conflicted", CONFLICTED_SDL);
             var server = server(build);
             var client = connect(server.port())) {
            client.initialize();

            var mutation = onlyType(structured(client.callTool(
                McpSchema.CallToolRequest.builder("schema")
                    .arguments(Map.of("type", "Mutation")).build())), "Mutation");
            var conflict = (Map<String, Object>) fieldNamed(mutation, "Mutation.deleteFilm")
                .get("conflict");

            assertThat(conflict)
                .containsEntry("verdict", "CONFLICT")
                .containsEntry("message",
                    "Field 'Mutation.deleteFilm': @service, @mutation are mutually exclusive")
                .containsKey("location")
                // No directive list of its own: the claims below are the contesting directives, one
                // row each, so a caller asks membership rather than comparing a joined set.
                .doesNotContainKey("directives");

            // Both rival claims survive with their own provenance, which is what the conflicted arm
            // of the retired wire carried and what generalises here to every coordinate.
            assertThat((List<Map<String, Object>>) fieldNamed(mutation, "Mutation.deleteFilm")
                .get("claims"))
                .extracting(claim -> claim.get("classifier"))
                .containsExactly("MUTATION", "SERVICE");

            // The relation carries both grains and marks the type grain by a null field name, so a
            // field's violation must not surface as its parent type's.
            assertThat(mutation).doesNotContainKey("conflict");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void diagnosticsReturnsMappedErrorsAndReportsSnapshotFreshness(@TempDir Path tmp) throws Exception {
        try (var build = StoreBackedBuild.run(tmp, "mcp-diagnostics", DIAGNOSTICS_SDL);
             var server = server(build);
             var client = connect(server.port())) {
            client.initialize();

            var structured = structured(client.callTool(McpSchema.CallToolRequest.builder("diagnostics").build()));
            var diagnostics = (List<Map<String, Object>>) structured.get("diagnostics");

            var error = diagnostics.stream()
                .filter(d -> "error".equals(d.get("severity"))).findFirst().orElseThrow();
            assertThat(error)
                .containsEntry("source", "schema")
                .containsEntry("coordinate", "Film.badColumn")
                .containsEntry("rejectionKind", "author-error");
            assertThat((String) error.get("message")).contains("badColumn");
            var location = (Map<String, Object>) error.get("location");
            // The stored path rendered as a file URI, and the 1-based stored position on the
            // 0-based wire. The
            // build fixture writes its schema under the graph name, so that is what the URI ends on.
            assertThat((String) location.get("uri"))
                .startsWith("file:").endsWith("mcp-diagnostics.graphqls");
            assertThat(location).containsEntry("line", 2);

            // The rule-less advisory (a real producer: @table on an input type) keeps surfacing
            // as a warning with no lintRule key.
            assertThat(diagnostics).anySatisfy(d -> {
                assertThat(d).containsEntry("severity", "warning").doesNotContainKey("lintRule");
                assertThat((String) d.get("message")).contains("@table");
            });
            // Snapshot axes reported alongside so an agent can tell whether diagnostics are current.
            assertThat(structured).containsEntry("snapshotAvailability", "Built")
                .containsEntry("snapshotFreshness", "Current");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void diagnosticsProjectsLintRuleIdForLintFindings(@TempDir Path tmp) throws Exception {
        // A lint finding rides the suppression-filtered warning list into the lint_finding arm,
        // and the diagnostics tool projects its typed LintRule id onto the wire, so an MCP-aware
        // agent sees which rule fired.
        try (var build = StoreBackedBuild.run(tmp, "mcp-lint", DIAGNOSTICS_SDL);
             var server = server(build);
             var client = connect(server.port())) {
            client.initialize();

            var structured = structured(client.callTool(McpSchema.CallToolRequest.builder("diagnostics").build()));
            var diagnostics = (List<Map<String, Object>>) structured.get("diagnostics");
            assertThat(diagnostics).anySatisfy(d -> assertThat(d)
                .containsEntry("severity", "warning")
                .containsEntry("lintRule", "field-names-camel-case"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void diagnosticsFiltersBySeverity(@TempDir Path tmp) throws Exception {
        try (var build = StoreBackedBuild.run(tmp, "mcp-severity", DIAGNOSTICS_SDL);
             var server = server(build);
             var client = connect(server.port())) {
            client.initialize();

            var structured = structured(client.callTool(McpSchema.CallToolRequest.builder("diagnostics")
                .arguments(Map.of("severity", "error")).build()));
            var diagnostics = (List<Map<String, Object>>) structured.get("diagnostics");
            assertThat(diagnostics).isNotEmpty();
            assertThat(diagnostics).allSatisfy(d -> assertThat(d).containsEntry("severity", "error"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void diagnosticsAggregateAnswersTheTriagePresetEndToEnd(@TempDir Path tmp) throws Exception {
        try (var build = StoreBackedBuild.run(tmp, "mcp-aggregate", DIAGNOSTICS_SDL);
             var server = server(build);
             var client = connect(server.port())) {
            client.initialize();

            var structured = structured(client.callTool(
                McpSchema.CallToolRequest.builder("diagnostics.aggregate").build()));
            assertThat(structured.get("groupBy")).isEqualTo(List.of("actionable", "kind"));
            var groups = (List<Map<String, Object>>) structured.get("groups");
            assertThat(groups).isNotEmpty();
            long shown = groups.stream().mapToLong(g -> ((Number) g.get("count")).longValue()).sum();
            assertThat(shown + ((Number) structured.get("elidedCount")).longValue())
                .isEqualTo(((Number) structured.get("totalDiagnostics")).longValue());
            assertThat(structured).containsEntry("snapshotAvailability", "Built")
                .containsEntry("snapshotFreshness", "Current");
        }
    }

    /**
     * A server holding the fixture's session store handle and its reader, as the dev loop wires it:
     * the host mints both, so a case that passed only one would be testing a server no session
     * builds.
     */
    private static GraphitronMcpServer server(StoreBackedBuild build) throws IOException {
        return new GraphitronMcpServer(loopback(0), null, null, null, null,
            build.handle(), build.reader());
    }

    /**
     * The one entry the tool returns for {@code typeName}, reached through the narrow rather than by
     * position, so a fixture type added later cannot shift a case onto a different entry.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> onlyType(Map<String, Object> structured, String typeName) {
        var types = (List<Map<String, Object>>) structured.get("types");
        assertThat(types).as("the narrow to %s returns exactly that type", typeName).hasSize(1);
        assertThat(types.getFirst()).containsEntry("typeRef", typeName);
        return types.getFirst();
    }

    /** One field entry of a type by its coordinate id, which is the id every tool spells it as. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> fieldNamed(Map<String, Object> type, String fieldRef) {
        return ((List<Map<String, Object>>) type.get("fields")).stream()
            .filter(field -> fieldRef.equals(field.get("fieldRef")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no field entry for " + fieldRef));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(McpSchema.CallToolResult result) {
        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(result.structuredContent()).isInstanceOf(Map.class);
        return (Map<String, Object>) result.structuredContent();
    }

    private static InetSocketAddress loopback(int port) {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
    }

    private static McpSyncClient connect(int port) {
        var transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port)
            .endpoint(GraphitronMcpServer.MCP_ENDPOINT)
            .build();
        return McpClient.sync(transport)
            .requestTimeout(Duration.ofSeconds(10))
            .initializationTimeout(Duration.ofSeconds(10))
            .build();
    }
}
