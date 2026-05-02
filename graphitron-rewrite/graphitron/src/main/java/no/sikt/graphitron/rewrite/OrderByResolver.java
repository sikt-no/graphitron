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
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.Rejection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 * ({@link ServiceDirectiveResolver}, {@link TableMethodDirectiveResolver},
 * {@link ExternalFieldDirectiveResolver}, {@link LookupKeyDirectiveResolver}).
 *
 * <p>Three concrete result shapes ride under {@link Resolved.Ok}:
 *
 * <ul>
 *   <li>{@link OrderBySpec.None} when ordering is not applicable — single-value returns or
 *       non-table-bound fields.</li>
 *   <li>{@link OrderBySpec.Fixed} when an {@code @defaultOrder} directive is present, or the
 *       parent table has a primary key and no {@code @orderBy} argument is in play.</li>
 *   <li>{@link OrderBySpec.Argument} when an {@code @orderBy} argument is present and its
 *       input-type structure validates (sort enum + direction field).</li>
 * </ul>
 *
 * <p>Every rejection path adds exactly one message to the {@link Resolved.Rejected} arm. The
 * caller appends that message to its accumulating {@code errors} list and surfaces a
 * {@code TableFieldComponents.Rejected}. Notably, the resolver owns the fallback
 * "could not resolve @defaultOrder columns in table '...'" rejection message that was previously
 * synthesized at the {@code projectForFilter} call site — it belongs inside the resolver since
 * it's the rejection reason for one specific failure path (silent {@code null} from the column /
 * index lookup chain).
 *
 * <p>Implementation note: only the public {@link #resolve} entry point and the internal
 * {@code resolveDefaultOrderSpec} / {@code resolveOrderByArgSpec} return {@link Resolved}; the
 * deeper plumbing ({@code resolveColumnOrderSpec}, {@code resolveOrderEntries},
 * {@code resolveIndexColumns}, {@code resolveEnumValueOrderSpec}) keeps its nullable-return shape
 * since those helpers don't carry the rejection-message responsibility.
 */
final class OrderByResolver {

    /**
     * Outcome of {@link #resolve}. Two terminal arms; the caller exhausts them with a switch or
     * an instanceof.
     *
     * <ul>
     *   <li>{@link Ok} — successful resolution; carries the resolved {@link OrderBySpec}.</li>
     *   <li>{@link Rejected} — every error path: invalid {@code @orderBy} input type, missing
     *       sort/direction enum field, unresolvable {@code @order} columns on an enum value,
     *       or unresolvable {@code @defaultOrder} columns / index.</li>
     * </ul>
     */
    sealed interface Resolved {
        record Ok(OrderBySpec spec) implements Resolved {}
        record Rejected(Rejection rejection) implements Resolved {
            public String message() { return rejection.message(); }
        }
    }

    private final BuildContext ctx;

    OrderByResolver(BuildContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Projects the classified arguments into an {@link OrderBySpec}.
     *
     * <p>Returns {@code Ok(None)} when ordering is not applicable: for single-value returns, or
     * when {@code tableSqlName} is {@code null} (non-table-bound field). Returns {@code Ok(None)}
     * (not a rejection) when the table has no primary key and no {@code @defaultOrder} is
     * present. Returns {@code Rejected} when an {@code @orderBy} argument failed to classify or
     * when {@code @defaultOrder}'s column / index resolution failed.
     */
    Resolved resolve(List<ArgumentRef> refs, GraphQLFieldDefinition fieldDef, String tableSqlName) {
        GraphQLType unwrapped = GraphQLTypeUtil.unwrapNonNull(fieldDef.getType());
        boolean isList = (unwrapped instanceof GraphQLList)
            || ctx.isConnectionType(baseTypeName(fieldDef))
            || fieldDef.hasAppliedDirective(DIR_AS_CONNECTION);
        if (!isList || tableSqlName == null) return new Resolved.Ok(new OrderBySpec.None());

        for (var ref : refs) {
            if (ref instanceof ArgumentRef.OrderByArg ob) {
                return resolveOrderByArgSpec(ob, fieldDef, tableSqlName);
            }
        }
        return resolveDefaultOrderSpec(fieldDef, tableSqlName);
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
                return new Resolved.Rejected(Rejection.structural("could not resolve @defaultOrder columns in table '" + tableSqlName + "'"));
            }
            return new Resolved.Ok(fixed);
        }
        var pkCols = ctx.catalog.findPkColumns(tableSqlName);
        if (pkCols.isEmpty()) return new Resolved.Ok(new OrderBySpec.None());
        return new Resolved.Ok(new OrderBySpec.Fixed(
            pkCols.stream()
                .map(ce -> new OrderBySpec.ColumnOrderEntry(new ColumnRef(ce.sqlName(), ce.javaName(), ce.columnClass()), null))
                .toList(),
            "ASC"));
    }

    /**
     * Resolves the {@code @defaultOrder} directive on a field into a fully-normalised
     * {@link OrderBySpec.Fixed} against {@code tableSqlName}. Only called when the directive is
     * confirmed present. Returns {@code null} when any catalog lookup fails; the caller
     * synthesises the rejection message.
     */
    private OrderBySpec.Fixed resolveColumnOrderSpec(GraphQLFieldDefinition fieldDef, String tableSqlName) {
        var dir = fieldDef.getAppliedDirective(DIR_DEFAULT_ORDER);

        // direction has a default of ASC in the directive; absent arg means ASC.
        String direction = "ASC";
        var dirArg = dir.getArgument(ARG_DIRECTION);
        if (dirArg != null) {
            Object dirVal = dirArg.getValue();
            if (dirVal instanceof EnumValue ev) direction = ev.getName();
            else if (dirVal instanceof String s) direction = s;
        }

        var entries = resolveOrderEntries(dir, tableSqlName);
        if (entries == null) return null;
        return new OrderBySpec.Fixed(entries, direction);
    }

    /**
     * Resolves an {@code @order} directive on an enum value into a {@link OrderBySpec.Fixed}.
     *
     * <p>The direction is not stored here — it comes from the runtime input object's direction
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
            entries = resolveOrderEntries(dir, tableSqlName);
        } else {
            // @index is a deprecated alias: @index(name: "idx") ≡ @order(index: "idx")
            var indexDir = ev.getAppliedDirective("index");
            var nameArg = indexDir != null ? indexDir.getArgument(ARG_NAME) : null;
            Object nameVal = nameArg != null ? nameArg.getValue() : null;
            String indexName = nameVal instanceof StringValue sv ? sv.getValue().strip()
                : nameVal instanceof String s ? s.strip() : null;
            entries = resolveIndexColumns(tableSqlName, indexName);
        }
        if (entries == null) {
            errors.add("enum value '" + ev.getName() + "': could not resolve @order columns in table '" + tableSqlName + "'");
            return null;
        }
        return new OrderBySpec.Fixed(entries, "ASC");
    }

    /** Looks up named index columns from the catalog; returns {@code null} when not found. */
    private List<OrderBySpec.ColumnOrderEntry> resolveIndexColumns(String tableSqlName, String indexName) {
        if (indexName == null) return null;
        var colsOpt = ctx.catalog.findIndexColumns(tableSqlName, indexName);
        if (colsOpt.isEmpty() || colsOpt.get().isEmpty()) return null;
        return colsOpt.get().stream()
            .map(ce -> new OrderBySpec.ColumnOrderEntry(new ColumnRef(ce.sqlName(), ce.javaName(), ce.columnClass()), null))
            .toList();
    }

    /**
     * Resolves the column entries from an {@code @order} or {@code @defaultOrder} directive.
     *
     * <p>All three source variants are resolved at build time:
     * <ul>
     *   <li>{@code index:} — columns come from the named index via the jOOQ catalog.</li>
     *   <li>{@code primaryKey:} — columns come from the table's primary key.</li>
     *   <li>{@code fields:} — each column name is looked up in the table via the jOOQ catalog.</li>
     * </ul>
     * Returns {@code null} when any lookup fails (index not found, PK absent, or a column name is
     * unresolvable). The caller is responsible for generating a diagnostic message.
     */
    private List<OrderBySpec.ColumnOrderEntry> resolveOrderEntries(GraphQLAppliedDirective dir, String tableSqlName) {
        var indexArg = dir.getArgument(ARG_INDEX);
        if (indexArg != null) {
            Object indexVal = indexArg.getValue();
            String indexName = indexVal instanceof StringValue sv ? sv.getValue().strip()
                : indexVal instanceof String s ? s.strip() : null;
            if (indexName != null) return resolveIndexColumns(tableSqlName, indexName);
        }

        var pkArg = dir.getArgument(ARG_PRIMARY_KEY);
        boolean primaryKey = pkArg != null && (
            pkArg.getValue() instanceof BooleanValue bv ? bv.isValue()
            : Boolean.TRUE.equals(pkArg.getValue()));
        if (primaryKey) {
            var pkCols = ctx.catalog.findPkColumns(tableSqlName);
            if (pkCols.isEmpty()) return null;
            return pkCols.stream()
                .map(ce -> new OrderBySpec.ColumnOrderEntry(new ColumnRef(ce.sqlName(), ce.javaName(), ce.columnClass()), null))
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
                var ceOpt = ctx.catalog.findColumn(tableSqlName, colName);
                if (ceOpt.isEmpty()) return null;
                var ce = ceOpt.get();
                entries.add(new OrderBySpec.ColumnOrderEntry(new ColumnRef(ce.sqlName(), ce.javaName(), ce.columnClass()), collation));
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
    private Resolved resolveOrderByArgSpec(ArgumentRef.OrderByArg ob, GraphQLFieldDefinition fieldDef, String tableSqlName) {
        var errors = new ArrayList<String>();
        var inputType = (GraphQLInputObjectType) ctx.schema.getType(ob.typeName());
        var sortEnum = (GraphQLEnumType) GraphQLTypeUtil.unwrapNonNull(
            inputType.getFieldDefinition(ob.sortFieldName()).getType());
        var namedOrders = new ArrayList<OrderBySpec.NamedOrder>();
        for (var value : sortEnum.getValues()) {
            if (!value.hasAppliedDirective("order") && !value.hasAppliedDirective("index")) continue;
            OrderBySpec.Fixed order = resolveEnumValueOrderSpec(value, tableSqlName, errors);
            if (order == null) return new Resolved.Rejected(Rejection.structural(errors.get(errors.size() - 1)));
            namedOrders.add(new OrderBySpec.NamedOrder(value.getName(), order));
        }
        var baseResolved = resolveDefaultOrderSpec(fieldDef, tableSqlName);
        if (baseResolved instanceof Resolved.Rejected r) return r;
        OrderBySpec baseSpec = ((Resolved.Ok) baseResolved).spec();
        return new Resolved.Ok(new OrderBySpec.Argument(ob.name(), ob.typeName(), ob.nonNull(), ob.list(),
            ob.sortFieldName(), ob.directionFieldName(), List.copyOf(namedOrders), baseSpec));
    }
}
