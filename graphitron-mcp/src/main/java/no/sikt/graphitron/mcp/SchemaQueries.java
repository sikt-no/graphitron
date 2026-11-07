package no.sikt.graphitron.mcp;

import no.sikt.graphitron.model.boot.StoreAnswer;
import no.sikt.graphitron.model.boot.StoreReader;
import no.sikt.graphitron.model.derive.AuthoredClaim;
import no.sikt.graphitron.model.tables.GraphqlDirectiveApplication;
import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.Record1;
import org.jooq.OrderField;
import org.jooq.Records;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLETYPE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_CLAIM;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DIRECTIVE_APPLICATION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT_SITE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT_CLAIM;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENTRY_DEFECT_PATH_STEP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_CODE_REFERENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_CODE_REFERENCE_SITE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_NODE_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_NODE_KEYCOLUMN_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_APPLICATION;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_DECLARATION;
import static no.sikt.graphitron.model.Tables.GRAPHQL_POLY_MEMBER;
import static no.sikt.graphitron.model.Tables.CODE_TYPE;
import static no.sikt.graphitron.model.Tables.CODE_READ_SLOT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_AST_PATH_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_BINDING_CLAIM;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_CLAIM;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_CLAIMED;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP_INFERRED_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP_METHOD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP_PRIMARY_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STEP_ROUTINE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_STOP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PATH_NEED;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PATH_ELEMENT_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PATH_ELEMENT_PRIMARY_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE_BACKING;
import static no.sikt.graphitron.model.Tables.INTENT_TYPE_BACKING_CONFLICT;
import static no.sikt.graphitron.model.Tables.CODE_METHOD_PARAMETER;
import static no.sikt.graphitron.model.Tables.SQL_PRIMARY_KEY;
import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.concat;
import static org.jooq.impl.DSL.when;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectCount;
import static org.jooq.impl.DSL.selectDistinct;
import static org.jooq.impl.DSL.selectOne;

