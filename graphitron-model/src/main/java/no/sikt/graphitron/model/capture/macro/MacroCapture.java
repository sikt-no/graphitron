package no.sikt.graphitron.model.capture.macro;

import no.sikt.graphitron.model.catalog.SchemaCoordinateSyntax;
import no.sikt.graphitron.model.grammar.ConnectionDefaults;
import no.sikt.graphitron.model.grammar.ConnectionNaming;
import no.sikt.graphitron.model.grammar.FacetNaming;
import no.sikt.graphitron.model.sink.FactSink;
import java.time.LocalDateTime;
import org.jooq.DSLContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_CONNECTION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_MINTED_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_MINTED_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_MINTED_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;
import static no.sikt.graphitron.model.Tables.INTENT_CONNECTION_FACET;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.select;

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
 * <p>No exception for pagination, and there used to be one. The arguments were minted only where
 * the carrier declared no pagination argument at all, so an author who wrote {@code last} got
 * neither {@code first} nor {@code after}; the assembled-schema synthesis appends both to every
 * carrier regardless, so the store and the schema disagreed wherever an author paginated
 * backwards. Dropping the condition settles it the way the row already could: a minted argument
 * yields to an authored one of the same name, so an author who writes {@code first} keeps theirs
 * and an author who writes {@code last} gets the forward pair beside it. That is better than the
 * synthesis it matches, which appends unconditionally and would state {@code first} twice.
 *
 * <p>It reads the store rather than the parse. Its input is the decode's own
 * {@code graphitron_connection_entry} rows joined to the carrier's transcribed field, so it runs as a
 * stage of the graphitron gatherer after both crawlers and the directive decode have flushed.
 *
 * <p>Nothing here rejects. A macro whose precondition does not hold contributes no rows, exactly as
 * the rest of capture declines to throw on author input.
 */
public final class MacroCapture {

    /** The directive whose applications this expansion answers to, as {@code graphql_directive} keys it. */
    private static final String DIRECTIVE = "asConnection";

    private static final String PAGE_INFO = "PageInfo";
    private static final String OBJECT = "OBJECT";

    /** A mint that takes the author's place at a coordinate they also declared. */
    private static final String REPLACE = "REPLACE";
    /** A mint that stands down where the author declared the coordinate. */
    private static final String YIELD = "YIELD";

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
    private static final String DESC_FACETS = "Per-facet value counts for the items in the connection.";
    private static final String DESC_FACETS_TYPE = "Facet value counts for a connection.";
    private static final String DESC_FACET_FIELD = "Value counts for this facet, under the connection's filter minus this facet's own predicate.";
    private static final String DESC_FACET_VALUE_TYPE = "One facet bucket: a filterable value and its count.";
    private static final String DESC_FACET_VALUE = "The facet value; feed it back into the filter to select this bucket.";
    private static final String DESC_FACET_COUNT = "The number of items in this bucket.";
    private static final String DESC_HAS_NEXT_PAGE = "When paginating forwards, are there more items?";
    private static final String DESC_HAS_PREVIOUS_PAGE = "When paginating backwards, are there more items?";
    private static final String DESC_START_CURSOR = "When paginating backwards, the cursor to continue.";
    private static final String DESC_END_CURSOR = "When paginating forwards, the cursor to continue.";

    /**
     * How many fields {@link #mintConnection} puts on a connection, and so the ordinal the facet
     * half appends at. Named rather than written as a literal at the one site that needs it, because
     * the two have to move together.
     */
    private static final int CONNECTION_FIELDS = 4;

    private final FactSink sink;

    private MacroCapture(FactSink sink) {
        this.sink = sink;
    }

