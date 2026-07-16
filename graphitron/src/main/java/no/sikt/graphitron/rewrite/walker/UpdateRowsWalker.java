package no.sikt.graphitron.rewrite.walker;

import graphql.language.SourceLocation;
import graphql.schema.GraphQLFieldDefinition;
import no.sikt.graphitron.rewrite.ArgConditionRef;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.KeyColumn;
import no.sikt.graphitron.rewrite.model.MatchedKey;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.SetColumn;
import no.sikt.graphitron.rewrite.model.TableRef;
import no.sikt.graphitron.rewrite.model.UpdateRows;
import no.sikt.graphitron.rewrite.model.UpdateRowsError;
import no.sikt.graphitron.rewrite.model.WalkerResult;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Produces the {@link UpdateRows} carrier for an {@code @mutation(typeName: UPDATE)} field
 * that returns its {@code @table} type directly. The walker's load-bearing claim is PK-or-UK
 * identification: it queries jOOQ's {@code Table.getPrimaryKey()} / {@code Table.getKeys()} (via
 * {@link JooqCatalog#candidateKeys(String)}) and picks the first candidate (PK preferred) whose
 * column set is a subset of the input-covered columns, then partitions the input fields into the
 * WHERE (matched-key) and SET (everything else) halves.
 *
 * <p><b>Substrate concession (mirrors {@code ServiceMethodCallWalker}).</b> The spec's ideal
 * is a walker reading {@code GraphQLFieldDefinition} + jOOQ catalog directly and re-deriving the
 * column classification from raw SDL. Re-running {@code InputFieldResolver} /
 * {@code EnumMappingResolver.buildLookupBindings} (with {@code @reference} FK-join and {@code @nodeId}
 * decode resolution) inside the walker would duplicate a substantial classifier. Following the same
 * walker substrate concession on blast-radius grounds, this walker instead translates
 * over the already-classified {@link InputField} permits the upstream classifier produced. The
 * follow-up that retires the intermediate and reflects from SDL directly is tracked as a Backlog
 * item ({@code updaterows-walker-sdl-substrate}). The {@code field} parameter is reserved for that
 * future direct-SDL substrate; the current translator does not read it. The walk is
 * cardinality-independent: a self-FK {@code @reference} routes its columns wholly to SET on both the
 * single-row and bulk (list-input) forms, so the caller's list shape never reaches here.
 *
 * <p>The new logic this walker owns — PK-or-UK subset matching, SET/WHERE partition by key
 * membership, empty-SET rejection, and the override-condition rejection — is fully expressible over
 * the classified permits plus the jOOQ keys, so the concession costs nothing in this slice's scope.
 * Errors are collected across stages without short-circuiting so the LSP surfaces every per-field
 * issue at once.
 *
 * <p><b>Nested grouping inputs.</b> A plain (non-{@code @table}) input object grouping
 * columns of the outer table classifies as an {@link InputField.NestingField}; the walker flattens
 * it in place ({@link #classifyInto}) into the same flat leaf carriers it would admit at the input
 * root, rewrapping each nested leaf's {@code extraction} as a
 * {@link CallSiteExtraction.NestedInputField} that records the SDL access path. The SET/WHERE
 * partition and the PK-or-UK match are column-identity-driven, so they run over the flattened
 * leaves unchanged; the access path rides on the leaf's {@link SetColumn} / {@link KeyColumn}
 * {@code extraction} for the emitter to descend the wire map. A list-typed nesting is rejected.
 */
public final class UpdateRowsWalker {

    /** A reshaped, column-bearing input field: the SDL field name, its target columns on the
     * input's own table, the extraction shape the emitter reuses, and whether the carrier is a
 * self-FK reference. A self-FK carrier's columns route wholly to the SET partition and
     * never count toward WHERE-key coverage; every other carrier partitions by key membership. */
    private record Contribution(String sdlFieldName, List<ColumnRef> columns, CallSiteExtraction extraction,
                                boolean selfReference) {}

    /** True when the carrier decodes a {@code @nodeId} (directly or behind a nested-input access path),
     *  i.e. its value is only knowable at runtime — the distinction between a runtime-agreement
     *  overlap and the build-time plain-field collision. */
    private static boolean isDecodeExtraction(CallSiteExtraction extraction) {
        var leaf = extraction instanceof CallSiteExtraction.NestedInputField nif ? nif.leaf() : extraction;
        return leaf instanceof CallSiteExtraction.NodeIdDecodeKeys;
    }

    public WalkerResult<UpdateRows> walk(
        GraphQLFieldDefinition field,
        TableRef table,
        List<InputField> inputFields,
        JooqCatalog catalog,
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

        // The bulk self-FK reject that used to live here (Stage 2b) is gone. A self-FK
        // @reference on a bulk (list-input) UPDATE routes exactly as the single-row form does — its
        // shared column lands in the UPDATE … SET c = v.c FROM (VALUES …) derived table, which the
        // bulk SET dedup now collapses to one v-column with a per-row WHERE∩SET agreement check.

        // Stage 3: union of every admitted field's target columns (all carriers, for diagnostics),
        // plus the identity-only subset (non-self-FK carriers) the key match is computed over.
        var inputColumns = new ArrayList<ColumnRef>();
        var inputColumnSqlNames = new LinkedHashSet<String>();
        var identityColumnSqlNames = new LinkedHashSet<String>();
        for (var c : contributions) {
            for (var col : c.columns()) {
                if (inputColumnSqlNames.add(col.sqlName())) {
                    inputColumns.add(col);
                }
                if (!c.selfReference()) {
                    identityColumnSqlNames.add(col.sqlName());
                }
            }
        }

        // Stage 4-5: PK-or-UK identification via the shared matcher. Find the
        // first candidate key (PK preferred) whose column set the input covers — over the identity
        // (non-self-FK) columns only. A self-FK points at a sibling row, so it can never
        // pin the row it lives on; a PK column reachable only through a self-FK fails coverage
        // (NoUniqueKeyCoverage), matching the semantic "your identity fields do not pin a key".
        MatchedKey matchedKey = MatchedKeys.firstCovered(catalog, table, identityColumnSqlNames).orElse(null);
        if (matchedKey == null) {
            return new WalkerResult.Err<>(List.of(
                new UpdateRowsError.NoUniqueKeyCoverage(
                    table.tableName(), inputColumns, MatchedKeys.candidates(catalog, table))));
        }

        // Stage 6: partition each admitted field into the WHERE (matched-key) or SET (everything
        // else) half. A self-FK reference carrier routes wholly to SET regardless of key
        // membership — its columns are a pointer to a sibling row, never this row's identity, so a
        // shared key column it writes is an ordinary SET write (the FK forces it equal to the WHERE
        // value, agreement-checked at emit). Every other carrier partitions by membership; a
        // genuine straddle (a cross-table FK reference whose lifted columns span the key boundary)
        // still rejects with MixedCarrierKeyMembership.
        var keySqlNames = sqlNameSet(matchedKey.columns());
        var setColumns = new ArrayList<SetColumn>();
        var keyColumns = new ArrayList<KeyColumn>();
        for (var c : contributions) {
            if (c.selfReference()) {
                for (var col : c.columns()) {
                    setColumns.add(new SetColumn(c.sdlFieldName(), col, c.extraction()));
                }
                continue;
            }
            var inKey = new ArrayList<ColumnRef>();
            var outsideKey = new ArrayList<ColumnRef>();
            for (var col : c.columns()) {
                (keySqlNames.contains(col.sqlName()) ? inKey : outsideKey).add(col);
            }
            if (!inKey.isEmpty() && !outsideKey.isEmpty()) {
                errors.add(new UpdateRowsError.MixedCarrierKeyMembership(c.sdlFieldName(), inKey, outsideKey));
                continue;
            }
            if (outsideKey.isEmpty()) {
                for (var col : c.columns()) {
                    keyColumns.add(new KeyColumn(c.sdlFieldName(), col, c.extraction()));
                }
            } else {
                for (var col : c.columns()) {
                    setColumns.add(new SetColumn(c.sdlFieldName(), col, c.extraction()));
                }
            }
        }
        if (!errors.isEmpty()) {
            return new WalkerResult.Err<>(errors);
        }

        // Stage 6b: two or more plain @field writers on one SET column silently
        // last-write-wins through the single-row Map.put (and crashes the bulk VALUES-join with a
        // duplicate derived column); reject at validate time, the UPDATE mirror of the INSERT-path
        // reject. An overlap involving a @nodeId decode is admitted and reconciled by the runtime
        // value-agreement check, so it is not caught here.
        var setByColumn = new java.util.LinkedHashMap<String, List<SetColumn>>();
        for (var sc : setColumns) {
            setByColumn.computeIfAbsent(sc.targetColumn().sqlName(), k -> new ArrayList<>()).add(sc);
        }
        for (var e : setByColumn.entrySet()) {
            var group = e.getValue();
            if (group.size() >= 2 && group.stream().noneMatch(sc -> isDecodeExtraction(sc.extraction()))) {
                errors.add(new UpdateRowsError.PlainColumnCollision(
                    group.get(0).sdlFieldName(), group.get(1).sdlFieldName(), e.getKey()));
            }
        }
        if (!errors.isEmpty()) {
            return new WalkerResult.Err<>(errors);
        }

        // Stage 7: UPDATE with nothing to set is structurally ill-formed.
        if (setColumns.isEmpty()) {
            return new WalkerResult.Err<>(List.of(
                new UpdateRowsError.NoSetFields(table.tableName(), matchedKey)));
        }

        // Stage 8: success.
        return new WalkerResult.Ok<>(new UpdateRows.Identified(matchedKey, setColumns, keyColumns));
    }

    /**
     * Flatten {@code fields} into {@link Contribution}s, descending into any
 * {@link InputField.NestingField} grouping input so a plain non-{@code @table} input that
     * groups columns of the outer table contributes the same flat leaf carriers it would at the
     * input root. A nested leaf's {@code extraction} is rewrapped as a
     * {@link CallSiteExtraction.NestedInputField} carrying the full SDL access path from the
     * {@code @table} argument root to the leaf, so the emitter descends the wire map; a top-level
     * leaf keeps its extraction unchanged (the access path is the single field name and the emit
     * stays byte-identical). The PK-or-UK match downstream runs over the flattened leaves' resolved
     * columns unchanged: a nested leaf's column counts toward key coverage exactly as a root leaf's.
     */
    private void classifyInto(
        List<InputField> fields, List<String> prefix, String outerArgName,
        List<Rejection.AuthorError> errors, List<Contribution> contributions
    ) {
        for (var f : fields) {
            switch (f) {
                case InputField.ColumnField c -> classifyColumnCarrier(
                    c.name(), c.list(), List.of(c.column()), wrap(c.extraction(), prefix, c.name(), outerArgName), false, c.condition(), c.location(), errors, contributions);
                case InputField.CompositeColumnField c -> classifyColumnCarrier(
                    c.name(), c.list(), c.columns(), wrap(c.extraction(), prefix, c.name(), outerArgName), false, c.condition(), c.location(), errors, contributions);
                case InputField.ColumnReferenceField c -> classifyColumnCarrier(
                    c.name(), c.list(), c.liftedSourceColumns(), wrap(c.extraction(), prefix, c.name(), outerArgName), c.selfReference(), c.condition(), c.location(), errors, contributions);
                case InputField.CompositeColumnReferenceField c -> classifyColumnCarrier(
                    c.name(), c.list(), c.liftedSourceColumns(), wrap(c.extraction(), prefix, c.name(), outerArgName), c.selfReference(), c.condition(), c.location(), errors, contributions);
                case InputField.UnboundField u -> {
                    if (u.condition().isPresent() && u.condition().get().override()) {
                        errors.add(new UpdateRowsError.OverrideConditionNotSupported(u.name(), u.location()));
                    } else {
                        errors.add(new UpdateRowsError.UnsupportedInputFieldShape(
                            u.name(), "UnboundField",
                            "the field binds no column and carries no @condition(override: true); "
                            + "UPDATE input fields must bind a column"));
                    }
                }
                case InputField.NestingField n -> {
                    if (n.list()) {
                        errors.add(new UpdateRowsError.UnsupportedInputFieldShape(
                            n.name(), "list-typed NestingField",
                            "list-typed nested input types (e.g. '" + n.name() + ": [" + n.typeName()
                            + "!]') on @mutation(typeName: UPDATE) fields are not yet supported (R186); "
                            + "a list grouping has no obvious meaning when flattening onto one outer row."));
                    } else if (n.condition().isPresent()) {
                        errors.add(new UpdateRowsError.UnsupportedInputFieldShape(
                            n.name(), "NestingField with @condition",
                            "@condition on a nested grouping input is not supported on "
                            + "@mutation(typeName: UPDATE); the filter would not be applied. Remove the directive."));
                    } else {
                        var childPrefix = new ArrayList<>(prefix);
                        childPrefix.add(n.name());
                        classifyInto(n.fields(), childPrefix, outerArgName, errors, contributions);
                    }
                }
                default ->
                    errors.add(new UpdateRowsError.UnsupportedInputFieldShape(
                        f.name(), f.getClass().getSimpleName(),
                        "input field shape is not a supported UPDATE input carrier"));
            }
        }
    }

    /**
     * Rewrap a leaf's call-site extraction so a nested leaf descends the wire map. Top-level leaves
     * ({@code prefix} empty) keep their extraction unchanged so the emit is byte-identical; nested
     * leaves get a {@link CallSiteExtraction.NestedInputField} with the full access path. The leaf
     * extraction (never itself a {@code NestedInputField}, since the classifier produces only
     * {@code Direct} / {@code NodeIdDecodeKeys} leaves) rides through as the {@code NestedInputField}
     * leaf, so deep nesting collapses to one carrier with a multi-segment path.
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
     * Reshape an admitted column carrier into a {@link Contribution}, unless it carries a
     * field-level {@code @condition}. Input-field conditions are not emitted on UPDATE, so a
     * condition would be silently dropped, the same footgun {@link UpdateRowsError.OverrideConditionNotSupported}
     * makes honest; reject rather than admit. An {@code override: true} condition reports through
     * that arm; any other condition (e.g. {@code override: false}) reports as an unsupported shape.
     */
    private void classifyColumnCarrier(
        String name, boolean list, List<ColumnRef> columns, CallSiteExtraction extraction,
        boolean selfReference, Optional<ArgConditionRef> condition, SourceLocation location,
        List<Rejection.AuthorError> errors, List<Contribution> contributions
    ) {
        if (list) {
            errors.add(new UpdateRowsError.UnsupportedInputFieldShape(
                name, "list-typed column carrier",
                "list-typed input field is not supported; list cardinality must live on the outer "
                + "@table argument, not on an individual input field."));
            return;
        }
        if (condition.isPresent()) {
            if (condition.get().override()) {
                errors.add(new UpdateRowsError.OverrideConditionNotSupported(name, location));
            } else {
                errors.add(new UpdateRowsError.UnsupportedInputFieldShape(
                    name, "column carrier with @condition",
                    "@condition on a @mutation(typeName: UPDATE) input field is not supported; the "
                    + "filter would not be applied. Remove the directive."));
            }
            return;
        }
        contributions.add(new Contribution(name, columns, extraction, selfReference));
    }

    private static Set<String> sqlNameSet(List<ColumnRef> columns) {
        var out = new LinkedHashSet<String>();
        for (var c : columns) out.add(c.sqlName());
        return out;
    }
}
