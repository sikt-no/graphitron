package no.sikt.graphitron.model.capture.macro;

import no.sikt.graphitron.model.catalog.SchemaCoordinateSyntax;
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
import static no.sikt.graphitron.model.Tables.GRAPHITRON_MINTED_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_MINTED_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_MINTED_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;

/**
 * The {@code @asConnection} expansion: the Relay machinery it mints, the arguments it appends and
 * the rewrite it performs on the field that carried the application.
 *
 * <p>Nothing it writes lands in the {@code graphql_} family. The transcription is what the author
 * declared and only that, so everything this contributes is a row in one of the three minted
 * relations, keyed by the coordinate that coined it; a reader wanting the population the generator
 * actually emits reads {@code graphitron_type}, {@code graphitron_field} and
 * {@code graphitron_argument}, which are those rows resolved against the transcription. That split
 * is what makes the rewrite recoverable in both directions: the authored type expression stays in
 * {@code graphql_field} where it was written, the expansion's replacement is a row of its own, and
 * neither is reconstructed from the other by an anti-join.
 *
 * <p>It writes what it would mint whether or not the mint wins. Precedence is a column on the row
 * and the anti-joins that form the emitted population read it, so this class does not ask the schema
 * what the author declared and does not count how many carriers came before. That is what makes the
 * expansion a function of one carrier's own declaration, which is the rule the family's own comment
 * gives as the reason a macro may run inside capture at all, and which this expansion did not
 * satisfy while it read a whole-schema set of declared names. Shared machinery needs no special case
 * either: every carrier states the whole of {@code PageInfo} and the primary key is the only dedupe.
 *
 * <p>One exception, and it is not a collision rule. The pagination arguments are minted only where
 * the carrier declares no pagination argument at all, which is not a per-name test: an author who
 * wrote {@code last} keeps their pagination and gets neither {@code first} nor {@code after}, though
 * neither name collides. A precedence column cannot say that, being a property of one row, so the
 * condition stays here; it reads the carrier's own argument list and nothing wider, so the
 * qualification rule above still holds.
 *
 * <p>It reads the store rather than the parse. Its input is the decode's own
 * {@code graphitron_connection} rows joined to the carrier's transcribed field, so it runs as a
 * stage of the graphitron gatherer after both crawlers and the directive decode have flushed.
 *
 * <p>Nothing here rejects. A macro whose precondition does not hold contributes no rows, exactly as
 * the rest of capture declines to throw on author input.
 */
public final class MacroCapture {

    /** The directive whose applications this expansion answers to, as {@code graphql_directive} keys it. */
    private static final String DIRECTIVE = "asConnection";

    private static final String CONNECTION_SUFFIX = "Connection";
    private static final String EDGE_SUFFIX = "Edge";
    private static final String PAGE_INFO = "PageInfo";
    private static final String OBJECT = "OBJECT";

    /** A mint that takes the author's place at a coordinate they also declared. */
    private static final String REPLACE = "REPLACE";
    /** A mint that stands down where the author declared the coordinate. */
    private static final String YIELD = "YIELD";

    /**
     * The pagination arguments, and the names whose presence stands the whole mint down. The
     * generator's own fallback page size is the one value here that also lives in the generator
     * module, on {@code FieldWrapper.DEFAULT_PAGE_SIZE}; the two are held equal by a test rather
     * than by a shared constant, the modules running at different tiers.
     */
    private static final int DEFAULT_PAGE_SIZE = 100;
    private static final Set<String> PAGINATION_ARGUMENTS = Set.of("first", "last", "after", "before");

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

    private MacroCapture(FactSink sink) {
        this.sink = sink;
    }

