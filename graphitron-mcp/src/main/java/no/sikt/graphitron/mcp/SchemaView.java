package no.sikt.graphitron.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import no.sikt.graphitron.model.boot.StoreAnswer;
import no.sikt.graphitron.model.boot.StoreReader;
import no.sikt.graphitron.model.read.StoreHandle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The {@code schema} tool's wire shape: what {@link SchemaQueries} read, rendered.
 *
 * <p>One entry per SDL coordinate, answering five questions and carrying a slot only where the store
 * holds a row for it. An absent slot is therefore an answer rather than a gap, and it is the same
 * answer at every coordinate: nothing claims this, nothing binds it, nothing demands a verdict of it.
 *
 * <p>What retired here was ninety-odd exhaustive switch arms over the classification permits, and the
 * shape of what replaced them is the argument for it: the entry no longer names the generator's internal
 * taxonomy at all. A permit name was a fact about how the classifier is written, promised on a wire that
 * had no business promising it, and holding the arms inside this module was the price of keeping the
 * label stable. The claim, binding and demand vocabularies are the store's, and they are about the
 * author's schema.
 */
final class SchemaView {

    private SchemaView() {}

    /** Default page size when listing types; a {@code type} narrow returns the one entry. */
    static final int DEFAULT_LIMIT = SchemaQueries.DEFAULT_LIMIT;

    /**
     * Answers the {@code schema} call, or refuses where the server holds no store.
     *
     * <p>The refusal is the arm the diagnostics tools established and for their reason: a server built
     * without a store handle can only answer empty, and an empty schema reads as a schema with no types
     * in it. A store present and holding nothing is a different thing and answers, that being the
     * pre-capture state a consumer is genuinely in before their first build.
     *
     * <p>The {@link SchemaLifecycle} axes ride along rather than gating the answer, which is the
     * diagnostics tools' arrangement as well. The store holds every fact the parseable sources yielded
     * whatever the newest parse did, so answering as well as the facts allow and reporting how current
     * they are is strictly better than declining to answer at all. They are read with the payload
     * rather than beside it; {@link SchemaQueries#read} says why.
     */
    static McpSchema.CallToolResult schemaResult(
        StoreHandle store, StoreReader reader, Map<String, Object> args
    ) {
        if (store == null || reader == null) {
            return DiagnosticFacets.refusal("schema");
        }
        var typeFilter = McpWire.stringArg(args, "type");
        int limit = McpWire.intArg(args, "limit", DEFAULT_LIMIT);
        if (limit < 1) limit = DEFAULT_LIMIT;

        return switch (SchemaQueries.read(reader, store.graphName(), typeFilter,
            McpWire.stringArg(args, "cursor"), limit)) {
            case StoreAnswer.Answered<SchemaQueries.SchemaAnswer> read ->
                render(read.value(), typeFilter);
            // An empty type list would read as a graph declaring no types, which is the one thing a
            // read that never finished must not be mistaken for.
            case StoreAnswer.OutOfBudget<SchemaQueries.SchemaAnswer> expired ->
                DiagnosticFacets.outOfBudget("schema", expired);
        };
    }

    /** Maps one answered page onto the wire, with the lifecycle axes it was read alongside. */
    private static McpSchema.CallToolResult render(
        SchemaQueries.SchemaAnswer answer, Optional<String> typeFilter
    ) {
        var page = answer.page();

        var fields = new LinkedHashMap<String, Object>();
        McpWire.writeSnapshotAxes(fields, answer.lifecycle());
        var types = new ArrayList<Map<String, Object>>(page.types().size());
        for (var type : page.types()) {
            types.add(mapType(type, answer.fieldsByType().getOrDefault(type.typeName(), List.of())));
        }
        fields.put("types", types);
        page.nextCursor().ifPresent(cursor -> fields.put("nextCursor", cursor));

        if (typeFilter.isPresent()) {
            if (types.isEmpty()) {
                fields.put("notFound", typeFilter.get());
                return result("schema: type '" + typeFilter.get() + "' is not declared in this graph.",
                    fields);
            }
            return result("schema: type '" + typeFilter.get() + "'.", fields);
        }
        return result("schema: " + page.total() + " type(s); showing " + types.size()
            + (page.nextCursor().isPresent() ? " (more available)" : "") + ".", fields);
    }

