package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;

/**
 * A resolved reference to a zero-arg instance accessor on a class-backed parent's backing class
 * whose return type is a concrete jOOQ {@code TableRecord}, or a {@code List}/{@code Set} of one.
 *
 * <p>Built by the auto-derivation pass in {@code FieldBuilder.classifyChildFieldOnResultType}
 * when a child field on a class-backed parent returns a {@code @table}-bound type and the
 * parent's backing class exposes a single matching accessor (name-and-shape rule documented in
 * that classifier method). Carried by {@link KeyLift.Accessor}.
 *
 * <p>Sibling of {@link LifterRef}: a lifter is a developer-supplied static method on a separate
 * utility class returning the batch-key tuple directly; an accessor is an instance method on the
 * parent's backing class returning one or many {@code TableRecord}s, from whose PK columns the
 * batch-key tuple is projected at emit time. {@link StaticProducerRef} is this record's static
 * twin on the batched child {@code @service} path, where the author declares the producing method
 * instead of the classifier inferring one.
 *
 * <p>{@code parentBackingClass} is the javapoet {@link ClassName} of the parent's backing class,
 * the cast target on {@code env.getSource()} in the emitted fetcher; resolved at the classifier
 * boundary from {@code GraphitronType.ResultType.fqClassName()} so the emitter never re-parses
 * the binary class name. {@code methodName} is the literal declared instance-method name (no
 * {@code get}/{@code is} normalisation). {@code elementClass} is the {@link ClassName} of the
 * {@code TableRecord} subtype the accessor returns (in the many case, the element type of the
 * returned {@code List<X>}/{@code Set<X>}), resolved from the reflection match so the emitter
 * has typed access without redoing reflection.
 *
 * <p>Cardinality is not carried here: the single vs list-or-set split lives on
 * {@link KeyLift.Accessor#arity()} and {@link LoaderRegistration#container()}, and the
 * {@code List<X>} vs {@code Set<X>} split is not preserved because the emitter iterates any
 * {@code Iterable}.
 */
public record AccessorRef(
    ClassName parentBackingClass,
    String methodName,
    ClassName elementClass
) {}
