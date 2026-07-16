package no.sikt.graphitron.rewrite.model;

/**
 * Capability marker for classified SDL {@code input} types that produce a per-input-type
 * graphitron-emitted Java class (the validation target, eventually a Java {@code record} once
 * graphitron-javapoet gains record support). Declared on each
 * {@link GraphitronType.InputType} leaf and on {@link GraphitronType.TableInputType}.
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
     * {@link InputRecordShape.InputComponent} per SDL field. Never {@code null}; an input type that fails to
     * construct a shape surfaces as {@link GraphitronType.UnclassifiedType} via the existing
     * classifier fail-mode. The method name retains "record" pending the javapoet upgrade,
     * after which the emitted artifact becomes a Java {@code record} verbatim.
     */
    InputRecordShape recordShape();
}
