package no.sikt.graphitron.mcp;

import no.sikt.graphitron.model.boot.StoreReader;
import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.Record1;
import org.jooq.Records;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static java.util.Comparator.comparing;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_CONDITION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE_KEY_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHQL_IMPLEMENTS;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_DECLARATION;
import static no.sikt.graphitron.model.Tables.GRAPHQL_UNION_MEMBER;
import static no.sikt.graphitron.model.Tables.INTENT_AUTHORED_CLAIM_CONFLICT;
import static no.sikt.graphitron.model.Tables.INTENT_AUTHORED_FIELD_CLAIM;
import static no.sikt.graphitron.model.Tables.INTENT_AUTHORED_TYPE_CLAIM;
import static no.sikt.graphitron.model.Tables.INTENT_BOUND_TABLE;
import static no.sikt.graphitron.model.Tables.INTENT_CLASS_MEMBER_SLOT;
import static no.sikt.graphitron.model.Tables.INTENT_COLUMN_MATCH_CLAIM;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_PRODUCER_METHOD;
import static no.sikt.graphitron.model.Tables.INTENT_FIELD_REFERENCE_STEP_TARGET;
import static no.sikt.graphitron.model.Tables.INTENT_RESOLVED_FIELD_CLAIM;
import static no.sikt.graphitron.model.Tables.INTENT_RESOLVED_FIELD_DEMAND;
import static no.sikt.graphitron.model.Tables.INTENT_RESOLVED_TYPE_DEMAND;
import static no.sikt.graphitron.model.Tables.INTENT_TYPE_BACKING;
import static no.sikt.graphitron.model.Tables.INTENT_TYPE_BACKING_CONFLICT;
import static no.sikt.graphitron.model.Tables.JVM_METHOD;
import static no.sikt.graphitron.model.Tables.JVM_METHOD_PARAMETER;
import static org.jooq.impl.DSL.concat;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectCount;
import static org.jooq.impl.DSL.selectOne;

/**
 * This module's reads for the {@code schema} tool: what the store says a graph's SDL coordinates are
 * and what it made of them.
 *
 * <p>Five questions per coordinate, each answered by the relations that own it: what claims it, what it
 * binds, whether a verdict was demanded of it, what conflicts on it, and where it is declared. None of
 * those is a projection of a classification verdict, and that is the point of the shape rather than a
 * consequence of it: a permit name is a fact about the generator's own taxonomy, where a claim, a
 * binding and a demand are facts about the author's schema, and it is the second set an agent asking
 * "what did graphitron make of this" actually wants.
 *
 * <p>Two statements, one per grain, and the grain boundary is the store's. A type and a
 * {@code Type.field} coordinate are two keys, every relation here is keyed by one or the other, and the
 * field-grain derivations are the expensive ones: {@code intent_column_match_claim} carries a DDL
 * comment warning that H2 re-evaluates a joined derived relation once per outer row and measured
 * seventy times the shape when read from underneath {@code graphql_field}. Correlating it per field
 * inside the type-grain projection is exactly the shape that comment warns about, so the fields of a
 * page are their own projection, driven from {@code graphql_field} narrowed to the page's types. The
 * page's entries and their fields are then paired on {@code type_name}, which is the type's own key
 * rather than a grouping invented here.
 *
 * <p>Within a grain nothing is folded. Every child list is a correlated {@code MULTISET} hanging off
 * the key its relation already declares, nested a second level where the child has children of its own
 * (a backing class's member slots, an {@code @node}'s key columns), so a mis-paired child cannot arise
 * from a projection that never joins siblings together.
 *
 * <p>No rule is re-implemented here, and where that costs an answer the slot is simply absent. An
 * {@code @node} whose {@code typeId} or whose key columns were omitted has a type-name fallback and a
 * catalog-primary-key fallback in the generator, neither of which any view resolves, so this reports
 * what the author wrote and nothing where they wrote nothing. Four bindings are absent on the same
 * terms and are named in the manual: a composite {@code @nodeId}'s key columns, an interface's
 * {@code @discriminate} column, a {@code @pivot}'s two columns, and a participant's cross-table column.
 */
final class SchemaQueries {

    private SchemaQueries() {}

    /** Default page size when listing types; a {@code type} narrow returns the one entry. */
    static final int DEFAULT_LIMIT = 100;

    /**
     * The classifier a column match claims, which is also how the column binding is reached: the
     * structural reading survives in {@code intent_column_match_claim} even where an authored directive
     * overrides it, and {@code intent_resolved_field_claim} is the relation that says whether it won.
     * Joining the witness through the resolution is what reads the store's masking rather than
     * restating it.
     */
    private static final String TABLE_COLUMN = "TABLE_COLUMN";

    /**
     * The provenance this module's own {@code @condition} read stamps on a method binding, beside the
     * two {@code intent_field_producer_method} carries. A third value in the wire's vocabulary and
     * deliberately not a third arm on that view; see {@link #methods}.
     */
    private static final String CONDITION = "CONDITION";

    // ---- the answer ----

    /**
     * One SDL type and what the store made of it. Every list is empty and every {@link Optional} absent
     * where the relation behind it holds no row, which the wire renders as an absent slot.
     *
     * @param kind the SDL kind {@code graphql_type} records, so a reader can tell an object from an
     *     input object without inferring it from which slots are filled
     */
    record TypeEntry(
        String typeName,
        String kind,
        List<TypeClaim> claims,
        Optional<Demand> demand,
        Optional<Conflict> conflict,
        List<TableBinding> tables,
        List<Backing> backing,
        Optional<BackingConflict> backingConflict,
        List<String> unionMembers,
        List<String> implementors,
        Optional<Node> node,
        List<Declaration> declarations
    ) {}

