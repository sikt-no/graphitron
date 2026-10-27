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
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.model.diagnostics.Rejection;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static no.sikt.graphitron.rewrite.BuildContext.ARG_COLLATE;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_DIRECTION;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_FIELDS;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_INDEX;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_NAME;
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
 * <p>Every rejection path carries exactly one message in the {@link Resolved.Rejected} arm.
 * The deeper helpers keep a nullable-return shape; their callers synthesise the message.
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

    OrderByResolver(BuildContext ctx) {
        this.ctx = ctx;
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
     * @param returnTypeName the field's element type, whose {@code @node} key can identify a row
     * @param singleTable    {@code false} at a multitable participant, whose orders are folded
     *                       with key slots of their own and so are never completed here
     */
    Resolved resolve(List<ArgumentRef> refs, GraphQLFieldDefinition fieldDef, TableRef table,
                     String returnTypeName, boolean singleTable) {
        GraphQLType unwrapped = GraphQLTypeUtil.unwrapNonNull(fieldDef.getType());
        boolean isList = (unwrapped instanceof GraphQLList)
            || ctx.isConnectionType(baseTypeName(fieldDef))
            || fieldDef.hasAppliedDirective(DIR_AS_CONNECTION);
        if (!isList || table == null) return new Resolved.Ok(new OrderBySpec.None());
        String tableSqlName = table.tableName();

        Optional<RowIdentity> identity = singleTable
                && ctx.buildWrapper(fieldDef) instanceof FieldWrapper.Connection
            ? Optional.of(rowIdentity(table, returnTypeName))
            : Optional.empty();

        Resolved resolved = null;
        for (var ref : refs) {
            if (ref instanceof ArgumentRef.OrderByArg ob) {
                resolved = resolveOrderByArgSpec(ob, fieldDef, tableSqlName, identity);
                break;
            }
        }
        if (resolved == null) {
            resolved = completed(resolveDefaultOrderSpec(fieldDef, tableSqlName), identity);
        }
        if (resolved instanceof Resolved.Ok ok
                && identity.orElse(null) instanceof RowIdentity.Keyless
                && !isEmpty(ok.spec())) {
            return new Resolved.Ok(ok.spec(), true);
        }
        return resolved;
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
        if (fixed.columns().isEmpty()) return fixed;
        var listed = new HashSet<String>();
        for (var entry : fixed.columns()) listed.add(entry.column().sqlName());
        if (identifies(listed, keyed.identifyingKeys())) return fixed;
        var direction = fixed.columns().getLast().direction();
        var columns = new ArrayList<>(fixed.columns());
        for (var column : keyed.rowKey()) {
            if (!listed.contains(column.sqlName())) {
                columns.add(new OrderBySpec.ColumnOrderEntry(column, null, direction, false));
            }
        }
        return new OrderBySpec.Fixed(List.copyOf(columns), fixed.uniformAsc());
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
            case OrderBySpec.Fixed fixed -> fixed.columns().isEmpty();
            case OrderBySpec.Argument arg -> isEmpty(arg.base())
                && arg.namedOrders().stream().allMatch(named -> named.order().columns().isEmpty());
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
    private Resolved resolveDefaultOrderSpec(GraphQLFieldDefinition fieldDef, String tableSqlName) {
        if (fieldDef.hasAppliedDirective(DIR_DEFAULT_ORDER)) {
            var fixed = resolveColumnOrderSpec(fieldDef, tableSqlName);
            if (fixed == null) {
                return new Resolved.Rejected(Rejection.structural(defaultOrderFailure(
                    fieldDef.getAppliedDirective(DIR_DEFAULT_ORDER), tableSqlName)));
            }
            return new Resolved.Ok(fixed);
        }
        var pkCols = ctx.catalog.findPkColumns(tableSqlName);
        if (pkCols.isEmpty()) return new Resolved.Ok(new OrderBySpec.None());
        return new Resolved.Ok(new OrderBySpec.Fixed(
            pkCols.stream()
                .map(ce -> new OrderBySpec.ColumnOrderEntry(
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

    /**
     * Resolves the {@code @defaultOrder} directive on a field into a fully-normalised
     * {@link OrderBySpec.Fixed} against {@code tableSqlName}. Only called when the directive is
     * confirmed present. Returns {@code null} when any catalog lookup fails; the caller
     * synthesises the rejection message.
     */
    private OrderBySpec.Fixed resolveColumnOrderSpec(GraphQLFieldDefinition fieldDef, String tableSqlName) {
        var dir = fieldDef.getAppliedDirective(DIR_DEFAULT_ORDER);
        OrderBySpec.SortDirection fallback = readDirectionArg(dir, OrderBySpec.SortDirection.ASC);
        var entries = resolveOrderEntries(dir, tableSqlName, fallback);
        if (entries == null) return null;
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
            List<String> errors) {
        var dir = ev.getAppliedDirective("order");
        List<OrderBySpec.ColumnOrderEntry> entries;
        if (dir != null) {
            // @order has no directive-level direction surface; ASC is the per-entry fallback.
            entries = resolveOrderEntries(dir, tableSqlName, OrderBySpec.SortDirection.ASC);
        } else {
            // @index is a deprecated alias: @index(name: "idx") ≡ @order(index: "idx")
            var indexDir = ev.getAppliedDirective("index");
            var nameArg = indexDir != null ? indexDir.getArgument(ARG_NAME) : null;
            Object nameVal = nameArg != null ? nameArg.getValue() : null;
            String indexName = nameVal instanceof StringValue sv ? sv.getValue().strip()
                : nameVal instanceof String s ? s.strip() : null;
            entries = resolveIndexColumns(tableSqlName, indexName, OrderBySpec.SortDirection.ASC);
        }
        if (entries == null) {
            errors.add("enum value '" + ev.getName() + "': could not resolve @order columns in table '" + tableSqlName + "'");
            return null;
        }
        boolean uniformAsc = entries.stream().allMatch(e -> e.direction() == OrderBySpec.SortDirection.ASC);
        return new OrderBySpec.Fixed(entries, uniformAsc);
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
     * Resolves the column entries from an {@code @order} or {@code @defaultOrder} directive
     * ({@code index:}, {@code primaryKey:}, or {@code fields:}), all resolved at build time via
     * the jOOQ catalog. Returns {@code null} when any lookup fails; the caller synthesises the
     * diagnostic message.
     */
    private List<OrderBySpec.ColumnOrderEntry> resolveOrderEntries(
            GraphQLAppliedDirective dir, String tableSqlName, OrderBySpec.SortDirection defaultDirection) {
        String indexName = indexNameOf(dir);
        if (indexName != null) {
            return resolveIndexColumns(tableSqlName, indexName, defaultDirection);
        }

        if (readsPrimaryKey(dir)) {
            var pkCols = ctx.catalog.findPkColumns(tableSqlName);
            if (pkCols.isEmpty()) return null;
            return pkCols.stream()
                .map(ce -> new OrderBySpec.ColumnOrderEntry(
                    new ColumnRef(ce.sqlName(), ce.javaName(), ce.columnClass(), ce.databaseType()),
                    null,
                    defaultDirection,
                    ce.nullable()))
                .toList();
        }

        var fieldsArg = dir.getArgument(ARG_FIELDS);
        if (fieldsArg != null) {
            Object value = fieldsArg.getValue();
            List<?> items = value instanceof List<?> l ? l : List.of(value);
            var entries = new ArrayList<OrderBySpec.ColumnOrderEntry>();
            for (var item : items) {
                if (!(item instanceof Map)) continue;
                var map = asMap(item);
                Object nameRaw = map.get(ARG_NAME);
                if (nameRaw == null) return null;
                String colName = nameRaw.toString().strip();
                String collation = Optional.ofNullable(map.get(ARG_COLLATE)).map(Object::toString).map(String::strip).orElse(null);
                OrderBySpec.SortDirection entryDirection = parseDirection(map.get(ARG_DIRECTION), defaultDirection);
                var ceOpt = ctx.catalog.findColumn(tableSqlName, colName);
                if (ceOpt.isEmpty()) return null;
                var ce = ceOpt.get();
                entries.add(new OrderBySpec.ColumnOrderEntry(
                    new ColumnRef(ce.sqlName(), ce.javaName(), ce.columnClass(), ce.databaseType()), collation, entryDirection, ce.nullable()));
            }
            return entries;
        }

        return null;
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
                                           String tableSqlName, Optional<RowIdentity> identity) {
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
            OrderBySpec.Fixed order = resolveEnumValueOrderSpec(value, tableSqlName, errors);
            if (order == null) return new Resolved.Rejected(Rejection.structural(errors.get(errors.size() - 1)));
            namedOrders.add(new OrderBySpec.NamedOrder(value.getName(), order));
        }
        if (!missingOrder.isEmpty()) {
            return new Resolved.Rejected(Rejection.sortEnumMissingOrder(sortEnum.getName(), missingOrder));
        }
        var baseResolved = completed(resolveDefaultOrderSpec(fieldDef, tableSqlName), identity);
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
