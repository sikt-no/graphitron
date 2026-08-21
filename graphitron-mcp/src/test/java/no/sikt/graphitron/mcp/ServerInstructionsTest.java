package no.sikt.graphitron.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import no.sikt.graphitron.model.boot.StoreReader;
import no.sikt.graphitron.model.read.StoreHandle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the bundled ambient prose (the {@code instructions} string returned in the MCP
 * {@code initialize} handshake) and the three claims it makes to an agent.
 *
 * <p>The instructions string is <em>ambient</em>: the client receives it once at
 * {@code initialize} and holds it for the session, so it is the agent's standing model of this
 * server and its cost is paid on every request. That makes it the one surface where a tool the
 * prose never names is effectively undiscovered, and a tool the prose names but the server no
 * longer registers is an active lie. Both directions are asserted here.
 *
 * <p>Three assertions, one subject:
 *
 * <ul>
 *   <li>A bidirectional coverage pin over the advertised surface, run against the ambient string
 *       and against the manual's tool table, both derived from a booted server rather than from a
 *       hand-written list. Partition shape follows {@code VariantCoverageTest}; the
 *       producer-and-hand-written-view shape follows
 *       {@code MojoDocCoverageTest.everyMojoParameterHasADocRowAndViceVersa}.
 *   <li>A pin under the first cross-tool convention the prose states, that a paged tool's first
 *       line carries the total before paging.
 *   <li>A character ceiling on the composed string.
 * </ul>
 *
 * <p>Deliberately not named {@code ...CoverageTest}: only the first assertion is coverage, and a
 * name promising coverage would make the other two look misfiled.
 */
class ServerInstructionsTest {

    // ---- the advertised surface ----

    private static final String TOOL = "tool";
    private static final String RESOURCE = "resource";
    private static final String PROMPT = "prompt";

    /**
     * One advertised name plus the namespace it lives in. The three namespaces stay distinct rather
     * than collapsing into a flat {@code Set<String>}: a bare failure line reading "unrouted:
     * directives" would not say which surface that is, a tool and a resource may legitimately share
     * a name, and a per-surface exemption reason needs the kind to hang off.
     */
    private record Advertised(String kind, String name) implements Comparable<Advertised> {
        @Override
        public String toString() {
            return kind + " " + name;
        }

        @Override
        public int compareTo(Advertised other) {
            int byKind = kind.compareTo(other.kind);
            return byKind != 0 ? byKind : name.compareTo(other.name);
        }
    }

    /**
     * Deliberate omissions from the ambient routing block. Empty, and the composition of the
     * instructions string (base resource plus the conditional {@code execute} tail) is what lets it
     * be: the tail means the per-boot assertion below passes with nothing exempted, rather than with
     * {@code execute} parked here as a permanent placeholder.
     *
     * <p>The slot stays because it is where a future deliberate omission has to become visible.
     * Every entry added must carry a written reason for why an agent is better served by not being
     * routed to that name; "it did not fit" is not one.
     */
    private static final Set<Advertised> EXEMPT_FROM_AMBIENT_ROUTING = Set.of();

    /** Deliberate omissions from the manual. Same rule as above: a written reason per entry. */
    private static final Set<Advertised> EXEMPT_FROM_MANUAL = Set.of();

    // ---- token extraction ----

    private static final Pattern BACKTICKED = Pattern.compile("`([^`]+)`");

    /**
     * A backticked token that reads as a tool claim: a lowercase identifier, optionally
     * dot-separated. Matched in full, which is what excludes the other backticked vocabulary in the
     * prose without an ignore list that would grow with it: {@code Type.field} and
     * {@code Unclassified} start uppercase, {@code .graphqls} starts with the dot,
     * {@code mvn graphitron:dev} carries a space and a colon, and {@code fqcn#method/arity} carries
     * a {@code #} and a {@code /}.
     */
    private static final Pattern TOOL_SHAPED = Pattern.compile("[a-z][a-z0-9]*(?:\\.[a-z0-9]+)*");

    // ---- the manual, the second hand-maintained view of the same surface ----

    private static final String MANUAL_PATH = "docs/manual/how-to/mcp-agent-context.adoc";

    private static final Pattern TABLE_DELIM = Pattern.compile("^\\|===\\s*$", Pattern.MULTILINE);

