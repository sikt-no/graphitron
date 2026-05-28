package no.sikt.graphitron.rewrite.model;

/**
 * A field that delegates to a developer-provided {@code @service} method via a structured
 * {@link ServiceMethodCall} carrier.
 *
 * <p>Sibling to {@link MethodBackedField}, not a sub-interface. The four root sync service
 * permits ({@code QueryServiceTableField}, {@code QueryServiceRecordField},
 * {@code MutationServiceTableField}, {@code MutationServiceRecordField}) implement
 * {@link ServiceField} and stop implementing {@link MethodBackedField}. The carrier's
 * {@code Static} / {@code Instance} arms encode the call shape directly; consumers
 * pattern-match on {@code instanceof ServiceField} and read {@link #serviceMethodCall()}.
 *
 * <p>The slot is interface-required: every {@code ServiceField} carries a populated carrier.
 * The construction-time invariant is enforced by {@code FieldBuilder} via the orchestrator's
 * collect-Err-exclude-field flow — a field with an Err-state walker result never gets
 * constructed.
 */
public interface ServiceField {
    ServiceMethodCall serviceMethodCall();
}
