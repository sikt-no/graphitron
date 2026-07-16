package no.sikt.graphitron.mcp;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import no.sikt.graphitron.mcp.rag.AsyncWarm;
import no.sikt.graphitron.mcp.rag.Embedder;
import no.sikt.graphitron.mcp.rag.EmbeddingStore;
import no.sikt.graphitron.mcp.rag.WarmState;
import no.sikt.graphitron.mcp.rag.docs.DocChunk;
import no.sikt.graphitron.mcp.rag.docs.DocsBundle;
import no.sikt.graphitron.mcp.rag.docs.DocsIndex;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code docs.search} tool: natural-language retrieval over the bundled public manual, the
 * first of the two semantic tools and the first to ride the {@link AsyncWarm async-warm lifecycle}
 * inside the server. An author who does not yet know which directive or pattern solves their problem
 * asks in prose and gets the relevant manual passages, rather than needing the vocabulary to grep for.
 *
 * <p>Held as one instance per server (like the reverse-edge cache), constructed with the shared
 * embedder warm and the docs-index warm. Either warm may be {@code null} when the server is stood up
 * without RAG (the structured-tool tests, an IDE run off un-embedded classes); a null warm reads as
 * still-warming, so the tool is always advertised and degrades cleanly to the structured tools.
 *
 * <p><strong>Dimension guard.</strong> When both warms first reach {@code Ready}, the runtime
 * embedder's {@link Embedder#dimension()} (the source of truth) is reconciled once against the
 * bundle's build-time dimension; a mismatch degrades with a clear message rather than letting a
 * build/runtime model-version skew surface as an opaque Lucene KNN width error. The verdict is
 * memoised so the reconcile runs once, not per query.
 */
final class DocsSearchTool {

    /** The rendered public-manual site root; deep links resolve against it. */
    private static final String SITE_BASE = "https://graphitron.sikt.no/";

    /** The repo-relative prefix the corpus lives under; stripped to map a source path onto the site. */
    private static final String DOCS_PREFIX = "docs/";

    static final int DEFAULT_K = 5;

    private final AsyncWarm<Embedder> embedderWarm;
    private final AsyncWarm<DocsIndex> docsWarm;

    /** Null until both warms first reach Ready and the guard runs; then the cached verdict. */
    private volatile Boolean dimensionGuardPassed;

    DocsSearchTool(AsyncWarm<Embedder> embedderWarm, AsyncWarm<DocsIndex> docsWarm) {
        this.embedderWarm = embedderWarm;
        this.docsWarm = docsWarm;
    }

