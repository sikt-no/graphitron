package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.TypeName;

/**
 * One Java parameter slot's binding inside a {@link ServiceMethodCall}'s {@code methodArgs}
 * (or {@code ctorArgs}). Three flat arms — none recursive, each carrying its own
 * variant-specific component types. Consumers switch on the arm and read the precise
 * component.
 *
 * <p>{@code javaName} is the Java parameter name from {@code -parameters} reflection;
 * the emitter uses it both as the var-decl local name and as the identifier in the call's
 * actual-arg list.
 */
public sealed interface MappingEntry permits MappingEntry.FromArg, MappingEntry.FromContext, MappingEntry.FromDsl {

    /** GraphQL-argument-sourced slot. {@code shape}'s {@code javaType} is the slot's Java type. */
    record FromArg(String javaName, ValueShape shape) implements MappingEntry {}

    /**
     * Context-argument-sourced slot. {@code javaType} is the walker's own site-local reflection
     * of the Java parameter type; the emitter ignores it in favour of the agreed
     * {@code GraphitronSchema.contextArguments().resolved().get(key).javaType()} at emit time.
     */
    record FromContext(String javaName, TypeName javaType, String contextKey) implements MappingEntry {}

    /**
     * DSLContext-typed slot. The variant identity determines the Java type
     * ({@code DSLContext.class}) and the conventional local name ({@code "dsl"}); the variant
     * carries no fields.
     */
    record FromDsl() implements MappingEntry {}
}
