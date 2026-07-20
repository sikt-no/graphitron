package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * Sealed sub-family of {@link Rejection.AuthorError} for {@code @pivot} classification. Every
 * classifier decision that implies a pivot generator branch fails the build through one of these
 * typed arms when violated; each carries the structural data its diagnostic message and LSP
 * projection need, so downstream tooling switches on the arm rather than parsing prose.
 *
 * <p>The arm-to-code mapping is exposed via {@link #lspCode()} under the {@code graphitron.pivot.}
 * namespace, mirroring {@link ServiceMethodCallError}'s convention.
 */
public sealed interface PivotError extends Rejection.AuthorError permits
    PivotError.NonNullSlot,
    PivotError.NonScalarSlot,
    PivotError.DivergentSlotType,
    PivotError.VocabularyNotTextEnum,
    PivotError.SlotMissingFromVocabulary,
    PivotError.DuplicateSlotToken,
    PivotError.ColumnUnresolved,
    PivotError.ValueTypeMismatch,
    PivotError.ListReturn,
    PivotError.UnsupportedReferencePath,
    PivotError.RecordBackedParent,
    PivotError.InvalidProjectionType
{
    /** LSP wire code under the {@code graphitron.pivot.} namespace. */
    String lspCode();

    @Override default Rejection prefixedWith(String prefix) {
        // Typed arms keep their structural components; the renderer prepends author-facing
        // prose via diagnostic projection, not via Rejection#prefixedWith.
        return this;
    }

    /**
     * A non-null slot on the return type of a {@code @pivot} field. Pivot slots are filtered
     * aggregates that are SQL null whenever no row carries the token, and the generator cannot
     * know at build time whether any does, so every slot is inherently best-effort. Per
     * {@code @pivot} field: the same type reached only by plain nesting consumers carries no such
     * constraint.
     */
    record NonNullSlot(String slotName, String projectionTypeName) implements PivotError {
        @Override public String message() {
            return "slot '" + projectionTypeName + "." + slotName + "' must be nullable: a pivot "
                + "slot is a filtered aggregate that is null when no row carries its discriminator "
                + "token. The @pivot field itself may be non-null (one projection record always "
                + "exists per parent); only its slots must be nullable.";
        }
        @Override public String lspCode() { return "graphitron.pivot.non-null-slot"; }
    }

    /** A projection slot that is itself an object or list rather than a nullable scalar. */
    record NonScalarSlot(String slotName, String projectionTypeName) implements PivotError {
        @Override public String message() {
            return "slot '" + projectionTypeName + "." + slotName + "' must be a single-valued "
                + "scalar: every pivot slot projects one aggregate of the @pivot value column.";
        }
        @Override public String lspCode() { return "graphitron.pivot.non-scalar-slot"; }
    }

    /**
     * Slots on one projection type declaring different scalar types. All slots read the same
     * value column, so they must share one scalar type.
     */
    record DivergentSlotType(
        String slotName, String projectionTypeName, String expectedScalar, String actualScalar
    ) implements PivotError {
        @Override public String message() {
            return "slot '" + projectionTypeName + "." + slotName + "' declares scalar type '"
                + actualScalar + "' but sibling slots declare '" + expectedScalar + "'; all slots "
                + "of a pivot projection read the same value column and must share one scalar type.";
        }
        @Override public String lspCode() { return "graphitron.pivot.divergent-slot-type"; }
    }

    /** {@code vocabulary:} names a type that is not a text-mapped enum. */
    record VocabularyNotTextEnum(String vocabularyName) implements PivotError {
        @Override public String message() {
            return "@pivot(vocabulary: \"" + vocabularyName + "\") must name an enum type whose "
                + "values carry the discriminator tokens (via @field(name:) on the values, or the "
                + "value names themselves); '" + vocabularyName + "' is not such an enum.";
        }
        @Override public String lspCode() { return "graphitron.pivot.vocabulary-not-text-enum"; }
    }

    /** A slot whose SDL name is not a value of the named {@code vocabulary:} enum. */
    record SlotMissingFromVocabulary(
        String slotName, String vocabularyName, List<String> knownValues
    ) implements PivotError {
        public SlotMissingFromVocabulary { knownValues = List.copyOf(knownValues); }
        @Override public String message() {
            return "slot '" + slotName + "' has no matching value on the @pivot vocabulary enum '"
                + vocabularyName + "'; known values: " + String.join(", ", knownValues) + ".";
        }
        @Override public String lspCode() { return "graphitron.pivot.slot-missing-from-vocabulary"; }
    }

    /**
     * Two slots on one {@code @pivot} field resolving to the same discriminator token (two enum
     * values sharing a {@code @field(name:)}, or an identity collision): the pivot would emit two
     * identical aggregates under different aliases, always an authoring mistake. Per-{@code @pivot}
     * check, since the token map is the field's fact.
     */
    record DuplicateSlotToken(String token, List<String> slotNames) implements PivotError {
        public DuplicateSlotToken { slotNames = List.copyOf(slotNames); }
        @Override public String message() {
            return "slots " + String.join(", ", slotNames) + " all resolve to discriminator token '"
                + token + "'; each pivot slot must select a distinct token.";
        }
        @Override public String lspCode() { return "graphitron.pivot.duplicate-slot-token"; }
    }

    /** The {@code on:} or {@code value:} column does not resolve on the {@code @reference} terminus. */
    record ColumnUnresolved(
        String argumentName, String columnName, String tableName, List<String> candidates
    ) implements PivotError {
        public ColumnUnresolved { candidates = List.copyOf(candidates); }
        @Override public String message() {
            var sb = new StringBuilder("@pivot(").append(argumentName).append(": \"")
                .append(columnName).append("\") does not resolve to a column on the @reference "
                    + "terminus table '").append(tableName).append("'");
            if (!candidates.isEmpty()) {
                sb.append("; available columns: ").append(String.join(", ", candidates));
            }
            return sb.append('.').toString();
        }
        @Override public String lspCode() { return "graphitron.pivot.column-unresolved"; }
    }

    /**
     * The {@code value:} column's Java type cannot produce the slots' declared scalar type. The
     * projection type is reused across usages with different value columns, so this check is per
     * {@code @pivot} field, not per type.
     */
    record ValueTypeMismatch(
        String columnName, String columnClass, String declaredScalar
    ) implements PivotError {
        @Override public String message() {
            return "@pivot(value: \"" + columnName + "\") has Java type '" + columnClass
                + "', which does not map to the projection slots' declared scalar type '"
                + declaredScalar + "'.";
        }
        @Override public String lspCode() { return "graphitron.pivot.value-type-mismatch"; }
    }

    /** A {@code @pivot} field with a list return type: the projection is one record per parent. */
    record ListReturn(String fieldName) implements PivotError {
        @Override public String message() {
            return "@pivot field '" + fieldName + "' must be single-valued: the pivot projects "
                + "exactly one record per parent.";
        }
        @Override public String lspCode() { return "graphitron.pivot.list-return"; }
    }

    /**
     * A {@code @pivot} field whose {@code @reference} path is anything other than a single FK hop
     * (a multi-hop chain, or a condition-join hop). The batched delivery's one-record-per-parent
     * invariant requires the whole parent-input to terminus chain to be key-preserving, and v1
     * guarantees that only for the single-hop shape.
     */
    record UnsupportedReferencePath(String fieldName, String reason) implements PivotError {
        @Override public String message() {
            return "@pivot field '" + fieldName + "' requires a single foreign-key @reference hop "
                + "to the attribute table; " + reason + ".";
        }
        @Override public String lspCode() { return "graphitron.pivot.unsupported-reference-path"; }
    }

    /**
     * A {@code @pivot} field on a record-backed (class-backed) parent: inline correlation requires
     * a parent query to fold into, and the record-parent batched seam is a different capability.
     * The message deliberately does not suggest {@code @splitQuery}, which is lint-ignored as
     * redundant on record-backed parents.
     */
    record RecordBackedParent(String fieldName, String parentTypeName) implements PivotError {
        @Override public String message() {
            return "@pivot field '" + fieldName + "' sits on '" + parentTypeName + "', which is not "
                + "an SQL-backed (@table) type; @pivot requires a parent query to correlate the "
                + "aggregate projection with. Move the field to a @table-backed type.";
        }
        @Override public String lspCode() { return "graphitron.pivot.record-backed-parent"; }
    }

    /**
     * The return type of a {@code @pivot} field is not a plain output type usable as a projection:
     * it is a {@code @table} type, a non-object type, or otherwise fails the plain-nesting-target
     * gate.
     */
    record InvalidProjectionType(String fieldName, String returnTypeName, String reason) implements PivotError {
        @Override public String message() {
            return "@pivot field '" + fieldName + "' must return a plain output type whose fields "
                + "are the projection slots; '" + returnTypeName + "' " + reason + ".";
        }
        @Override public String lspCode() { return "graphitron.pivot.invalid-projection-type"; }
    }
}
