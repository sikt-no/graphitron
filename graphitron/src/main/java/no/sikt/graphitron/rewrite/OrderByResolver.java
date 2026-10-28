package no.sikt.graphitron.rewrite;

import graphql.language.BooleanValue;
import graphql.language.EnumValue;
import graphql.language.StringValue;
import graphql.schema.GraphQLAppliedDirective;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLEnumValueDefinition;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.model.jooq.TableRef;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.On;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.model.diagnostics.Rejection;
import no.sikt.graphitron.rewrite.walker.MatchedKeys;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static no.sikt.graphitron.rewrite.BuildContext.ARG_ARGMAPPING;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_CLASS_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_COLLATE;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_CONDITION;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_DIRECTION;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_FIELDS;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_INDEX;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_METHOD;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_PATH;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_PRIMARY_KEY;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_AS_CONNECTION;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_DEFAULT_ORDER;
import static no.sikt.graphitron.rewrite.BuildContext.asMap;
import static no.sikt.graphitron.rewrite.BuildContext.baseTypeName;

/**
 * Resolves the OrderBy concern for a table-bound list/connection field into a sealed
 * {@link Resolved} the caller switches on. Sibling to the directive resolvers
 * ({@link ServiceDirectiveResolver},
 * {@link ExternalFieldDirectiveResolver}, {@link LookupKeyDirectiveResolver}).
 *
 * <p>Three concrete result shapes ride under {@link Resolved.Ok}: {@link OrderBySpec.None}
 * (ordering not applicable), {@link OrderBySpec.Fixed} ({@code @defaultOrder} or the parent
 * table's primary key), and {@link OrderBySpec.Argument} (an {@code @orderBy} argument).
 *
 * <p>Every rejection path carries exactly one message in the {@link Resolved.Rejected} arm. A
 * directive's entries resolve to a result that names what failed; a plain catalog miss carries no
 * reason of its own, and its caller synthesises the generic message.
 *
 * <p>On a single-table connection the resolver also makes the order identify a row, because a
 * keyset seek from a cursor steps over every row that ties with the cursor's row on all sort
 * columns. It decides the table's {@link RowIdentity} once, completes every static order against
 * it ({@link #complete}), and leaves a list-valued {@code @orderBy} argument's completion to the
 * generated helper ({@link OrderBySpec.SequenceCompletion.AppendRowKey}), since only the request
 * knows the composed sequence. Plain lists are not completed (no cursor, so a tie never drops a
 * row), and neither are multitable participants, whose fold appends its own key slots.
 */
final class OrderByResolver {

    /**
     * Outcome of {@link #resolve}: {@link Ok} carries the {@link OrderBySpec}, {@link Rejected} the
     * failure. {@code keylessConnection} is {@code true} when the field is a single-table
     * connection with a non-empty order over a table with no {@link RowIdentity.Keyed row key}, so
     * nothing could be appended and the order's uniqueness is the author's word; the caller turns
     * it into the {@code connection-order-not-unique} lint finding.
     */
    sealed interface Resolved {
        record Ok(OrderBySpec spec, boolean keylessConnection) implements Resolved {
            Ok(OrderBySpec spec) { this(spec, false); }
        }
        record Rejected(Rejection rejection) implements Resolved {
            public String message() { return rejection.message(); }
        }
    }

    /**
     * Whether a table's rows can be told apart by key, decided once per single-table connection.
     */
    sealed interface RowIdentity {

        /**
         * {@code rowKey} is what completion appends: the primary key, else the element type's
         * {@code @node} key, else the first unique key whose columns are all {@code NOT NULL}.
         * {@code identifyingKeys} is every one of those that exists; an order whose column set
         * contains one of them in full already identifies a row.
         */
        record Keyed(List<ColumnRef> rowKey, List<List<ColumnRef>> identifyingKeys) implements RowIdentity {
            public Keyed {
                rowKey = List.copyOf(rowKey);
                identifyingKeys = identifyingKeys.stream().map(List::copyOf).toList();
            }
        }

        /** No key at all: a keyless view, or a table-valued function result. */
        record Keyless() implements RowIdentity {}
    }

    private final BuildContext ctx;
    private final ServiceCatalog svc;

    OrderByResolver(BuildContext ctx, ServiceCatalog svc) {
        this.ctx = ctx;
        this.svc = svc;
    }

