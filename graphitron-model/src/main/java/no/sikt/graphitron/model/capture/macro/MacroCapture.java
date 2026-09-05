package no.sikt.graphitron.model.capture.macro;

import no.sikt.graphitron.model.grammar.ConnectionNaming;
import no.sikt.graphitron.model.sink.FactSink;
import org.jooq.DSLContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_CONNECTION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_SYNTHESIS;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_MINTED_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_MINTED_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_MINTED_TYPE_SITE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_ELEMENT;

/**
 * The {@code @asConnection} expansion: the Relay machinery it mints, and the rewrite it performs on
 * the field that carried the application.
 *
 * <p>Nothing it writes lands in the {@code graphql_} family. The transcription is what the author
 * declared and only that, so a type this expansion minted is a {@code graphitron_minted_type} and a
 * field of one is a {@code graphitron_minted_field}; a reader wanting the population the generator
 * actually emits reads {@code intent_expanded_type} and {@code intent_expanded_field}, which union
 * the two. That split is what makes the rewrite recoverable in both directions: the authored type
 * expression stays in {@code graphql_field} where it was written, the expansion's replacement is a
 * row of its own, and neither is reconstructed from the other by an anti-join.
 *
 * <p>It reads the store rather than the parse. Its input is the decode's own
 * {@code graphitron_connection} rows joined to the carrier's transcribed field, so it runs as a
 * stage of the graphitron gatherer after both crawlers and the directive decode have flushed. That
 * is also what makes the author-declared-name rule a query: a name a carrier would mint but an
 * author already declared is left to the author, which is one lookup against the type coordinates
 * rather than a registry the walk happened to be holding.
 *
 * <p>Nothing here rejects. A macro whose precondition does not hold contributes no rows, exactly as
 * the rest of capture declines to throw on author input.
 */
public final class MacroCapture {

    private static final String MACRO_CONNECTION = "CONNECTION";
    private static final String CONNECTION_SUFFIX = "Connection";
    private static final String EDGE_SUFFIX = "Edge";
    private static final String PAGE_INFO = "PageInfo";
    private static final String OBJECT = "OBJECT";

    /** The Relay shapes' descriptions, matching what the assembled-schema synthesis emits. */
    private static final String DESC_CONNECTION = "A connection to a list of items.";
    private static final String DESC_EDGES = "A list of edges.";
    private static final String DESC_NODES = "A list of nodes.";
    private static final String DESC_PAGE_INFO_FIELD = "Information to aid in pagination.";
    private static final String DESC_TOTAL_COUNT = "Identifies the total count of items in the connection.";
    private static final String DESC_EDGE = "An edge in a connection.";
    private static final String DESC_CURSOR = "A cursor for use in pagination.";
    private static final String DESC_NODE = "The item at the end of the edge.";
    private static final String DESC_PAGE_INFO = "Information about pagination in a connection.";
    private static final String DESC_HAS_NEXT_PAGE = "When paginating forwards, are there more items?";
    private static final String DESC_HAS_PREVIOUS_PAGE = "When paginating backwards, are there more items?";
    private static final String DESC_START_CURSOR = "When paginating backwards, the cursor to continue.";
    private static final String DESC_END_CURSOR = "When paginating forwards, the cursor to continue.";

    private final FactSink sink;
    private final Set<String> declared;
    private final Set<String> minted = new LinkedHashSet<>();
    private int pageInfoSites;

    private MacroCapture(FactSink sink, Set<String> declared) {
        this.sink = sink;
        this.declared = declared;
    }

    /**
     * Runs every expansion this store's decode calls for, returning the edges the expansion adds to
     * the schema the store describes.
     */
    public static Map<String, Set<String>> expand(FactSink sink, DSLContext dsl, String graphName) {
        var declared = Set.copyOf(dsl.select(GRAPHQL_TYPE_ELEMENT.TYPE_NAME)
            .from(GRAPHQL_TYPE_ELEMENT)
            .where(GRAPHQL_TYPE_ELEMENT.GRAPH_NAME.eq(graphName))
            .fetch(GRAPHQL_TYPE_ELEMENT.TYPE_NAME));
        var expansion = new MacroCapture(sink, declared);
        List<Carrier> carriers = expansion.carriers(dsl, graphName);
        for (Carrier carrier : carriers) {
            expansion.rewriteCarrier(carrier);
            expansion.mintConnection(carrier);
            expansion.mintEdge(carrier);
            expansion.mintPageInfo(carrier);
        }
        return synthesizedEdges(carriers);
    }