    /**
     * Runs every expansion this store's decode calls for.
     *
     * <p>Writes rows and returns nothing. What the expansion adds to the schema the store
     * describes used to be handed back as a map of name edges beside them, which was the same
     * fact said twice: an edge is a minted field's owning type reaching its named type, so every
     * edge the map carried is a row this method writes. The reader derives it there instead.
     */
    public static void expand(FactSink sink, DSLContext dsl, String graphName) {
        var expansion = new MacroCapture(sink);
        for (Carrier carrier : expansion.carriers(dsl, graphName)) {
            expansion.rewriteCarrier(carrier);
            expansion.mintPaginationArguments(carrier);
            expansion.mintConnection(carrier);
            expansion.mintEdge(carrier);
            expansion.mintPageInfo(carrier);
        }
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
                           boolean outerNonNull, int fieldOrdinal, String fieldDescription,
                           int argumentCount, Integer authoredPageSize) {

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
        var argumentsByField = argumentsByField(dsl, graphName);
        for (var row : dsl
                .select(GRAPHITRON_CONNECTION_ENTRY.TYPE_NAME, GRAPHITRON_CONNECTION_ENTRY.FIELD_NAME,
                    GRAPHITRON_CONNECTION_ENTRY.CONNECTION_NAME,
                    GRAPHITRON_CONNECTION_ENTRY.DEFAULT_FIRST_VALUE,
                    GRAPHQL_FIELD.TYPE_SDL, GRAPHQL_FIELD.NAMED_TYPE, GRAPHQL_FIELD.NON_NULL,
                    GRAPHQL_FIELD.IS_LIST, GRAPHQL_FIELD.ITEM_NON_NULL,
                    GRAPHQL_FIELD.ORDINAL, GRAPHQL_FIELD.DESCRIPTION)
                .from(GRAPHITRON_CONNECTION_ENTRY)
                .join(GRAPHQL_FIELD)
                .on(GRAPHQL_FIELD.GRAPH_NAME.eq(GRAPHITRON_CONNECTION_ENTRY.GRAPH_NAME))
                .and(GRAPHQL_FIELD.TYPE_NAME.eq(GRAPHITRON_CONNECTION_ENTRY.TYPE_NAME))
                .and(GRAPHQL_FIELD.FIELD_NAME.eq(GRAPHITRON_CONNECTION_ENTRY.FIELD_NAME))
                .where(GRAPHITRON_CONNECTION_ENTRY.GRAPH_NAME.eq(graphName))
                .orderBy(GRAPHITRON_CONNECTION_ENTRY.TYPE_NAME, GRAPHITRON_CONNECTION_ENTRY.FIELD_NAME)
                .fetch()) {
            if (!row.get(GRAPHQL_FIELD.IS_LIST) || nested(row.get(GRAPHQL_FIELD.TYPE_SDL))) {
                // @asConnection on something that is not a bare list of a named type. The misuse is
                // a detection, and the field keeps the type its author wrote.
                continue;
            }
            String typeName = row.get(GRAPHITRON_CONNECTION_ENTRY.TYPE_NAME);
            String fieldName = row.get(GRAPHITRON_CONNECTION_ENTRY.FIELD_NAME);
            String declared = row.get(GRAPHITRON_CONNECTION_ENTRY.CONNECTION_NAME);
            String connectionName = declared != null && !declared.isEmpty()
                ? declared
                : ConnectionNaming.defaultConnectionName(typeName, fieldName);
            var arguments = argumentsByField.getOrDefault(List.of(typeName, fieldName), List.of());
            carriers.add(new Carrier(typeName, fieldName, connectionName,
                ConnectionNaming.defaultEdgeName(connectionName),
                row.get(GRAPHQL_FIELD.NAMED_TYPE),
                !Boolean.TRUE.equals(row.get(GRAPHQL_FIELD.ITEM_NON_NULL)),
                row.get(GRAPHQL_FIELD.NON_NULL),
                row.get(GRAPHQL_FIELD.ORDINAL), row.get(GRAPHQL_FIELD.DESCRIPTION),
                arguments.size(),
                row.get(GRAPHITRON_CONNECTION_ENTRY.DEFAULT_FIRST_VALUE)));
        }
        return carriers;
    }

