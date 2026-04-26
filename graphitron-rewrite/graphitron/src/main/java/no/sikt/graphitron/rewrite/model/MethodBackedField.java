package no.sikt.graphitron.rewrite.model;

/**
 * A field that delegates to a user-provided Java method reference.
 *
 * <p>Implemented by all field variants that carry a {@link MethodRef}:
 * {@link ChildField.TableMethodField}, {@link ChildField.ServiceTableField},
 * {@link ChildField.ServiceRecordField}, {@link QueryField.QueryTableMethodTableField},
 * {@link QueryField.QueryServiceTableField}, {@link QueryField.QueryServiceRecordField},
 * {@link MutationField.MutationServiceTableField}, {@link MutationField.MutationServiceRecordField}.
 *
 * <p>This interface is intentionally standalone (does not extend {@link GraphitronField}) so that
 * it can be applied as an orthogonal capability without being restricted by the sealed hierarchy.
 * Generators receive {@link GraphitronField} and pattern-match with {@code instanceof MethodBackedField}.
 *
 * <p>The generator uses {@code mbf.method().callParams()} for argument extraction, giving a single
 * code path for all method-backed variants instead of per-variant branches.
 */
public interface MethodBackedField {
    MethodRef method();
}