    /**
     * One directive-driven {@code @asConnection} carrier: everything the mint needs, read from the
     * carrier's own two rows. The element type is a name, not a resolved type, which is what keeps
     * the expansion from depending on anything but the coordinate it sits on.
     */
    private record Carrier(String parentTypeName, String fieldName, String connectionName,
                           String edgeName, String elementTypeName, boolean itemNullable,
                           String sourceName, int sourceLine, int sourceColumn) {}

    /**
     * The applications that expand, in position order. Ordered by where the author wrote them rather
     * than by name, because the order decides which carrier's position defines the shared PageInfo
     * and which ones extend it, and a document order is the one a reader can predict.
     */
    private List<Carrier> carriers(DSLContext dsl, String graphName) {
        var carriers = new ArrayList<Carrier>();
        for (var row : dsl
                .select(GRAPHITRON_CONNECTION.TYPE_NAME, GRAPHITRON_CONNECTION.FIELD_NAME,
                    GRAPHITRON_CONNECTION.CONNECTION_NAME,
                    GRAPHITRON_CONNECTION.SOURCE_NAME, GRAPHITRON_CONNECTION.SOURCE_LINE,
                    GRAPHITRON_CONNECTION.SOURCE_COLUMN,
                    GRAPHQL_FIELD.TYPE_SDL, GRAPHQL_FIELD.SOURCE_NAME,
                    GRAPHQL_FIELD.SOURCE_LINE, GRAPHQL_FIELD.SOURCE_COLUMN)
                .from(GRAPHITRON_CONNECTION)
                .join(GRAPHQL_FIELD)
                .on(GRAPHQL_FIELD.GRAPH_NAME.eq(GRAPHITRON_CONNECTION.GRAPH_NAME))
                .and(GRAPHQL_FIELD.TYPE_NAME.eq(GRAPHITRON_CONNECTION.TYPE_NAME))
                .and(GRAPHQL_FIELD.FIELD_NAME.eq(GRAPHITRON_CONNECTION.FIELD_NAME))
                .where(GRAPHITRON_CONNECTION.GRAPH_NAME.eq(graphName))
                .fetch()) {
            Element element = element(row.value7());
            if (element == null) {
                // @asConnection on something that is not a bare list of a named type. The misuse is
                // a detection, and the field keeps the type its author wrote.
                continue;
            }
            // The application's own position where it has one, the carrier field's otherwise; both
            // are lines the author can edit, which is the point of inheriting a position.
            boolean own = row.value4() != null && row.value5() != null && row.value6() != null;
            String sourceName = own ? row.value4() : row.value8();
            Integer line = own ? row.value5() : row.value9();
            Integer column = own ? row.value6() : row.value10();
            if (line == null || column == null) {
                continue;
            }
            String connectionName = row.value3() != null && !row.value3().isEmpty()
                ? row.value3()
                : ConnectionNaming.defaultConnectionName(row.value1(), row.value2());
            carriers.add(new Carrier(row.value1(), row.value2(), connectionName,
                connectionName.replace(CONNECTION_SUFFIX, EDGE_SUFFIX),
                element.name(), element.nullable(), sourceName, line, column));
        }
        carriers.sort(java.util.Comparator
            .comparing(Carrier::sourceName)
            .thenComparingInt(Carrier::sourceLine)
            .thenComparingInt(Carrier::sourceColumn)
            .thenComparing(Carrier::parentTypeName)
            .thenComparing(Carrier::fieldName));
        return carriers;
    }

    /** The element of a bare list of a named type, or null where the application expands nothing. */
    private record Element(String name, boolean nullable) {}

