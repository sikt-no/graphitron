package no.sikt.graphitron.rewrite.walker;

import graphql.language.SourceLocation;
import graphql.schema.GraphQLFieldDefinition;
import no.sikt.graphitron.rewrite.ArgConditionRef;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.DeleteRows;
import no.sikt.graphitron.rewrite.model.DeleteRowsError;
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
 * The DELETE analogue of R246's {@code UpdateRowsWalker}, but where that walker partitions the input
 * into a matched-key WHERE half and an everything-else SET half, this walker has no SET destination:
 * <em>every</em> admitted input column is a WHERE filter ({@link DeleteRows#whereColumns()}), and the
 * matched key is a single-row <em>guard</em> rather than a column subset.
 *
 * <p>The PK-or-UK identification is the shared {@link MatchedKeys#firstCovered} matcher both walkers
 * call. Three outcomes:
 * <ul>
 *   <li>A candidate key is covered → {@link DeleteRows.Identified} (single-row; any {@code multiRow}
 *       flag is moot because the key already proves at most one row).</li>
 *   <li>No key covered but {@code multiRow: true} → {@link DeleteRows.Broadcast} (the DELETE
 *       broadcasts over every matching row; non-key filters are legitimate predicates).</li>
 *   <li>No key covered and not {@code multiRow} → {@link DeleteRowsError.NoUniqueKeyCoverage}
 *       (subsumes R188's silent PK-less gap and the {@code table-has-no-pk} rejection).</li>
 * </ul>
 *
 * <p><b>Substrate concession (mirrors R246's {@code UpdateRowsWalker}).</b> The spec's ideal is a
 * walker reading {@code GraphQLFieldDefinition} + jOOQ catalog directly and re-deriving the column
 * classification from raw SDL. Re-running the input-field classifier inside the walker would
 * duplicate a substantial component, so this walker instead translates over the already-classified
 * {@link InputField} permits the upstream classifier produced. The direct-SDL substrate follow-up
 * mirrors R257 (tracked as {@code deleterows-walker-sdl-substrate}). The {@code field} parameter is
 * reserved for that future substrate; the current translator does not read it. Errors are collected
 * across stages without short-circuiting so the LSP surfaces every per-field issue at once.
 *
 * <p><b>Nested grouping inputs.</b> Mirrors {@code UpdateRowsWalker}: a plain
 * (non-{@code @table}) {@link InputField.NestingField} grouping columns of the outer table is
 * flattened in place ({@link #classifyInto}) into its leaf carriers, each nested leaf's
 * {@code extraction} rewrapped as a {@link CallSiteExtraction.NestedInputField} that records the SDL
 * access path. Every flattened leaf's column becomes a WHERE filter and counts toward the single-row
 * PK-or-UK guard unchanged. A list-typed nesting is rejected.
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

        // Stage 2: classify each input field into a walker-local column contribution, flattening
        // any nested (non-@table) grouping input into its leaf carriers in place; collect
        // per-field admissibility rejections across the loop (no short-circuit).
        var contributions = new ArrayList<Contribution>();
        classifyInto(inputFields, List.of(), outerArgName, errors, contributions);
        if (!errors.isEmpty()) {
            // Unadmitted fields make the covered-column set unreliable; surface every per-field
            // issue without muddying the result with a spurious key-coverage error.
            return new WalkerResult.Err<>(errors);
        }

        // Stage 3: union of every admitted field's target columns, and the WHERE column list (every
        // admitted column — DELETE has no SET partition).
        var inputColumns = new ArrayList<ColumnRef>();
        var inputColumnSqlNames = new LinkedHashSet<String>();
        var whereColumns = new ArrayList<KeyColumn>();
        for (var c : contributions) {
            for (var col : c.columns()) {
                if (inputColumnSqlNames.add(col.sqlName())) {
                    inputColumns.add(col);
                }
                whereColumns.add(new KeyColumn(c.sdlFieldName(), col, c.extraction()));
            }
        }

        // Stage 4-5: PK-or-UK identification via the shared matcher. A covered key proves single-row;
        // the matched key is a cardinality guard, not a column subset, so whereColumns is unaffected.
        MatchedKey matchedKey = MatchedKeys.firstCovered(catalog, table, inputColumnSqlNames).orElse(null);
        if (matchedKey != null) {
            return new WalkerResult.Ok<>(new DeleteRows.Identified(matchedKey, whereColumns));
        }

        // Stage 6: no key covered. multiRow: true opts into a broadcast (non-key) delete; otherwise
        // the input cannot identify rows and is a typed rejection (subsumes R188's PK-less gap).
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
     * leaf keeps its extraction unchanged (byte-identical emit). Every flattened leaf's column
     * becomes a WHERE filter and counts toward the single-row PK-or-UK guard exactly as a root
     * leaf's does.
     */
    private void classifyInto(
        List<InputField> fields, List<String> prefix, String outerArgName,
        List<Rejection.AuthorError> errors, List<Contribution> contributions
    ) {
        for (var f : fields) {
            switch (f) {
                case InputField.ColumnField c -> classifyColumnCarrier(
                    c.name(), c.list(), List.of(c.column()), wrap(c.extraction(), prefix, c.name(), outerArgName), c.condition(), c.location(), errors, contributions);
                case InputField.CompositeColumnField c -> classifyColumnCarrier(
                    c.name(), c.list(), c.columns(), wrap(c.extraction(), prefix, c.name(), outerArgName), c.condition(), c.location(), errors, contributions);
                case InputField.ColumnReferenceField c -> classifyColumnCarrier(
                    c.name(), c.list(), c.liftedSourceColumns(), wrap(c.extraction(), prefix, c.name(), outerArgName), c.condition(), c.location(), errors, contributions);
                case InputField.CompositeColumnReferenceField c -> classifyColumnCarrier(
                    c.name(), c.list(), c.liftedSourceColumns(), wrap(c.extraction(), prefix, c.name(), outerArgName), c.condition(), c.location(), errors, contributions);
                case InputField.UnboundField u -> {
                    if (u.condition().isPresent() && u.condition().get().override()) {
                        errors.add(new DeleteRowsError.OverrideConditionNotSupported(u.name(), u.location()));
                    } else {
                        errors.add(new DeleteRowsError.UnsupportedInputFieldShape(
                            u.name(), "UnboundField",
                            "the field binds no column and carries no @condition(override: true); "
                            + "DELETE input fields must bind a column"));
                    }
                }
                case InputField.NestingField n -> {
                    if (n.list()) {
                        errors.add(new DeleteRowsError.UnsupportedInputFieldShape(
                            n.name(), "list-typed NestingField",
                            "list-typed nested input types (e.g. '" + n.name() + ": [" + n.typeName()
                            + "!]') on @mutation(typeName: DELETE) fields are not yet supported (R186); "
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
     * carries a field-level {@code @condition}. R266 does not emit input-field conditions on DELETE,
     * so a condition would be silently dropped, the same footgun
     * {@link DeleteRowsError.OverrideConditionNotSupported} makes honest; reject rather than admit.
     * An {@code override: true} condition reports through that arm; any other condition reports as an
     * unsupported shape.
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