    /**
     * One {@code Type.field} coordinate and what the store made of it.
     *
     * @param typeName the owning type, carried because this is how a field row finds its entry
     * @param typeSdl the field's type as the schema spells it, non-null and free in the driving row
     */
    record FieldEntry(
        String typeName,
        String fieldName,
        String typeSdl,
        ClaimSet claims,
        Optional<Demand> demand,
        Optional<Conflict> conflict,
        List<Hop> joinPath,
        List<MethodBinding> producerMethods,
        List<MethodBinding> conditionMethods
    ) {

        /**
         * Every method the coordinate's authored Java references name, which is what the wire carries as
         * one list.
         *
         * <p>Two components rather than one because H2 will not correlate an outer column into a
         * {@code UNION ALL} nested inside a {@code MULTISET}: jOOQ wraps the union in a derived table and
         * the reference to the driving field row no longer resolves. So the two populations are two
         * projections at one grain instead, and joining them is a concatenation and nothing more. Both
         * lists already belong to this row, having each been correlated to it, so there is no key to
         * match and nothing that can be mispaired.
         */
        List<MethodBinding> methods() {
            return Stream.concat(producerMethods.stream(), conditionMethods.stream())
                .sorted(comparing(MethodBinding::declaredVia)
                    .thenComparing(MethodBinding::className)
                    .thenComparing(MethodBinding::methodName)
                    .thenComparing(MethodBinding::arity))
                .toList();
        }
    }

    /**
     * One authored claim on a type: which classification the author asked for, through which directive.
     *
     * @param decoded {@code false} where the application exists and its decode declined, which is a
     *     claim the walk still honours by diverting
     */
    record TypeClaim(
        String classifier, String trigger, boolean decoded, Optional<McpWire.Position> position
    ) {}

    /**
     * One resolved claim on a field: the classification and which tier decided it, plus the authoring
     * application where the tier is the authored one.
     *
     * @param tier {@code AUTHORED} where a directive claimed the coordinate, {@code INFERRED} where a
     *     structural classifier did at a coordinate no directive covers
     * @param trigger the claiming directive, {@code null} on an inferred claim, which has none
     * @param decoded {@code null} on an inferred claim, for the same reason
     */
    record FieldClaim(
        String classifier, String tier, String trigger, Boolean decoded,
        Optional<McpWire.Position> position
    ) {}

    /**
     * The claim resolution's whole answer for one coordinate: which classifiers claim it, and the column
     * the structural one matched where that is the reading that won.
     *
     * <p>One value because it is one read. The column match is both a claim and the witness of that
     * claim, so the relation that says whether the structural reading survived is the same relation the
     * claim list comes from, and asking twice would mean evaluating the resolution twice.
     */
    record ClaimSet(List<FieldClaim> claims, Optional<ColumnBinding> column) {

        static final ClaimSet EMPTY = new ClaimSet(List.of(), Optional.empty());
    }

    /** A {@code Type.field} coordinate, the key the claim read's answer is paired to its field row on. */
    private record Coordinate(String typeName, String fieldName) {}

    /**
     * Whether a verdict was demanded of a coordinate, and the rule that says so.
     *
     * <p>Strictly more than the absence it replaces: a coordinate with no classification used to report
     * one verdict whether it was expected to classify or was never in scope, where {@code DEMANDED}
     * plus a rule names why one was expected and {@code EXEMPT} says the coordinate is out of scope.
     */
    record Demand(String verdict, String rule) {}

    /**
     * A claim conflict at a coordinate.
     *
     * @param directives the canonical comma-joined render the store groups by, sorted, so two readers
     *     grouping on a directive set cannot split a group on claim order
     * @param message the report's own message for the violation, display only
     */
    record Conflict(
        String verdict, String directives, String message, Optional<McpWire.Position> position
    ) {}

    /**
     * One catalog table a type's {@code @table} binding resolves to.
     *
     * @param table the table's schema-qualified SQL name, which is the table id every tool hands back
     * @param candidates how many tables the reference reached, this row being one of them; above one
     *     the binding is ambiguous and the store declines to pick
     */
    record TableBinding(String table, int candidates) {}

    /**
     * One class the store says stands for a type, with the member names it offers an author.
     *
     * @param declaredVia {@code BOUND_TABLE} where the type's {@code @table} binding was read through
     *     its table's generated record, {@code BACKING_CLOSURE} where a producer's return or an
     *     accessor hop reached the class. Provenance and never a preference: a type its two populations
     *     answer differently is two entries here, where the walk applies a precedence and never says so
     * @param members empty where the classpath census never reached the class, which is the ordinary
     *     case on the table arm: the generated jOOQ records it names are deliberately never scanned
     */
    record Backing(String className, String declaredVia, List<MemberSlot> members) {}

    /**
     * One member name a backing class offers, in the author's vocabulary rather than the JVM's.
     *
     * @param type the declared form, so a hover reads {@code List<Film>} rather than {@code List}
     * @param origin {@code RECORD_COMPONENT} or {@code BEAN_ACCESSOR}
     */
    record MemberSlot(String name, String type, String origin, String accessorMethodName) {}

    /** The classes a type is answered by where that is more than one, and how many. */
    record BackingConflict(String classNames, int candidates) {}

    /**
     * An {@code @node}'s identity as the author wrote it.
     *
     * @param typeId {@code null} where the argument was omitted; the generator's type-name fallback is
     *     a derivation no view resolves, so nothing stands in for it here
     * @param keyColumns empty where {@code keyColumns} was omitted, on the same terms: the
     *     catalog-primary-key fallback is a rule of the generator's and reading it would mean
     *     re-implementing one here
     */
    record Node(String typeId, List<String> keyColumns) {}

    /**
     * One declaration site of a type: its base definition or one extension.
     *
     * @param isExtension {@code true} on an {@code extend} site
     * @param kind the kind the site declares, which a malformed extension can disagree with the type's
     */
    record Declaration(McpWire.Position position, boolean isExtension, String kind) {}

    /**
     * The column a field's name resolves to on the table its site navigates to.
     *
     * @param matchedName the effective name the classifier resolved: the {@code @field} binding where
     *     one decoded, else the field's own name
     * @param matchedBy {@code JOOQ_NAME} or {@code SQL_NAME}, the tier that matched
     */
    record ColumnBinding(String table, String column, String matchedName, String matchedBy) {}