    /**
     * Every field's authored argument names, in one read. Per carrier this is a lookup rather than
     * a query: a schema with two hundred carriers would otherwise make two hundred round trips to
     * ask each one a question about itself, which is the shape this whole line of work exists to
     * stop, and it would be the more embarrassing for sitting inside the expansion rather than in a
     * view somebody could blame on the planner.
     */
    private static Map<List<String>, List<String>> argumentsByField(DSLContext dsl, String graphName) {
        var byField = new LinkedHashMap<List<String>, List<String>>();
        for (var row : dsl
                .select(GRAPHQL_ARGUMENT.TYPE_NAME, GRAPHQL_ARGUMENT.FIELD_NAME,
                    GRAPHQL_ARGUMENT.ARGUMENT_NAME)
                .from(GRAPHQL_ARGUMENT)
                .where(GRAPHQL_ARGUMENT.GRAPH_NAME.eq(graphName))
                .fetch()) {
            byField.computeIfAbsent(List.of(row.value1(), row.value2()), key -> new ArrayList<>())
                .add(row.value3());
        }
        return byField;
    }

    /**
     * Whether the expression lists something that is itself a list, which disqualifies the carrier.
     *
     * <p>The one question about the authored expression this class still takes a string apart to
     * answer, and it is here rather than in a predicate because the anchor cannot state it.
     * {@code graphql_field} describes exactly one list wrapper, so {@code [[Film]]} and
     * {@code [Film]} agree on {@code named_type}, {@code non_null}, {@code is_list} and
     * {@code item_non_null} alike and no combination of those columns tells them apart. The column
     * that would is {@code list_depth}, which the entry relations carry and the anchors do not; when
     * it reaches them this method becomes a predicate on {@code list_depth > 1} and stops being
     * Java.
     *
     * <p>Everything else this class used to read out of the expression is a column and is read as
     * one: the element's name from {@code named_type}, its nullability from {@code item_non_null},
     * the carrier's own from {@code non_null}, the list-ness from {@code is_list}. Recomputing them
     * was string surgery paying for columns that already existed, and it cost a defect rather than
     * only duplication: the outer non-null was parsed and thrown away, so the rewrite emitted a
     * nullable carrier wherever an author had written a non-null one.
     */
    private static boolean nested(String typeSdl) {
        String expression = typeSdl.trim();
        if (expression.endsWith("!")) {
            expression = expression.substring(0, expression.length() - 1);
        }
        if (!expression.startsWith("[") || !expression.endsWith("]")) {
            return true;
        }
        String item = expression.substring(1, expression.length() - 1).trim();
        if (item.endsWith("!")) {
            item = item.substring(0, item.length() - 1);
        }
        return item.isEmpty() || item.contains("[") || item.contains("]") || item.contains("!");
    }

    /**
     * The rewrite itself, which at this grain is a minted field whose coining coordinate is its own.
     * The carrier returns the Connection this expansion mints, under the outer nullability its
     * author wrote, and the row states the ordinal and description it did not change so that the
     * winner is taken wholesale.
     *
     * <p>The outer non-null is carried rather than dropped, and the distinction is the whole of what
     * this rewrite changes: the expansion replaces what a field returns and says nothing about
     * whether the field may be null, which is the author's claim and survives. So
     * {@code films: [Film!]!} becomes {@code QueryFilmsConnection!} and {@code films: [Film!]}
     * becomes {@code QueryFilmsConnection}. Writing a bare name for both would make the row disagree
     * with the schema the run emits, and an output field that loses its non-null is a breaking
     * change to every consumer reading it.
     */
    private void rewriteCarrier(Carrier carrier) {
        mintField(carrier, carrier.parentTypeName(), carrier.fieldName(), REPLACE,
            carrier.fieldOrdinal(),
            carrier.outerNonNull() ? carrier.connectionName() + "!" : carrier.connectionName(),
            carrier.fieldDescription());
    }

    /**
     * The two pagination arguments, appended after whatever the author wrote, on every carrier.
     *
     * <p>Yielding rather than replacing, which is the whole of the collision rule this pair needs:
     * an author who names one of these keeps theirs and the mint stands down. A carrier that
     * paginates backwards gets the forward pair beside its own, which is what the assembled-schema
     * synthesis has always emitted.
     */
    private void mintPaginationArguments(Carrier carrier) {
        int pageSize = carrier.authoredPageSize() != null
            ? carrier.authoredPageSize() : ConnectionDefaults.DEFAULT_PAGE_SIZE;
        mintArgument(carrier, "first", carrier.argumentCount(), "Int", String.valueOf(pageSize));
        mintArgument(carrier, "after", carrier.argumentCount() + 1, "String", null);
    }