    /**
     * Projects the classified arguments into an {@link OrderBySpec}.
     *
     * <p>Returns {@code Ok(None)} when ordering is not applicable: for single-value returns, or
     * when {@code table} is {@code null} (non-table-bound field). Returns {@code Ok(None)}
     * (not a rejection) when the table has no primary key and no {@code @defaultOrder} is
     * present. Returns {@code Rejected} when an {@code @orderBy} argument failed to classify or
     * when {@code @defaultOrder}'s column / index resolution failed.
     *
     * @param parentTypeName the field's declaring type, for the coordinate rejections name
     * @param returnTypeName the field's element type, whose {@code @node} key can identify a row
     * @param singleTable    {@code false} at a multitable participant, whose orders are folded
     *                       with key slots of their own and so are never completed here, and
     *                       which therefore admits no sort-key entry
     */
    Resolved resolve(List<ArgumentRef> refs, String parentTypeName, GraphQLFieldDefinition fieldDef,
                     TableRef table, String returnTypeName, boolean singleTable) {
        GraphQLType unwrapped = GraphQLTypeUtil.unwrapNonNull(fieldDef.getType());
        boolean isList = (unwrapped instanceof GraphQLList)
            || ctx.isConnectionType(baseTypeName(fieldDef))
            || fieldDef.hasAppliedDirective(DIR_AS_CONNECTION);
        if (!isList || table == null) return new Resolved.Ok(new OrderBySpec.None());
        String tableSqlName = table.tableName();
        var scope = new FieldScope(parentTypeName + "." + fieldDef.getName(), fieldDef.getName(), table,
            sortKeyRefusal(tableSqlName, singleTable));

        Optional<RowIdentity> identity = singleTable
                && ctx.buildWrapper(fieldDef) instanceof FieldWrapper.Connection
            ? Optional.of(rowIdentity(table, returnTypeName))
            : Optional.empty();

        Resolved resolved = null;
        for (var ref : refs) {
            if (ref instanceof ArgumentRef.OrderByArg ob) {
                resolved = resolveOrderByArgSpec(ob, fieldDef, tableSqlName, identity, scope);
                break;
            }
        }
        if (resolved == null) {
            resolved = completed(resolveDefaultOrderSpec(fieldDef, tableSqlName, scope), identity);
        }
        if (resolved instanceof Resolved.Ok ok
                && identity.orElse(null) instanceof RowIdentity.Keyless
                && !isEmpty(ok.spec())) {
            return new Resolved.Ok(ok.spec(), true);
        }
        return resolved;
    }

    /**
     * Why the field admits no sort-key entry, or {@code null} when it does. A multitable
     * participant folds each participant's order into shared slots typed from a participant column,
     * which an expression slot does not fit; a table-valued function result is not a catalog table,
     * so it has no foreign keys for a path to walk and is not what a method's parameter is typed on.
     */
    private String sortKeyRefusal(String tableSqlName, boolean singleTable) {
        if (!singleTable) {
            return "at a multitable interface or union participant: the participants' orders are folded"
                + " into shared column slots, and a related-table or derived sort key has no slot";
        }
        if (ctx.catalog.isTableValuedFunction(tableSqlName)) {
            return "over '" + tableSqlName + "', a table-valued function result: it has no foreign keys"
                + " for a path to walk and is not a table class a method takes";
        }
        return null;
    }

    /**
     * The table's {@link RowIdentity}. Keys come from the primary key the {@link TableRef}
     * carries, the element type's {@code @node} key, and the catalog's unique keys (the
     * enumeration {@link no.sikt.graphitron.rewrite.walker.MatchedKeys#candidates} lifts) whose
     * columns are all {@code NOT NULL}, since PostgreSQL lets several rows hold {@code NULL} in a
     * unique key. The node key is taken without that check: it is the identity the author
     * declared, its node ids already break on a {@code NULL}, and jOOQ reports a view's columns
     * as nullable whatever the data holds.
     */
    private RowIdentity rowIdentity(TableRef table, String returnTypeName) {
        var identifying = new ArrayList<List<ColumnRef>>();
        List<ColumnRef> rowKey = null;
        if (table.hasPrimaryKey()) {
            rowKey = table.primaryKeyColumns();
            identifying.add(rowKey);
        }
        var nodeKey = ctx.nodes.forName(returnTypeName)
            .filter(node -> node.table().denotesSameTableAs(table))
            .map(GraphitronType.NodeType::nodeKeyColumns)
            .filter(cols -> !cols.isEmpty());
        if (nodeKey.isPresent()) {
            if (rowKey == null) rowKey = nodeKey.get();
            identifying.add(nodeKey.get());
        }
        for (var key : ctx.catalog.candidateKeys(table.tableName())) {
            if (key.primary() || key.columns().stream().anyMatch(JooqCatalog.ColumnEntry::nullable)) continue;
            var cols = key.columns().stream()
                .map(ce -> new ColumnRef(ce.sqlName(), ce.javaName(), ce.columnClass(), ce.databaseType()))
                .toList();
            if (rowKey == null) rowKey = cols;
            identifying.add(cols);
        }
        return rowKey == null ? new RowIdentity.Keyless() : new RowIdentity.Keyed(rowKey, identifying);
    }