    /**
     * One element of a field's {@code @reference} path, resolved: where the chain stands when it reads
     * the element and where the element lands.
     *
     * <p>Both endpoints are schema-qualified, which is the whole of what the retired projection's bare
     * target name could not carry. Each row states its own {@code (ordinal, position)} key, so a field
     * carrying two {@code @reference} applications reports two paths without either being concatenated
     * into the other by a grouping done here.
     *
     * @param ordinal the owning application's ordinal, the directive being repeatable
     * @param position the element's 0-based position within its own application's path
     * @param via {@code KEY} where the element named a constraint, {@code TABLE} where it named a table
     * @param keyMatchedBy which namespace answered a {@code KEY} element, {@code null} on a
     *     {@code TABLE} one
     * @param targets how many distinct tables the element reaches; above one the destination is
     *     uncertain
     * @param candidates how many resolutions the element has, counting routes and not just
     *     destinations; the arity the generator's "which foreign key did you mean" rejection counts
     */
    record Hop(
        int ordinal, int position, String via, String keyMatchedBy,
        String fromTable, String toTable, String constraintName, boolean fkOnFrom,
        int targets, int candidates
    ) {}

    /**
     * One census method a field's authored Java reference names.
     *
     * @param declaredVia {@code SERVICE}, {@code EXTERNAL_FIELD} or {@code CONDITION}: which directive
     *     named the method. The first two are {@code intent_field_producer_method}'s own two arms; the
     *     third is this module's read, that view's population being scoped to the directives whose
     *     method produces a field's value
     * @param candidates how many census methods the reference matches, this row being one of them;
     *     above one the reference names an overload set and the store declines to pick
     */
    record MethodBinding(
        String className, String methodName, int arity, String declaredVia, int candidates
    ) {}

    /**
     * A page of type entries, the size of the population it was drawn from, and the cursor for the next
     * page, absent on the last one.
     *
     * @param total the whole population rather than what is left after the cursor, which is what the
     *     summary line reports and what tells an agent whether paging is worth starting
     */
    record TypePage(List<TypeEntry> types, int total, Optional<String> nextCursor) {}

    /**
     * A page of types beside the fields of exactly the types on it, keyed by the owning type name.
     *
     * <p>A type with no fields at all has no entry in the map rather than an empty one, absence and
     * emptiness being the same answer to "what fields does this type have" and the map's own
     * {@code getOrDefault} being where the two meet.
     */
    record SchemaAnswer(TypePage page, Map<String, List<FieldEntry>> fieldsByType) {}

    /**
     * Reads one page of the graph's types and the fields of exactly the types on it.
     *
     * <p>Through {@code reader} rather than the handle the single-query tools use, for the reason every
     * multi-statement read here takes one: a second statement on the session writer's connection is a
     * savepoint rather than a transaction boundary, so a capture commit could land between the page and
     * its fields and the fields would come back for a schema the page no longer describes. A reader's
     * own connection makes the pairing structural.
     *
     * @param typeFilter narrows to one type, which returns it or nothing; the page's own bound and
     *     cursor are then beside the point, one type being one entry
     */
    static SchemaAnswer read(
        StoreReader reader, String graphName, Optional<String> typeFilter, Optional<String> cursor,
        int limit
    ) {
        return reader.read(dsl -> {
            var store = new StoreHandle(dsl, graphName);
            var page = types(store, typeFilter, cursor, limit);
            return new SchemaAnswer(page,
                fields(store, page.types().stream().map(TypeEntry::typeName).toList()));
        });
    }

    // ---- the type grain ----

    /**
     * The graph's types ordered by name, keyset-paged on the name, each carrying what the store made of
     * it.
     *
     * <p>The population is the types this graph's schema declares, which is
     * {@code graphql_type} narrowed to the rows carrying at least one declaration site. Engine-provided
     * built-in scalars have an existence row and no site, which the relation's own comment states, so
     * the narrowing is a documented distinction read rather than a name list maintained here; a
     * user-declared scalar has a site and stays.
     *
     * <p>Keyset rather than offset, and the type name is both the order and the cursor: it is the
     * relation's own key within a graph, so the page's stability stops being something the ordering has
     * to promise. Fetched as {@code limit + 1} so the last page is recognised by what came back.
     */
    private static TypePage types(
        StoreHandle store, Optional<String> typeFilter, Optional<String> cursor, int limit
    ) {
        var filters = new ArrayList<Condition>();
        filters.add(GRAPHQL_TYPE.GRAPH_NAME.eq(store.graphName()));
        filters.add(exists(selectOne()
            .from(GRAPHQL_TYPE_DECLARATION)
            .where(ofType(GRAPHQL_TYPE_DECLARATION.GRAPH_NAME, GRAPHQL_TYPE_DECLARATION.TYPE_NAME))));
        typeFilter.ifPresent(name -> filters.add(GRAPHQL_TYPE.TYPE_NAME.eq(name)));

        int total = store.dsl().fetchCount(GRAPHQL_TYPE, filters);

        var page = new ArrayList<>(filters);
        McpWire.decodeKeysetCursor(cursor.orElse(null), 1)
            .ifPresent(key -> page.add(GRAPHQL_TYPE.TYPE_NAME.gt(key.getFirst())));

        var rows = store.dsl()
            .select(GRAPHQL_TYPE.TYPE_NAME, GRAPHQL_TYPE.KIND,
                typeClaims(), typeDemand(), typeConflict(), tables(), backing(store),
                backingConflict(), unionMembers(), implementors(), node(), declarations())
            .from(GRAPHQL_TYPE)
            .where(page)
            .orderBy(GRAPHQL_TYPE.TYPE_NAME.asc())
            .limit(limit + 1)
            .fetch(Records.mapping(TypeEntry::new));

        var types = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        var nextCursor = rows.size() > types.size() && !types.isEmpty()
            ? Optional.of(McpWire.encodeKeysetCursor(List.of(types.getLast().typeName())))
            : Optional.<String>empty();
        return new TypePage(types, total, nextCursor);
    }

    /** The predicate correlating a graph-keyed type-grain relation to the type row being projected. */
    private static Condition ofType(Field<String> graph, Field<String> type) {
        return graph.eq(GRAPHQL_TYPE.GRAPH_NAME).and(type.eq(GRAPHQL_TYPE.TYPE_NAME));
    }