    /**
     * The facet half of the expansion, which runs after the half above has flushed.
     *
     * <p>Separate because of what it reads. {@code intent_connection_facet} resolves which facets a
     * carrier surfaces and in what order, and it reaches the carriers through the rewrite rows
     * {@link #expand} writes: a minted field whose coining coordinate is its own. So those rows have
     * to be in the store before it is asked, which is the rule the gatherer's own stages run under
     * one level up. The alternative was to reimplement that relation's join here against the
     * carriers already in hand, which is one rule read twice and the thing this class exists to
     * stop.
     *
     * <p>What it mints is the triad the generator's synthesis mints: a {@code <Connection>Facets}
     * container with one field per facet, a {@code <Scalar>FacetValue} per distinct value shape, and
     * the connection's own {@code facets} field reaching the container. The value shapes are shared
     * machinery like {@code PageInfo}, so every carrier that has one states the whole of it and the
     * primary key is the only dedupe.
     */
    public static void expandFacets(FactSink sink, DSLContext dsl, String graphName) {
        var expansion = new MacroCapture(sink);
        for (var carrier : expansion.facetedCarriers(dsl, graphName)) {
            expansion.mintFacets(carrier);
        }
    }

    /** One carrier that surfaces facets, with its own in the order the container's fields take. */
    private record FacetedCarrier(String coordinate, String connectionName, List<Facet> facets) {}

    /** One {@code @asFacet} binding a carrier reaches, as the container renders it. */
    private record Facet(String fieldName, String valueTypeName, boolean valueNullable) {

        /** The shared value shape this facet's buckets take. */
        String valueTypeRef() {
            return FacetNaming.facetValueTypeName(valueTypeName, valueNullable);
        }
    }

    /**
     * The carriers that surface facets, each with its own nested on their own key.
     *
     * <p>One row of the answer is one carrier, so the ordering and the first-wins dedup on a
     * repeated facet name stay the relation's rather than being restated here. The connection's name
     * comes off the rewrite row's {@code named_type} rather than being re-derived from the naming
     * rule: the expansion already decided it, including where an author overrode it.
     */
    private List<FacetedCarrier> facetedCarriers(DSLContext dsl, String graphName) {
        var rewrite = GRAPHITRON_MINTED_FIELD;
        return dsl
            .select(rewrite.TYPE_NAME, rewrite.FIELD_NAME, rewrite.NAMED_TYPE,
                multiset(
                    select(INTENT_CONNECTION_FACET.FACET_FIELD_NAME,
                        INTENT_CONNECTION_FACET.VALUE_TYPE_NAME,
                        INTENT_CONNECTION_FACET.VALUE_NULLABLE)
                        .from(INTENT_CONNECTION_FACET)
                        .where(INTENT_CONNECTION_FACET.GRAPH_NAME.eq(rewrite.GRAPH_NAME))
                        .and(INTENT_CONNECTION_FACET.TYPE_NAME.eq(rewrite.TYPE_NAME))
                        .and(INTENT_CONNECTION_FACET.FIELD_NAME.eq(rewrite.FIELD_NAME))
                        .orderBy(INTENT_CONNECTION_FACET.POSITION))
                    .convertFrom(r -> r.map(x -> new Facet(x.value1(), x.value2(),
                        Boolean.TRUE.equals(x.value3())))))
            .from(rewrite)
            .where(rewrite.GRAPH_NAME.eq(graphName))
            .and(rewrite.DIRECTIVE_NAME.eq(DIRECTIVE))
            .and(rewrite.SOURCE_COORDINATE.eq(
                rewrite.TYPE_NAME.concat(".").concat(rewrite.FIELD_NAME)))
            .orderBy(rewrite.TYPE_NAME, rewrite.FIELD_NAME)
            .fetch(r -> new FacetedCarrier(
                SchemaCoordinateSyntax.ofField(r.value1(), r.value2()), r.value3(), r.value4()))
            .stream()
            .filter(c -> !c.facets().isEmpty())
            .toList();
    }