    /**
     * {@code fixed} made to identify a row against {@code keyed}. Unchanged when it is empty (the
     * pagination-requires-ordering rejection owns that case) or when its column set already
     * contains an identifying key in full, whatever the position, direction or collation.
     * Otherwise the row-key columns it does not list follow it in row-key order, uncollated, in
     * the direction of its last entry: a uniform-direction order stays uniform, so jOOQ keeps its
     * row-value seek and an index scanned either way still serves it, and {@code uniformAsc} is
     * unchanged, so a runtime {@code direction:} flips the appended key with the rest. The appended
     * entries carry {@code nullable = false} by rule rather than by catalog read: {@link #rowIdentity}
     * has already decided they identify the row, and the catalog would call a view's node key
     * nullable, costing the order its row-value seek and adding {@code NULLS LAST} for nothing.
     */
    static OrderBySpec.Fixed complete(OrderBySpec.Fixed fixed, RowIdentity.Keyed keyed) {
        if (fixed.entries().isEmpty()) return fixed;
        // Only own-table columns tell parents apart: a to-one hop lets many parents share one
        // related row, and a derived key is opaque to the build, so a sort-key entry never counts,
        // even when its column is named like one of the table's key columns.
        var listed = new HashSet<String>();
        for (var entry : fixed.entries()) {
            if (entry instanceof OrderBySpec.ColumnOrderEntry column) listed.add(column.column().sqlName());
        }
        if (identifies(listed, keyed.identifyingKeys())) return fixed;
        var direction = fixed.entries().getLast().direction();
        var entries = new ArrayList<>(fixed.entries());
        for (var column : keyed.rowKey()) {
            if (!listed.contains(column.sqlName())) {
                entries.add(new OrderBySpec.ColumnOrderEntry(column, null, direction, false));
            }
        }
        return new OrderBySpec.Fixed(entries, fixed.uniformAsc());
    }

    private static boolean identifies(Set<String> listedSqlNames, List<List<ColumnRef>> identifyingKeys) {
        return identifyingKeys.stream().anyMatch(key ->
            key.stream().allMatch(column -> listedSqlNames.contains(column.sqlName())));
    }

    /** {@code spec} completed when {@code identity} is {@link RowIdentity.Keyed}; otherwise as is. */
    private static OrderBySpec completed(OrderBySpec spec, Optional<RowIdentity> identity) {
        return spec instanceof OrderBySpec.Fixed fixed && identity.orElse(null) instanceof RowIdentity.Keyed keyed
            ? complete(fixed, keyed)
            : spec;
    }

    private static Resolved completed(Resolved resolved, Optional<RowIdentity> identity) {
        return resolved instanceof Resolved.Ok ok ? new Resolved.Ok(completed(ok.spec(), identity)) : resolved;
    }

    /** Whether {@code spec} orders by nothing, for every value a request could pick. */
    private static boolean isEmpty(OrderBySpec spec) {
        return switch (spec) {
            case OrderBySpec.None n -> true;
            case OrderBySpec.Fixed fixed -> fixed.entries().isEmpty();
            case OrderBySpec.Argument arg -> isEmpty(arg.base())
                && arg.namedOrders().stream().allMatch(named -> named.order().entries().isEmpty());
        };
    }

    /**
     * Resolves the effective default order for a table-backed list/connection field.
     *
     * <p>Returns {@code Ok(Fixed)} when {@code @defaultOrder} resolves successfully or the table
     * has a primary key. Returns {@code Ok(None)} when the table has no primary key and no
     * {@code @defaultOrder} is present. Returns {@code Rejected} when {@code @defaultOrder} is
     * present but column/index resolution fails (with the canonical fallback message).
     */
    private Resolved resolveDefaultOrderSpec(GraphQLFieldDefinition fieldDef, String tableSqlName, FieldScope scope) {
        if (fieldDef.hasAppliedDirective(DIR_DEFAULT_ORDER)) {
            var dir = fieldDef.getAppliedDirective(DIR_DEFAULT_ORDER);
            var entries = resolveOrderEntries(dir, tableSqlName, readDirectionArg(dir, OrderBySpec.SortDirection.ASC), scope);
            return switch (entries) {
                case Entries.Failed f -> new Resolved.Rejected(Rejection.structural(f.reason() == null
                    ? defaultOrderFailure(dir, tableSqlName)
                    : "@defaultOrder on '" + scope.coordinate + "': " + f.reason()));
                case Entries.Ok ok -> new Resolved.Ok(fixedOf(ok.entries()));
            };
        }
        var pkCols = ctx.catalog.findPkColumns(tableSqlName);
        if (pkCols.isEmpty()) return new Resolved.Ok(new OrderBySpec.None());
        return new Resolved.Ok(new OrderBySpec.Fixed(
            pkCols.stream()
                .<OrderBySpec.OrderEntry>map(ce -> new OrderBySpec.ColumnOrderEntry(
                    new ColumnRef(ce.sqlName(), ce.javaName(), ce.columnClass(), ce.databaseType()),
                    null,
                    OrderBySpec.SortDirection.ASC,
                    ce.nullable()))
                .toList(),
            true));
    }

