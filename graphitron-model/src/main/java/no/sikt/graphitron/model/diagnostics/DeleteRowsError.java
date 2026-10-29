package no.sikt.graphitron.model.diagnostics;

import graphql.language.SourceLocation;
import no.sikt.graphitron.model.jooq.ColumnRef;

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
 * straddle: every admitted column is a WHERE filter, or dropped where a node id covers the whole
 * matched key plus more). The three remaining arms map verbatim; the arms on answering a key that
 * matches no row are DELETE's own.
 *
 * <p>The arm-to-code mapping is exposed via {@link #lspCode()} under the
 * {@code graphitron.delete-rows.} namespace so the LSP {@code Diagnostic} projector can read the
 * stable wire code without a separate dispatch table.
 */
public sealed interface DeleteRowsError extends Rejection.AuthorError permits
    DeleteRowsError.NoUniqueKeyCoverage,
    DeleteRowsError.UnsupportedInputFieldShape,
    DeleteRowsError.OverrideConditionNotSupported,
    DeleteRowsError.IdempotentDeleteNeedsPrimaryKey,
    DeleteRowsError.IdempotentDeleteEchoUnencodable,
    DeleteRowsError.FailOnMissingNeedsKey
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
                    .append(". Add the missing column(s) to the input type so one key is covered, "
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

    /**
     * A DELETE answers a key that matches no row as already deleted, building that key's answer
     * from the input, but its input does not identify rows by exactly the primary key: it covers
     * only a unique key, contributes a column beyond the primary key, or binds a primary-key column
     * twice. The answer for a missing row is the primary key, which only an input supplying the
     * whole primary key can build, and an extra filter column would let the answer claim a deletion
     * that did not happen. A node id covering the primary key plus more is admitted, since its
     * other columns are dropped rather than filtered on. Names the table's primary key (empty for a
     * table without one) and the input's columns.
     */
    record IdempotentDeleteNeedsPrimaryKey(
        String table,
        List<ColumnRef> inputColumns,
        List<ColumnRef> primaryKeyColumns
    ) implements DeleteRowsError {
        public IdempotentDeleteNeedsPrimaryKey {
            inputColumns = List.copyOf(inputColumns);
            primaryKeyColumns = List.copyOf(primaryKeyColumns);
        }
        @Override public String message() {
            var sb = new StringBuilder("@mutation(typeName: DELETE) on table '").append(table)
                .append("' answers a key that matches no row as deleted, which needs the input to "
                    + "bind exactly the primary key, each primary-key column once and no other "
                    + "column. Input contributes ")
                .append(sqlNames(inputColumns)).append("; ");
            if (primaryKeyColumns.isEmpty()) {
                sb.append("the table declares no primary key. ");
            } else {
                sb.append("the primary key is ").append(sqlNames(primaryKeyColumns)).append(". ");
            }
            return sb.append("Change the input to bind exactly the primary key, or add "
                + "failOnMissing: true to fail the call on a key that matches no row. A node id "
                + "whose key columns are the whole primary key plus more also qualifies: it filters "
                + "on the primary key alone.").toString();
        }
        @Override public String lspCode() { return "graphitron.delete-rows.idempotent-delete-needs-primary-key"; }
    }

    /**
     * A DELETE answers a key that matches no row as already deleted, but its response ids encode a
     * node key reaching beyond the primary key, while the input supplies no value for some of those
     * extra columns. The input identifies rows by exactly the primary key, so a key that matches a
     * row deletes it and the id is read back from the deleted row; but a key that matches no row is
     * answered from the input, and there is nothing to encode its extra columns from. Separate from
     * {@link IdempotentDeleteNeedsPrimaryKey} because the fix differs: key the input by the node id
     * rather than narrow it to the primary key. Names the table, the response's node type, the
     * input's columns and the node-key columns no input field supplies.
     */
    record IdempotentDeleteEchoUnencodable(
        String table,
        String nodeTypeName,
        List<ColumnRef> inputColumns,
        List<ColumnRef> unsuppliedColumns
    ) implements DeleteRowsError {
        public IdempotentDeleteEchoUnencodable {
            inputColumns = List.copyOf(inputColumns);
            unsuppliedColumns = List.copyOf(unsuppliedColumns);
        }
        @Override public String message() {
            return "@mutation(typeName: DELETE) on table '" + table
                + "' answers a key that matches no row with a '" + nodeTypeName + "' id, whose node "
                + "key also encodes " + sqlNames(unsuppliedColumns) + ", but the input contributes "
                + "only " + sqlNames(inputColumns) + ", so a missing row's id has nothing to encode "
                + "those columns from. Key the input by the '" + nodeTypeName + "' node id instead, "
                + "or add failOnMissing: true.";
        }
        @Override public String lspCode() { return "graphitron.delete-rows.idempotent-delete-echo-unencodable"; }
    }

    /**
     * {@code failOnMissing: true} on a {@code multiRow: true} DELETE whose input covers no primary
     * key or unique key. Such a DELETE deletes whatever its filter matches; a filter that matches
     * nothing deleted nothing, and there is no key to report missing. Names the table and the
     * input's columns.
     */
    record FailOnMissingNeedsKey(
        String table,
        List<ColumnRef> inputColumns
    ) implements DeleteRowsError {
        public FailOnMissingNeedsKey {
            inputColumns = List.copyOf(inputColumns);
        }
        @Override public String message() {
            return "@mutation(typeName: DELETE, multiRow: true, failOnMissing: true) input for table '"
                + table + "' covers no primary key or unique key (it contributes "
                + sqlNames(inputColumns) + "), so it deletes whatever its filter matches and names "
                + "no key that could be missing. Remove failOnMissing: true, or add the column(s) "
                + "of a primary key or unique key to the input.";
        }
        @Override public String lspCode() { return "graphitron.delete-rows.fail-on-missing-needs-key"; }
    }
}