/**
 * This module's reads for the {@code schema} tool: what the store says a graph's SDL coordinates are
 * and what it made of them.
 *
 * <p>Four questions per coordinate, each answered by the relations that own it: what claims it, what it
 * binds, what conflicts on it, and where it is declared. None of those is a projection of a
 * classification verdict, and that is the point of the shape rather than a consequence of it: a
 * permit name is a fact about the generator's own taxonomy, where a claim and a binding are facts
 * about the author's schema, and it is the second set an agent asking
 * "what did graphitron make of this" actually wants.
 *
 * <p>Two statements, one per grain, and the grain boundary is the store's. A type and a
 * {@code Type.field} coordinate are two keys, every relation here is keyed by one or the other, and the
 * field-grain derivations are the expensive ones: H2 re-evaluates a joined derived relation once per
 * outer row, so correlating one per field inside the type-grain projection pays it once per field,
 * and the fields of a page are their own projection, driven from {@code graphql_field} narrowed to the
 * page's types. The
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

    /** The directives a method binding is written under, as the reference site names them. */
    private static final String SERVICE_DIRECTIVE = "service";
    private static final String EXTERNAL_FIELD_DIRECTIVE = "externalField";
    private static final String CONDITION_DIRECTIVE = "condition";

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
        Optional<Conflict> conflict,
        List<Hop> joinPath,
        List<PathStop> pathStops,
        List<MethodBinding> methods
    ) {}

    /** One authored claim on a type: which classification the author asked for, through which directive. */
    record TypeClaim(String classifier, String trigger, Optional<McpWire.Position> position) {}

    /**
     * One claim a directive written on the field makes, as written: the claim's kind, the directive
     * written at its position, and the position. A claim decides nothing, so a field claiming two
     * things lists both, and its conflict is the field's {@code conflict} slot.
     */
    record FieldClaim(String classifier, String trigger, Optional<McpWire.Position> position) {}

    /**
     * What one coordinate claims and the column it reads: the claims as written, and the column the
     * field's occurrences bind, which is what took effect, so a field a directive makes into anything
     * else has none.
     */
    record ClaimSet(List<FieldClaim> claims, Optional<ColumnBinding> column) {

        static final ClaimSet EMPTY = new ClaimSet(List.of(), Optional.empty());
    }

    /** A {@code Type.field} coordinate, the key the claim read's answer is paired to its field row on. */
    private record Coordinate(String typeName, String fieldName) {}

    /**
     * A claim conflict at a coordinate.
     *
     * <p>Carries no directive list of its own: the entry this sits on already publishes the
     * coordinate's claims as rows, each with the trigger that claimed it, so a caller reads which
     * directives contest the coordinate there and can ask membership rather than compare a joined
     * set. A second, joined spelling here would have answered strictly less for one more column.
     *
     * @param message the report's own message for the violation, display only
     */
    record Conflict(String verdict, String message, Optional<McpWire.Position> position) {}

    /**
     * The one catalog table a type's {@code @table} binds it to. An ambiguous or unresolved
     * {@code @table} binds none, and its defect says why.
     *
     * @param table the table's schema-qualified SQL name, which is the table id every tool hands back
     */
    record TableBinding(String table) {}

    /**
     * One class the store says stands for a type, with the member names it offers an author.
     *
     * @param declaredVia {@code BOUND_TABLE} where the type's {@code @table} binding was read through
     *     its table's generated record, {@code PRODUCER} where a producer's return or parameter
     *     grounded the class, {@code ACCESSOR} where a field read off a class backing its parent
     *     reached it. Provenance and never a preference: a type its two populations
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

    /**
     * How many classes answer a type where that is more than one.
     *
     * <p>The arity alone: the contesting classes are the entry's own {@code backing} rows, each
     * with the population that named it, so a caller reads them there rather than from a second
     * comma-joined spelling of the same set.
     */
    record BackingConflict(int candidates) {}

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
     * One step of a field's path as the store walks it: where the walk stands when it reads the
     * element and where the element lands.
     *
     * <p>Both endpoints are schema-qualified. A field's {@code @routine} and {@code @reference}
     * elements compose one path, so {@code position} is the element's place in the field's path rather
     * than within its own application; a field reached from more than one table reports the steps
     * from each, the first step of each departing where its occurrence starts.
     *
     * @param position the element's 0-based place in the field's path; a step no element wrote is
     *     placed by its position in the path
     * @param via {@code INFERRED} on a step no element wrote, a key found between two tables or the
     *     step from a routine's result to the {@code @table} the field returns; {@code ROUTINE} where
     *     the element calls a routine,
     *     {@code KEY} where it named a constraint, {@code PRIMARY_KEY} where it named the table's own
     *     primary key and so joins each row to itself, {@code CONDITION} where it names a method the
     *     join is, {@code TABLE} otherwise
     * @param keyMatchedBy which namespace answered a {@code KEY} or {@code PRIMARY_KEY} element,
     *     {@code null} on any other
     * @param constraintName the constraint a {@code KEY}, {@code PRIMARY_KEY} or inferred step
     *     crosses, {@code null} on any other
     * @param fkOnFrom whether that constraint is declared on the table the step departs
     */
    record Hop(
        int position, String via, String keyMatchedBy,
        String fromTable, String toTable, String constraintName, boolean fkOnFrom
    ) {}

    /**
     * Where a field's walk stops short of its path's end: the element it could not take one step
     * through.
     *
     * @param fromTable where the walk stands at the element
     * @param moves how many steps the element offers there: none means it reaches nothing from that
     *     table, more than one that the store declines to pick between them
     */
    record PathStop(int position, String fromTable, int moves) {}

    /**
     * The one census method a field's authored Java reference resolves to.
     *
     * @param declaredVia {@code SERVICE}, {@code EXTERNAL_FIELD} or {@code CONDITION}: which directive
     *     named the method
     */
    record MethodBinding(String className, String methodName, int arity, String declaredVia) {}

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
    record SchemaAnswer(
        TypePage page, Map<String, List<FieldEntry>> fieldsByType, SchemaLifecycle lifecycle
    ) {}

    /**
     * Reads one page of the graph's types, the fields of exactly the types on it, and the lifecycle
     * axes the answer is reported under.
     *
     * <p>Through {@code reader} rather than the handle the single-query tools use, for the reason every
     * multi-statement read here takes one: a second statement on the session writer's connection is a
     * savepoint rather than a transaction boundary, so a capture commit could land between the page and
     * its fields and the fields would come back for a schema the page no longer describes. A reader's
     * own connection makes the pairing structural.
     *
     * <p>The axes are read inside the same transaction, for that same reason: axes off a later commit
     * could report a document read clean beside coordinates from the read that refused it, which is
     * the one disagreement the two halves of this answer must not be able to have.
     *
     * @param typeFilter narrows to one type, which returns it or nothing; the page's own bound and
     *     cursor are then beside the point, one type being one entry
     */
    static StoreAnswer<SchemaAnswer> read(
        StoreReader reader, String graphName, Optional<String> typeFilter, Optional<String> cursor,
        int limit
    ) {
        return reader.read(dsl -> {
            var store = new StoreHandle(dsl, graphName);
            var page = types(store, typeFilter, cursor, limit);
            return new SchemaAnswer(page,
                fields(store, page.types().stream().map(TypeEntry::typeName).toList()),
                SchemaLifecycle.read(store));
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
                typeClaims(), typeConflict(), tables(), backing(store),
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

    /**
     * What the author claimed the type is, ordered by classifier so two claims read in one order: the
     * type family of the claims, each with the directive written at its position.
     */
    private static Field<List<TypeClaim>> typeClaims() {
        var c = GRAPHITRON_CLAIM;
        var da = GRAPHITRON_DIRECTIVE_APPLICATION;
        var classifier = when(c.KIND.eq("TABLE_TYPE"), "TABLE").otherwise("ERROR");
        return multiset(
            select(classifier, da.DIRECTIVE_NAME, c.SOURCE_NAME, c.SOURCE_LINE, c.SOURCE_COLUMN)
                .from(c)
                .join(da).on(da.GRAPH_NAME.eq(c.GRAPH_NAME), da.SOURCE_NAME.eq(c.SOURCE_NAME),
                    da.SOURCE_LINE.eq(c.SOURCE_LINE), da.SOURCE_COLUMN.eq(c.SOURCE_COLUMN))
                .where(ofType(c.GRAPH_NAME, c.COORDINATE), c.KIND.in("TABLE_TYPE", "ERROR_TYPE"))
                .orderBy(classifier.asc()))
            .convertFrom(r -> r.map(row -> new TypeClaim(row.value1(), row.value2(),
                McpWire.position(row.value3(), row.value4(), row.value5()))));
    }

    /**
     * The type-grain claim conflict, which the defect's site marks by a null field name: the same
     * relation carries the field-grain rows and telling them apart on the key is what keeps a field's
     * violation out of its type's slot.
     */
    private static Field<Optional<Conflict>> typeConflict() {
        var site = GRAPHITRON_ENTRY_DEFECT_SITE;
        return conflicts(ofType(site.GRAPH_NAME, site.TYPE_NAME).and(site.FIELD_NAME.isNull()));
    }

    /**
     * The claim conflict at one coordinate, whichever grain {@code coordinate} names: the entry defect
     * at its site, with the directives that wrote its claims and path steps nested on the defect's
     * key, composed into the sentence a build reports, naming the directives in the order a build
     * names them.
     */
    private static Field<Optional<Conflict>> conflicts(Condition coordinate) {
        var site = GRAPHITRON_ENTRY_DEFECT_SITE;
        var ec = GRAPHITRON_ENTRY_DEFECT_CLAIM;
        var c = GRAPHITRON_CLAIM;
        var da = GRAPHITRON_DIRECTIVE_APPLICATION;
        var ps = GRAPHITRON_ENTRY_DEFECT_PATH_STEP;
        var claimDirectives = multiset(select(da.DIRECTIVE_NAME)
                .from(ec)
                .join(c).on(c.GRAPH_NAME.eq(ec.GRAPH_NAME), c.COORDINATE.eq(ec.COORDINATE),
                    c.KIND.eq(ec.KIND), c.CLAIMER.eq(ec.CLAIMER))
                .join(da).on(da.GRAPH_NAME.eq(c.GRAPH_NAME), da.SOURCE_NAME.eq(c.SOURCE_NAME),
                    da.SOURCE_LINE.eq(c.SOURCE_LINE), da.SOURCE_COLUMN.eq(c.SOURCE_COLUMN))
                .where(ec.GRAPH_NAME.eq(site.GRAPH_NAME), ec.SOURCE_NAME.eq(site.SOURCE_NAME),
                    ec.SOURCE_LINE.eq(site.SOURCE_LINE), ec.SOURCE_COLUMN.eq(site.SOURCE_COLUMN),
                    ec.CODE.eq(site.CODE)))
            .convertFrom(rows -> rows.map(r -> r.value1()));
        var stepDirectives = multiset(select(inline(AuthoredClaim.ROUTINE.directive()))
                .from(ps)
                .where(ps.GRAPH_NAME.eq(site.GRAPH_NAME), ps.SOURCE_NAME.eq(site.SOURCE_NAME),
                    ps.SOURCE_LINE.eq(site.SOURCE_LINE), ps.SOURCE_COLUMN.eq(site.SOURCE_COLUMN),
                    ps.CODE.eq(site.CODE)))
            .convertFrom(rows -> rows.map(r -> r.value1()));
        return multiset(
            select(site.CODE, site.TYPE_NAME, site.FIELD_NAME, claimDirectives, stepDirectives,
                site.SOURCE_NAME, site.SOURCE_LINE, site.SOURCE_COLUMN)
                .from(site)
                .where(coordinate, site.CODE.in(CLAIMS_CONFLICT, ROUTINE_LOOKUP_DEFERRED)))
            .convertFrom(r -> r.map(row -> {
                boolean deferred = ROUTINE_LOOKUP_DEFERRED.equals(row.value1());
                String at = row.value3() == null
                    ? "Type '" + row.value2() + "': "
                    : "Field '" + row.value2() + "." + row.value3() + "': ";
                String sentence = deferred
                    ? "@routine with @lookupKey on a root field classifies but does not emit yet"
                    : ordered(Stream.concat(row.value4().stream(), row.value5().stream()).toList())
                        .stream().map(n -> "@" + n).collect(Collectors.joining(", "))
                        + " are mutually exclusive";
                return new Conflict(deferred ? "DEFERRED" : "CONFLICT", at + sentence,
                    McpWire.position(row.value6(), row.value7(), row.value8()));
            }).stream().findFirst());
    }

    /** A conflict's directive names, distinct, in {@link AuthoredClaim}'s order, any other after. */
    private static List<String> ordered(List<String> names) {
        var order = new HashMap<String, Integer>();
        for (var claim : AuthoredClaim.values()) {
            order.put(claim.directive(), claim.ordinal());
        }
        return names.stream().distinct()
            .sorted(Comparator.comparing((String n) -> order.getOrDefault(n, Integer.MAX_VALUE))
                .thenComparing(Comparator.naturalOrder()))
            .toList();
    }

    private static final String CLAIMS_CONFLICT = "CLAIMS_CONFLICT";

    private static final String ROUTINE_LOOKUP_DEFERRED = "ROUTINE_LOOKUP_DEFERRED";

    /**
     * The catalog tables the type's {@code @table} binding resolves to, ordered by the pair the id is
     * spelled from. Ambiguity arrives as rows with the arity on each, which is the relation's own shape:
     * a reader that counted for itself would be re-deriving the resolution's arity.
     */
    private static Field<List<TableBinding>> tables() {
        return multiset(
            select(qualified(GRAPHITRON_TABLETYPE.TABLE_SCHEMA, GRAPHITRON_TABLETYPE.TABLE_NAME))
                .from(GRAPHITRON_TABLETYPE)
                .where(ofType(GRAPHITRON_TABLETYPE.GRAPH_NAME, GRAPHITRON_TABLETYPE.TYPE_NAME)))
            .convertFrom(r -> r.map(Records.mapping(TableBinding::new)));
    }

    /**
     * The classes the store says stand for the type, from either population that can answer, each with
     * the members it offers.
     *
     * <p>One relation for the question every consumer of a backing asks, which is what class and not
     * which population found it. A type both populations answer differently is two entries with their
     * {@code declared_via}, never one with the walk's table-wins precedence quietly applied.
     */
    private static Field<List<Backing>> backing(StoreHandle store) {
        return multiset(
            select(GRAPHITRON_TYPE_BACKING.CLASS_NAME, GRAPHITRON_TYPE_BACKING.DECLARED_VIA, members(store))
                .from(GRAPHITRON_TYPE_BACKING)
                .where(ofType(GRAPHITRON_TYPE_BACKING.GRAPH_NAME, GRAPHITRON_TYPE_BACKING.TYPE_NAME))
                .orderBy(GRAPHITRON_TYPE_BACKING.CLASS_NAME.asc(),
                    GRAPHITRON_TYPE_BACKING.DECLARED_VIA.asc()))
            .convertFrom(r -> r.map(Records.mapping(Backing::new)));
    }

    /**
     * The member names one backing class offers, read from the store's own member-slot relation.
     *
     * <p>Source-keyed like the reading it stands on, so the graph reaches it through
     * {@link StoreHandle#reads} rather than by a graph column it does not carry. A class the reading
     * never reached answers with nothing, which is not a failure: the table arm's classes are generated
     * jOOQ records the reading deliberately never scans.
     *
     * <p>The rendered type is one key join out, the slot naming what reading it yields and the
     * rendering belonging to that type. On a whole primary key, so it cannot widen what the class
     * offers.
     */
    private static Field<List<MemberSlot>> members(StoreHandle store) {
        return multiset(
            select(CODE_READ_SLOT.SLOT_NAME, CODE_TYPE.DISPLAY_NAME,
                CODE_READ_SLOT.ORIGIN, CODE_READ_SLOT.METHOD_NAME)
                .from(CODE_READ_SLOT)
                .join(CODE_TYPE)
                .on(CODE_TYPE.SOURCE_NAME.eq(CODE_READ_SLOT.SOURCE_NAME)
                    .and(CODE_TYPE.TYPE_NAME.eq(CODE_READ_SLOT.SLOT_TYPE)))
                .where(CODE_READ_SLOT.CLASS_NAME.eq(GRAPHITRON_TYPE_BACKING.CLASS_NAME)
                    .and(store.reads(CODE_READ_SLOT.SOURCE_NAME)))
                .orderBy(CODE_READ_SLOT.SLOT_NAME.asc(), CODE_READ_SLOT.METHOD_NAME.asc()))
            .convertFrom(r -> r.map(Records.mapping(MemberSlot::new)));
    }

    /** The types the store answers this one with where that is more than one class. */
    private static Field<Optional<BackingConflict>> backingConflict() {
        return multiset(
            select(INTENT_TYPE_BACKING_CONFLICT.CANDIDATES)
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
        return members("UNION", GRAPHQL_POLY_MEMBER.POSITION.asc());
    }

    /**
     * The types declaring that they implement this interface, ordered by name.
     *
     * <p>The stored edge inverted, which is the direction every consumer of an abstract type reads: the
     * relation records the declaration ("this object implements that interface") and what an agent asks
     * of an interface is who its participants are.
     */
    private static Field<List<String>> implementors() {
        return members("INTERFACE", GRAPHQL_POLY_MEMBER.MEMBER_TYPE_NAME.asc());
    }

    /**
     * What the container in hand holds, in the order this projection wants it. One method for both
     * abstract kinds because the store holds one relation for both: what a union lists and what an
     * interface is named by are the same edge read from the same rows, and only the ordering each
     * projection promises differs.
     *
     * @param order what to sort by; the union's own authored position, or the implementor's name,
     *        each being what the projection above it documents
     */
    private static Field<List<String>> members(String containerKind, OrderField<?> order) {
        return multiset(
            select(GRAPHQL_POLY_MEMBER.MEMBER_TYPE_NAME)
                .from(GRAPHQL_POLY_MEMBER)
                .where(GRAPHQL_POLY_MEMBER.GRAPH_NAME.eq(GRAPHQL_TYPE.GRAPH_NAME)
                    .and(GRAPHQL_POLY_MEMBER.CONTAINER_NAME.eq(GRAPHQL_TYPE.TYPE_NAME))
                    .and(GRAPHQL_POLY_MEMBER.CONTAINER_KIND.eq(containerKind)))
                .orderBy(order))
            .convertFrom(r -> r.map(Record1::value1));
    }

    /** The type's {@code @node} identity, with its key columns in the order the author wrote them. */
    private static Field<Optional<Node>> node() {
        var a = GRAPHQL_DIRECTIVE_APPLICATION.as("node_application");
        var n = GRAPHITRON_AST_NODE_ENTRY;
        return multiset(
            select(n.TYPE_ID, nodeKeyColumns(a))
                .from(a)
                .leftJoin(n).on(n.GRAPH_NAME.eq(a.GRAPH_NAME), n.SOURCE_NAME.eq(a.SOURCE_NAME),
                    n.SOURCE_LINE.eq(a.SOURCE_LINE), n.SOURCE_COLUMN.eq(a.SOURCE_COLUMN))
                .where(ofType(a.GRAPH_NAME, a.COORDINATE))
                .and(a.DIRECTIVE_NAME.eq("node"))
                .and(a.ORDINAL.eq(0)))
            .convertFrom(r -> r.map(Records.mapping(Node::new)).stream().findFirst());
    }

    /** One {@code @node}'s authored key columns, correlated to the application being projected. */
    private static Field<List<String>> nodeKeyColumns(GraphqlDirectiveApplication a) {
        var k = GRAPHITRON_AST_NODE_KEYCOLUMN_ENTRY;
        return multiset(
            select(k.COLUMN_REF)
                .from(k)
                .where(k.GRAPH_NAME.eq(a.GRAPH_NAME), k.SOURCE_NAME.eq(a.SOURCE_NAME),
                    k.APPLICATION_LINE.eq(a.SOURCE_LINE), k.APPLICATION_COLUMN.eq(a.SOURCE_COLUMN))
                .orderBy(k.POSITION.asc()))
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
                fieldConflict(), joinPath(), pathStops(), methods())
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
        String typeName, String fieldName, String typeSdl,
        Optional<Conflict> conflict, List<Hop> joinPath, List<PathStop> pathStops,
        List<MethodBinding> methods
    ) {

        FieldEntry withClaims(ClaimSet claims) {
            return new FieldEntry(typeName, fieldName, typeSdl, claims, conflict, joinPath,
                pathStops, methods);
        }
    }

    /**
     * What each of the page's coordinates claims, plus the column the field reads.
     *
     * <p>Two statements at the coordinate grain. The claims are {@code graphitron_claim}'s rows at the
     * field, each with the directive written at its position. The column is the one
     * {@code graphitron_occurrence_column} states at the field's occurrences, with the name it matched
     * by; a field reading a column on several tables, one per place it is reached, reports the first in
     * table order.
     */
    private static Map<Coordinate, ClaimSet> claims(StoreHandle store, List<String> typeNames) {
        var f = GRAPHITRON_FIELD;
        var c = GRAPHITRON_CLAIM;
        var da = GRAPHITRON_DIRECTIVE_APPLICATION;
        var written = store.dsl()
            .select(f.TYPE_NAME, f.FIELD_NAME, c.KIND, da.DIRECTIVE_NAME,
                c.SOURCE_NAME, c.SOURCE_LINE, c.SOURCE_COLUMN)
            .from(f)
            .join(c).on(c.GRAPH_NAME.eq(f.GRAPH_NAME), c.COORDINATE.eq(f.COORDINATE))
            .leftJoin(da).on(da.GRAPH_NAME.eq(c.GRAPH_NAME), da.SOURCE_NAME.eq(c.SOURCE_NAME),
                da.SOURCE_LINE.eq(c.SOURCE_LINE), da.SOURCE_COLUMN.eq(c.SOURCE_COLUMN))
            .where(f.GRAPH_NAME.eq(store.graphName()).and(f.TYPE_NAME.in(typeNames)))
            .orderBy(f.TYPE_NAME.asc(), f.FIELD_NAME.asc(), c.KIND.asc(), c.CLAIMER.asc())
            .fetch();

        var o = GRAPHITRON_OCCURRENCE;
        var oc = GRAPHITRON_OCCURRENCE_COLUMN;
        var b = GRAPHITRON_BINDING_CLAIM;
        var read = store.dsl()
            .selectDistinct(f.TYPE_NAME, f.FIELD_NAME, oc.TABLE_SCHEMA, oc.TABLE_NAME, oc.COLUMN_NAME,
                coalesce(b.NAMED, f.FIELD_NAME), oc.MATCHED_BY)
            .from(f)
            .join(o).on(o.GRAPH_NAME.eq(f.GRAPH_NAME), o.ELEMENT_COORDINATE.eq(f.COORDINATE), o.KIND.eq("FIELD"))
            .join(oc).on(oc.GRAPH_NAME.eq(o.GRAPH_NAME), oc.COORDINATE.eq(o.COORDINATE))
            .leftJoin(b).on(b.GRAPH_NAME.eq(f.GRAPH_NAME), b.COORDINATE.eq(f.COORDINATE), b.KIND.eq("COLUMN"))
            .where(f.GRAPH_NAME.eq(store.graphName()).and(f.TYPE_NAME.in(typeNames)))
            .orderBy(f.TYPE_NAME.asc(), f.FIELD_NAME.asc(), oc.TABLE_SCHEMA.asc(), oc.TABLE_NAME.asc())
            .fetch();

        var claims = new LinkedHashMap<Coordinate, List<FieldClaim>>();
        var columns = new LinkedHashMap<Coordinate, ColumnBinding>();
        for (var row : written) {
            claims.computeIfAbsent(new Coordinate(row.value1(), row.value2()), ignored -> new ArrayList<>())
                .add(new FieldClaim(row.value3(), row.value4(),
                    McpWire.position(row.value5(), row.value6(), row.value7())));
        }
        for (var row : read) {
            columns.putIfAbsent(new Coordinate(row.value1(), row.value2()),
                new ColumnBinding(row.value3() + "." + row.value4(), row.value5(), row.value6(), row.value7()));
        }
        var sets = new LinkedHashMap<Coordinate, ClaimSet>();
        var coordinates = new java.util.LinkedHashSet<Coordinate>(claims.keySet());
        coordinates.addAll(columns.keySet());
        for (var coordinate : coordinates) {
            sets.put(coordinate, new ClaimSet(List.copyOf(claims.getOrDefault(coordinate, List.of())),
                Optional.ofNullable(columns.get(coordinate))));
        }
        return sets;
    }

    /** The predicate correlating a field-grain relation to the field row being projected. */
    private static Condition ofField(Field<String> graph, Field<String> type, Field<String> fieldName) {
        return graph.eq(GRAPHQL_FIELD.GRAPH_NAME)
            .and(type.eq(GRAPHQL_FIELD.TYPE_NAME))
            .and(fieldName.eq(GRAPHQL_FIELD.FIELD_NAME));
    }

    /** The field-grain claim conflict, told from its type's by carrying a field name. */
    private static Field<Optional<Conflict>> fieldConflict() {
        var site = GRAPHITRON_ENTRY_DEFECT_SITE;
        return conflicts(ofField(site.GRAPH_NAME, site.TYPE_NAME, site.FIELD_NAME));
    }

    /**
     * The field's join paths as the store walks them: each step from where the one before it landed,
     * at every occurrence of the field and for every type it reaches, so a row exists only for a step
     * a path took and a field reached along several chains reports a step once. Where a path could
     * not take one, the field's {@link #pathStops()} says which element and why.
     *
     * <p>A step is placed by the element it resolved, found through the claim its path follows: the
     * claim's position is the anchor its written elements are numbered under. An inferred step wrote
     * no element and is placed by its own position.
     */
    private static Field<List<Hop>> joinPath() {
        var o = GRAPHITRON_OCCURRENCE;
        var s = GRAPHITRON_JOIN_PATH_STEP;
        var k = GRAPHITRON_JOIN_PATH_STEP_KEY;
        var ik = GRAPHITRON_JOIN_PATH_STEP_INFERRED_KEY;
        var pk = GRAPHITRON_JOIN_PATH_STEP_PRIMARY_KEY;
        var m = GRAPHITRON_JOIN_PATH_STEP_METHOD;
        var rt = GRAPHITRON_JOIN_PATH_STEP_ROUTINE;
        var cl = GRAPHITRON_JOIN_PATH_CLAIMED;
        var c = GRAPHITRON_JOIN_PATH_CLAIM;
        var ap = GRAPHITRON_AST_PATH_ENTRY;
        var e = GRAPHITRON_PATH_ELEMENT_KEY;
        var pe = GRAPHITRON_PATH_ELEMENT_PRIMARY_KEY;
        var sp = SQL_PRIMARY_KEY;
        Field<String> keySchema = coalesce(k.CONSTRAINT_SCHEMA, ik.CONSTRAINT_SCHEMA);
        Field<String> keyTable = coalesce(k.CONSTRAINT_TABLE, ik.CONSTRAINT_TABLE);
        return multiset(
            selectDistinct(coalesce(s.ELEMENT_PLACE, s.POSITION),
                when(s.ELEMENT_PLACE.isNull(), inline("INFERRED"))
                    .when(rt.ROUTINE_NAME.isNotNull(), inline("ROUTINE"))
                    .when(keyTable.isNotNull(), inline("KEY"))
                    .when(pk.TABLE_NAME.isNotNull(), inline("PRIMARY_KEY"))
                    .when(exists(selectOne().from(m).where(m.GRAPH_NAME.eq(s.GRAPH_NAME),
                        m.COORDINATE.eq(s.COORDINATE), m.TARGET_TYPE.eq(s.TARGET_TYPE),
                        m.POSITION.eq(s.POSITION))), inline("CONDITION"))
                    .otherwise(inline("TABLE")),
                coalesce(e.MATCHED_BY, pe.MATCHED_BY),
                qualified(s.FROM_SCHEMA, s.FROM_TABLE),
                qualified(s.TO_SCHEMA, s.TO_TABLE),
                coalesce(k.CONSTRAINT_NAME, ik.CONSTRAINT_NAME, sp.CONSTRAINT_NAME),
                keySchema.eq(s.FROM_SCHEMA).and(keyTable.eq(s.FROM_TABLE)))
                .from(o)
                .join(s).on(s.GRAPH_NAME.eq(o.GRAPH_NAME), s.COORDINATE.eq(o.COORDINATE))
                .leftJoin(rt).on(rt.GRAPH_NAME.eq(s.GRAPH_NAME), rt.COORDINATE.eq(s.COORDINATE),
                    rt.TARGET_TYPE.eq(s.TARGET_TYPE), rt.POSITION.eq(s.POSITION))
                .leftJoin(k).on(k.GRAPH_NAME.eq(s.GRAPH_NAME), k.COORDINATE.eq(s.COORDINATE),
                    k.TARGET_TYPE.eq(s.TARGET_TYPE), k.POSITION.eq(s.POSITION))
                .leftJoin(ik).on(ik.GRAPH_NAME.eq(s.GRAPH_NAME), ik.COORDINATE.eq(s.COORDINATE),
                    ik.TARGET_TYPE.eq(s.TARGET_TYPE), ik.POSITION.eq(s.POSITION))
                .leftJoin(pk).on(pk.GRAPH_NAME.eq(s.GRAPH_NAME), pk.COORDINATE.eq(s.COORDINATE),
                    pk.TARGET_TYPE.eq(s.TARGET_TYPE), pk.POSITION.eq(s.POSITION))
                .leftJoin(sp).on(sp.SOURCE_NAME.eq(pk.TABLE_SOURCE_NAME),
                    sp.TABLE_SCHEMA.eq(pk.TABLE_SCHEMA), sp.TABLE_NAME.eq(pk.TABLE_NAME))
                // The claim the path follows, or, for a path that stopped and so follows none, the
                // occurrence's own claim for the type.
                .leftJoin(cl).on(cl.GRAPH_NAME.eq(s.GRAPH_NAME), cl.COORDINATE.eq(s.COORDINATE),
                    cl.TARGET_TYPE.eq(s.TARGET_TYPE))
                .leftJoin(c).on(c.GRAPH_NAME.eq(s.GRAPH_NAME),
                    c.ELEMENT_COORDINATE.eq(coalesce(cl.CLAIM_COORDINATE, o.ELEMENT_COORDINATE)),
                    c.TARGET_TYPE.eq(coalesce(cl.CLAIM_TARGET_TYPE, s.TARGET_TYPE)))
                .leftJoin(ap).on(ap.GRAPH_NAME.eq(c.GRAPH_NAME), ap.SOURCE_NAME.eq(c.SOURCE_NAME),
                    ap.ANCHOR_LINE.eq(c.SOURCE_LINE), ap.ANCHOR_COLUMN.eq(c.SOURCE_COLUMN),
                    ap.PLACE.eq(s.ELEMENT_PLACE))
                .leftJoin(e).on(e.GRAPH_NAME.eq(ap.GRAPH_NAME), e.SOURCE_NAME.eq(ap.SOURCE_NAME),
                    e.SOURCE_LINE.eq(ap.ELEMENT_LINE), e.SOURCE_COLUMN.eq(ap.ELEMENT_COLUMN),
                    e.CONSTRAINT_SOURCE_NAME.eq(k.CONSTRAINT_SOURCE_NAME),
                    e.CONSTRAINT_SCHEMA.eq(k.CONSTRAINT_SCHEMA),
                    e.CONSTRAINT_TABLE.eq(k.CONSTRAINT_TABLE),
                    e.CONSTRAINT_NAME.eq(k.CONSTRAINT_NAME))
                .leftJoin(pe).on(pe.GRAPH_NAME.eq(ap.GRAPH_NAME), pe.SOURCE_NAME.eq(ap.SOURCE_NAME),
                    pe.SOURCE_LINE.eq(ap.ELEMENT_LINE), pe.SOURCE_COLUMN.eq(ap.ELEMENT_COLUMN),
                    pe.TABLE_SOURCE_NAME.eq(pk.TABLE_SOURCE_NAME),
                    pe.TABLE_SCHEMA.eq(pk.TABLE_SCHEMA),
                    pe.TABLE_NAME.eq(pk.TABLE_NAME))
                .where(onOccurrenceOfField(o))
                .orderBy(coalesce(s.ELEMENT_PLACE, s.POSITION).asc(),
                    qualified(s.FROM_SCHEMA, s.FROM_TABLE).asc(), qualified(s.TO_SCHEMA, s.TO_TABLE).asc()))
            .convertFrom(r -> r.map(row -> new Hop(row.value1(), row.value2(), row.value3(),
                row.value4(), row.value5(), row.value6(), Boolean.TRUE.equals(row.value7()))));
    }

    /** Where each of the field's join paths stopped short of its end; empty where none did. */
    private static Field<List<PathStop>> pathStops() {
        var o = GRAPHITRON_OCCURRENCE;
        var st = GRAPHITRON_JOIN_PATH_STOP;
        var n = GRAPHITRON_PATH_NEED;
        var p = GRAPHITRON_JOIN_PATH_STEP;
        // Where the path stands at the stop: the table the step before it reached, or the need's start
        // where it stops before its first.
        return multiset(
            selectDistinct(coalesce(st.ELEMENT_PLACE, st.POSITION),
                coalesce(qualified(p.TO_SCHEMA, p.TO_TABLE), qualified(n.START_SCHEMA, n.START_TABLE)),
                st.MOVES)
                .from(o)
                .join(st).on(st.GRAPH_NAME.eq(o.GRAPH_NAME), st.COORDINATE.eq(o.COORDINATE))
                .join(n).on(n.GRAPH_NAME.eq(st.GRAPH_NAME), n.COORDINATE.eq(st.COORDINATE),
                    n.TARGET_TYPE.eq(st.TARGET_TYPE))
                .leftJoin(p).on(p.GRAPH_NAME.eq(st.GRAPH_NAME), p.COORDINATE.eq(st.COORDINATE),
                    p.TARGET_TYPE.eq(st.TARGET_TYPE), p.POSITION.eq(st.POSITION.minus(1)))
                .where(onOccurrenceOfField(o))
                .orderBy(coalesce(st.ELEMENT_PLACE, st.POSITION).asc()))
            .convertFrom(r -> r.map(Records.mapping(PathStop::new)));
    }

    /** An occurrence of the field row being projected. */
    private static Condition onOccurrenceOfField(no.sikt.graphitron.model.tables.GraphitronOccurrence o) {
        return o.GRAPH_NAME.eq(GRAPHQL_FIELD.GRAPH_NAME)
            .and(o.ELEMENT_COORDINATE.eq(concat(GRAPHQL_FIELD.TYPE_NAME, inline("."), GRAPHQL_FIELD.FIELD_NAME)))
            .and(o.KIND.eq("FIELD"));
    }

    /**
     * The methods the coordinate's {@code @service}, {@code @externalField} and {@code @condition}
     * references resolve to, each a written reference beside the one method the classpath settled it
     * on. A reference naming no method or an overload set has no settled method and no row here; the
     * code-reference defects say which, at the place it was written.
     */
    private static Field<List<MethodBinding>> methods() {
        var site = GRAPHITRON_CODE_REFERENCE_SITE;
        var r = GRAPHITRON_CODE_REFERENCE;
        var declaredVia = when(site.DIRECTIVE_NAME.eq(SERVICE_DIRECTIVE), inline("SERVICE"))
            .when(site.DIRECTIVE_NAME.eq(EXTERNAL_FIELD_DIRECTIVE), inline("EXTERNAL_FIELD"))
            .otherwise(inline("CONDITION"));
        return multiset(
            select(r.METHOD_CLASS_NAME, r.METHOD_NAME,
                arity(r.METHOD_SOURCE_NAME, r.METHOD_CLASS_NAME, r.METHOD_NAME, r.METHOD_DESCRIPTOR),
                declaredVia)
                .from(site)
                // The field coordinates alone: a reference on an argument is the argument's.
                .join(GRAPHQL_FIELD_ELEMENT)
                .on(GRAPHQL_FIELD_ELEMENT.GRAPH_NAME.eq(site.GRAPH_NAME)
                    .and(GRAPHQL_FIELD_ELEMENT.COORDINATE.eq(site.COORDINATE)))
                .join(r)
                .on(r.GRAPH_NAME.eq(site.GRAPH_NAME).and(r.COORDINATE.eq(site.COORDINATE))
                    .and(r.DIRECTIVE_NAME.eq(site.DIRECTIVE_NAME))
                    .and(r.ORDINAL.eq(site.ORDINAL)).and(r.OWNER.eq(site.OWNER)))
                .where(site.DIRECTIVE_NAME.in(SERVICE_DIRECTIVE, EXTERNAL_FIELD_DIRECTIVE,
                        CONDITION_DIRECTIVE)
                    .and(ofField(GRAPHQL_FIELD_ELEMENT.GRAPH_NAME, GRAPHQL_FIELD_ELEMENT.TYPE_NAME,
                        GRAPHQL_FIELD_ELEMENT.FIELD_NAME)))
                .orderBy(declaredVia, r.METHOD_CLASS_NAME, r.METHOD_NAME))
            .convertFrom(rows -> rows.map(Records.mapping(MethodBinding::new)));
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
            .from(CODE_METHOD_PARAMETER)
            .where(CODE_METHOD_PARAMETER.SOURCE_NAME.eq(source)
                .and(CODE_METHOD_PARAMETER.CLASS_NAME.eq(className))
                .and(CODE_METHOD_PARAMETER.METHOD_NAME.eq(methodName))
                .and(CODE_METHOD_PARAMETER.DESCRIPTOR.eq(descriptor))));
    }

    /** A schema-qualified table name composed in SQL, the form every tool hands a table back as. */
    private static Field<String> qualified(Field<String> schema, Field<String> table) {
        return concat(schema, inline("."), table);
    }
}