    /**
     * The message for a {@code @defaultOrder} that resolved to nothing. The generic arm says only
     * that the columns did not resolve, which is the whole story for a misspelled column or a
     * missing index. {@code primaryKey: true} over a table with no primary key is a different
     * failure and gets its own arm: the author asked for a key the table does not have, which is
     * the standing case on a table-valued function result, so the message names the absence and
     * points at the surface that does work.
     */
    private String defaultOrderFailure(GraphQLAppliedDirective dir, String tableSqlName) {
        boolean pkRequested = indexNameOf(dir) == null && readsPrimaryKey(dir);
        if (pkRequested && ctx.catalog.findPkColumns(tableSqlName).isEmpty()) {
            return "@defaultOrder(primaryKey: true) cannot resolve: '" + tableSqlName
                + "' has no primary key. Name the ordering columns instead, with "
                + "@defaultOrder(fields: [{name: \"...\"}]); the available columns are "
                + String.join(", ", ctx.catalog.columnSqlNamesOf(tableSqlName)) + ".";
        }
        return "could not resolve @defaultOrder columns in table '" + tableSqlName + "'";
    }

    /**
     * The directive's {@code index:} name, or {@code null} when it names none. Not the same
     * question as "is the argument present": graphql-java hands back a declared argument whether
     * or not the author wrote it, so absence is a null value, not a null argument.
     */
    private static String indexNameOf(GraphQLAppliedDirective dir) {
        var indexArg = dir.getArgument(ARG_INDEX);
        if (indexArg == null) {
            return null;
        }
        Object indexVal = indexArg.getValue();
        return indexVal instanceof StringValue sv ? sv.getValue().strip()
            : indexVal instanceof String s ? s.strip() : null;
    }

    /** Whether the directive asks for the table's primary key. */
    private static boolean readsPrimaryKey(GraphQLAppliedDirective dir) {
        var pkArg = dir.getArgument(ARG_PRIMARY_KEY);
        return pkArg != null && (pkArg.getValue() instanceof BooleanValue bv ? bv.isValue()
            : Boolean.TRUE.equals(pkArg.getValue()));
    }

    /** A {@link OrderBySpec.Fixed} over {@code entries}, {@code uniformAsc} computed from them. */
    private static OrderBySpec.Fixed fixedOf(List<OrderBySpec.OrderEntry> entries) {
        boolean uniformAsc = entries.stream().allMatch(e -> e.direction() == OrderBySpec.SortDirection.ASC);
        return new OrderBySpec.Fixed(entries, uniformAsc);
    }

    /** Reads the directive's {@code direction:} argument as a typed {@link OrderBySpec.SortDirection}. */
    private static OrderBySpec.SortDirection readDirectionArg(GraphQLAppliedDirective dir, OrderBySpec.SortDirection fallback) {
        var dirArg = dir.getArgument(ARG_DIRECTION);
        if (dirArg == null) return fallback;
        return parseDirection(dirArg.getValue(), fallback);
    }

    /** Parses a directive-argument or input-map value into {@link OrderBySpec.SortDirection}. */
    private static OrderBySpec.SortDirection parseDirection(Object value, OrderBySpec.SortDirection fallback) {
        String name = switch (value) {
            case null -> null;
            case EnumValue ev -> ev.getName();
            case String s -> s;
            default -> value.toString();
        };
        if (name == null) return fallback;
        return "DESC".equalsIgnoreCase(name) ? OrderBySpec.SortDirection.DESC : OrderBySpec.SortDirection.ASC;
    }

