package no.sikt.graphitron.rewrite.model;

import graphql.language.SourceLocation;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Sealed sub-family of {@link Rejection.AuthorError} for {@code DeleteRowsWalker}. Each typed
 * arm carries the structural data its diagnostic message and LSP {@code relatedInformation} need;
 * downstream tooling switches on the arm rather than parsing prose. Sibling to
 * {@link UpdateRowsError}: per the dimensional-model-pivot principle, each walker slice adds its own
 * sub-seal of {@link Rejection.AuthorError} (and one row in that interface's {@code permits} clause)
 * rather than piling typed arms under the flat {@link Rejection.AuthorError.Structural}.
 *
 * <p>The arm set is {@link UpdateRowsError}'s minus the two arms DELETE's shape makes meaningless:
 * there is <b>no {@code NoSetFields}</b> (DELETE has no SET clause to be empty) and <b>no
 * {@code MixedCarrierKeyMembership}</b> (DELETE has no SET boundary for a composite carrier to
 * straddle — every admitted column is a WHERE filter). The three remaining arms map verbatim.
 *
 * <p>The arm-to-code mapping is exposed via {@link #lspCode()} under the
 * {@code graphitron.delete-rows.} namespace so the LSP {@code Diagnostic} projector can read the
 * stable wire code without a separate dispatch table.
 */
public sealed interface DeleteRowsError extends Rejection.AuthorError permits
    DeleteRowsError.NoUniqueKeyCoverage,
    DeleteRowsError.UnsupportedInputFieldShape,
    DeleteRowsError.OverrideConditionNotSupported
{
    /** LSP wire code under the {@code graphitron.delete-rows.} namespace. */
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
     * No primary key and no unique key had its column set covered by the input's columns, and the
     * mutation did not opt into {@code multiRow: true}. Names the table, the columns the input
     * contributes, and every candidate key the walker considered (with its column set) so the
     * author can see the shortfall. A table with no keys at all is the degenerate
     * {@code candidateKeys = []} case. Subsumes the legacy {@code table-has-no-pk} rejection.
     */
    record NoUniqueKeyCoverage(
        String table,
        List<ColumnRef> inputColumns,
        List<MatchedKey> candidateKeys
    ) implements DeleteRowsError {
        public NoUniqueKeyCoverage {
            inputColumns = List.copyOf(inputColumns);
            candidateKeys = List.copyOf(candidateKeys);
        }
        @Override public String message() {
            var sb = new StringBuilder("@mutation(typeName: DELETE) input for table '")
                .append(table).append("' covers no primary key or unique key. Input contributes ")
                .append(sqlNames(inputColumns)).append(". ");
            if (candidateKeys.isEmpty()) {
                sb.append("The table declares no primary key or unique key to identify rows by. "
                    + "Opt into broadcast semantics with multiRow: true on the @mutation directive "
                    + "to delete every matching row.");
            } else {
                sb.append("Candidate keys: ")
                    .append(candidateKeys.stream().map(DeleteRowsError::describeKey)
                        .collect(Collectors.joining("; ")))
                    .append(". Add the missing column(s) to the @table input so one key is covered, "
                    + "or opt into broadcast semantics with multiRow: true on the @mutation directive.");
            }
            return sb.toString();
        }
        @Override public String lspCode() { return "graphitron.delete-rows.no-unique-key-coverage"; }
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
    ) implements DeleteRowsError {
        @Override public String message() {
            return "@mutation(typeName: DELETE) input field '" + fieldName + "' (" + shape
                + ") is not a supported DELETE input shape: " + reason;
        }
        @Override public String lspCode() { return "graphitron.delete-rows.unsupported-input-field-shape"; }
    }

    /**
     * An input field carries {@code @condition(override: true)}. The classifier admits this
     * shape today, but its emit-side wiring never landed, so the author's filter would silently
     * never run. This rejection makes the deferral honest by rejecting with the field's name and the
     * directive's source location; re-admit when override-condition emit support lands.
     */
    record OverrideConditionNotSupported(
        String fieldName,
        SourceLocation conditionLocation
    ) implements DeleteRowsError {
        @Override public String message() {
            return "@condition(override: true) on the @mutation(typeName: DELETE) input field '"
                + fieldName + "' is not yet emitted; the filter will not run. Remove the directive "
                + "or wait for override-condition emit support to land.";
        }
        @Override public String lspCode() { return "graphitron.delete-rows.override-condition-not-supported"; }
    }
}