    private static Element element(String typeSdl) {
        String expression = typeSdl.trim();
        if (expression.endsWith("!")) {
            expression = expression.substring(0, expression.length() - 1);
        }
        if (!expression.startsWith("[") || !expression.endsWith("]")) {
            return null;
        }
        String item = expression.substring(1, expression.length() - 1).trim();
        boolean nullable = !item.endsWith("!");
        if (!nullable) {
            item = item.substring(0, item.length() - 1);
        }
        if (item.isEmpty() || item.contains("[") || item.contains("]") || item.contains("!")) {
            return null;
        }
        return new Element(item, nullable);
    }

    /**
     * The rewrite itself: the carrier returns the Connection this expansion mints, stated beside the
     * authored expression rather than over it. A bare nullable name, the wrapping the expansion puts
     * on a carrier being none.
     */
    private void rewriteCarrier(Carrier carrier) {
        if (!sink.claim(GRAPHITRON_FIELD_SYNTHESIS, carrier.parentTypeName(), carrier.fieldName())) {
            return;
        }
        var row = sink.dsl().newRecord(GRAPHITRON_FIELD_SYNTHESIS);
        row.setTypeName(carrier.parentTypeName());
        row.setFieldName(carrier.fieldName());
        row.setMacro(MACRO_CONNECTION);
        row.setTypeSdl(carrier.connectionName());
        row.setNamedType(carrier.connectionName());
        row.setNonNull(false);
        row.setIsList(false);
        sink.add(row);
    }

    private void mintConnection(Carrier carrier) {
        if (!mintType(carrier.connectionName(), DESC_CONNECTION, carrier, 0, false)) {
            return;
        }
        var fields = new MintedFields(carrier, carrier.connectionName());
        fields.add("edges", DESC_EDGES, "[" + carrier.edgeName() + "!]!");
        fields.add("nodes", DESC_NODES, "[" + item(carrier) + "]!");
        fields.add("pageInfo", DESC_PAGE_INFO_FIELD, PAGE_INFO + "!");
        // Nullable like the connection's other aggregate: a skipped count degrades to null rather
        // than bubbling a failure through the connection.
        fields.add("totalCount", DESC_TOTAL_COUNT, "Int");
    }

    private void mintEdge(Carrier carrier) {
        if (!mintType(carrier.edgeName(), DESC_EDGE, carrier, 0, false)) {
            return;
        }
        var fields = new MintedFields(carrier, carrier.edgeName());
        fields.add("cursor", DESC_CURSOR, "String!");
        fields.add("node", DESC_NODE, item(carrier));
    }

    /**
     * PageInfo is shared machinery, so the first carrier defines it and every later carrier adds an
     * empty extension site at its own position. The site count is the carrier multiplicity, which is
     * what lets an incremental refresh refcount the shared type instead of guessing when it is
     * orphaned.
     */
    private void mintPageInfo(Carrier carrier) {
        int mergeOrdinal = pageInfoSites;
        boolean extension = mergeOrdinal > 0;
        if (!mintType(PAGE_INFO, extension ? null : DESC_PAGE_INFO, carrier, mergeOrdinal, extension)) {
            return;
        }
        pageInfoSites++;
        if (extension) {
            return;
        }
        var fields = new MintedFields(carrier, PAGE_INFO);
        fields.add("hasNextPage", DESC_HAS_NEXT_PAGE, "Boolean!");
        fields.add("hasPreviousPage", DESC_HAS_PREVIOUS_PAGE, "Boolean!");
        fields.add("startCursor", DESC_START_CURSOR, "String");
        fields.add("endCursor", DESC_END_CURSOR, "String");
    }