    /**
     * Resolves an {@code @order} directive on an enum value into a {@link OrderBySpec.Fixed}.
     *
     * <p>The direction is not stored here; it comes from the runtime input object's direction
     * field and is applied at code-generation time in the {@code *OrderBy} helper method.
     * Returns {@code null} and appends an error when catalog lookup fails.
     */
    private OrderBySpec.Fixed resolveEnumValueOrderSpec(
            GraphQLEnumValueDefinition ev,
            String tableSqlName,
            List<String> errors,
            FieldScope scope) {
        var dir = ev.getAppliedDirective("order");
        Entries entries;
        if (dir != null) {
            // @order has no directive-level direction surface; ASC is the per-entry fallback.
            entries = resolveOrderEntries(dir, tableSqlName, OrderBySpec.SortDirection.ASC, scope);
        } else {
            // @index is a deprecated alias: @index(name: "idx") ≡ @order(index: "idx")
            var indexDir = ev.getAppliedDirective("index");
            var nameArg = indexDir != null ? indexDir.getArgument(ARG_NAME) : null;
            Object nameVal = nameArg != null ? nameArg.getValue() : null;
            String indexName = nameVal instanceof StringValue sv ? sv.getValue().strip()
                : nameVal instanceof String s ? s.strip() : null;
            entries = Entries.of(resolveIndexColumns(tableSqlName, indexName, OrderBySpec.SortDirection.ASC));
        }
        return switch (entries) {
            case Entries.Failed f -> {
                errors.add("enum value '" + ev.getName() + "': " + (f.reason() == null
                    ? "could not resolve @order columns in table '" + tableSqlName + "'"
                    : f.reason()));
                yield null;
            }
            case Entries.Ok ok -> fixedOf(ok.entries());
        };
    }

    /**
     * Looks up named index columns from the catalog; returns {@code null} when not found.
     *
     * <p>{@code direction} is stamped onto every resolved entry. The {@code @defaultOrder} call site
     * passes the directive-level {@code direction:}; the {@code @order} enum-value alias passes
     * {@code ASC} because its direction comes from the runtime input object, not the directive.
     */
    private List<OrderBySpec.ColumnOrderEntry> resolveIndexColumns(
            String tableSqlName, String indexName, OrderBySpec.SortDirection direction) {
        if (indexName == null) return null;
        var colsOpt = ctx.catalog.findIndexColumns(tableSqlName, indexName);
        if (colsOpt.isEmpty() || colsOpt.get().isEmpty()) return null;
        return colsOpt.get().stream()
            .map(ce -> new OrderBySpec.ColumnOrderEntry(
                new ColumnRef(ce.sqlName(), ce.javaName(), ce.columnClass(), ce.databaseType()),
                null,
                direction,
                ce.nullable()))
            .toList();
    }

