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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ambient MCP instructions promise an agent that a paged result's first line carries the total
 * before paging, so it can skip counting the page itself. This pins that claim across the tools
 * that page.
 *
 * <p>It came out of {@code graphitron-mcp}'s own {@code ServerInstructionsTest}, whose other cases
 * read the composed instruction string and the manual and stayed there. This one is here because it
 * needs a build: the {@code diagnostics} rows it counts are written by loaders that consume the
 * walk's own streams, and the {@code code} census needs a classpath root a run captured, so no
 * fixture inside the client can stand it up.
 *
 * <p>Declared in the client's package so the relocation keeps the access the case had.
 */
class PagedTotalOverBuildTest {

    /**
     * The schema is chosen to clear the per-tool minimum this pin needs, which a build does not
     * promise by construction: two coordinates naming a column no table declares yield the
     * diagnostics, and the generated test model's census yields the tables.
     */
    private static final String PAGED_SDL = """
        type Film @table(name: "film") {
          film_id: Int
          missingOne: Int
        }
        type Actor @table(name: "actor") {
          missingTwo: Int
        }
        type Query {
          film: Film
          actor: Actor
        }
        """;

    /**
     * Exactly the tools that take a cursor. The scope is the claim's honest grain:
     * {@code docs.search} and {@code catalog.search} do carry counts but take no cursor, so "the
     * total before paging" names nothing for them, and their warm-degradation arms return a bare
     * notice with no tool prefix and no number.
     */
    private static final List<PagedTool> PAGED_TOOLS = List.of(
        new PagedTool("catalog.tables", "tables"),
        new PagedTool("schema", "types"),
        new PagedTool("diagnostics", "diagnostics"),
        new PagedTool("code", "classes", Map.of("kind", "service")));

    @Test
    void everyPagedToolLeadsWithTheUnpagedTotal(@TempDir Path tmp) throws Exception {
        // The classpath root is the census half of the fixture: the code tool answers from jvm_class,
        // and a build with no classpath roots captures no classes at all. This module's own compiled
        // test classes are more than one, which is what the limit=1 call needs.
        try (var build = StoreBackedBuild.run(tmp, "paged", PAGED_SDL, List.of(testClassesRoot()));
             var server = server(build);
             var client = connect(server.port())) {
            client.initialize();
            var advertised = client.listTools().tools().stream().map(McpSchema.Tool::name).toList();

            for (var tool : PAGED_TOOLS) {
                assertThat(advertised)
                    .as("%s is a paged tool this pin covers, so it must be advertised", tool.name())
                    .contains(tool.name());

                int total = page(client, tool, 1_000).size();
                assertThat(total)
                    .as("the %s fixture must hold more than one entry, or a limit=1 call cannot "
                        + "distinguish the unpaged total from the page size", tool.name())
                    .isGreaterThan(1);

                var firstPage = client.callTool(McpSchema.CallToolRequest.builder(tool.name())
                    .arguments(tool.arguments(1)).build());
                assertThat(items(firstPage, tool.resultKey()))
                    .as("%s honours limit=1", tool.name())
                    .hasSize(1);

                String firstLine = firstLine(firstPage);
                assertThat(leadingTotal(tool.name(), firstLine))
                    .as("the ambient instructions promise an agent that a paged result's first line "
                        + "carries the total before paging, so it can skip counting the page itself. "
                        + "%s returned first line: %s", tool.name(), firstLine)
                    .hasValue(total);
            }
        }
    }

    /**
     * A paged tool paired with the structured-content key its page lands under, and whatever else
     * the tool requires to answer at all: {@code code} takes a mandatory {@code kind}, the
     * convention under test being about the summary line rather than about an argument-free call.
     */
    private record PagedTool(String name, String resultKey, Map<String, Object> required) {

        PagedTool(String name, String resultKey) {
            this(name, resultKey, Map.of());
        }

        /** The tool's arguments for a call bounded at {@code limit}. */
        Map<String, Object> arguments(int limit) {
            var args = new LinkedHashMap<String, Object>(required);
            args.put("limit", limit);
            return args;
        }
    }

    private static List<?> page(McpSyncClient client, PagedTool tool, int limit) {
        var result = client.callTool(McpSchema.CallToolRequest.builder(tool.name())
            .arguments(tool.arguments(limit)).build());
        return items(result, tool.resultKey());
    }

    private static List<?> items(McpSchema.CallToolResult result, String key) {
        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(result.structuredContent()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        var structured = (Map<String, Object>) result.structuredContent();
        assertThat(structured).containsKey(key);
        return (List<?>) structured.get(key);
    }

    private static String firstLine(McpSchema.CallToolResult result) {
        assertThat(result.content()).isNotEmpty();
        assertThat(result.content().getFirst()).isInstanceOf(McpSchema.TextContent.class);
        String text = ((McpSchema.TextContent) result.content().getFirst()).text();
        return text.lines().findFirst().orElse("");
    }

    /** The leading {@code "<tool>: <n> "} count, absent when the line does not open that way. */
    private static Optional<Integer> leadingTotal(String tool, String line) {
        Matcher m = Pattern.compile("^\\Q" + tool + "\\E: (\\d+)\\b").matcher(line);
        return m.find() ? Optional.of(Integer.parseInt(m.group(1))) : Optional.empty();
    }

    /** This module's compiled test classes, the one classpath entry the census is taken over. */
    private static Path testClassesRoot() {
        try {
            return Path.of(PagedTotalOverBuildTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException("test classes root is not a file path", e);
        }
    }

    private static GraphitronMcpServer server(StoreBackedBuild build) throws IOException {
        return new GraphitronMcpServer(loopback(), null, null, null, null,
            build.handle(), build.reader());
    }

    private static InetSocketAddress loopback() {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
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