    /** What the author claimed the type is, ordered by classifier so two claims read in one order. */
    private static Field<List<TypeClaim>> typeClaims() {
        return multiset(
            select(INTENT_AUTHORED_TYPE_CLAIM.CLASSIFIER, INTENT_AUTHORED_TYPE_CLAIM.TRIGGER,
                INTENT_AUTHORED_TYPE_CLAIM.DECODED, INTENT_AUTHORED_TYPE_CLAIM.SOURCE_NAME,
                INTENT_AUTHORED_TYPE_CLAIM.SOURCE_LINE, INTENT_AUTHORED_TYPE_CLAIM.SOURCE_COLUMN)
                .from(INTENT_AUTHORED_TYPE_CLAIM)
                .where(ofType(INTENT_AUTHORED_TYPE_CLAIM.GRAPH_NAME,
                    INTENT_AUTHORED_TYPE_CLAIM.TYPE_NAME))
                .orderBy(INTENT_AUTHORED_TYPE_CLAIM.CLASSIFIER.asc()))
            .convertFrom(r -> r.map(row -> new TypeClaim(row.value1(), row.value2(),
                Boolean.TRUE.equals(row.value3()),
                McpWire.position(row.value4(), row.value5(), row.value6()))));
    }

    /**
     * Whether the type grain demanded a verdict of this type. At most one row, the reduction being one
     * verdict per member of the classification domain, so the list is a presence check with a payload.
     */
    private static Field<Optional<Demand>> typeDemand() {
        return multiset(
            select(INTENT_RESOLVED_TYPE_DEMAND.VERDICT, INTENT_RESOLVED_TYPE_DEMAND.RULE)
                .from(INTENT_RESOLVED_TYPE_DEMAND)
                .where(ofType(INTENT_RESOLVED_TYPE_DEMAND.GRAPH_NAME,
                    INTENT_RESOLVED_TYPE_DEMAND.TYPE_NAME)))
            .convertFrom(r -> r.map(Records.mapping(Demand::new)).stream().findFirst());
    }

    /**
     * The type-grain claim conflict, which the two-grain relation marks by a null field name: the same
     * relation carries the field-grain rows and telling them apart on the key is what keeps a field's
     * violation out of its type's slot.
     */
    private static Field<Optional<Conflict>> typeConflict() {
        return conflicts(ofType(INTENT_AUTHORED_CLAIM_CONFLICT.GRAPH_NAME,
            INTENT_AUTHORED_CLAIM_CONFLICT.TYPE_NAME)
            .and(INTENT_AUTHORED_CLAIM_CONFLICT.FIELD_NAME.isNull()));
    }

    /** The conflict at one coordinate, whichever grain {@code coordinate} names. */
    private static Field<Optional<Conflict>> conflicts(Condition coordinate) {
        return multiset(
            select(INTENT_AUTHORED_CLAIM_CONFLICT.VERDICT, INTENT_AUTHORED_CLAIM_CONFLICT.DIRECTIVES,
                INTENT_AUTHORED_CLAIM_CONFLICT.MESSAGE, INTENT_AUTHORED_CLAIM_CONFLICT.SOURCE_NAME,
                INTENT_AUTHORED_CLAIM_CONFLICT.SOURCE_LINE,
                INTENT_AUTHORED_CLAIM_CONFLICT.SOURCE_COLUMN)
                .from(INTENT_AUTHORED_CLAIM_CONFLICT)
                .where(coordinate))
            .convertFrom(r -> r.map(row -> new Conflict(row.value1(), row.value2(), row.value3(),
                McpWire.position(row.value4(), row.value5(), row.value6()))).stream().findFirst());
    }

    /**
     * The catalog tables the type's {@code @table} binding resolves to, ordered by the pair the id is
     * spelled from. Ambiguity arrives as rows with the arity on each, which is the relation's own shape:
     * a reader that counted for itself would be re-deriving the resolution's arity.
     */
    private static Field<List<TableBinding>> tables() {
        return multiset(
            select(qualified(INTENT_BOUND_TABLE.TABLE_SCHEMA, INTENT_BOUND_TABLE.TABLE_NAME),
                INTENT_BOUND_TABLE.CANDIDATES)
                .from(INTENT_BOUND_TABLE)
                .where(ofType(INTENT_BOUND_TABLE.GRAPH_NAME, INTENT_BOUND_TABLE.TYPE_NAME))
                .orderBy(INTENT_BOUND_TABLE.TABLE_SCHEMA.asc(), INTENT_BOUND_TABLE.TABLE_NAME.asc()))
            .convertFrom(r -> r.map(Records.mapping(TableBinding::new)));
    }

    /**
     * The classes the store says stand for the type, from either population that can answer, each with
     * the members it offers.
     *
     * <p>The coalescing view rather than the closure underneath it, on that view's own terms: it is the
     * one relation for the question every consumer of a backing asks, which is what class and not which
     * walk found it. A type both populations answer differently is two entries with their
     * {@code declared_via}, never one with the walk's table-wins precedence quietly applied.
     */
    private static Field<List<Backing>> backing(StoreHandle store) {
        return multiset(
            select(INTENT_TYPE_BACKING.CLASS_NAME, INTENT_TYPE_BACKING.DECLARED_VIA, members(store))
                .from(INTENT_TYPE_BACKING)
                .where(ofType(INTENT_TYPE_BACKING.GRAPH_NAME, INTENT_TYPE_BACKING.TYPE_NAME))
                .orderBy(INTENT_TYPE_BACKING.CLASS_NAME.asc(),
                    INTENT_TYPE_BACKING.DECLARED_VIA.asc()))
            .convertFrom(r -> r.map(Records.mapping(Backing::new)));
    }

    /**
     * The member names one backing class offers, read from the store's own member-slot relation.
     *
     * <p>Source-keyed like the census it stands on, so the graph reaches it through
     * {@link StoreHandle#reads} rather than by a graph column it does not carry. A class the census
     * never reached answers with nothing, which is not a failure: the table arm's classes are generated
     * jOOQ records the census deliberately never scans.
     */
    private static Field<List<MemberSlot>> members(StoreHandle store) {
        return multiset(
            select(INTENT_CLASS_MEMBER_SLOT.SLOT_NAME, INTENT_CLASS_MEMBER_SLOT.DISPLAY_TYPE,
                INTENT_CLASS_MEMBER_SLOT.ORIGIN, INTENT_CLASS_MEMBER_SLOT.ACCESSOR_METHOD_NAME)
                .from(INTENT_CLASS_MEMBER_SLOT)
                .where(INTENT_CLASS_MEMBER_SLOT.CLASS_NAME.eq(INTENT_TYPE_BACKING.CLASS_NAME)
                    .and(store.reads(INTENT_CLASS_MEMBER_SLOT.SOURCE_NAME)))
                .orderBy(INTENT_CLASS_MEMBER_SLOT.SLOT_NAME.asc(),
                    INTENT_CLASS_MEMBER_SLOT.ACCESSOR_METHOD_NAME.asc()))
            .convertFrom(r -> r.map(Records.mapping(MemberSlot::new)));
    }

