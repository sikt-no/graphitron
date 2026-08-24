package no.sikt.graphitron.rewrite.walker;

import graphql.language.SourceLocation;
import graphql.schema.GraphQLFieldDefinition;
import no.sikt.graphitron.rewrite.ArgConditionRef;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.DeleteRows;
import no.sikt.graphitron.rewrite.model.DeleteRowsError;
import no.sikt.graphitron.rewrite.model.FilterBinding;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.KeyColumn;
import no.sikt.graphitron.rewrite.model.MatchedKey;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.model.WalkerResult;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * Produces the {@link DeleteRows} carrier for an {@code @mutation(typeName: DELETE)} field.
 * The DELETE analogue of {@link UpdateRowsWalker}, but where that walker partitions the input
 * into a matched-key WHERE half and an everything-else SET half, this walker has no SET destination:
 * <em>every</em> admitted input column is a WHERE filter ({@link DeleteRows#whereColumns()}), and the
 * matched key is a single-row <em>guard</em> rather than a column subset.
 *
 * <p>PK-or-UK identification is the shared {@link MatchedKeys#firstCovered} matcher both walkers
 * call: a covered key yields {@link DeleteRows.Identified} (single-row; {@code multiRow} is moot
 * because the key already proves at most one row); no covered key yields
 * {@link DeleteRows.Broadcast} when {@code multiRow: true}, and
 * {@link DeleteRowsError.NoUniqueKeyCoverage} otherwise.
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
     * input's own table, and the extraction shape the emitter reuses. */
    private record Contribution(String sdlFieldName, List<ColumnRef> columns, CallSiteExtraction extraction) {}

    public WalkerResult<DeleteRows> walk(
        GraphQLFieldDefinition field,
        TableRef table,
        List<InputField> inputFields,
        JooqCatalog catalog,
        boolean multiRow,
        String outerArgName
    ) {
        var errors = new ArrayList<Rejection.AuthorError>();

        var contributions = new ArrayList<Contribution>();
        classifyInto(inputFields, List.of(), outerArgName, errors, contributions);
        if (!errors.isEmpty()) {
            // Unadmitted fields make the covered-column set unreliable; surface every per-field
            // issue without muddying the result with a spurious key-coverage error.
            return new WalkerResult.Err<>(errors);
        }

        // Union of every admitted field's target columns; every admitted column is a WHERE
        // filter (DELETE has no SET partition).
        var inputColumns = new ArrayList<ColumnRef>();
        var inputColumnSqlNames = new LinkedHashSet<String>();
        var whereColumns = new ArrayList<KeyColumn>();
        // DELETE never splits a carrier: every column of every contribution is a WHERE filter, so a
        // column's index in its contribution is also its decode slot, and the rows are contiguous.
        for (var c : contributions) {
            for (int slot = 0; slot < c.columns().size(); slot++) {
                var col = c.columns().get(slot);
                if (inputColumnSqlNames.add(col.sqlName())) {
                    inputColumns.add(col);
                }
                whereColumns.add(new KeyColumn(c.sdlFieldName(), col, c.extraction(), slot));
            }
        }

        // A covered key proves single-row; the matched key is a cardinality guard, not a column
        // subset, so whereColumns is unaffected.
        MatchedKey matchedKey = MatchedKeys.firstCovered(catalog, table, inputColumnSqlNames).orElse(null);
        if (matchedKey != null) {
            return new WalkerResult.Ok<>(new DeleteRows.Identified(matchedKey, whereColumns));
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
                    c.name(), c.list(), c.columns(), wrap(c.extraction(), prefix, c.name(), outerArgName), c.condition(), c.location(), errors, contributions);
                // Same binding gate as UpdateRowsWalker: a Remote-bound reference carrier has no
                // column on this table for the DELETE's WHERE to key on, so it needs the same
                // key-to-FK-column subquery and reports the same shared cause.
                case InputField.ColumnBackedReferenceField c -> {
                    switch (c.binding()) {
                        case FilterBinding.Local(var ownTableColumns) -> classifyColumnCarrier(
                            c.name(), c.list(), ownTableColumns, wrap(c.extraction(), prefix, c.name(), outerArgName), c.condition(), c.location(), errors, contributions);
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
        Optional<ArgConditionRef> condition, SourceLocation location,
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
        contributions.add(new Contribution(name, columns, extraction));
    }
}