    /** Identifies the tool table specifically, against the TLDR table earlier in the same document. */
    private static final Pattern TOOL_TABLE_HEADER =
        Pattern.compile("^\\|\\s+Tool\\s+\\|\\s+What it answers\\s*$", Pattern.MULTILINE);

    // ---- the ambient size ceiling ----

    /**
     * Characters, not the words the {@code DocSizeBudgetTest} precedent counts: the real cost is
     * tokens, and a routing table's words are much shorter than prose words, so a word count would
     * understate this file against the document that budget governs.
     *
     * <p>The value is a round number roughly a quarter above the composed string as first written,
     * so a wording pass does not fire the pin but a new paragraph has to be paid for. Arbitrary in
     * its exact value, principled in its existence: unlike a document loaded per design consult,
     * this string is charged on every request of every session, so an append should displace
     * something rather than accrete. Raising it is an argued change, not a bumped constant.
     */
    private static final int AMBIENT_CHARACTER_BUDGET = 3_600;

    // ---- coverage ----

    @Test
    void ambientInstructionsRouteToEveryAdvertisedNameAndViceVersa() throws Exception {
        // Per boot, not over a union: the instructions string is composed to match the conditional
        // registration, so the no-database boot must not mention execute and the configured boot must.
        // A union check would let either arm drift.
        assertAmbientRoutingOnBootWith(null, "no dev database configured");
        assertAmbientRoutingOnBootWith(executeConfig(), "a dev database configured");
    }

    @Test
    void theManualDocumentsEveryAdvertisedNameAndViceVersa() throws Exception {
        // The manual is a single static document that documents execute unconditionally, with an
        // "appears only when a dev database is configured" note, so it is pinned against the
        // configured boot's full surface rather than per boot.
        try (var server = server(executeConfig());
             var client = connect(server.port())) {
            client.initialize();
            String manual = Files.readString(locateManual(), StandardCharsets.UTF_8);

            // The two directions get different spans, because the document's shape does not support
            // one rule for both. Presence runs over the whole document: every advertised name is
            // already backticked somewhere in it, the `about` prompt and the `directives` resource
            // included, and neither is a table row. Staleness runs over the tool table's first column
            // only, where a backticked token is unambiguously a tool claim; the wider prose backticks
            // tool-shaped tokens that are not tools (`dev`, `graphitron`).
            assertPartition(MANUAL_PATH, advertisedSurface(client),
                backtickedTokens(manual), toolTableFirstColumn(manual), EXEMPT_FROM_MANUAL);
        }
    }

    /**
     * The advertised surface is exactly this, named rather than merely self-consistent. The two pins
     * above assert that the ambient string, the manual and the booted server agree with each other,
     * which a tool added to all three at once satisfies; this one is what makes adding or removing a
     * tool a decision somebody writes down. A registration that changes fails here with both names in
     * the message, and whoever trips it either updates this list on purpose or has found the accident
     * it exists to catch.
     *
     * <p>The configured boot, so {@code execute} is present. Its absence on a no-database boot is the
     * subject of the composition assertion above rather than of this one.
     */
    private static final Set<Advertised> EXPECTED_SURFACE = Set.of(
        new Advertised(TOOL, "status"),
        new Advertised(TOOL, "catalog.tables"),
        new Advertised(TOOL, "catalog.describe"),
        new Advertised(TOOL, "catalog.search"),
        new Advertised(TOOL, "schema"),
        new Advertised(TOOL, "diagnostics"),
        new Advertised(TOOL, "diagnostics.aggregate"),
        new Advertised(TOOL, "code"),
        new Advertised(TOOL, "docs.search"),
        new Advertised(TOOL, "store.console"),
        new Advertised(TOOL, "execute"),
        new Advertised(RESOURCE, "directives"),
        new Advertised(PROMPT, "about"));

    @Test
    void theAdvertisedSurfaceIsExactlyTheNamedSet() throws Exception {
        try (var server = server(executeConfig());
             var client = connect(server.port())) {
            client.initialize();

            assertThat(advertisedSurface(client))
                .as("the advertised surface changed; update EXPECTED_SURFACE deliberately or revert "
                    + "the registration")
                .containsExactlyInAnyOrderElementsOf(EXPECTED_SURFACE);
        }
    }

