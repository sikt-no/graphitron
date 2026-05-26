package no.sikt.graphitron.rewrite.model;

/**
 * Capability marker for classified SDL {@code input} types that produce a per-input-type
 * graphitron-emitted Java class (the validation target lifted by R94 — eventually a Java
 * {@code record} once R174 lands graphitron-javapoet's record support). Declared on each
 * {@link GraphitronType.InputType} leaf and on {@link GraphitronType.TableInputType};
 * folding those siblings under a sealed {@code InputLikeType} parent so the capability
 * declaration becomes one site instead of five is tracked as R171
 * ({@code input-like-type-sealed-parent}, Backlog).
 *
 * <p>Two consumers reach the slot via {@link #recordShape()}:
 * {@code InputRecordGenerator} emits one class per shape, and the validator pre-step in
 * {@code TypeFetcherGenerator} materialises the carrier via the generated {@code fromMap}
 * factory and hands it to {@code jakarta.validation.Validator#validate}.
 */
public sealed interface HasInputRecordShape
    permits GraphitronType.JavaRecordInputType,
            GraphitronType.PojoInputType,
            GraphitronType.JooqRecordInputType,
            GraphitronType.JooqTableRecordInputType,
            GraphitronType.TableInputType {

    /**
     * Pre-resolved shape for the emitted class: target class name plus one
     * {@link InputComponent} per SDL field. Never {@code null}; an input type that fails to
     * construct a shape surfaces as {@link GraphitronType.UnclassifiedType} via the existing
     * classifier fail-mode. The method name retains "record" pending R174's javapoet upgrade,
     * after which the emitted artifact becomes a Java {@code record} verbatim.
     */
    InputRecordShape recordShape();
}
