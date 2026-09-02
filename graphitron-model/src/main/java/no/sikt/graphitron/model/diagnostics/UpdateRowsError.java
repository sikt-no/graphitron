package no.sikt.graphitron.model.diagnostics;

import graphql.language.SourceLocation;
import no.sikt.graphitron.model.jooq.ColumnRef;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Sealed sub-family of {@link Rejection.AuthorError} for {@code UpdateRowsWalker}. Each typed
 * arm carries the structural data its diagnostic message and LSP {@code relatedInformation} need;
 * downstream tooling switches on the arm rather than parsing prose. Sibling to
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
    UpdateRowsError.NullableStraddlingReference,
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
                    .append(". Add the missing column(s) to the input type so one key is covered.");
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
                + matchedKey.keyName() + "'. Add at least one non-key column to the input type.";
        }
        @Override public String lspCode() { return "graphitron.update-rows.no-set-fields"; }
    }

    /**
     * A same-table composite {@code @nodeId} carrier's own columns straddle the matched key: some
     * are in the key, some are outside it.
     *
     * <p>Narrowed to the own-columns carrier. A carrier's own columns <em>are</em> this row's
     * identity, so writing half of them means moving the row, which is a different act from
     * re-pointing a sibling reference and is not what an UPDATE input asks for. Reference carriers
     * no longer reach this arm: a self-FK routes wholly to SET (its columns point at a sibling row,
     * never identity), and a cross-table FK partitions per column, its in-key half staying identity
     * and its out-of-key half becoming a SET write. The one cross-table shape still rejected is the
     * optional reference that is a matched-key column's only contributor, under
     * {@link NullableStraddlingReference}.
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
                + "' lifts its own key columns across the matched key: " + sqlNames(columnsInKey)
                + " are in the key but " + sqlNames(columnsOutsideKey)
                + " are not. These columns are this row's own identity, so writing only some of them"
                + " would move the row rather than update it. Split the field, or point it at a"
                + " foreign key with @reference so it re-points a sibling row instead.";
        }
        @Override public String lspCode() { return "graphitron.update-rows.mixed-carrier-key-membership"; }
    }

    /**
     * A <em>nullable</em> cross-table {@code @nodeId} reference field whose lifted foreign-key
     * columns straddle the matched key, and whose in-key half no other contributor supplies. The
     * straddle itself is admitted whatever the spelling; what this arm refuses is an optional field
     * being the only thing that pins a key column.
     *
     * <p>A straddling reference partitions per column: the out-of-key half is written, the in-key
     * half is the row's identity. Where nothing else supplies an in-key column, the reference is
     * itself the WHERE predicate for it, and an optional field cannot be load-bearing identity:
     * omitted, it leaves the row unidentifiable, and no per-row conditional recovers a WHERE
     * conjunct that was never sent. Where something else does supply it, the reference neither
     * filters nor writes that column and an explicit null clears the out-of-key half cleanly, so
     * there is nothing to refuse.
     *
     * <p>An <em>identity contributor</em> to a column is a carrier guaranteed present on every call
     * whose decode supplies, or can supply, that column's WHERE predicate: a whole carrier other
     * than a self-FK, or a non-null cross-table straddler lifting the column in its in-key half.
     * The columns this arm names are the ones with none.
     *
     * <p>This is a build-time reject rather than a runtime throw because the hazard is knowable from
     * the schema alone. It is a separate permit from {@link MixedCarrierKeyMembership} rather than a
     * widening of it, because {@link #lspCode()} is what downstream tooling switches on and "don't
     * straddle your own key" and "give that key column another contributor" are different fixes.
     *
     * <p>Carries the matched key and write target as well as the field, because the rejection is not
     * a property of the field alone: the same nullable reference is legal wherever its in-key half is
     * pinned, so the message has to be able to say why the same spelling is fine elsewhere.
     */
    record NullableStraddlingReference(
        String fieldName,
        SourceLocation location,
        String table,
        MatchedKey matchedKey,
        List<ColumnRef> unpinnedColumns,
        List<ColumnRef> columnsOutsideKey
    ) implements UpdateRowsError {
        public NullableStraddlingReference {
            unpinnedColumns = List.copyOf(unpinnedColumns);
            columnsOutsideKey = List.copyOf(columnsOutsideKey);
        }
        @Override public String message() {
            return "@mutation(typeName: UPDATE) input field '" + fieldName + "' on table '" + table
                + "' is an optional cross-table @nodeId reference whose foreign-key columns straddle "
                + describeKey(matchedKey) + ", and it is the only contributor to "
                + sqlNames(unpinnedColumns) + ". The in-key half of a straddling reference is this"
                + " row's identity, so an omitted value would leave nothing to find the row by."
                + " Either give " + sqlNames(unpinnedColumns) + " another contributor on this input"
                + " (a field or reference that is present on every call), or spell this field"
                + " non-null (ID!). The out-of-key half " + sqlNames(columnsOutsideKey)
                + " is not what refuses: a nullable straddling reference whose key columns are"
                + " pinned elsewhere is admitted, and an explicit null on it clears that half.";
        }
        @Override public String lspCode() { return "graphitron.update-rows.nullable-straddling-reference"; }
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
     * An input field carries {@code @condition(override: true)}. The classifier admits this
     * shape today, but its emit-side wiring never landed, so the author's filter would silently
     * never run. This arm makes the deferral honest by rejecting with the field's name and the
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
     * Two or more plain {@code @field} writers (no {@code @nodeId} decode among them) resolve
     * to one SET column. Rejected on this path's own two mechanisms: the single-row UPDATE's SET map
     * holds one value per column, so the second {@code Map.put} silently clobbers the first, and the
     * bulk path's VALUES join crashes outright on a duplicate derived column. An overlap involving a
     * decode is admitted and reconciled by the runtime value-agreement check, so it does not reach
     * this arm.
     *
     * <p>Not a general "one column takes one field" rule: on the {@code @service} jOOQ-record
     * parameter path, whose runtime is a presence-guarded per-column load, a shared write column is
     * admitted when the superseded fields carry {@code @deprecated}. The bulk VALUES-join crash is
     * what makes the same relaxation unsound here.
     */
    record PlainColumnCollision(
        String fieldA,
        String fieldB,
        String column
    ) implements UpdateRowsError {
        @Override public String message() {
            return "@mutation(typeName: UPDATE) input fields '" + fieldA + "' and '" + fieldB
                + "' both resolve to column '" + column + "'; the single-row SET map holds one value per"
                + " column, so the second write would silently clobber the first, and the bulk path's"
                + " VALUES join cannot name one derived column twice. Remove one, or point its"
                + " @field(name:) at a different column. (Declaring a shared write column through a"
                + " @deprecated alias is supported on @service jOOQ-record parameters, not on this path.)";
        }
        @Override public String lspCode() { return "graphitron.update-rows.plain-column-collision"; }
    }
}
