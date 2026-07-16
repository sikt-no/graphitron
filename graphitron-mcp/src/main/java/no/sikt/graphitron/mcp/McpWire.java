package no.sikt.graphitron.mcp;

import no.sikt.graphitron.rewrite.catalog.CatalogFacts;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.catalog.SourceWalker;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Shared MCP wire helpers for the structured read-tools (catalog tools, code / schema /
 * diagnostics tools). One home for the conventions the slices agree on so they
 * cannot drift: argument coercion, the opaque page-cursor convention, the stable-ID grammar
 * the edges tool walks, the source-location wire shape, and the typed source-location join the
 * code tools layer over the LSP source index.
 *
 * <p>Package-private: these are wire-mapping mechanics internal to the MCP module, not part of
 * the server's public surface.
 */
final class McpWire {

    private McpWire() {}

    // ---- argument coercion (lenient: MCP clients send JSON, numbers may arrive as strings) ----

    static Optional<String> stringArg(Map<String, Object> args, String name) {
        if (args == null) return Optional.empty();
        Object value = args.get(name);
        return value instanceof String s && !s.isBlank() ? Optional.of(s) : Optional.empty();
    }

    static int intArg(Map<String, Object> args, String name, int fallback) {
        if (args == null) return fallback;
        Object value = args.get(name);
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    // ---- opaque page cursor: a base64-encoded offset into the stable ID ordering ----

    /**
     * Opaque page cursor: a base64-encoded offset into the stable ordering. Opaque so the wire
     * contract does not promise offset semantics; a malformed or absent cursor decodes to offset 0.
     */
    static String encodeCursor(int offset) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(Integer.toString(offset).getBytes(StandardCharsets.UTF_8));
    }

    static int decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return 0;
        try {
            int offset = Integer.parseInt(
                new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
            return Math.max(offset, 0);
        } catch (IllegalArgumentException ignored) {
            return 0;
        }
    }

    /** A page of items plus the cursor for the next page (absent on the last page). */
    record Page<T>(List<T> items, Optional<String> nextCursor) {}

    /**
     * Pages {@code all} by the {@code limit} / opaque-{@code cursor} convention: an offset into
     * the stable ordering, {@code nextCursor} absent once the tail is reached. A {@code limit}
     * below 1 falls back to {@code defaultLimit}.
     */
    static <T> Page<T> page(List<T> all, Map<String, Object> args, int defaultLimit) {
        int limit = intArg(args, "limit", defaultLimit);
        if (limit < 1) limit = defaultLimit;
        int offset = decodeCursor(stringArg(args, "cursor").orElse(null));
        int from = Math.min(offset, all.size());
        int to = Math.min(from + limit, all.size());
        var items = List.copyOf(all.subList(from, to));
        return new Page<>(items, to < all.size() ? Optional.of(encodeCursor(to)) : Optional.empty());
    }

    // ---- stable cross-tool node IDs (binding principle) ----
    //
    // The edges tool walks these IDs, so the grammar is settled here, in one
    // place, so a later slice does not invent a fourth convention. Three separators are in use,
    // each over identifiers that cannot themselves contain the separator (so every form
    // round-trips by splitting):
    //   - {@code .} qualifies a table by its schema ({@code schema.table}; CatalogFacts key).
    //   - {@code #} + {@code /} compose a method ref ({@code fqcn#method/arity}; the /arity suffix
    //     disambiguates overloads).
    //   - {@code :} binds a column to its (already-qualified) table ({@code schema.table:column}).
    // The structured owner of these forms is the {@link NodeRef} hierarchy, which composes
    // each from its resolved parts; these helpers are the shared composers so the bare-string form
    // is produced in exactly one place.

    /**
     * Method-ref ID: {@code fqcn#method/arity}. Carries the {@link SourceWalker.MethodKey}
     * {@code (className, methodName, paramCount)} triple the source join uses; the {@code /arity}
     * suffix disambiguates overloads.
     */
    static String methodRef(String className, String methodName, int arity) {
        return className + "#" + methodName + "/" + arity;
    }

    /**
     * Column-of-table ID: {@code schema.table:column}. The {@code :} is a wire separator only (SQL
     * identifiers carry no colon, so the form round-trips: the table half is directly
     * {@code catalog.describe}-able by splitting on the last colon). Composed from the
     * already-qualified {@code schema.table} table ID and the bare SQL column name.
     */
    static String columnId(String qualifiedTable, String sqlColumn) {
        return qualifiedTable + ":" + sqlColumn;
    }