    /**
     * Resolves the entries of an {@code @order} or {@code @defaultOrder} directive
     * ({@code index:}, {@code primaryKey:}, or {@code fields:}), all resolved at build time via
     * the jOOQ catalog. A {@code FieldSort} element names an own-table column ({@code name:}), a
     * column on a to-one related table ({@code name:} beside {@code path:}), or a sort key a
     * consumer method derives ({@code method:}); the last two become
     * {@link OrderBySpec.SortKeyEntry} arms numbered by {@code scope}.
     */
    private Entries resolveOrderEntries(
            GraphQLAppliedDirective dir, String tableSqlName, OrderBySpec.SortDirection defaultDirection,
            FieldScope scope) {
        String indexName = indexNameOf(dir);
        if (indexName != null) {
            return Entries.of(resolveIndexColumns(tableSqlName, indexName, defaultDirection));
        }

        if (readsPrimaryKey(dir)) {
            var pkCols = ctx.catalog.findPkColumns(tableSqlName);
            if (pkCols.isEmpty()) return new Entries.Failed(null);
            return new Entries.Ok(pkCols.stream()
                .<OrderBySpec.OrderEntry>map(ce -> new OrderBySpec.ColumnOrderEntry(
                    new ColumnRef(ce.sqlName(), ce.javaName(), ce.columnClass(), ce.databaseType()),
                    null,
                    defaultDirection,
                    ce.nullable()))
                .toList());
        }

        var fieldsArg = dir.getArgument(ARG_FIELDS);
        if (fieldsArg != null) {
            Object value = fieldsArg.getValue();
            List<?> items = value instanceof List<?> l ? l : List.of(value);
            var entries = new ArrayList<OrderBySpec.OrderEntry>();
            for (var item : items) {
                if (!(item instanceof Map)) continue;
                var map = asMap(item);
                String collation = Optional.ofNullable(map.get(ARG_COLLATE)).map(Object::toString).map(String::strip).orElse(null);
                OrderBySpec.SortDirection entryDirection = parseDirection(map.get(ARG_DIRECTION), defaultDirection);
                Object nameRaw = map.get(ARG_NAME);
                Object pathRaw = map.get(ARG_PATH);
                Object methodRaw = map.get(ARG_METHOD);
                if (methodRaw != null) {
                    if (nameRaw != null) {
                        return new Entries.Failed("a FieldSort entry sets both name: and method:; an entry sets"
                            + " exactly one of them");
                    }
                    if (pathRaw != null) {
                        return new Entries.Failed("a FieldSort entry sets both method: and path:; the method"
                            + " already receives the field's table and can reach anything from it, so drop path:");
                    }
                    var method = resolveSortKeyMethod(asMap(methodRaw), dir.getName(), scope);
                    if (method instanceof SortKeyResolution.Failed f) return new Entries.Failed(f.reason());
                    entries.add(new OrderBySpec.SortKeyEntry(((SortKeyResolution.Ok) method).key(),
                        collation, entryDirection, scope.nextOrdinal()));
                    continue;
                }
                if (nameRaw == null) {
                    return new Entries.Failed(pathRaw != null
                        ? "a FieldSort entry sets path: without name:; path: says which table the named"
                            + " column is on, so name the column to sort by"
                        : "a FieldSort entry sets neither name: nor method:; name a column, or a method"
                            + " that returns the sort key");
                }
                String colName = nameRaw.toString().strip();
                if (pathRaw != null) {
                    List<?> elements = pathRaw instanceof List<?> pl ? pl : List.of(pathRaw);
                    var path = resolveSortKeyPath(elements, colName, tableSqlName, scope);
                    if (path instanceof SortKeyResolution.Failed f) return new Entries.Failed(f.reason());
                    entries.add(new OrderBySpec.SortKeyEntry(((SortKeyResolution.Ok) path).key(),
                        collation, entryDirection, scope.nextOrdinal()));
                    continue;
                }
                var ceOpt = ctx.catalog.findColumn(tableSqlName, colName);
                if (ceOpt.isEmpty()) return new Entries.Failed(null);
                var ce = ceOpt.get();
                entries.add(new OrderBySpec.ColumnOrderEntry(
                    new ColumnRef(ce.sqlName(), ce.javaName(), ce.columnClass(), ce.databaseType()), collation, entryDirection, ce.nullable()));
            }
            return new Entries.Ok(entries);
        }

        return new Entries.Failed(null);
    }

    /**
     * A {@code FieldSort.path:} sort key: the elements resolve from the field's table through the
     * {@code @reference} grammar, every hop must be an unfiltered key join proven to-one
     * ({@link #fansOut}), and {@code colName} must be a column of the table the last hop lands on.
     * The walk is to-one by construction, so a self-referential {@code {key:}} resolves with the
     * FK on the source side ({@code isList = false}).
     */
    private SortKeyResolution resolveSortKeyPath(List<?> elements, String colName, String tableSqlName,
                                                 FieldScope scope) {
        String refused = scope.sortKeyRefusal("path:");
        if (refused != null) return new SortKeyResolution.Failed(refused);
        // A condition is refused as written, before resolution: a condition-only element has no
        // key to resolve a target table from, so its resolution error would name the wrong problem.
        int written = 0;
        for (var element : elements) {
            if (!(element instanceof Map<?, ?> map)) continue;
            written++;
            if (map.get(ARG_CONDITION) != null) {
                return new SortKeyResolution.Failed(conditionRefusal(
                    "hop " + written + " of the path: for sort column '" + colName + "'"));
            }
        }
        var parsed = ctx.parseExplicitPath(elements, scope.fieldName, tableSqlName, null, null, false);
        if (parsed.hasError()) {
            return new SortKeyResolution.Failed("path: for sort column '" + colName + "' could not be resolved: "
                + parsed.errorMessage());
        }
        var hops = new ArrayList<JoinStep.Hop>();
        for (int i = 0; i < parsed.elements().size(); i++) {
            var hop = (JoinStep.Hop) parsed.elements().get(i);
            String where = "hop " + (i + 1) + " of the path: for sort column '" + colName + "' ('"
                + hop.originTable().tableName() + "' to '" + hop.targetTable().tableName() + "')";
            if (hop.filter() != null || hop.on() instanceof On.Predicate) {
                return new SortKeyResolution.Failed(conditionRefusal(where));
            }
            if (!(hop.on() instanceof On.ColumnPairs pairs)) {
                return new SortKeyResolution.Failed(where + " joins a routine result, which carries no"
                    + " key to prove the hop to-one; derive the sort key in a method: instead");
            }
            if (fansOut(hop, pairs)) {
                return new SortKeyResolution.Failed(where + " via " + pairs.keying().describe()
                    + " is to-many: one '" + hop.originTable().tableName() + "' row has many '"
                    + hop.targetTable().tableName() + "' rows. An ordering path may only follow to-one"
                    + " hops. To sort by a value derived from many related rows, supply it through"
                    + " method:, or make the hop to-one with a view that has a unique key on the"
                    + " parent's id plus a jOOQ synthetic foreign key to it");
            }
            hops.add(hop);
        }
        var landing = hops.getLast().targetTable();
        var ceOpt = ctx.catalog.findColumn(landing.tableName(), colName);
        if (ceOpt.isEmpty()) {
            return new SortKeyResolution.Failed("sort column '" + colName + "' is not a column of '"
                + landing.tableName() + "', the table the path: lands on");
        }
        var ce = ceOpt.get();
        return new SortKeyResolution.Ok(new OrderBySpec.SortKey.Path(hops,
            new ColumnRef(ce.sqlName(), ce.javaName(), ce.columnClass(), ce.databaseType())));
    }