    /** The types the store answers this one with where that is more than one class. */
    private static Field<Optional<BackingConflict>> backingConflict() {
        return multiset(
            select(INTENT_TYPE_BACKING_CONFLICT.CLASS_NAMES, INTENT_TYPE_BACKING_CONFLICT.CANDIDATES)
                .from(INTENT_TYPE_BACKING_CONFLICT)
                .where(ofType(INTENT_TYPE_BACKING_CONFLICT.GRAPH_NAME,
                    INTENT_TYPE_BACKING_CONFLICT.TYPE_NAME)))
            .convertFrom(r -> r.map(Records.mapping(BackingConflict::new)).stream().findFirst());
    }

    /**
     * A union's member types, in the order the union lists them.
     *
     * <p>Beside {@link #implementors()} rather than unioned with it, and that is the wire shape too. The
     * two are different SDL mechanisms, no type is both a union and an interface, and naming the
     * mechanism by which slot answered says more than one list with a provenance column on every row.
     */
    private static Field<List<String>> unionMembers() {
        return multiset(
            select(GRAPHQL_UNION_MEMBER.MEMBER_TYPE_NAME)
                .from(GRAPHQL_UNION_MEMBER)
                .where(GRAPHQL_UNION_MEMBER.GRAPH_NAME.eq(GRAPHQL_TYPE.GRAPH_NAME)
                    .and(GRAPHQL_UNION_MEMBER.UNION_NAME.eq(GRAPHQL_TYPE.TYPE_NAME)))
                .orderBy(GRAPHQL_UNION_MEMBER.ORDINAL.asc()))
            .convertFrom(r -> r.map(Record1::value1));
    }

    /**
     * The types declaring that they implement this interface, ordered by name.
     *
     * <p>The stored edge inverted, which is the direction every consumer of an abstract type reads: the
     * relation records the declaration ("this object implements that interface") and what an agent asks
     * of an interface is who its participants are.
     */
    private static Field<List<String>> implementors() {
        return multiset(
            select(GRAPHQL_IMPLEMENTS.TYPE_NAME)
                .from(GRAPHQL_IMPLEMENTS)
                .where(GRAPHQL_IMPLEMENTS.GRAPH_NAME.eq(GRAPHQL_TYPE.GRAPH_NAME)
                    .and(GRAPHQL_IMPLEMENTS.INTERFACE_NAME.eq(GRAPHQL_TYPE.TYPE_NAME)))
                .orderBy(GRAPHQL_IMPLEMENTS.TYPE_NAME.asc()))
            .convertFrom(r -> r.map(Record1::value1));
    }

    /** The type's {@code @node} identity, with its key columns in the order the author wrote them. */
    private static Field<Optional<Node>> node() {
        return multiset(
            select(GRAPHITRON_NODE.TYPE_ID, nodeKeyColumns())
                .from(GRAPHITRON_NODE)
                .where(ofType(GRAPHITRON_NODE.GRAPH_NAME, GRAPHITRON_NODE.TYPE_NAME)))
            .convertFrom(r -> r.map(Records.mapping(Node::new)).stream().findFirst());
    }

    /** One {@code @node}'s authored key columns, correlated to the node row being projected. */
    private static Field<List<String>> nodeKeyColumns() {
        return multiset(
            select(GRAPHITRON_NODE_KEY_COLUMN.COLUMN_REF)
                .from(GRAPHITRON_NODE_KEY_COLUMN)
                .where(GRAPHITRON_NODE_KEY_COLUMN.GRAPH_NAME.eq(GRAPHITRON_NODE.GRAPH_NAME)
                    .and(GRAPHITRON_NODE_KEY_COLUMN.TYPE_NAME.eq(GRAPHITRON_NODE.TYPE_NAME)))
                .orderBy(GRAPHITRON_NODE_KEY_COLUMN.POSITION.asc()))
            .convertFrom(r -> r.map(Record1::value1));
    }

    /**
     * Every site the type is declared or extended at, in merge order: the base definition first, then
     * the extensions as the documents contribute them.
     *
     * <p>Every site rather than the one a projection reduced them to. A type declared once answers
     * identically, so what this adds is visible only on an extended type, where the reduction was
     * hiding files an author needs to open.
     */
    private static Field<List<Declaration>> declarations() {
        return multiset(
            select(GRAPHQL_TYPE_DECLARATION.SOURCE_NAME, GRAPHQL_TYPE_DECLARATION.SOURCE_LINE,
                GRAPHQL_TYPE_DECLARATION.SOURCE_COLUMN, GRAPHQL_TYPE_DECLARATION.IS_EXTENSION,
                GRAPHQL_TYPE_DECLARATION.KIND)
                .from(GRAPHQL_TYPE_DECLARATION)
                .where(ofType(GRAPHQL_TYPE_DECLARATION.GRAPH_NAME,
                    GRAPHQL_TYPE_DECLARATION.TYPE_NAME))
                .orderBy(GRAPHQL_TYPE_DECLARATION.MERGE_ORDINAL.asc(),
                    GRAPHQL_TYPE_DECLARATION.SOURCE_NAME.asc(),
                    GRAPHQL_TYPE_DECLARATION.SOURCE_LINE.asc()))
            .convertFrom(r -> r.map(row -> new Declaration(
                McpWire.position(row.value1(), row.value2(), row.value3())
                    .orElseThrow(() -> new IllegalStateException(
                        "a declaration site with no position: " + row.value1())),
                Boolean.TRUE.equals(row.value4()), row.value5())));
    }

    // ---- the field grain ----