    /**
     * Splits a schema-qualified table ID ({@code schema.table}; the {@code CatalogFacts} key, the
     * same form {@link CatalogFacts.Table#qualifiedName() qualifiedName} composes) back into its
     * {@code [schema, name]} halves on the first {@code .}. SQL schema identifiers carry no dot, so
     * the first separator is the schema boundary; an unqualified id (no dot) yields an empty schema
     * and the whole string as the name. The inverse of the {@code schema + "." + name} composition.
     */
    static String[] splitQualifiedTable(String qualifiedTable) {
        int dot = qualifiedTable.indexOf('.');
        return dot < 0
            ? new String[] {"", qualifiedTable}
            : new String[] {qualifiedTable.substring(0, dot), qualifiedTable.substring(dot + 1)};
    }

    // ---- source-location wire shape ----

    /** Maps a {@link CompletionData.SourceLocation} onto the {@code {uri, line, column}} wire shape. */
    static Map<String, Object> location(CompletionData.SourceLocation loc) {
        var m = new LinkedHashMap<String, Object>();
        m.put("uri", loc.uri());
        m.put("line", loc.line());
        m.put("column", loc.column());
        return m;
    }

    /**
     * Typed outcome of joining a code reference against the LSP source index, mirroring the LSP
     * {@code DefinitionTarget}: a resolved location, a not-yet-indexed degraded state (the
     * {@code .java} source cadence has not caught up), or an overload-ambiguous outcome (the
     * {@code (class, name, arity)} key cannot pick one overload). Never a silent drop and never a
     * hard failure; the tool handlers map each arm to the wire shape (location present / absent).
     */
    sealed interface SourceJoin permits SourceJoin.Resolved, SourceJoin.NotIndexed, SourceJoin.Ambiguous {
        record Resolved(CompletionData.SourceLocation location) implements SourceJoin {}

        record NotIndexed() implements SourceJoin {}

        record Ambiguous() implements SourceJoin {}
    }

    /** Joins a method against the source index by its {@code (className, name, arity)} key. */
    static SourceJoin joinMethod(SourceWalker.Index index, String className, String methodName, int arity) {
        var key = new SourceWalker.MethodKey(className, methodName, arity);
        var decl = index.methods().get(key);
        if (decl != null) return new SourceJoin.Resolved(decl.location());
        if (index.ambiguousMethods().contains(key)) return new SourceJoin.Ambiguous();
        return new SourceJoin.NotIndexed();
    }

    /** Joins a class against the source index by FQN. Classes carry no overload-ambiguity arm. */
    static SourceJoin joinClass(SourceWalker.Index index, String fqn) {
        var decl = index.classes().get(fqn);
        return decl != null ? new SourceJoin.Resolved(decl.location()) : new SourceJoin.NotIndexed();
    }

    /**
     * Writes a join outcome onto a wire entry: {@code location} present when resolved, a
     * {@code locationStatus} marker ({@code "notIndexed"} / {@code "ambiguous"}) on the degraded
     * arms so an agent can tell an un-rewalked {@code .java} from a missing method, without the
     * read failing. Exhaustive over the {@link SourceJoin} permits.
     */
    static void writeLocation(Map<String, Object> entry, SourceJoin join) {
        switch (join) {
            case SourceJoin.Resolved r -> entry.put("location", location(r.location()));
            case SourceJoin.NotIndexed ignored -> entry.put("locationStatus", "notIndexed");
            case SourceJoin.Ambiguous ignored -> entry.put("locationStatus", "ambiguous");
        }
    }

    // ---- snapshot availability / freshness axes ----

    /**
     * Writes the live snapshot's two orthogonal axes onto a result so a reader can tell whether
     * the projection it just read is current relative to the schema (the benign
     * same-cadence story). Keyed {@code snapshotAvailability} / {@code snapshotFreshness} so the
     * axes never collide with a tool's own payload fields. Exhaustive over the
     * {@link LspSchemaSnapshot} sealed permits; a new arm forces a choice here.
     */
    static void writeSnapshotAxes(Map<String, Object> fields, LspSchemaSnapshot snapshot) {
        switch (snapshot) {
            case LspSchemaSnapshot.Unavailable ignored -> fields.put("snapshotAvailability", "Unavailable");
            case LspSchemaSnapshot.Built.Current ignored -> {
                fields.put("snapshotAvailability", "Built");
                fields.put("snapshotFreshness", "Current");
            }
            case LspSchemaSnapshot.Built.Previous ignored -> {
                fields.put("snapshotAvailability", "Built");
                fields.put("snapshotFreshness", "Previous");
            }
        }
    }

    /** Puts {@code value} under {@code key} only when non-null; keeps absent fields out of the wire shape. */
    static void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) map.put(key, value);
    }
}