    // ---- the type grain ----

    private static Map<String, Object> mapType(
        SchemaQueries.TypeEntry type, List<SchemaQueries.FieldEntry> fields
    ) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("typeRef", type.typeName());
        entry.put("kind", type.kind());

        putList(entry, "claims", type.claims(), SchemaView::mapTypeClaim);
        putDemand(entry, type.demand());
        putConflict(entry, type.conflict());
        putList(entry, "tables", type.tables(), SchemaView::mapTable);
        putList(entry, "backing", type.backing(), SchemaView::mapBacking);
        // No class list of its own: the backing rows above are the contesting classes, each with
        // the population that named it, so this slot carries the arity that makes them a contest.
        type.backingConflict().ifPresent(conflict -> {
            var map = new LinkedHashMap<String, Object>();
            map.put("candidates", conflict.candidates());
            entry.put("backingConflict", map);
        });
        if (!type.unionMembers().isEmpty()) entry.put("unionMembers", type.unionMembers());
        if (!type.implementors().isEmpty()) entry.put("implementors", type.implementors());
        type.node().ifPresent(node -> {
            var map = new LinkedHashMap<String, Object>();
            McpWire.putIfNotNull(map, "typeId", node.typeId());
            if (!node.keyColumns().isEmpty()) map.put("keyColumns", node.keyColumns());
            entry.put("node", map);
        });
        putList(entry, "declarations", type.declarations(), SchemaView::mapDeclaration);

