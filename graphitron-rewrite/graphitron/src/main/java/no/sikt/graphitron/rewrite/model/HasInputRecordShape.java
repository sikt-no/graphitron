package no.sikt.graphitron.rewrite.model;

/**
 * Capability marker for classified SDL {@code input} types that produce a per-input-type
 * graphitron-emitted Java record (the validation target lifted by R94). Declared on each
 * {@link GraphitronType.InputType} leaf and on {@link GraphitronType.TableInputType};
 * folding those siblings under a sealed {@code InputLikeType} parent so the capability
 * declaration becomes one site instead of five is tracked as R171
 * ({@code input-like-type-sealed-parent}, Backlog).
 *
 * <p>Two consumers reach the slot via {@link #recordShape()}:
 * {@code InputRecordGenerator} emits one Java record per shape, and the validator pre-step
 * in {@code TypeFetcherGenerator} materialises the record via the generated {@code fromMap}
 * factory and hands it to {@code jakarta.validation.Validator#validate}.
 */
public sealed interface HasInputRecordShape
    permits GraphitronType.JavaRecordInputType,
            GraphitronType.PojoInputType,
            GraphitronType.JooqRecordInputType,
            GraphitronType.JooqTableRecordInputType,
            GraphitronType.TableInputType {

    /**
     * Pre-resolved record shape: emitted class name plus one {@link InputComponent} per SDL
     * field. Never {@code null}; an input type that fails to construct a shape surfaces as
     * {@link GraphitronType.UnclassifiedType} via the existing classifier fail-mode (see the
     * {@code input-record.shape-from-input-type} load-bearing classifier check).
     */
    InputRecordShape recordShape();
}
