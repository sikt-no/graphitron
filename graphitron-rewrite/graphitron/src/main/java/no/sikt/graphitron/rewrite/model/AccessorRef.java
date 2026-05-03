package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;

/**
 * A resolved reference to a typed zero-arg instance accessor on an {@code @record} parent's
 * backing class whose return type is a concrete jOOQ {@code TableRecord} (single, list, or set
 * cardinality, recorded by the surrounding {@link BatchKey} variant rather than here).
 *
 * <p>Built by the auto-derivation pass in {@code FieldBuilder.classifyChildFieldOnResultType}
 * when a child field on a {@code @record}-typed parent returns a {@code @table}-bound type and
 * the parent's backing class exposes a single matching accessor (name-and-shape rule documented
 * in that classifier method). Carried by {@link BatchKey.AccessorRowKeyedSingle} for
 * single-cardinality fields and {@link BatchKey.AccessorRowKeyedMany} for list / set fields.
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
 * {@code SET}): the single / list-or-set split lives on the variant identity
 * ({@link BatchKey.AccessorRowKeyedSingle} vs {@link BatchKey.AccessorRowKeyedMany}); the
 * {@code List<X>} vs {@code Set<X>} split inside {@code Many} is not preserved on the model
 * because the emitter iterates any {@code Iterable}. Per the {@code rewrite-design-principles.adoc}
 * rule on narrow component types, an {@code AccessorRef} flowing through {@code Single} cannot
 * be misread as carrying a list / set marker.
 */
public record AccessorRef(
    ClassName parentBackingClass,
    String methodName,
    ClassName elementClass
) {}