    /**
     * Every field of {@code typeNames}, with what the store made of each coordinate, keyed by the
     * owning type.
     *
     * <p>Ordered by type then field name, so a page's fields read the way the retired projection's
     * sorted coordinate keys did. The declaration order is a column away and deliberately not used: it
     * runs per contributing site on an extended type, so a name order is the one this read can state.
     */
    private static Map<String, List<FieldEntry>> fields(StoreHandle store, List<String> typeNames) {
        if (typeNames.isEmpty()) {
            return Map.of();
        }
        var claims = claims(store, typeNames);
        var rows = store.dsl()
            .select(GRAPHQL_FIELD.TYPE_NAME, GRAPHQL_FIELD.FIELD_NAME, GRAPHQL_FIELD.TYPE_SDL,
                fieldDemand(), fieldConflict(), joinPath(), producerMethods(),
                conditionMethods(store))
            .from(GRAPHQL_FIELD)
            .where(GRAPHQL_FIELD.GRAPH_NAME.eq(store.graphName())
                .and(GRAPHQL_FIELD.TYPE_NAME.in(typeNames)))
            .orderBy(GRAPHQL_FIELD.TYPE_NAME.asc(), GRAPHQL_FIELD.FIELD_NAME.asc())
            .fetch(Records.mapping(FieldRow::new));

        var byType = new LinkedHashMap<String, List<FieldEntry>>();
        for (var row : rows) {
            byType.computeIfAbsent(row.typeName(), ignored -> new ArrayList<>())
                .add(row.withClaims(claims.getOrDefault(
                    new Coordinate(row.typeName(), row.fieldName()), ClaimSet.EMPTY)));
        }
        return byType;
    }

    /**
     * One field row as its own statement projects it: everything at the coordinate grain except the claim
     * resolution, which is the read beside this one.
     */
    private record FieldRow(
        String typeName, String fieldName, String typeSdl, Optional<Demand> demand,
        Optional<Conflict> conflict, List<Hop> joinPath, List<MethodBinding> producerMethods,
        List<MethodBinding> conditionMethods
    ) {

        FieldEntry withClaims(ClaimSet claims) {
            return new FieldEntry(typeName, fieldName, typeSdl, claims, demand, conflict, joinPath,
                producerMethods, conditionMethods);
        }
    }

    /**
     * What claims each of the page's coordinates, resolved, plus the column the structural classifier
     * matched where that reading won.
     *
     * <p>A statement of its own, driven from the resolution rather than correlated per field row, and the
     * reason is measured rather than stylistic. {@code intent_column_match_claim} collapses its matches
     * with a {@code ROW_NUMBER() OVER (PARTITION BY ...)} over a derived relation, and a window cannot be
     * pruned by a predicate applied outside it, so a correlated read pays the whole view's evaluation on
     * every call instead of a filtered one. {@code intent_resolved_field_claim} unions that view in and
     * inherits the property. On a schema of sixty types and eight hundred coordinates, correlating these
     * two cost twenty-four seconds where every other field-grain slot together cost a third of one; read
     * this way they cost about as much as the rest. The relation's own DDL comment states the rule this
     * follows: any relation joining a derivation this deep wants the derivation first in the
     * {@code FROM} clause.
     *
     * <p>The authoring application and the column witness join in as left joins on the coordinate, so the
     * statement stays one row per resolved claim: the authored relation holds at most one application per
     * classifier at a coordinate, and the column match at most one row per coordinate, which is what
     * makes both joins arity-preserving rather than a fold. The witness is gated on the resolved
     * classifier being the column match's own, so a coordinate an authored directive claims does not
     * report a column binding the directive overrides. That gate is a read of the store's masking; the
     * structural reading survives in the classifier view by design, precisely so a diagnostic can say
     * what it would have classified as.
     */
    private static Map<Coordinate, ClaimSet> claims(StoreHandle store, List<String> typeNames) {
        var rows = store.dsl()
            .select(INTENT_RESOLVED_FIELD_CLAIM.TYPE_NAME, INTENT_RESOLVED_FIELD_CLAIM.FIELD_NAME,
                INTENT_RESOLVED_FIELD_CLAIM.CLASSIFIER, INTENT_RESOLVED_FIELD_CLAIM.TIER,
                INTENT_AUTHORED_FIELD_CLAIM.TRIGGER, INTENT_AUTHORED_FIELD_CLAIM.DECODED,
                INTENT_AUTHORED_FIELD_CLAIM.SOURCE_NAME, INTENT_AUTHORED_FIELD_CLAIM.SOURCE_LINE,
                INTENT_AUTHORED_FIELD_CLAIM.SOURCE_COLUMN,
                INTENT_COLUMN_MATCH_CLAIM.TABLE_SCHEMA, INTENT_COLUMN_MATCH_CLAIM.TABLE_NAME,
                INTENT_COLUMN_MATCH_CLAIM.COLUMN_NAME, INTENT_COLUMN_MATCH_CLAIM.MATCHED_NAME,
                INTENT_COLUMN_MATCH_CLAIM.MATCHED_BY)
            .from(INTENT_RESOLVED_FIELD_CLAIM)
            .leftJoin(INTENT_AUTHORED_FIELD_CLAIM)
            .on(INTENT_AUTHORED_FIELD_CLAIM.GRAPH_NAME.eq(INTENT_RESOLVED_FIELD_CLAIM.GRAPH_NAME)
                .and(INTENT_AUTHORED_FIELD_CLAIM.TYPE_NAME
                    .eq(INTENT_RESOLVED_FIELD_CLAIM.TYPE_NAME))
                .and(INTENT_AUTHORED_FIELD_CLAIM.FIELD_NAME
                    .eq(INTENT_RESOLVED_FIELD_CLAIM.FIELD_NAME))
                .and(INTENT_AUTHORED_FIELD_CLAIM.CLASSIFIER
                    .eq(INTENT_RESOLVED_FIELD_CLAIM.CLASSIFIER)))
            .leftJoin(INTENT_COLUMN_MATCH_CLAIM)
            .on(INTENT_COLUMN_MATCH_CLAIM.GRAPH_NAME.eq(INTENT_RESOLVED_FIELD_CLAIM.GRAPH_NAME)
                .and(INTENT_COLUMN_MATCH_CLAIM.TYPE_NAME
                    .eq(INTENT_RESOLVED_FIELD_CLAIM.TYPE_NAME))
                .and(INTENT_COLUMN_MATCH_CLAIM.FIELD_NAME
                    .eq(INTENT_RESOLVED_FIELD_CLAIM.FIELD_NAME))
                .and(INTENT_RESOLVED_FIELD_CLAIM.CLASSIFIER.eq(TABLE_COLUMN)))
            .where(INTENT_RESOLVED_FIELD_CLAIM.GRAPH_NAME.eq(store.graphName())
                .and(INTENT_RESOLVED_FIELD_CLAIM.TYPE_NAME.in(typeNames)))
            .orderBy(INTENT_RESOLVED_FIELD_CLAIM.TYPE_NAME.asc(),
                INTENT_RESOLVED_FIELD_CLAIM.FIELD_NAME.asc(),
                INTENT_RESOLVED_FIELD_CLAIM.CLASSIFIER.asc())
            .fetch(Records.mapping(ClaimRow::new));

        var claims = new LinkedHashMap<Coordinate, List<FieldClaim>>();
        var columns = new LinkedHashMap<Coordinate, ColumnBinding>();
        for (var row : rows) {
            var coordinate = new Coordinate(row.typeName(), row.fieldName());
            claims.computeIfAbsent(coordinate, ignored -> new ArrayList<>()).add(row.claim());
            row.column().ifPresent(column -> columns.put(coordinate, column));
        }
        var sets = new LinkedHashMap<Coordinate, ClaimSet>();
        claims.forEach((coordinate, list) -> sets.put(coordinate,
            new ClaimSet(List.copyOf(list), Optional.ofNullable(columns.get(coordinate)))));
        return sets;
    }

