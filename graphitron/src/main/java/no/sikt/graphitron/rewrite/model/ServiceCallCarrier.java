package no.sikt.graphitron.rewrite.model;

/**
 * Holder for the two {@code @service} call carriers a service-call operation member holds
 * ({@link OperationMember.ServiceCall}): root {@code @service} leaves carry the structured
 * {@link ServiceMethodCall} invocation, child {@code @service} leaves a reflected
 * {@link MethodRef}. The arm names describe the carrier type held, not an operation
 * distinction; the difference tracks arrival position ({@link Source.Root} vs
 * {@link Source.Child}).
 */
public sealed interface ServiceCallCarrier {

    /** Root {@code @service} leaf: the {@link ServiceMethodCall} structured invocation. */
    record StructuredCall(ServiceMethodCall call) implements ServiceCallCarrier {}

    /** Child {@code @service} leaf: a reflected {@link MethodRef}. */
    record ReflectedMethod(MethodRef method) implements ServiceCallCarrier {}
}