    /**
     * Writes the minted type (first site only) and the site itself. Returns false when the site was
     * already claimed, or when the author declared the name: capture is first-wins and the collision
     * is the author's to resolve, so the mint stands down and their declaration stands. Adding
     * machinery fields to a type the author wrote would silently merge two types nobody asked to
     * merge, and the primary key stays a capture-bug detector rather than an author-triggerable throw.
     */
    private boolean mintType(String typeName, String description, Carrier carrier,
                             int mergeOrdinal, boolean extension) {
        if (declared.contains(typeName)) {
            return false;
        }
        if (!sink.claim(GRAPHITRON_MINTED_TYPE_SITE, typeName,
                carrier.sourceName(), carrier.sourceLine(), carrier.sourceColumn())) {
            return false;
        }
        if (minted.add(typeName)) {
            var type = sink.dsl().newRecord(GRAPHITRON_MINTED_TYPE);
            type.setTypeName(typeName);
            type.setKind(OBJECT);
            type.setDescription(description);
            type.setMacro(MACRO_CONNECTION);
            sink.add(type);
        }
        var site = sink.dsl().newRecord(GRAPHITRON_MINTED_TYPE_SITE);
        site.setTypeName(typeName);
        site.setSourceName(carrier.sourceName());
        site.setSourceLine(carrier.sourceLine());
        site.setSourceColumn(carrier.sourceColumn());
        site.setMergeOrdinal(mergeOrdinal);
        site.setIsExtension(extension);
        site.setCarrierTypeName(carrier.parentTypeName());
        site.setCarrierFieldName(carrier.fieldName());
        sink.add(site);
        return true;
    }

    /** The element reference a Connection's {@code nodes} and an Edge's {@code node} share. */
    private static String item(Carrier carrier) {
        return carrier.itemNullable() ? carrier.elementTypeName() : carrier.elementTypeName() + "!";
    }

    /** Writes a minted type's fields, numbering them in the order the macro writes them. */
    private final class MintedFields {
        private final Carrier carrier;
        private final String typeName;
        private int ordinal;

        MintedFields(Carrier carrier, String typeName) {
            this.carrier = carrier;
            this.typeName = typeName;
        }

        void add(String fieldName, String description, String typeSdl) {
            if (!sink.claim(GRAPHITRON_MINTED_FIELD, typeName, fieldName)) {
                return;
            }
            var record = sink.dsl().newRecord(GRAPHITRON_MINTED_FIELD);
            record.setTypeName(typeName);
            record.setFieldName(fieldName);
            record.setOrdinal(ordinal++);
            record.setTypeSdl(typeSdl);
            boolean nonNull = typeSdl.endsWith("!");
            String inner = nonNull ? typeSdl.substring(0, typeSdl.length() - 1) : typeSdl;
            boolean isList = inner.startsWith("[");
            record.setNamedType(namedTypeOf(typeSdl));
            record.setNonNull(nonNull);
            record.setIsList(isList);
            record.setItemNonNull(isList
                ? inner.substring(1, inner.length() - 1).endsWith("!") : null);
            record.setDescription(description);
            record.setSourceName(carrier.sourceName());
            record.setSourceLine(carrier.sourceLine());
            record.setSourceColumn(carrier.sourceColumn());
            sink.add(record);
        }
    }

    private static String namedTypeOf(String typeSdl) {
        return typeSdl.replace("[", "").replace("]", "").replace("!", "");
    }

    /**
     * The edges this expansion adds to the schema the store describes, source type name to the type
     * names its minted members reference. The rooted traversal follows them because the schema it
     * walks is the one capture read, before the pipeline's own rewrite mints these shapes: without
     * them a minted Connection would be a census member no traversal reaches.
     *
     * <p>Stated from the carriers rather than from what the mint landed, so a name the author had
     * already declared still carries its edge: the carrier's field type is rewritten to the
     * connection name either way, and whether the type behind that name is the author's or this
     * expansion's is not this map's question.
     */
    private static Map<String, Set<String>> synthesizedEdges(List<Carrier> carriers) {
        var edges = new LinkedHashMap<String, Set<String>>();
        for (Carrier carrier : carriers) {
            edge(edges, carrier.parentTypeName(), carrier.connectionName());
            edge(edges, carrier.connectionName(), carrier.edgeName());
            edge(edges, carrier.connectionName(), PAGE_INFO);
            edge(edges, carrier.connectionName(), carrier.elementTypeName());
            edge(edges, carrier.connectionName(), "Int");
            edge(edges, carrier.edgeName(), carrier.elementTypeName());
            edge(edges, carrier.edgeName(), "String");
            edge(edges, PAGE_INFO, "Boolean");
            edge(edges, PAGE_INFO, "String");
        }
        return edges;
    }

    private static void edge(Map<String, Set<String>> edges, String from, String to) {
        edges.computeIfAbsent(from, key -> new LinkedHashSet<>()).add(to);
    }
}