    private static String conditionRefusal(String where) {
        return where + " carries a condition:; an ordering path follows key joins only, and the rule"
            + " for which related row counts belongs in a method: that returns the sort key";
    }

    /**
     * Whether {@code hop} can reach more than one target row per origin row: it is to-one exactly
     * when the columns on its target side cover a candidate key of its target table. Slot
     * orientation is fixed at synthesis, so this one test serves child-to-parent hops (the target
     * side is the parent's key) and one-to-one extensions (the target side is a uniquely keyed FK)
     * alike. Nullable key columns do not matter: an equality join never matches a {@code NULL}.
     */
    private boolean fansOut(JoinStep.Hop hop, On.ColumnPairs pairs) {
        var covered = new HashSet<String>();
        for (var column : pairs.targetSideColumns()) covered.add(column.sqlName());
        return MatchedKeys.firstCovered(ctx.catalog, hop.targetTable(), covered).isEmpty();
    }

    /**
     * A {@code FieldSort.method:} sort key: the {@code ExternalCodeReference} names a static method
     * with {@code @externalField}'s convention (the field's table in, {@code Field<X>} out),
     * reflected by the same {@link ServiceCatalog#reflectExternalField}. There is no field name to
     * default {@code method} from, and the method's parameter set is fixed, so {@code argMapping}
     * has nothing to bind.
     */
    private SortKeyResolution resolveSortKeyMethod(Map<String, Object> ref, String directiveName, FieldScope scope) {
        String refused = scope.sortKeyRefusal("method:");
        if (refused != null) return new SortKeyResolution.Failed(refused);
        String className = Optional.ofNullable(ref.get(ARG_CLASS_NAME)).map(Object::toString).map(String::strip).orElse(null);
        String methodName = Optional.ofNullable(ref.get(ARG_METHOD)).map(Object::toString).map(String::strip).orElse(null);
        String argMapping = Optional.ofNullable(ref.get(ARG_ARGMAPPING)).map(Object::toString).orElse(null);
        if (argMapping != null && !argMapping.isBlank()) {
            return new SortKeyResolution.Failed("method: does not take argMapping; a sort-key method takes"
                + " the field's table and nothing else");
        }
        if (className == null || className.isEmpty()) {
            return new SortKeyResolution.Failed("method: is missing className");
        }
        if (methodName == null || methodName.isEmpty()) {
            return new SortKeyResolution.Failed("method: is missing method; a sort key has no field name"
                + " to default it from");
        }
        var reflected = svc.reflectExternalField(className, methodName, scope.table, "@" + directiveName);
        if (reflected.failed()) {
            return new SortKeyResolution.Failed("method: could not be resolved: " + reflected.rejection().message());
        }
        return new SortKeyResolution.Ok(new OrderBySpec.SortKey.Method((MethodRef.StaticOnly) reflected.ref()));
    }

    /** The resolved entries of one directive, or why they did not resolve. */
    private sealed interface Entries {
        record Ok(List<OrderBySpec.OrderEntry> entries) implements Entries {}

        /** {@code reason} is {@code null} for a catalog miss, which the caller's generic message names. */
        record Failed(String reason) implements Entries {}

        static Entries of(List<OrderBySpec.ColumnOrderEntry> columns) {
            return columns == null ? new Failed(null) : new Ok(List.copyOf(columns));
        }
    }

    private sealed interface SortKeyResolution {
        record Ok(OrderBySpec.SortKey key) implements SortKeyResolution {}
        record Failed(String reason) implements SortKeyResolution {}
    }

