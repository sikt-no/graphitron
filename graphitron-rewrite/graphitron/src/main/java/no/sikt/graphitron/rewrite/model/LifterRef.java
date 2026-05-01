package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;

/**
 * A resolved reference to a developer-supplied static lifter method that extracts a
 * batch-key {@code RowN<...>} value from an {@code @record} parent's backing class.
 *
 * <p>Sibling of {@link MethodRef} and {@link HelperRef}. {@code MethodRef} models user-authored
 * methods reached via the {@code ParamSource} indirection ({@code @service}, {@code @condition},
 * {@code @tableMethod}); {@code HelperRef} models methods Graphitron itself emits;
 * {@code LifterRef} models the narrow case of a {@code @batchKeyLifter} static method whose
 * call-site signature is exactly {@code (ParentBackingClass) -> RowN<...>}. Reusing
 * {@code MethodRef.Basic} would force {@code params}/{@code returnType} slots that are
 * fully recoverable from the {@link BatchKey.LifterRowKeyed#hop()} columns, and would risk
 * a lifter ref flowing through {@code MethodRef}-walking call sites (e.g. {@code ArgCallEmitter})
 * that have no semantics for it. Following the in-tree {@link HelperRef} precedent: a typed
 * sibling whose only components are the pre-resolved {@link ClassName} plus the simple method
 * name.
 *
 * <p>{@code declaringClass} is the fully resolved javapoet {@link ClassName} (binary class name
 * already turned into a {@code ClassName} at the resolver boundary, never re-parsed at emit
 * time). {@code methodName} is the simple static-method name on that class.
 */
public record LifterRef(ClassName declaringClass, String methodName) {
}