    McpServerFeatures.SyncToolSpecification specification() {
        var tool = McpSchema.Tool.builder("docs.search", Map.of(
                "type", "object",
                "properties", Map.of(
                    "query", Map.of("type", "string",
                        "description", "A natural-language question about graphitron usage."),
                    "k", Map.of("type", "integer",
                        "description", "Maximum passages to return (default " + DEFAULT_K + ").")),
                "required", List.of("query")))
            .title("Search the documentation")
            .description("Answers natural-language questions over the bundled graphitron manual by "
                + "semantic + lexical retrieval: pass a question and get the most relevant passages "
                + "with their heading path, source location, and a deep link into the rendered site. "
                + "Use it to find which directive or pattern solves a problem when you do not yet know "
                + "the vocabulary to grep for. While the index is still warming (it loads "
                + "asynchronously at startup) this returns a short notice and no passages; the "
                + "structured tools (catalog, schema, services, conditions, records, diagnostics, "
                + "edges) are available meanwhile.")
            .build();
        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> search(request.arguments()))
            .build();
    }

    McpSchema.CallToolResult search(Map<String, Object> args) {
        String query = McpWire.stringArg(args, "query").orElse("");
        int k = McpWire.intArg(args, "k", DEFAULT_K);
        if (k < 1) {
            k = DEFAULT_K;
        }

        WarmState<Embedder> embedderState = stateOf(embedderWarm);
        WarmState<DocsIndex> docsState = stateOf(docsWarm);
        if (!(embedderState instanceof WarmState.Ready<Embedder> embedderReady)
            || !(docsState instanceof WarmState.Ready<DocsIndex> docsReady)) {
            return degraded(WarmState.degradationMessage(firstNotReady(embedderState, docsState)));
        }

        Embedder embedder = embedderReady.handle();
        DocsIndex index = docsReady.handle();
        if (!dimensionGuardPasses(embedder, index)) {
            return degraded("The semantic index was built with a different embedding model "
                + "(bundle dimension " + index.dimension() + ", runtime embedder " + embedder.dimension()
                + "); docs.search is unavailable until the bundle is rebuilt. The structured tools "
                + "remain available.");
        }

        List<EmbeddingStore.Hit> hits = index.store().search(embedder.embedQuery(query), k);
        return passages(query, hits);
    }

    /**
     * The dimension reconcile, run at most once. Synchronised so concurrent first calls compute one
     * verdict; thereafter the {@code volatile} read short-circuits without locking.
     */
    private boolean dimensionGuardPasses(Embedder embedder, DocsIndex index) {
        Boolean cached = dimensionGuardPassed;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (dimensionGuardPassed == null) {
                dimensionGuardPassed = embedder.dimension() == index.dimension();
            }
            return dimensionGuardPassed;
        }
    }

    /** Maps the store hits to the {@code passages} result shape plus a text summary naming the top hit. */
    private static McpSchema.CallToolResult passages(String query, List<EmbeddingStore.Hit> hits) {
        var passages = new ArrayList<Map<String, Object>>(hits.size());
        for (EmbeddingStore.Hit hit : hits) {
            DocChunk chunk = DocsBundle.decodePayload(hit.payload());
            var entry = new LinkedHashMap<String, Object>();
            entry.put("headingPath", chunk.headingPath());
            entry.put("sourcePath", chunk.sourcePath());
            entry.put("anchor", chunk.anchor());
            entry.put("text", chunk.text());
            entry.put("score", hit.score());
            entry.put("url", deepLink(chunk.sourcePath(), chunk.anchor()));
            passages.add(entry);
        }

        var fields = new LinkedHashMap<String, Object>();
        fields.put("passages", passages);
        String summary = hits.isEmpty()
            ? "docs.search: no passages matched '" + query + "'."
            : "docs.search: " + hits.size() + " passage(s) for '" + query + "'; top: "
                + String.join(DocChunk.HEADING_SEPARATOR, DocsBundle.decodePayload(hits.getFirst().payload()).headingPath()) + ".";
        return McpSchema.CallToolResult.builder()
            .addTextContent(summary)
            .structuredContent(fields)
            .build();
    }

    /** A degradation result: the shared notice as the text summary, and an empty {@code passages} list. */
    private static McpSchema.CallToolResult degraded(String message) {
        return McpSchema.CallToolResult.builder()
            .addTextContent(message)
            .structuredContent(Map.of("passages", List.of()))
            .build();
    }

    /** The current state of a warm, or a {@code Warming} stand-in when the warm is absent (no RAG). */
    private static <T> WarmState<T> stateOf(AsyncWarm<T> warm) {
        return warm == null ? new WarmState.Warming<>() : warm.state();
    }

    /**
     * The state to report when degrading: prefer a {@code Failed} over a {@code Warming} so a load
     * failure surfaces its cause, falling back to the embedder's state. Never returns a {@code Ready}.
     */
    private static WarmState<?> firstNotReady(WarmState<?> embedderState, WarmState<?> docsState) {
        if (embedderState instanceof WarmState.Failed<?>) {
            return embedderState;
        }
        if (docsState instanceof WarmState.Failed<?>) {
            return docsState;
        }
        return embedderState instanceof WarmState.Ready<?> ? docsState : embedderState;
    }

    /**
     * Derives the rendered-site deep link from a source path and anchor: strip the repo-relative
     * {@code docs/} prefix, swap {@code .adoc} for {@code .html}, and append the section anchor. The
     * site preserves the source directory layout, so {@code docs/manual/reference/directives/table.adoc}
     * renders at {@code manual/reference/directives/table.html}.
     */
    static String deepLink(String sourcePath, String anchor) {
        String relative = sourcePath.startsWith(DOCS_PREFIX)
            ? sourcePath.substring(DOCS_PREFIX.length())
            : sourcePath;
        if (relative.endsWith(".adoc")) {
            relative = relative.substring(0, relative.length() - ".adoc".length()) + ".html";
        }
        String url = SITE_BASE + relative;
        return anchor == null || anchor.isEmpty() ? url : url + "#" + anchor;
    }
}