    /**
     * What one {@link #resolve} call knows about the field that a sort-key entry needs: the
     * coordinate its rejections name, the field's table (a {@code method:}'s parameter type), why
     * the site admits no sort key ({@code null} when it does), and the running ordinal that
     * numbers the field's sort keys across all of its orders.
     */
    private static final class FieldScope {
        final String coordinate;
        final String fieldName;
        final TableRef table;
        private final String refusal;
        private int ordinal;

        FieldScope(String coordinate, String fieldName, TableRef table, String refusal) {
            this.coordinate = coordinate;
            this.fieldName = fieldName;
            this.table = table;
            this.refusal = refusal;
        }

        int nextOrdinal() { return ordinal++; }

        String sortKeyRefusal(String form) {
            return refusal == null ? null : "field '" + coordinate + "' cannot order by " + form + " " + refusal;
        }
    }

    /**
     * Resolves an {@code @orderBy} argument into an {@link OrderBySpec.Argument}, or a
     * {@link Resolved.Rejected} when a referenced enum value's columns can't be resolved.
     *
     * <p>Input-type structure (single sort enum + single direction field) is already validated by
     * {@code FieldBuilder.classifyOrderByArg} at classification time, which is what populates
     * {@link ArgumentRef.OrderByArg#sortFieldName()} / {@link ArgumentRef.OrderByArg#directionFieldName()}.
     * The classifier rejects malformed shapes as {@link ArgumentRef.UnclassifiedArg} before they
     * reach this resolver, so the only failure modes here are catalog-side: an {@code @order}'d
     * enum value whose columns / index don't resolve in {@code tableSqlName}.
     */
    private Resolved resolveOrderByArgSpec(ArgumentRef.OrderByArg ob, GraphQLFieldDefinition fieldDef,
                                           String tableSqlName, Optional<RowIdentity> identity,
                                           FieldScope scope) {
        var errors = new ArrayList<String>();
        var inputType = (GraphQLInputObjectType) ctx.schema.getType(ob.typeName());
        var sortEnum = (GraphQLEnumType) GraphQLTypeUtil.unwrapNonNull(
            inputType.getFieldDefinition(ob.sortFieldName()).getType());
        var namedOrders = new ArrayList<OrderBySpec.NamedOrder>();
        var missingOrder = new ArrayList<String>();
        for (var value : sortEnum.getValues()) {
            if (!value.hasAppliedDirective("order") && !value.hasAppliedDirective("index")) {
                // A value with no ordering directive would be silently skipped, generating an
                // empty ORDER BY when a request selects only such values (nondeterministic keyset
                // pagination). Accumulate every missing value and reject after the loop; the docs
                // already promise this per-value build failure.
                missingOrder.add(value.getName());
                continue;
            }
            OrderBySpec.Fixed order = resolveEnumValueOrderSpec(value, tableSqlName, errors, scope);
            if (order == null) return new Resolved.Rejected(Rejection.structural(errors.get(errors.size() - 1)));
            namedOrders.add(new OrderBySpec.NamedOrder(value.getName(), order));
        }
        if (!missingOrder.isEmpty()) {
            return new Resolved.Rejected(Rejection.sortEnumMissingOrder(sortEnum.getName(), missingOrder));
        }
        var baseResolved = completed(resolveDefaultOrderSpec(fieldDef, tableSqlName, scope), identity);
        if (baseResolved instanceof Resolved.Rejected r) return r;
        OrderBySpec baseSpec = ((Resolved.Ok) baseResolved).spec();
        var keyed = identity.orElse(null) instanceof RowIdentity.Keyed k ? k : null;
        OrderBySpec.Composition composition;
        if (ob.list()) {
            // The request composes a sequence of values, so the key goes after the sequence, not
            // after each value; the generated helper does it once the sequence is known.
            composition = new OrderBySpec.Composition.Sequence(namedOrders, keyed == null
                ? new OrderBySpec.SequenceCompletion.None()
                : new OrderBySpec.SequenceCompletion.AppendRowKey(keyed.rowKey(), keyed.identifyingKeys()));
        } else {
            composition = new OrderBySpec.Composition.Single(keyed == null
                ? namedOrders
                : namedOrders.stream()
                    .map(named -> new OrderBySpec.NamedOrder(named.name(), complete(named.order(), keyed)))
                    .toList());
        }
        return new Resolved.Ok(new OrderBySpec.Argument(ob.name(), ob.typeName(), ob.nonNull(), composition,
            ob.sortFieldName(), ob.directionFieldName(), baseSpec));
    }
}
