package no.sikt.graphitron.rewrite.model;

/**
 * Capability marker for classified SDL {@code input} types that produce a per-input-type
 * graphitron-emitted Java class (eventually a Java {@code record}). Declared on each
 * {@link GraphitronType.InputType} leaf.
 *
 * <p>One consumer reaches the slot via {@link #recordShape()}: {@code InputRecordGenerator} emits
 * one class per shape. Nothing reads the emitted classes; the validator pre-step in
 * {@code TypeFetcherGenerator} validates the class a {@code @service} parameter binds instead.
 */
public sealed interface HasInputRecordShape
    permits GraphitronType.JavaRecordInputType,
            GraphitronType.PojoInputType,
            GraphitronType.JooqRecordInputType,
            GraphitronType.JooqTableRecordInputType {

    /**
     * Pre-resolved shape for the emitted class: target class name plus one
     * {@link InputRecordShape.InputComponent} per SDL field. Never {@code null}; an input type that fails to
     * construct a shape surfaces as {@link GraphitronType.UnclassifiedType} via the existing
     * classifier fail-mode. The method name retains "record" because the emitted artifact
     * is meant to become a Java {@code record} verbatim.
     */
    InputRecordShape recordShape();
}
