package no.sikt.graphitron.rewrite.model;

import graphql.language.SourceLocation;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Sealed sub-family of {@link Rejection.AuthorError} for {@code UpdateRowsWalker}. Each typed
 * arm carries the structural data its diagnostic message and LSP {@code relatedInformation} need;
 * downstream tooling switches on the arm rather than parsing prose. Sibling to R238's
 * {@link ServiceMethodCallError}: per the dimensional-model-pivot principle, each walker slice adds
 * its own sub-seal of {@link Rejection.AuthorError} (and one row in that interface's {@code permits}
 * clause) rather than piling typed arms under the flat {@link Rejection.AuthorError.Structural}.
 *
 * <p>The arm-to-code mapping is exposed via {@link #lspCode()} under the
 * {@code graphitron.update-rows.} namespace so the LSP {@code Diagnostic} projector can read the
 * stable wire code without a separate dispatch table.
 */
public sealed interface UpdateRowsError extends Rejection.AuthorError permits
    UpdateRowsError.NoUniqueKeyCoverage,
    UpdateRowsError.NoSetFields,
    UpdateRowsError.MixedCarrierKeyMembership,
    UpdateRowsError.UnsupportedInputFieldShape,
    UpdateRowsError.OverrideConditionNotSupported,
    UpdateRowsError.PlainColumnCollision
{
    /** LSP wire code under the {@code graphitron.update-rows.} namespace. */
    String lspCode();

    @Override default Rejection prefixedWith(String prefix) {
        // Typed arms keep their structural components; prefixing is a no-op concerning structure.
        return this;
    }

    private static String sqlNames(List<ColumnRef> columns) {
        return columns.stream().map(ColumnRef::sqlName).collect(Collectors.joining(", ", "{", "}"));
    }

    private static String describeKey(MatchedKey key) {
        String kind = key instanceof MatchedKey.PrimaryKey ? "PK" : "UK";
        return kind + " '" + key.keyName() + "' " + sqlNames(key.columns());
    }

    /**
     * No primary key and no unique key had its column set covered by the input's columns. Names the
     * table, the columns the input contributes, and every candidate key the walker considered (with
     * its column set) so the author can see the shortfall. A table with no keys at all is the
     * degenerate {@code candidateKeys = []} case.
     */
    record NoUniqueKeyCoverage(
        String table,
        List<ColumnRef> inputColumns,
        List<MatchedKey> candidateKeys
    ) implements UpdateRowsError {
        public NoUniqueKeyCoverage {
            inputColumns = List.copyOf(inputColumns);
            candidateKeys = List.copyOf(candidateKeys);
        }
        @Override public String message() {
            var sb = new StringBuilder("@mutation(typeName: UPDATE) input for table '")
                .append(table).append("' covers no primary key or unique key. Input contributes ")
                .append(sqlNames(inputColumns)).append(". ");
            if (candidateKeys.isEmpty()) {
                sb.append("The table declares no primary key or unique key to identify rows by.");
            } else {
                sb.append("Candidate keys: ")
                    .append(candidateKeys.stream().map(UpdateRowsError::describeKey)
                        .collect(Collectors.joining("; ")))
                    .append(". Add the missing column(s) to the @table input so one key is covered.");
            }
            return sb.toString();
        }
        @Override public String lspCode() { return "graphitron.update-rows.no-unique-key-coverage"; }
    }

    /**
     * Every input field contributes to the matched key, leaving nothing to SET. UPDATE with an
     * empty SET clause is structurally ill-formed regardless of how well-pinned the WHERE is.
     */
    record NoSetFields(String table, MatchedKey matchedKey) implements UpdateRowsError {
        @Override public String message() {
            return "@mutation(typeName: UPDATE) input for table '" + table
                + "' has nothing to set; every input field contributes to matched key '"
                + matchedKey.keyName() + "'. Add at least one non-key column to the @table input.";
        }
        @Override public String lspCode() { return "graphitron.update-rows.no-set-fields"; }
    }

    /**
     * A single input field's lifted columns straddle the matched key: some are in the key, some
     * are outside it. Only possible on composite-reference shapes whose lifted source columns span
     * more than one column.
     */
    record MixedCarrierKeyMembership(
        String fieldName,
        List<ColumnRef> columnsInKey,
        List<ColumnRef> columnsOutsideKey
    ) implements UpdateRowsError {
        public MixedCarrierKeyMembership {
            columnsInKey = List.copyOf(columnsInKey);
            columnsOutsideKey = List.copyOf(columnsOutsideKey);
        }
        @Override public String message() {
            return "@mutation(typeName: UPDATE) input field '" + fieldName
                + "' lifts columns that straddle the matched key: " + sqlNames(columnsInKey)
                + " are in the key but " + sqlNames(columnsOutsideKey)
                + " are not. A single input field cannot span the WHERE and SET partitions.";
        }
        @Override public String lspCode() { return "graphitron.update-rows.mixed-carrier-key-membership"; }
    }

    /**
     * An input field is a {@code NestingField}, an {@code UnboundField} without
     * {@code @condition(override: true)}, or any other non-admitted carrier shape. Subsumes the
     * per-field rejection prose the legacy {@code MutationInputResolver} produced.
     */
    record UnsupportedInputFieldShape(
        String fieldName,
        String shape,
        String reason
    ) implements UpdateRowsError {
        @Override public String message() {
            return "@mutation(typeName: UPDATE) input field '" + fieldName + "' (" + shape
                + ") is not a supported UPDATE input shape: " + reason;
        }
        @Override public String lspCode() { return "graphitron.update-rows.unsupported-input-field-shape"; }
    }

    /**
     * An input field carries {@code @condition(override: true)}. R215's classifier admits this
     * shape today, but its emit-side wiring never landed, so the author's filter would silently
     * never run. R246 makes the deferral honest by rejecting with the field's name and the
     * directive's source location; re-admit when override-condition emit support lands.
     */
    record OverrideConditionNotSupported(
        String fieldName,
        SourceLocation conditionLocation
    ) implements UpdateRowsError {
        @Override public String message() {
            return "@condition(override: true) on the @mutation(typeName: UPDATE) input field '"
                + fieldName + "' is not yet emitted; the filter will not run. Remove the directive "
                + "or wait for override-condition emit support to land.";
        }
        @Override public String lspCode() { return "graphitron.update-rows.override-condition-not-supported"; }
    }

    /**
     * R322 (D2): two or more plain {@code @field} writers (no {@code @nodeId} decode among them) resolve
     * to one SET column. On single-row UPDATE the second {@code Map.put} silently last-write-wins; reject
     * at validate time, the UPDATE mirror of the INSERT-path / {@code @service} R336 reject. An overlap
     * involving a decode is admitted and reconciled by the runtime value-agreement check (D3), so it does
     * not reach this arm.
     */
    record PlainColumnCollision(
        String fieldA,
        String fieldB,
        String column
    ) implements UpdateRowsError {
        @Override public String message() {
            return "@mutation(typeName: UPDATE) input fields '" + fieldA + "' and '" + fieldB
                + "' both resolve to column '" + column + "' — two fields cannot populate one column;"
                + " remove one, or point its @field(name:) at a different column.";
        }
        @Override public String lspCode() { return "graphitron.update-rows.plain-column-collision"; }
    }
}