    /** One resolved claim with its authoring application and, on the column match, its witness. */
    private record ClaimRow(
        String typeName, String fieldName, String classifier, String tier, String trigger,
        Boolean decoded, String sourceName, Integer sourceLine, Integer sourceColumn,
        String tableSchema, String tableName, String columnName, String matchedName, String matchedBy
    ) {

        FieldClaim claim() {
            return new FieldClaim(classifier, tier, trigger, decoded,
                McpWire.position(sourceName, sourceLine, sourceColumn));
        }

        /**
         * The column this claim matched, absent on every claim but the column match's own.
         *
         * <p>Read off the column name rather than off the composed table id: the view declares the name
         * {@code NOT NULL}, so it is null exactly when no witness row joined, where a composed
         * qualification would be asking a dialect what it does with a null operand.
         */
        Optional<ColumnBinding> column() {
            return columnName == null
                ? Optional.empty()
                : Optional.of(new ColumnBinding(tableSchema + "." + tableName, columnName,
                    matchedName, matchedBy));
        }
    }

    /** The predicate correlating a field-grain relation to the field row being projected. */
    private static Condition ofField(Field<String> graph, Field<String> type, Field<String> fieldName) {
        return graph.eq(GRAPHQL_FIELD.GRAPH_NAME)
            .and(type.eq(GRAPHQL_FIELD.TYPE_NAME))
            .and(fieldName.eq(GRAPHQL_FIELD.FIELD_NAME));
    }

    /** Whether the field grain demanded a verdict of this coordinate. */
    private static Field<Optional<Demand>> fieldDemand() {
        return multiset(
            select(INTENT_RESOLVED_FIELD_DEMAND.VERDICT, INTENT_RESOLVED_FIELD_DEMAND.RULE)
                .from(INTENT_RESOLVED_FIELD_DEMAND)
                .where(ofField(INTENT_RESOLVED_FIELD_DEMAND.GRAPH_NAME,
                    INTENT_RESOLVED_FIELD_DEMAND.TYPE_NAME,
                    INTENT_RESOLVED_FIELD_DEMAND.FIELD_NAME)))
            .convertFrom(r -> r.map(Records.mapping(Demand::new)).stream().findFirst());
    }

    /** The field-grain claim conflict, told from its type's by carrying a field name. */
    private static Field<Optional<Conflict>> fieldConflict() {
        return conflicts(ofField(INTENT_AUTHORED_CLAIM_CONFLICT.GRAPH_NAME,
            INTENT_AUTHORED_CLAIM_CONFLICT.TYPE_NAME, INTENT_AUTHORED_CLAIM_CONFLICT.FIELD_NAME));
    }

    /**
     * The field's {@code @reference} path as the store resolves it: each element walked from the
     * enclosing type's table binding, so a row exists only for an element the chain can be shown to
     * reach.
     *
     * <p>The resolved relation rather than the local one underneath it. The hop relation enumerates
     * every table-to-table hop an element could express, both orientations of every foreign key
     * included, before anything decides where the chain stands; rendering that as a join path would show
     * one element departing from two different tables at once. Absence here means the chain did not
     * reach the element, never that the element resolves to nothing in particular.
     */
    private static Field<List<Hop>> joinPath() {
        return multiset(
            select(INTENT_FIELD_REFERENCE_STEP_TARGET.ORDINAL,
                INTENT_FIELD_REFERENCE_STEP_TARGET.POSITION,
                INTENT_FIELD_REFERENCE_STEP_TARGET.VIA,
                INTENT_FIELD_REFERENCE_STEP_TARGET.KEY_MATCHED_BY,
                qualified(INTENT_FIELD_REFERENCE_STEP_TARGET.FROM_SCHEMA,
                    INTENT_FIELD_REFERENCE_STEP_TARGET.FROM_TABLE),
                qualified(INTENT_FIELD_REFERENCE_STEP_TARGET.TO_SCHEMA,
                    INTENT_FIELD_REFERENCE_STEP_TARGET.TO_TABLE),
                INTENT_FIELD_REFERENCE_STEP_TARGET.CONSTRAINT_NAME,
                INTENT_FIELD_REFERENCE_STEP_TARGET.FK_ON_FROM,
                INTENT_FIELD_REFERENCE_STEP_TARGET.TARGETS,
                INTENT_FIELD_REFERENCE_STEP_TARGET.CANDIDATES)
                .from(INTENT_FIELD_REFERENCE_STEP_TARGET)
                .where(ofField(INTENT_FIELD_REFERENCE_STEP_TARGET.GRAPH_NAME,
                    INTENT_FIELD_REFERENCE_STEP_TARGET.TYPE_NAME,
                    INTENT_FIELD_REFERENCE_STEP_TARGET.FIELD_NAME))
                .orderBy(INTENT_FIELD_REFERENCE_STEP_TARGET.ORDINAL.asc(),
                    INTENT_FIELD_REFERENCE_STEP_TARGET.POSITION.asc(),
                    INTENT_FIELD_REFERENCE_STEP_TARGET.TO_SCHEMA.asc(),
                    INTENT_FIELD_REFERENCE_STEP_TARGET.TO_TABLE.asc(),
                    INTENT_FIELD_REFERENCE_STEP_TARGET.CONSTRAINT_NAME.asc()))
            .convertFrom(r -> r.map(row -> new Hop(row.value1(), row.value2(), row.value3(),
                row.value4(), row.value5(), row.value6(), row.value7(),
                Boolean.TRUE.equals(row.value8()), row.value9(), row.value10())));
    }