    /**
     * The container, the value shapes, and the connection's own field reaching them.
     *
     * <p>The {@code facets} field is nullable like the connection's other aggregate: a facet that
     * fails or times out degrades to null on its own field rather than propagating a failure through
     * the connection. Its ordinal follows the four the connection already carries.
     */
    private void mintFacets(FacetedCarrier carrier) {
        String facetsName = FacetNaming.facetsTypeName(carrier.connectionName());
        mintField(carrier.coordinate(), carrier.connectionName(), "facets", YIELD,
            CONNECTION_FIELDS, facetsName, DESC_FACETS);

        mintType(carrier.coordinate(), facetsName, DESC_FACETS_TYPE);
        int ordinal = 0;
        for (var facet : carrier.facets()) {
            mintField(carrier.coordinate(), facetsName, facet.fieldName(), YIELD, ordinal++,
                "[" + facet.valueTypeRef() + "!]", DESC_FACET_FIELD);
        }

        for (var facet : carrier.facets()) {
            String valueType = facet.valueTypeRef();
            mintType(carrier.coordinate(), valueType, DESC_FACET_VALUE_TYPE);
            mintField(carrier.coordinate(), valueType, "value", YIELD, 0,
                facet.valueNullable() ? facet.valueTypeName() : facet.valueTypeName() + "!",
                DESC_FACET_VALUE);
            mintField(carrier.coordinate(), valueType, "count", YIELD, 1, "Int!", DESC_FACET_COUNT);
        }
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
     * The rows this reading stopped minting, deleted once the expansion's own rows have reached the
     * store.
     *
     * <p>Separate from {@link #expand} because it has to run after the flush rather than inside the
     * pass that buffers: a sweep taken before the rows land would delete what the reading is about
     * to write. Children before parents, an argument hanging off a field and a field off a type.
     */
    public static void sweep(DSLContext dsl, String graphName, LocalDateTime touchedAt) {
        for (var table : List.of(GRAPHITRON_MINTED_ARGUMENT, GRAPHITRON_MINTED_FIELD,
                GRAPHITRON_MINTED_TYPE)) {
            dsl.deleteFrom(table)
                .where(table.field(GRAPHITRON_MINTED_TYPE.GRAPH_NAME).eq(graphName))
                .and(table.field(GRAPHITRON_MINTED_TYPE.TOUCHED_AT).ne(touchedAt))
                .execute();
        }
    }

    /**
     * A type this carrier would mint. Always YIELD: adding machinery fields to a type the author
     * wrote would silently merge two types nobody asked to merge, so where the name is taken the
     * author's declaration stands and this row records that the application stood down.
     */
    private void mintType(Carrier carrier, String typeName, String description) {
        mintType(carrier.coordinate(), typeName, description);
    }

    private void mintType(String coordinate, String typeName, String description) {
        if (!sink.claim(GRAPHITRON_MINTED_TYPE, coordinate, typeName)) {
            return;
        }
        var row = sink.dsl().newRecord(GRAPHITRON_MINTED_TYPE);
        row.setSourceCoordinate(coordinate);
        row.setTypeName(typeName);
        row.setDirectiveName(DIRECTIVE);
        row.setPrecedence(YIELD);
        row.setKind(OBJECT);
        row.setDescription(description);
        sink.add(row);
    }

    private void mintField(Carrier carrier, String typeName, String fieldName, String precedence,
                           int ordinal, String typeSdl, String description) {
        mintField(carrier.coordinate(), typeName, fieldName, precedence, ordinal, typeSdl,
            description);
    }

    private void mintField(String coordinate, String typeName, String fieldName, String precedence,
                           int ordinal, String typeSdl, String description) {
        if (!sink.claim(GRAPHITRON_MINTED_FIELD, coordinate, typeName, fieldName)) {
            return;
        }
        var row = sink.dsl().newRecord(GRAPHITRON_MINTED_FIELD);
        row.setSourceCoordinate(coordinate);
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
}
