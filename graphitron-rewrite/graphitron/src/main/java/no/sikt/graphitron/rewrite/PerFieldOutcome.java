package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.HelperRef;

import java.util.List;
import java.util.Optional;

/**
 * Builder-internal per-field outcome produced by the DELETE carrier projection step over a
 * {@code @table}-element payload-returning DELETE carrier (R156).
 *
 * <p>Each field on the element SDL type classifies into exactly one arm. The
 * {@code classifyDeleteTableProjection} step on {@link BuildContext} consumes the
 * {@code List<PerFieldOutcome>}, and either rejects the whole carrier (any
 * {@link NonPkNonNullable}, {@link ServiceField}, or {@link UnsupportedField} arm present, with
 * a diagnostic naming the offending fields) or projects the surviving outcomes to the narrow
 * model-facing {@link no.sikt.graphitron.rewrite.model.PkResolution}.
 *
 * <p>The five arms exist so the projection step can carry the certainty into the type system
 * that the rejection arms cannot reach the emitter. {@link PkResolution} (model-facing) admits
 * only the two non-rejecting arms; the projection step maps {@code PerFieldOutcome.PkRead} to
 * {@code PkResolution.PkRead} and {@code PerFieldOutcome.NonPkNullable} to
 * {@code PkResolution.NonPkNullable} with identical components. Duplicating the two arm names
 * across two sealed roots is the cost of the narrowing; the benefit is a compiler-enforced
 * exhaustive switch at the emitter with no defensive default and no observability of the
 * rejection arms downstream of the classifier.
 *
 * <p>This type is intentionally package-private — it does NOT escape the
 * {@code no.sikt.graphitron.rewrite} package. Consumers of the projection see only the narrow
 * {@link no.sikt.graphitron.rewrite.model.PkResolution} carried on
 * {@link no.sikt.graphitron.rewrite.model.ChildField.SingleRecordTableFieldFromReturning}.
 */
sealed interface PerFieldOutcome {

    /** The SDL field name on the element type. */
    String fieldName();

    /**
     * Field resolves to a PK column set on the input {@code @table}. {@code columns} carries the
     * resolved column refs in declaration order; {@code encode} carries the NodeId encoder when
     * the field is the SDL {@code id} alias on a {@code @node}-backed element type or a
     * {@code @nodeId} composite-PK projection, otherwise {@link Optional#empty()}.
     */
    record PkRead(String fieldName, List<ColumnRef> columns, Optional<HelperRef.Encode> encode)
            implements PerFieldOutcome {
        public PkRead {
            columns = List.copyOf(columns);
        }
    }

    /** Field maps to a non-PK column and is declared nullable in SDL; admits with silent-null. */
    record NonPkNullable(String fieldName) implements PerFieldOutcome {}

    /**
     * Field maps to a non-PK column and is declared non-nullable in SDL. The projection step
     * rejects the whole carrier with a diagnostic naming this field. After DELETE only the
     * row's PK is available; a non-nullable non-PK column field cannot resolve.
     */
    record NonPkNonNullable(String fieldName) implements PerFieldOutcome {}

    /**
     * Field is {@code @service}-resolved. The projection step rejects the whole carrier with a
     * diagnostic pointing the author at the ID-typed carrier shape (where the element type is
     * {@code ID} or {@code [ID!]} rather than a {@code @table}-projection element). Admitting a
     * service-resolved field on the element SDL type would open a silent-null hole: the service
     * receives a PK-only {@code Record} at runtime and any non-PK source param silently produces
     * {@code null}, with no classifier guarantee that the service's source params resolve to PK
     * columns. The strict reject closes the hole; authors who want to project service-backed
     * data on the deleted entity use the ID-typed carrier shape and resolve through a sibling
     * lookup.
     */
    record ServiceField(String fieldName) implements PerFieldOutcome {}

    /**
     * Field doesn't fit any of the four arms above (e.g. a child-collection
     * {@link no.sikt.graphitron.rewrite.model.ChildField.TableField}, a nested
     * {@link no.sikt.graphitron.rewrite.model.ChildField.RecordField}, a
     * {@link no.sikt.graphitron.rewrite.model.ChildField.ComputedField}). The projection step
     * rejects the whole carrier with a diagnostic naming the field and its leaf kind. After
     * DELETE the source is a PK-only RETURNING record; fields whose fetcher needs a follow-up
     * query or a non-column source value cannot resolve.
     *
     * <p>The arm is the catch-all that keeps {@code PerFieldOutcome} exhaustive over every
     * {@code GraphitronField} leaf the classifier might produce on the element type, and makes
     * the rejection an explicit type-system branch rather than an implicit default.
     */
    record UnsupportedField(String fieldName, String leafKind) implements PerFieldOutcome {}
}