        // Always present, unlike every slot above: a type with no fields is an answer an agent reads
        // off the same key as a type with twenty, where an absent list would need a second reading.
        var fieldEntries = new ArrayList<Map<String, Object>>(fields.size());
        for (var field : fields) {
            fieldEntries.add(mapField(field));
        }
        entry.put("fields", fieldEntries);
        return entry;
    }

    private static Map<String, Object> mapTypeClaim(SchemaQueries.TypeClaim claim) {
        var map = new LinkedHashMap<String, Object>();
        map.put("classifier", claim.classifier());
        map.put("trigger", "@" + claim.trigger());
        map.put("decoded", claim.decoded());
        McpWire.putPosition(map, "location", claim.position());
        return map;
    }

    private static Map<String, Object> mapTable(SchemaQueries.TableBinding table) {
        var map = new LinkedHashMap<String, Object>();
        map.put("table", table.table());
        map.put("candidates", table.candidates());
        return map;
    }

    private static Map<String, Object> mapBacking(SchemaQueries.Backing backing) {
        var map = new LinkedHashMap<String, Object>();
        map.put("class", backing.className());
        map.put("declaredVia", backing.declaredVia());
        putList(map, "members", backing.members(), SchemaView::mapMember);
        return map;
    }

    private static Map<String, Object> mapMember(SchemaQueries.MemberSlot member) {
        var map = new LinkedHashMap<String, Object>();
        map.put("name", member.name());
        map.put("type", member.type());
        map.put("origin", member.origin());
        map.put("accessorMethodName", member.accessorMethodName());
        return map;
    }

    private static Map<String, Object> mapDeclaration(SchemaQueries.Declaration declaration) {
        var map = new LinkedHashMap<String, Object>();
        map.put("uri", declaration.position().uri());
        map.put("line", declaration.position().line());
        map.put("column", declaration.position().column());
        map.put("isExtension", declaration.isExtension());
        map.put("kind", declaration.kind());
        return map;
    }

    // ---- the field grain ----

    private static Map<String, Object> mapField(SchemaQueries.FieldEntry field) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("fieldRef", field.typeName() + "." + field.fieldName());
        entry.put("typeSdl", field.typeSdl());

        putList(entry, "claims", field.claims().claims(), SchemaView::mapFieldClaim);
        putDemand(entry, field.demand());
        putConflict(entry, field.conflict());
        field.claims().column().ifPresent(column -> {
            var map = new LinkedHashMap<String, Object>();
            map.put("table", column.table());
            map.put("column", column.column());
            map.put("matchedName", column.matchedName());
            map.put("matchedBy", column.matchedBy());
            entry.put("column", map);
        });
        putList(entry, "joinPath", field.joinPath(), SchemaView::mapHop);
        putList(entry, "methods", field.methods(), SchemaView::mapMethod);
        return entry;
    }

    private static Map<String, Object> mapFieldClaim(SchemaQueries.FieldClaim claim) {
        var map = new LinkedHashMap<String, Object>();
        map.put("classifier", claim.classifier());
        map.put("tier", claim.tier());
        McpWire.putIfNotNull(map, "trigger", claim.trigger() == null ? null : "@" + claim.trigger());
        McpWire.putIfNotNull(map, "decoded", claim.decoded());
        McpWire.putPosition(map, "location", claim.position());
        return map;
    }

    private static Map<String, Object> mapHop(SchemaQueries.Hop hop) {
        var map = new LinkedHashMap<String, Object>();
        map.put("ordinal", hop.ordinal());
        map.put("position", hop.position());
        map.put("via", hop.via());
        McpWire.putIfNotNull(map, "keyMatchedBy", hop.keyMatchedBy());
        map.put("fromTable", hop.fromTable());
        map.put("toTable", hop.toTable());
        map.put("constraint", hop.constraintName());
        map.put("fkOnFrom", hop.fkOnFrom());
        map.put("targets", hop.targets());
        map.put("candidates", hop.candidates());
        return map;
    }

    private static Map<String, Object> mapMethod(SchemaQueries.MethodBinding method) {
        var map = new LinkedHashMap<String, Object>();
        map.put("methodRef",
            McpWire.methodRef(method.className(), method.methodName(), method.arity()));
        map.put("declaredVia", method.declaredVia());
        map.put("candidates", method.candidates());
        return map;
    }

    // ---- slot conventions, shared by the two grains ----

    private static void putDemand(Map<String, Object> entry, Optional<SchemaQueries.Demand> demand) {
        demand.ifPresent(d -> {
            var map = new LinkedHashMap<String, Object>();
            map.put("verdict", d.verdict());
            map.put("rule", d.rule());
            entry.put("demand", map);
        });
    }

    private static void putConflict(
        Map<String, Object> entry, Optional<SchemaQueries.Conflict> conflict
    ) {
        // No directive list of its own: the entry's own claims carry the triggers that contest the
        // coordinate, one per row, which answers membership where a joined set answered equality.
        conflict.ifPresent(c -> {
            var map = new LinkedHashMap<String, Object>();
            map.put("verdict", c.verdict());
            map.put("message", c.message());
            McpWire.putPosition(map, "location", c.position());
            entry.put("conflict", map);
        });
    }

    /** Writes {@code items} mapped under {@code key}, omitting the key where the list is empty. */
    private static <T> void putList(
        Map<String, Object> entry, String key, List<T> items,
        Function<T, Map<String, Object>> mapper
    ) {
        if (items.isEmpty()) return;
        var mapped = new ArrayList<Map<String, Object>>(items.size());
        for (var item : items) {
            mapped.add(mapper.apply(item));
        }
        entry.put(key, mapped);
    }

    private static McpSchema.CallToolResult result(String summary, Map<String, Object> fields) {
        return McpSchema.CallToolResult.builder()
            .addTextContent(summary)
            .structuredContent(fields)
            .build();
    }
}