    /**
     * Runs every expansion this store's decode calls for, returning the edges the expansion adds to
     * the schema the store describes.
     */
    public static Map<String, Set<String>> expand(FactSink sink, DSLContext dsl, String graphName) {
        var expansion = new MacroCapture(sink);
        List<Carrier> carriers = expansion.carriers(dsl, graphName);
        for (Carrier carrier : carriers) {
            expansion.rewriteCarrier(carrier);
            expansion.mintPaginationArguments(carrier);
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
     *
     * <p>The carrier's own ordinal and description ride along because a rewrite states the whole
     * row: the expansion changes the type expression and nothing else, so the columns it does not
     * change are copied rather than left for a reader to coalesce from the transcription.
     */
    private record Carrier(String parentTypeName, String fieldName, String connectionName,
                           String edgeName, String elementTypeName, boolean itemNullable,
                           int fieldOrdinal, String fieldDescription,
                           int argumentCount, boolean paginated, Integer authoredPageSize) {

        /** The coordinate that coins everything this carrier's application mints. */
        String coordinate() {
            return SchemaCoordinateSyntax.ofField(parentTypeName, fieldName);
        }
    }

    /**
     * The applications that expand. Ordered by coordinate rather than by position, the order no
     * longer deciding anything: shared machinery is stated whole by every carrier, so no carrier is
     * the one that defines it and none of the rest extends it.
     */
    private List<Carrier> carriers(DSLContext dsl, String graphName) {
        var carriers = new ArrayList<Carrier>();
        for (var row : dsl
                .select(GRAPHITRON_CONNECTION.TYPE_NAME, GRAPHITRON_CONNECTION.FIELD_NAME,
                    GRAPHITRON_CONNECTION.CONNECTION_NAME,
                    GRAPHITRON_CONNECTION.DEFAULT_FIRST_VALUE,
                    GRAPHQL_FIELD.TYPE_SDL, GRAPHQL_FIELD.ORDINAL, GRAPHQL_FIELD.DESCRIPTION)
                .from(GRAPHITRON_CONNECTION)
                .join(GRAPHQL_FIELD)
                .on(GRAPHQL_FIELD.GRAPH_NAME.eq(GRAPHITRON_CONNECTION.GRAPH_NAME))
                .and(GRAPHQL_FIELD.TYPE_NAME.eq(GRAPHITRON_CONNECTION.TYPE_NAME))
                .and(GRAPHQL_FIELD.FIELD_NAME.eq(GRAPHITRON_CONNECTION.FIELD_NAME))
                .where(GRAPHITRON_CONNECTION.GRAPH_NAME.eq(graphName))
                .orderBy(GRAPHITRON_CONNECTION.TYPE_NAME, GRAPHITRON_CONNECTION.FIELD_NAME)
                .fetch()) {
            Element element = element(row.value5());
            if (element == null) {
                // @asConnection on something that is not a bare list of a named type. The misuse is
                // a detection, and the field keeps the type its author wrote.
                continue;
            }
            String connectionName = row.value3() != null && !row.value3().isEmpty()
                ? row.value3()
                : ConnectionNaming.defaultConnectionName(row.value1(), row.value2());
            var arguments = authoredArguments(dsl, graphName, row.value1(), row.value2());
            carriers.add(new Carrier(row.value1(), row.value2(), connectionName,
                connectionName.replace(CONNECTION_SUFFIX, EDGE_SUFFIX),
                element.name(), element.nullable(), row.value6(), row.value7(),
                arguments.size(),
                arguments.stream().anyMatch(PAGINATION_ARGUMENTS::contains),
                row.value4()));
        }
        return carriers;
    }

    /** The carrier's own argument names, which is the whole of what the argument mint consults. */
    private static List<String> authoredArguments(DSLContext dsl, String graphName,
                                                  String typeName, String fieldName) {
        return dsl.select(GRAPHQL_ARGUMENT.ARGUMENT_NAME)
            .from(GRAPHQL_ARGUMENT)
            .where(GRAPHQL_ARGUMENT.GRAPH_NAME.eq(graphName))
            .and(GRAPHQL_ARGUMENT.TYPE_NAME.eq(typeName))
            .and(GRAPHQL_ARGUMENT.FIELD_NAME.eq(fieldName))
            .fetch(GRAPHQL_ARGUMENT.ARGUMENT_NAME);
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
     * The rewrite itself, which at this grain is a minted field whose coining coordinate is its own.
     * The carrier returns the Connection this expansion mints, a bare nullable name, and the row
     * states the ordinal and description it did not change so that the winner is taken wholesale.
     */
    private void rewriteCarrier(Carrier carrier) {
        mintField(carrier, carrier.parentTypeName(), carrier.fieldName(), REPLACE,
            carrier.fieldOrdinal(), carrier.connectionName(), carrier.fieldDescription());
    }

    /**
     * The two pagination arguments, appended after whatever the author wrote. Minted only where the
     * carrier declares no pagination argument at all, for the reason the class comment gives, and
     * yielding rather than replacing: an author who names one of these keeps it.
     */
    private void mintPaginationArguments(Carrier carrier) {
        if (carrier.paginated()) {
            return;
        }
        int pageSize = carrier.authoredPageSize() != null
            ? carrier.authoredPageSize() : DEFAULT_PAGE_SIZE;
        mintArgument(carrier, "first", carrier.argumentCount(), "Int", String.valueOf(pageSize));
        mintArgument(carrier, "after", carrier.argumentCount() + 1, "String", null);
    }

    private void mintConnection(Carrier carrier) {
        mintType(carrier, carrier.connectionName(), DESC_CONNECTION);
        var fields = new MintedFields(carrier, carrier.connectionName());
        fields.add("edges", DESC_EDGES, "[" + carrier.edgeName() + "!]!");
        fields.add("nodes", DESC_NODES, "[" + item(carrier) + "]!");
        fields.add("pageInfo", DESC_PAGE_INFO_FIELD, PAGE_INFO + "!");
        // Nullable like the connection's other aggregate: a skipped count degrades to null rather
        // than bubbling a failure through the connection.
        fields.add("totalCount", DESC_TOTAL_COUNT, "Int");
    }

    private void mintEdge(Carrier carrier) {
        mintType(carrier, carrier.edgeName(), DESC_EDGE);
        var fields = new MintedFields(carrier, carrier.edgeName());
        fields.add("cursor", DESC_CURSOR, "String!");
        fields.add("node", DESC_NODE, item(carrier));
    }

    /**
     * PageInfo is shared machinery and every carrier states the whole of it. That used to be a
     * merge, the first carrier defining the type and the rest adding empty extension sites, which
     * needed a counter this class held across carriers; the source coordinate in the key is what
     * makes it an ordinary mint.
     */
    private void mintPageInfo(Carrier carrier) {
        mintType(carrier, PAGE_INFO, DESC_PAGE_INFO);
        var fields = new MintedFields(carrier, PAGE_INFO);
        fields.add("hasNextPage", DESC_HAS_NEXT_PAGE, "Boolean!");
        fields.add("hasPreviousPage", DESC_HAS_PREVIOUS_PAGE, "Boolean!");
        fields.add("startCursor", DESC_START_CURSOR, "String");
        fields.add("endCursor", DESC_END_CURSOR, "String");
    }

    /**
     * A type this carrier would mint. Always YIELD: adding machinery fields to a type the author
     * wrote would silently merge two types nobody asked to merge, so where the name is taken the
     * author's declaration stands and this row records that the application stood down.
     */
    private void mintType(Carrier carrier, String typeName, String description) {
        if (!sink.claim(GRAPHITRON_MINTED_TYPE, carrier.coordinate(), typeName)) {
            return;
        }
        var row = sink.dsl().newRecord(GRAPHITRON_MINTED_TYPE);
        row.setSourceCoordinate(carrier.coordinate());
        row.setTypeName(typeName);
        row.setDirectiveName(DIRECTIVE);
        row.setPrecedence(YIELD);
        row.setKind(OBJECT);
        row.setDescription(description);
        sink.add(row);
    }

    private void mintField(Carrier carrier, String typeName, String fieldName, String precedence,
                           int ordinal, String typeSdl, String description) {
        if (!sink.claim(GRAPHITRON_MINTED_FIELD, carrier.coordinate(), typeName, fieldName)) {
            return;
        }
        var row = sink.dsl().newRecord(GRAPHITRON_MINTED_FIELD);
        row.setSourceCoordinate(carrier.coordinate());
        row.setTypeName(typeName);
        row.setFieldName(fieldName);
        row.setDirectiveName(DIRECTIVE);
        row.setPrecedence(precedence);
        row.setOrdinal(ordinal);
        row.setTypeSdl(typeSdl);
        row.setNamedType(namedTypeOf(typeSdl));
        boolean nonNull = typeSdl.endsWith("!");
        String inner = nonNull ? typeSdl.substring(0, typeSdl.length() - 1) : typeSdl;
        boolean isList = inner.startsWith("[");
        row.setNonNull(nonNull);
        row.setIsList(isList);
        row.setItemNonNull(isList ? inner.substring(1, inner.length() - 1).endsWith("!") : null);
        row.setDescription(description);
        sink.add(row);
    }

    private void mintArgument(Carrier carrier, String argumentName, int ordinal, String typeSdl,
                              String defaultValueSdl) {
        if (!sink.claim(GRAPHITRON_MINTED_ARGUMENT, carrier.coordinate(), carrier.parentTypeName(),
                carrier.fieldName(), argumentName)) {
            return;
        }
        var row = sink.dsl().newRecord(GRAPHITRON_MINTED_ARGUMENT);
        row.setSourceCoordinate(carrier.coordinate());
        row.setTypeName(carrier.parentTypeName());
        row.setFieldName(carrier.fieldName());
        row.setArgumentName(argumentName);
        row.setDirectiveName(DIRECTIVE);
        row.setPrecedence(YIELD);
        row.setOrdinal(ordinal);
        row.setTypeSdl(typeSdl);
        row.setNamedType(typeSdl);
        row.setNonNull(false);
        row.setIsList(false);
        row.setDefaultValueSdl(defaultValueSdl);
        sink.add(row);
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
            mintField(carrier, typeName, fieldName, YIELD, ordinal++, typeSdl, description);
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