    /**
     * The census methods a {@code @service} or an {@code @externalField} on this coordinate names, as
     * the store's producer view resolves them.
     *
     * <p>Ambiguity arrives as rows with the arity on each, which is the view's own shape: a reference
     * matching two overloads is two rows and {@code candidates} says so, where the walk this replaces
     * takes whichever method reflection hands back first.
     */
    private static Field<List<MethodBinding>> producerMethods() {
        return multiset(
            select(INTENT_FIELD_PRODUCER_METHOD.CLASS_NAME, INTENT_FIELD_PRODUCER_METHOD.METHOD_NAME,
                arity(INTENT_FIELD_PRODUCER_METHOD.SOURCE_NAME,
                    INTENT_FIELD_PRODUCER_METHOD.CLASS_NAME,
                    INTENT_FIELD_PRODUCER_METHOD.METHOD_NAME,
                    INTENT_FIELD_PRODUCER_METHOD.DESCRIPTOR),
                INTENT_FIELD_PRODUCER_METHOD.DECLARED_VIA, INTENT_FIELD_PRODUCER_METHOD.CANDIDATES)
                .from(INTENT_FIELD_PRODUCER_METHOD)
                .where(ofField(INTENT_FIELD_PRODUCER_METHOD.GRAPH_NAME,
                    INTENT_FIELD_PRODUCER_METHOD.TYPE_NAME,
                    INTENT_FIELD_PRODUCER_METHOD.FIELD_NAME)))
            .convertFrom(r -> r.map(Records.mapping(MethodBinding::new)));
    }

    /**
     * The census methods a {@code @condition} on this coordinate names, which the producer view does not
     * carry.
     *
     * <p>That view's population is scoped to the two directives whose method produces a field's value,
     * {@code @service} and {@code @externalField}, and its own comment says so. A {@code @condition}
     * names a class and a method too, so reading only the view would drop every condition-carrying
     * coordinate's method slot silently, without any sign on the wire that a slot had been dropped
     * rather than left empty.
     *
     * <p>A read here rather than a third arm on that view, which is the escalation rule applied rather
     * than dodged. A rule graduates to a shared relation where it genuinely must be shared, and this one
     * is not: no other surface in the workspace resolves a condition's method pair at all, so widening a
     * closed two-value provenance vocabulary would be putting this module's requirement into the model's.
     * What the read does is what that view does for its own arms: match the reference's class and method
     * against the census under this graph's sources, and report ambiguity as an arity rather than resolve
     * it by a pick.
     */
    private static Field<List<MethodBinding>> conditionMethods(StoreHandle store) {
        return multiset(
            select(JVM_METHOD.CLASS_NAME, JVM_METHOD.METHOD_NAME,
                arity(JVM_METHOD.SOURCE_NAME, JVM_METHOD.CLASS_NAME, JVM_METHOD.METHOD_NAME,
                    JVM_METHOD.DESCRIPTOR),
                inline(CONDITION), conditionCandidates(store))
                .from(GRAPHITRON_FIELD_CONDITION)
                .join(JVM_METHOD)
                .on(JVM_METHOD.CLASS_NAME.eq(GRAPHITRON_FIELD_CONDITION.CLASS_NAME)
                    .and(JVM_METHOD.METHOD_NAME.eq(GRAPHITRON_FIELD_CONDITION.METHOD)))
                .where(ofField(GRAPHITRON_FIELD_CONDITION.GRAPH_NAME,
                    GRAPHITRON_FIELD_CONDITION.TYPE_NAME, GRAPHITRON_FIELD_CONDITION.FIELD_NAME)
                    .and(store.reads(JVM_METHOD.SOURCE_NAME))))
            .convertFrom(r -> r.map(Records.mapping(MethodBinding::new)));
    }

    /**
     * How many census methods the {@code @condition} reference being projected matches.
     *
     * <p>A scalar subquery rather than a window count over the arm's own rows, which is what the store's
     * producer view uses and what this read cannot: the {@code MULTISET} emulation wraps a projection
     * inside an aggregate, and a window function is not allowed there. The count is the same either way,
     * being the size of the match set the row is one of.
     */
    private static Field<Integer> conditionCandidates(StoreHandle store) {
        var match = JVM_METHOD.as("condition_match");
        return field(selectCount()
            .from(match)
            .where(match.CLASS_NAME.eq(GRAPHITRON_FIELD_CONDITION.CLASS_NAME)
                .and(match.METHOD_NAME.eq(GRAPHITRON_FIELD_CONDITION.METHOD))
                .and(store.reads(match.SOURCE_NAME))));
    }

    /**
     * How many parameters a census method declares, counted from the parameter rows rather than parsed
     * out of the descriptor.
     *
     * <p>Arity is what the method ref on the wire carries, and counting rows is how every other reader
     * of this census reaches it: the descriptor is the overload discriminator and its spelling is the
     * JVM's business, where the parameter rows are the store's own statement of how many there are.
     */
    private static Field<Integer> arity(
        Field<String> source, Field<String> className, Field<String> methodName,
        Field<String> descriptor
    ) {
        return field(selectCount()
            .from(JVM_METHOD_PARAMETER)
            .where(JVM_METHOD_PARAMETER.SOURCE_NAME.eq(source)
                .and(JVM_METHOD_PARAMETER.CLASS_NAME.eq(className))
                .and(JVM_METHOD_PARAMETER.METHOD_NAME.eq(methodName))
                .and(JVM_METHOD_PARAMETER.DESCRIPTOR.eq(descriptor))));
    }

    /** A schema-qualified table name composed in SQL, the form every tool hands a table back as. */
    private static Field<String> qualified(Field<String> schema, Field<String> table) {
        return concat(schema, inline("."), table);
    }
}
