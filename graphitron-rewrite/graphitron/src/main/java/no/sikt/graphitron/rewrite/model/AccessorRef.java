package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;

/**
 * A resolved reference to a typed zero-arg instance accessor on a class-backed parent's
 * backing class whose return type is a concrete jOOQ {@code TableRecord} (single, list, or set
 * cardinality, recorded by the surrounding {@link SourceKey#cardinality()} +
 * {@link LoaderRegistration#container()} rather than here).
 *
 * <p>Built by the auto-derivation pass in {@code FieldBuilder.classifyChildFieldOnResultType}
 * when a child field on a class-backed parent returns a {@code @table}-bound type and
 * the parent's backing class exposes a single matching accessor (name-and-shape rule documented
 * in that classifier method). Carried by {@link SourceKey.Reader.AccessorCall} for both
 * single-cardinality and list / set fields.
 *
 * <p>Sibling of {@link LifterRef} (developer-supplied static lifter producing a
 * {@code RowN<...>}). The two records sit at the same model layer, but their semantics differ:
 * {@code LifterRef} is a static method on a separate utility class returning the batch-key tuple
 * directly; {@code AccessorRef} is an instance method on the parent's backing class returning
 * one or many {@code TableRecord}s, from whose PK columns the batch-key tuple is projected at
 * emit time.
 *
 * <p>{@code parentBackingClass} is the fully resolved javapoet {@link ClassName} of the parent's
 * backing class — the cast target on {@code env.getSource()} in the emitted fetcher. Resolved at
 * the classifier boundary from {@code GraphitronType.ResultType.fqClassName()} so the emitter
 * never re-parses the binary class name. {@code methodName} is the simple instance-method name
 * on that class (no {@code get}/{@code is} normalisation; the literal Java identifier the
 * accessor declared). {@code elementClass} is the fully resolved javapoet {@link ClassName} of
 * the {@code TableRecord} subtype the accessor returns (in the {@code Many} case, the element
 * type of the returned {@code List<X>} / {@code Set<X>}; in the {@code Single} case, the bare
 * return type). Resolved at the classifier boundary from the reflection match so the emitter
 * has typed access to the element record without redoing reflection.
 *
 * <p>{@code AccessorRef} does not carry the container axis ({@code SINGLE} / {@code LIST} /
 * {@code SET}): the single / list-or-set split lives on {@link SourceKey#cardinality()}, and
 * the {@code List<X>} vs {@code Set<X>} split for the many case is not preserved on the model
 * because the emitter iterates any {@code Iterable}.
 */
public record AccessorRef(
    ClassName parentBackingClass,
    String methodName,
    ClassName elementClass
) {}
