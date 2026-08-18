package no.sikt.graphitron.mcp;

import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Shared MCP wire helpers for the structured read-tools (catalog tools, code / schema /
 * diagnostics tools). One home for the conventions the slices agree on so they
 * cannot drift: argument coercion, the opaque page-cursor convention, the stable-ID grammar the
 * server instructions promise, and the source-location wire shape.
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

    /**
     * The keyset cursor's part joiner, spelled as an escape rather than written into the source: a
     * literal NUL byte in a {@code .java} file is legal inside a string literal and makes the file
     * binary to every tool that reads it.
     */
    private static final String NUL = "\0";

    /**
     * Opaque keyset cursor: the ordering key of the last entry a page emitted, NUL-joined and
     * base64-encoded. Beside {@link #encodeCursor} rather than replacing it, the tools that page an
     * in-memory list still keying pages by offset.
     *
     * <p>The encoding stays opaque for the reason the offset form gives, and the reason is stronger
     * here: nothing on the wire says whether a cursor is a position or a key, so a tool can move
     * from one to the other without the contract moving. NUL is the joiner because SQL identifiers
     * cannot contain it, so every key round-trips by splitting.
     */
    static String encodeKeysetCursor(List<String> key) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(String.join(NUL, key).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decodes a keyset cursor back to its {@code arity} key parts. Absent where the cursor is
     * absent, malformed, or carries the wrong number of parts, which the caller reads as the first
     * page: the same degradation {@link #decodeCursor} applies by clamping to offset 0, and the
     * same reason, a client that mangled a cursor being better served by the head of the ordering
     * than by an error about a value the contract calls opaque.
     */
    static Optional<List<String>> decodeKeysetCursor(String cursor, int arity) {
        if (cursor == null || cursor.isBlank()) return Optional.empty();
        try {
            var parts = List.of(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
                .split(NUL, -1));
            return parts.size() == arity ? Optional.of(parts) : Optional.empty();
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
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

    // ---- stable cross-tool IDs (binding principle) ----
    //
    // Two tools handing back the same thing spell it the same way, so the grammar is settled here in
    // one place rather than at each wire site. Both forms in use are over identifiers that cannot
    // themselves contain the separator, so each round-trips by splitting:
    //   - {@code .} qualifies a table by its schema ({@code schema.table}).
    //   - {@code #} + {@code /} compose a method ref ({@code fqcn#method/arity}; the /arity suffix
    //     disambiguates overloads).
    // The server instructions promise these two plus the {@code Type.field} coordinate, which needs
    // no composer, being what the schema carries already.

    /**
     * Method-ref ID: {@code fqcn#method/arity}. Carries the {@code (className, methodName, arity)}
     * triple the {@code java_} declaration family is matched on, arity being the only ground that
     * family and the classpath census share; the {@code /arity} suffix disambiguates overloads.
     */
    static String methodRef(String className, String methodName, int arity) {
        return className + "#" + methodName + "/" + arity;
    }

    /**
     * Splits a schema-qualified table ID ({@code schema.table}, the form
     * {@link CatalogQueries.TableDetail#qualifiedName() qualifiedName} composes) back into its
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
