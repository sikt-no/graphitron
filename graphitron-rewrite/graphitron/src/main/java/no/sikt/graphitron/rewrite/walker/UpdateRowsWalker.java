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
 * R246 — produces the {@link UpdateRows} carrier for an {@code @mutation(typeName: UPDATE)} field
 * that returns its {@code @table} type directly. The walker's load-bearing claim is PK-or-UK
 * identification: it queries jOOQ's {@code Table.getPrimaryKey()} / {@code Table.getKeys()} (via
 * {@link JooqCatalog#candidateKeys(String)}) and picks the first candidate (PK preferred) whose
 * column set is a subset of the input-covered columns, then partitions the input fields into the
 * WHERE (matched-key) and SET (everything else) halves.
 *
 * <p><b>Substrate concession (mirrors R238's {@code ServiceMethodCallWalker}).</b> The spec's ideal
 * is a walker reading {@code GraphQLFieldDefinition} + jOOQ catalog directly and re-deriving the
 * column classification from raw SDL. Re-running {@code InputFieldResolver} /
 * {@code EnumMappingResolver.buildLookupBindings} (with {@code @reference} FK-join and {@code @nodeId}
 * decode resolution) inside the walker would duplicate a substantial classifier. Following R238's
 * recorded "walker substrate concession on blast-radius grounds," this walker instead translates
 * over the already-classified {@link InputField} permits the upstream classifier produced. The
 * follow-up that retires the intermediate and reflects from SDL directly is tracked as a Backlog
 * item ({@code updaterows-walker-sdl-substrate}). The {@code field} parameter is reserved for that
 * future direct-SDL substrate; the current translator does not read it.
 *
 * <p>The new logic this walker owns — PK-or-UK subset matching, SET/WHERE partition by key
 * membership, empty-SET rejection, and the override-condition rejection — is fully expressible over
 * the classified permits plus the jOOQ keys, so the concession costs nothing in this slice's scope.
 * Errors are collected across stages without short-circuiting so the LSP surfaces every per-field
 * issue at once.
 */
public final class UpdateRowsWalker {

    /** A reshaped, column-bearing input field: the SDL field name, its target columns on the
     * input's own table, and the extraction shape the emitter reuses. */
    private record Contribution(String sdlFieldName, List<ColumnRef> columns, CallSiteExtraction extraction) {}

    public WalkerResult<UpdateRows> walk(
        GraphQLFieldDefinition field,
        TableRef table,
        List<InputField> inputFields,
        JooqCatalog catalog
    ) {
        var errors = new ArrayList<Rejection.AuthorError>();

        // Stage 2: classify each input field into a walker-local column contribution, collecting
        // per-field admissibility rejections across the loop (no short-circuit).
        var contributions = new ArrayList<Contribution>();
        for (var f : inputFields) {
            switch (f) {
                case InputField.ColumnField c -> classifyColumnCarrier(
                    c.name(), c.list(), List.of(c.column()), c.extraction(), c.condition(), c.location(), errors, contributions);
                case InputField.CompositeColumnField c -> classifyColumnCarrier(
                    c.name(), c.list(), c.columns(), c.extraction(), c.condition(), c.location(), errors, contributions);
                case InputField.ColumnReferenceField c -> classifyColumnCarrier(
                    c.name(), c.list(), c.liftedSourceColumns(), c.extraction(), c.condition(), c.location(), errors, contributions);
                case InputField.CompositeColumnReferenceField c -> classifyColumnCarrier(
                    c.name(), c.list(), c.liftedSourceColumns(), c.extraction(), c.condition(), c.location(), errors, contributions);
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
                case InputField.NestingField n ->
                    errors.add(new UpdateRowsError.UnsupportedInputFieldShape(
                        n.name(), "NestingField",
                        "nested input types in @mutation(typeName: UPDATE) fields are not yet supported"));
                default ->
                    errors.add(new UpdateRowsError.UnsupportedInputFieldShape(
                        f.name(), f.getClass().getSimpleName(),
                        "input field shape is not a supported UPDATE input carrier"));
            }
        }
        if (!errors.isEmpty()) {
            // Unadmitted fields make the covered-column set unreliable; surface every per-field
            // issue without muddying the result with a spurious key-coverage error.
            return new WalkerResult.Err<>(errors);
        }

        // Stage 3: union of every admitted field's target columns.
        var inputColumns = new ArrayList<ColumnRef>();
        var inputColumnSqlNames = new LinkedHashSet<String>();
        for (var c : contributions) {
            for (var col : c.columns()) {
                if (inputColumnSqlNames.add(col.sqlName())) {
                    inputColumns.add(col);
                }
            }
        }

        // Stage 4-5: PK-or-UK identification via the shared matcher (R266 extraction). Find the
        // first candidate key (PK preferred) whose column set the input covers.
        MatchedKey matchedKey = MatchedKeys.firstCovered(catalog, table, inputColumnSqlNames).orElse(null);
        if (matchedKey == null) {
            return new WalkerResult.Err<>(List.of(
                new UpdateRowsError.NoUniqueKeyCoverage(
                    table.tableName(), inputColumns, MatchedKeys.candidates(catalog, table))));
        }

        // Stage 6: partition each admitted field into the WHERE (matched-key) or SET (everything
        // else) half; mixed-membership composite carriers reject.
        var keySqlNames = sqlNameSet(matchedKey.columns());
        var setColumns = new ArrayList<SetColumn>();
        var keyColumns = new ArrayList<KeyColumn>();
        for (var c : contributions) {
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

        // Stage 7: UPDATE with nothing to set is structurally ill-formed.
        if (setColumns.isEmpty()) {
            return new WalkerResult.Err<>(List.of(
                new UpdateRowsError.NoSetFields(table.tableName(), matchedKey)));
        }

        // Stage 8: success.
        return new WalkerResult.Ok<>(new UpdateRows.Identified(matchedKey, setColumns, keyColumns));
    }

    /**
     * Reshape an admitted column carrier into a {@link Contribution}, unless it carries a
     * field-level {@code @condition}. R246 does not emit input-field conditions on UPDATE, so a
     * condition would be silently dropped, the same footgun {@link UpdateRowsError.OverrideConditionNotSupported}
     * makes honest; reject rather than admit. An {@code override: true} condition reports through
     * that arm; any other condition (e.g. {@code override: false}) reports as an unsupported shape.
     */
    private void classifyColumnCarrier(
        String name, boolean list, List<ColumnRef> columns, CallSiteExtraction extraction,
        Optional<ArgConditionRef> condition, SourceLocation location,
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
        contributions.add(new Contribution(name, columns, extraction));
    }

    private static Set<String> sqlNameSet(List<ColumnRef> columns) {
        var out = new LinkedHashSet<String>();
        for (var c : columns) out.add(c.sqlName());
        return out;
    }
}