    private static void assertAmbientRoutingOnBootWith(ExecuteTool.Config config, String boot)
        throws Exception {
        try (var server = server(config);
             var client = connect(server.port())) {
            String instructions = client.initialize().instructions();
            assertThat(instructions).as("the initialize handshake carries ambient instructions").isNotNull();

            var routed = toolShapedTokens(instructions);
            assertPartition("the ambient instructions (" + boot + ")",
                advertisedSurface(client), routed, routed, EXEMPT_FROM_AMBIENT_ROUTING);
        }
    }

    /**
     * The shared bidirectional assertion. {@code presence} is what the view mentions (the forward
     * direction, "advertised is routed"); {@code claims} is what the view asserts is a tool (the
     * reverse direction, "routed is advertised"). They are the same set for the ambient string and
     * two different spans for the manual.
     */
    private static void assertPartition(
        String view, Set<Advertised> advertised,
        Set<String> presence, Set<String> claims, Set<Advertised> exempt
    ) {
        assertThat(advertised)
            .as("%s: no advertised names were derived; the booted server, not the view, is broken", view)
            .isNotEmpty();
        assertThat(claims)
            .as("%s: no tool-shaped names were extracted, so the reverse direction would pass "
                + "vacuously; the extraction is broken, not the view", view)
            .isNotEmpty();

        var unrouted = new TreeSet<Advertised>();
        for (var a : advertised) {
            if (!exempt.contains(a) && !presence.contains(a.name())) unrouted.add(a);
        }
        assertThat(unrouted)
            .as("%s: advertised names the view never names. An agent cannot call what nothing "
                + "points it at, so add a line for each or exempt it with a written reason.", view)
            .isEmpty();

        var advertisedNames = advertised.stream().map(Advertised::name).collect(java.util.stream.Collectors.toSet());
        var stale = new TreeSet<>(claims);
        stale.removeAll(advertisedNames);
        assertThat(stale)
            .as("%s: names the view presents as tools that the server does not advertise. This is "
                + "the direction that actively misleads: a line surviving a rename or a removal "
                + "points an agent at a tool that is not there.", view)
            .isEmpty();

        var staleExemptions = new TreeSet<>(exempt);
        staleExemptions.removeAll(advertised);
        assertThat(staleExemptions)
            .as("%s: exemptions for names the server no longer advertises; drop them", view)
            .isEmpty();

        var deadExemptions = new TreeSet<Advertised>();
        for (var a : exempt) {
            if (presence.contains(a.name())) deadExemptions.add(a);
        }
        assertThat(deadExemptions)
            .as("%s: names exempted from the view that the view names anyway; drop the exemption", view)
            .isEmpty();
    }

    // ---- convention 1: a paged tool's first line carries the total before paging ----

    /**
     * A paged tool paired with the structured-content key its page lands under, and whatever else the
     * tool requires to answer at all: {@code code} takes a mandatory {@code kind}, the convention under
     * test being about the summary line rather than about an argument-free call.
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

    /**
     * Exactly the tools that take a cursor. The scope is the claim's honest grain:
     * {@code docs.search} and {@code catalog.search} do carry counts but take no cursor, so "the total
     * before paging" names nothing for them, and their warm-degradation arms return a bare notice with
     * no tool prefix and no number.
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
        try (var build = StoreBackedBuild.run(tmp, "paged", PAGED_SDL,
                 List.of(StoreFixture.testClassesRoot()));
             var server = server(null, build.handle(), build.reader());
             var client = connect(server.port())) {
            client.initialize();
            var advertised = advertisedSurface(client);

            for (var tool : PAGED_TOOLS) {
                assertThat(advertised)
                    .as("%s is a paged tool this pin covers, so it must be advertised", tool.name())
                    .contains(new Advertised(TOOL, tool.name()));

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
     * The store half of the paged fixture: a real capture, since the tools reading the store want
     * rows a pipeline run wrote rather than rows a test asserted into place. The schema is chosen to
     * clear the per-tool minimum this pin needs, which a capture does not promise by construction:
     * two coordinates naming a column no table declares yield the diagnostics, and the generated test
     * model's census yields the tables.
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

    // ---- the ambient size ceiling ----

    @Test
    void theComposedAmbientStringStaysWithinItsCharacterBudget() throws Exception {
        // The composed arm is the worst case, so it is the one measured.
        try (var server = server(executeConfig());
             var client = connect(server.port())) {
            int length = client.initialize().instructions().length();
            assertThat(length)
                .as("the composed ambient instructions run %d characters against a %d budget. "
                    + "This string is sent once per session and charged on every request, so an "
                    + "append has to be terse or displace something. Raising the ceiling is a "
                    + "decision to argue, not a constant to bump.", length, AMBIENT_CHARACTER_BUDGET)
                .isLessThanOrEqualTo(AMBIENT_CHARACTER_BUDGET);
        }
    }

    // ---- helpers: the advertised surface, derived from a booted server ----

    /**
     * Reads the surface off the SDK client rather than restating it. A hand-written expected list
     * would drift exactly the way the instructions drifted; derived, a newly registered tool fails
     * this test on the commit that registers it.
     */
    private static Set<Advertised> advertisedSurface(McpSyncClient client) {
        var surface = new TreeSet<Advertised>();
        client.listTools().tools().forEach(t -> surface.add(new Advertised(TOOL, t.name())));
        client.listResources().resources().forEach(r -> surface.add(new Advertised(RESOURCE, r.name())));
        client.listPrompts().prompts().forEach(p -> surface.add(new Advertised(PROMPT, p.name())));
        return surface;
    }

