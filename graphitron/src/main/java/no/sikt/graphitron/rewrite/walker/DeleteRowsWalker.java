package no.sikt.graphitron.rewrite.walker;

import graphql.language.SourceLocation;
import graphql.schema.GraphQLFieldDefinition;
import no.sikt.graphitron.rewrite.ArgConditionRef;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ColumnOverlap;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.rewrite.model.DeleteRows;
import no.sikt.graphitron.model.diagnostics.DeleteRowsError;
import no.sikt.graphitron.rewrite.model.FilterBinding;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.KeyColumn;
import no.sikt.graphitron.model.diagnostics.MatchedKey;
import no.sikt.graphitron.model.diagnostics.Rejection;
import no.sikt.graphitron.model.jooq.TableRef;
import no.sikt.graphitron.rewrite.model.WalkerResult;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Produces the {@link DeleteRows} carrier for an {@code @mutation(typeName: DELETE)} field.
 * The DELETE analogue of {@link UpdateRowsWalker}, but where that walker partitions the input
 * into a matched-key WHERE half and an everything-else SET half, this walker has no SET destination:
 * every admitted input column is a WHERE filter ({@link DeleteRows#whereColumns()}) or dropped, and
 * the matched key is a single-row <em>guard</em> rather than a column subset. A column is dropped
 * only where an own-columns carrier covers the whole matched key plus more (the node id of a type
 * whose node key is a strict superset of the key, {@link KeyMembership.CoversKeyPlus}): the key
 * alone names the row, so an id whose extra columns have since changed still deletes it.
 *
 * <p>PK-or-UK identification is the shared {@link MatchedKeys#firstCovered} matcher both walkers
 * call: a covered key yields {@link DeleteRows.Identified} (single-row; {@code multiRow} is moot
 * because the key already proves at most one row); no covered key yields
 * {@link DeleteRows.Broadcast} when {@code multiRow: true}, and
 * {@link DeleteRowsError.NoUniqueKeyCoverage} otherwise.
 *
 * <p>With {@code missingAsDeleted: true} the walker admits only an input that identifies rows by
 * exactly the primary key: the matched key is the primary key and the WHERE binds each of its
 * columns once and nothing else. That yields {@link DeleteRows.IdempotentByPrimaryKey}, with the
 * supplying {@link KeyColumn} picked for each primary-key slot in key order; anything else is
 * {@link DeleteRowsError.MissingAsDeletedNeedsPrimaryKey}. Where the payload's ids encode columns
 * beyond the primary key ({@link EchoColumns}), each must be one a carrier drops, so the echo has
 * the client's value to encode; otherwise {@link DeleteRowsError.MissingAsDeletedEchoUnencodable}.
 *
 * <p><b>Substrate concession (mirrors {@link UpdateRowsWalker}).</b> Re-deriving the column
 * classification from raw SDL would duplicate the upstream input-field classifier, so this walker
 * translates over the already-classified {@link InputField} permits instead. The {@code field}
 * parameter is unread; it keeps the signature shaped for a walker reading the SDL substrate
 * directly. Errors are collected across stages without short-circuiting so the LSP surfaces
 * every per-field issue at once.
 */
public final class DeleteRowsWalker {

    /** A reshaped, column-bearing input field: the SDL field name, its target columns on the
     * input's own table (positionally its decode slots), the extraction shape the emitter reuses,
     * and what the carrier points at. */
    private record Contribution(String sdlFieldName, List<ColumnRef> columns, CallSiteExtraction extraction,
                                CarrierRole role) {}

    /**
     * What a {@code missingAsDeleted} echo of a missing row must supply beyond the primary key: the
     * payload's node type and its node-key columns outside the primary key, in node-key order.
     * {@link #NONE} where the payload's ids need nothing beyond the primary key, and on the
     * direct-return path, where {@code missingAsDeleted} is refused before the walk.
     */
    public record EchoColumns(String nodeTypeName, List<ColumnRef> columns) {
        public static final EchoColumns NONE = new EchoColumns("", List.of());

        public EchoColumns {
            columns = List.copyOf(columns);
        }
    }

    public WalkerResult<DeleteRows> walk(
        GraphQLFieldDefinition field,
        TableRef table,
        List<InputField> inputFields,
        JooqCatalog catalog,
        boolean multiRow,
        boolean missingAsDeleted,
        String outerArgName,
        EchoColumns echoColumns
    ) {
        var errors = new ArrayList<Rejection.AuthorError>();

        var contributions = new ArrayList<Contribution>();
        classifyInto(inputFields, List.of(), outerArgName, errors, contributions);
        if (!errors.isEmpty()) {
            // Unadmitted fields make the covered-column set unreliable; surface every per-field
            // issue without muddying the result with a spurious key-coverage error.
            return new WalkerResult.Err<>(errors);
        }

        // Union of every admitted field's target columns, over which the matched key is found.
        var inputColumns = new ArrayList<ColumnRef>();
        var inputColumnSqlNames = new LinkedHashSet<String>();
        for (var c : contributions) {
            for (var col : c.columns()) {
                if (inputColumnSqlNames.add(col.sqlName())) {
                    inputColumns.add(col);
                }
            }
        }

        // A covered key proves single-row; the matched key is a cardinality guard, not a column
        // subset. It is found before the WHERE is built: a column a carrier drops is by definition
        // outside the key, so the match is the same with or without it.
        MatchedKey matchedKey = MatchedKeys.firstCovered(catalog, table, inputColumnSqlNames).orElse(null);
        var keySqlNames = matchedKey == null ? Set.<String>of() : sqlNameSet(matchedKey.columns());

        // Every column of every contribution is a WHERE filter at its decode slot, a straddle
        // included, since DELETE has no row-move hazard. The one exception is an own-columns carrier
        // covering the whole matched key plus more (a superkey node id): the key alone names the
        // row, so its other columns are dropped. That leaves the carrier's WHERE rows a
        // non-contiguous slice of its decode record, which is why each row states its slot.
        var whereColumns = new ArrayList<KeyColumn>();
        var droppedColumns = new ArrayList<KeyColumn>();
        for (var c : contributions) {
            var membership = matchedKey == null || c.role() instanceof CarrierRole.SelfFk
                ? null
                : KeyMembership.of(c.role(), c.columns(), keySqlNames);
            if (membership instanceof KeyMembership.CoversKeyPlus plus) {
                addColumns(whereColumns, c, plus.inKey());
                addColumns(droppedColumns, c, plus.dropped());
            } else {
                addColumns(whereColumns, c, ColumnOverlap.SlotColumn.contiguous(c.columns()));
            }
        }

        if (missingAsDeleted) {
            return idempotentByPrimaryKey(catalog, table, matchedKey, whereColumns, droppedColumns,
                inputColumns, echoColumns);
        }
        if (matchedKey != null) {
            return new WalkerResult.Ok<>(new DeleteRows.Identified(matchedKey, whereColumns, droppedColumns));
        }

        // No key covered. multiRow: true opts into a broadcast (non-key) delete; otherwise the
        // input cannot identify rows and is a typed rejection.
        if (multiRow) {
            return new WalkerResult.Ok<>(new DeleteRows.Broadcast(whereColumns));
        }
        return new WalkerResult.Err<>(List.of(
            new DeleteRowsError.NoUniqueKeyCoverage(
                table.tableName(), inputColumns, MatchedKeys.candidates(catalog, table))));
    }

    /**
     * The {@code missingAsDeleted: true} admission: the matched key must be the primary key, and
     * every WHERE column must bind a distinct primary-key column, so the binding covers the key
     * exactly once with nothing beside it. A unique-key match, an extra filter column, a column
     * bound twice (a node id and a plain field both filling it) and no covered key at all are each
     * the one typed rejection. The binding is re-ordered into the key's column order here, since
     * the contribution order is the WHERE's and need not be the key's. A superkey node id's dropped
     * columns do not count against "nothing beside it", since they are not filtered on.
     *
     * <p>Each of {@code echoColumns} is then bound to the dropped column that decodes it, in
     * {@code echoColumns} order, so a missing row's echo can encode the payload's id from the
     * client's value. An echo column no carrier decodes is
     * {@link DeleteRowsError.MissingAsDeletedEchoUnencodable}.
     */
    private static WalkerResult<DeleteRows> idempotentByPrimaryKey(
        JooqCatalog catalog, TableRef table, MatchedKey matchedKey,
        List<KeyColumn> whereColumns, List<KeyColumn> droppedColumns, List<ColumnRef> inputColumns,
        EchoColumns echoColumns
    ) {
        if (matchedKey instanceof MatchedKey.PrimaryKey pk && whereColumns.size() == pk.columns().size()) {
            var bySqlName = new java.util.HashMap<String, KeyColumn>();
            for (var kc : whereColumns) {
                bySqlName.putIfAbsent(kc.targetColumn().sqlName(), kc);
            }
            var binding = new ArrayList<KeyColumn>();
            for (var col : pk.columns()) {
                var kc = bySqlName.get(col.sqlName());
                if (kc != null) {
                    binding.add(kc);
                }
            }
            if (binding.size() == pk.columns().size()) {
                var echoBinding = new ArrayList<KeyColumn>();
                var unsupplied = new ArrayList<ColumnRef>();
                for (var col : echoColumns.columns()) {
                    droppedColumns.stream()
                        .filter(kc -> kc.targetColumn().sqlName().equals(col.sqlName()))
                        .findFirst()
                        .ifPresentOrElse(echoBinding::add, () -> unsupplied.add(col));
                }
                if (!unsupplied.isEmpty()) {
                    return new WalkerResult.Err<>(List.of(
                        new DeleteRowsError.MissingAsDeletedEchoUnencodable(
                            table.tableName(), echoColumns.nodeTypeName(), inputColumns, unsupplied)));
                }
                return new WalkerResult.Ok<>(
                    new DeleteRows.IdempotentByPrimaryKey(pk, binding, droppedColumns, echoBinding));
            }
        }
        var primaryKeyColumns = MatchedKeys.candidates(catalog, table).stream()
            .filter(k -> k instanceof MatchedKey.PrimaryKey)
            .findFirst()
            .map(MatchedKey::columns)
            .orElse(List.of());
        return new WalkerResult.Err<>(List.of(
            new DeleteRowsError.MissingAsDeletedNeedsPrimaryKey(
                table.tableName(), inputColumns, primaryKeyColumns)));
    }

    /**
     * Flatten {@code fields} into {@link Contribution}s, descending into any
     * {@link InputField.NestingField} grouping input, the DELETE analogue of
     * {@code UpdateRowsWalker.classifyInto}. A nested leaf's {@code extraction} is rewrapped as a
     * {@link CallSiteExtraction.NestedInputField} carrying the full SDL access path; a top-level
     * leaf keeps its extraction unchanged. Every flattened leaf's column becomes a WHERE filter
     * and counts toward the single-row PK-or-UK guard exactly as a root leaf's does.
     */
    private void classifyInto(
        List<InputField> fields, List<String> prefix, String outerArgName,
        List<Rejection.AuthorError> errors, List<Contribution> contributions
    ) {
        for (var f : fields) {
            switch (f) {
                case InputField.ColumnBackedField c -> classifyColumnCarrier(
                    c.name(), c.list(), c.columns(), wrap(c.extraction(), prefix, c.name(), outerArgName),
                    new CarrierRole.OwnColumns(), c.condition(), c.location(), errors, contributions);
                // Same binding gate as UpdateRowsWalker: a Remote-bound reference carrier has no
                // column on this table for the DELETE's WHERE to key on, so it needs the same
                // key-to-FK-column subquery and reports the same shared cause.
                case InputField.ColumnBackedReferenceField c -> {
                    switch (c.binding()) {
                        case FilterBinding.Local(var ownTableColumns) -> classifyColumnCarrier(
                            c.name(), c.list(), ownTableColumns, wrap(c.extraction(), prefix, c.name(), outerArgName),
                            c.selfReference() ? new CarrierRole.SelfFk() : new CarrierRole.CrossTableFk(),
                            c.condition(), c.location(), errors, contributions);
                        case FilterBinding.Remote ignored ->
                            errors.add(new DeleteRowsError.UnsupportedInputFieldShape(
                                c.name(), "translated FK-target @nodeId reference",
                                FilterBinding.remoteBindingUnsupported(c.name(),
                                    "used to key @mutation(typeName: DELETE)")));
                    }
                }
                case InputField.ConditionOwnedField c ->
                    errors.add(new DeleteRowsError.OverrideConditionNotSupported(c.name(), c.location()));
                case InputField.UnboundField u ->
                    errors.add(new DeleteRowsError.UnsupportedInputFieldShape(
                        u.name(), "UnboundField",
                        "the field binds no column and carries no @condition(override: true); "
                        + "DELETE input fields must bind a column"));
                case InputField.NestingField n -> {
                    if (n.list()) {
                        errors.add(new DeleteRowsError.UnsupportedInputFieldShape(
                            n.name(), "list-typed NestingField",
                            "list-typed nested input types (e.g. '" + n.name() + ": [" + n.typeName()
                            + "!]') on @mutation(typeName: DELETE) fields are not yet supported; "
                            + "a list grouping has no obvious meaning when flattening onto one outer row."));
                    } else if (n.condition().isPresent()) {
                        errors.add(new DeleteRowsError.UnsupportedInputFieldShape(
                            n.name(), "NestingField with @condition",
                            "@condition on a nested grouping input is not supported on "
                            + "@mutation(typeName: DELETE); the filter would not be applied. Remove the directive."));
                    } else {
                        var childPrefix = new ArrayList<>(prefix);
                        childPrefix.add(n.name());
                        classifyInto(n.fields(), childPrefix, outerArgName, errors, contributions);
                    }
                }
                default ->
                    errors.add(new DeleteRowsError.UnsupportedInputFieldShape(
                        f.name(), f.getClass().getSimpleName(),
                        "input field shape is not a supported DELETE input carrier"));
            }
        }
    }

    /**
     * Rewrap a leaf's call-site extraction so a nested leaf descends the wire map; top-level leaves
     * ({@code prefix} empty) keep their extraction unchanged. Mirrors {@code UpdateRowsWalker.wrap}.
     */
    private static CallSiteExtraction wrap(
        CallSiteExtraction leaf, List<String> prefix, String leafName, String outerArgName
    ) {
        if (prefix.isEmpty()) {
            return leaf;
        }
        var path = new ArrayList<>(prefix);
        path.add(leafName);
        return new CallSiteExtraction.NestedInputField(outerArgName, path, leaf);
    }

    /**
     * Reshape an admitted column carrier into a {@link Contribution}, unless it is list-typed or
     * carries a field-level {@code @condition}. This walker does not emit input-field conditions on
     * DELETE, so a condition would be silently dropped, the same footgun
     * {@link DeleteRowsError.OverrideConditionNotSupported} makes honest; reject rather than admit.
     */
    private void classifyColumnCarrier(
        String name, boolean list, List<ColumnRef> columns, CallSiteExtraction extraction,
        CarrierRole role, Optional<ArgConditionRef> condition, SourceLocation location,
        List<Rejection.AuthorError> errors, List<Contribution> contributions
    ) {
        if (list) {
            errors.add(new DeleteRowsError.UnsupportedInputFieldShape(
                name, "list-typed column carrier",
                "list-typed input field is not supported; list cardinality must live on the outer "
                + "@table argument, not on an individual input field."));
            return;
        }
        if (condition.isPresent()) {
            if (condition.get().override()) {
                errors.add(new DeleteRowsError.OverrideConditionNotSupported(name, location));
            } else {
                errors.add(new DeleteRowsError.UnsupportedInputFieldShape(
                    name, "column carrier with @condition",
                    "@condition on a @mutation(typeName: DELETE) input field is not supported; the "
                    + "filter would not be applied. Remove the directive."));
            }
            return;
        }
        contributions.add(new Contribution(name, columns, extraction, role));
    }

    /** Append the given columns of {@code c} to {@code out}, each at its stated decode slot. */
    private static void addColumns(List<KeyColumn> out, Contribution c, List<ColumnOverlap.SlotColumn> columns) {
        for (var sc : columns) {
            out.add(new KeyColumn(c.sdlFieldName(), sc.column(), c.extraction(), sc.slot()));
        }
    }

    private static Set<String> sqlNameSet(List<ColumnRef> columns) {
        var out = new LinkedHashSet<String>();
        for (var c : columns) out.add(c.sqlName());
        return out;
    }
}