    private static Set<String> backtickedTokens(String text) {
        var tokens = new TreeSet<String>();
        Matcher m = BACKTICKED.matcher(text);
        while (m.find()) tokens.add(m.group(1));
        return tokens;
    }

    /**
     * Presence is checked in backticked form throughout, and that is load-bearing rather than
     * cosmetic: the bare word "schema" runs through the prose, so a substring check would pass
     * vacuously on the very tool the routing gap was observed against.
     */
    private static Set<String> toolShapedTokens(String text) {
        var tokens = new TreeSet<String>();
        for (String token : backtickedTokens(text)) {
            if (TOOL_SHAPED.matcher(token).matches()) tokens.add(token);
        }
        return tokens;
    }

    /**
     * The tool table's first column, split so a shared cell yields each of its names. Rows are
     * blank-line separated with one cell per line, the convention throughout this document, so the
     * first non-blank line of a row is its first column.
     */
    private static Set<String> toolTableFirstColumn(String manual) {
        var names = new TreeSet<String>();
        for (String block : TABLE_DELIM.split(manual)) {
            if (!TOOL_TABLE_HEADER.matcher(block).find()) continue;
            for (String row : block.split("\\R\\s*\\R+")) {
                if (TOOL_TABLE_HEADER.matcher(row).find()) continue;
                String firstLine = row.lines().filter(s -> !s.isBlank()).findFirst().orElse("");
                if (!firstLine.startsWith("|")) continue;
                names.addAll(backtickedTokens(firstLine));
            }
        }
        return names;
    }

    /** Walk-up doc location, as the doc-coverage precedents do; surefire runs from the module dir. */
    private static Path locateManual() {
        Path cwd = Path.of("").toAbsolutePath();
        for (Path p = cwd; p != null; p = p.getParent()) {
            Path candidate = p.resolve(MANUAL_PATH);
            if (Files.isRegularFile(candidate)) return candidate;
        }
        throw new IllegalStateException("Could not locate " + MANUAL_PATH + " by walking up from " + cwd);
    }

    // ---- helpers: driving the paged tools ----

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

    // ---- fixtures ----

    /**
     * A dev-database configuration that is never connected to: the execute tool is registered from
     * it at boot, which is all the conditional arm needs.
     */
    private static ExecuteTool.Config executeConfig() {
        return new ExecuteTool.Config(
            new DevQueryExecutor.Wiring("com.example", Path.of("target/graphitron-classes"), List.of()),
            new DevQueryExecutor.DbConfig("jdbc:postgresql://localhost/dev", "dev", "dev", "POSTGRES", null),
            false);
    }

    private static GraphitronMcpServer server(ExecuteTool.Config executeConfig) throws IOException {
        return server(executeConfig, null, null);
    }

    private static GraphitronMcpServer server(ExecuteTool.Config executeConfig,
        StoreHandle storeHandle, StoreReader storeReader) throws IOException {
        return new GraphitronMcpServer(
            new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
            null, null, null, executeConfig, storeHandle, storeReader);
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
