package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;

/**
 * A classified field that emits a Java value (i.e. has a runtime resolver) and is therefore a
 * producer of its return type for its return type's child datafetchers. Sealed between
 * {@link GraphitronField} and the two output sub-hierarchies ({@link RootField} and
 * {@link ChildField}); {@link InputField} permits and {@link GraphitronField.UnclassifiedField}
 * sit outside this sub-interface because they have no resolver and therefore no
 * {@code env.getSource()} story.
 *
 * <p>R204: every leaf in {@link RootField} and {@link ChildField} answers
 * {@link #domainReturnType()} with the Java domain type its emitted resolver puts at
 * {@code env.getSource()} for the return type's child datafetchers. The validator's group-by
 * step over the classified field registry compares the answers across producers reaching the
 * same SDL return type; disagreement on the {@link DomainReturnType} sealed arm demotes every
 * producer in the group to {@link GraphitronField.UnclassifiedField} with a
 * {@link Rejection.AuthorError.MultiProducerDomainTypeDisagreement} payload.
 */
public sealed interface OutputField extends GraphitronField permits RootField, ChildField {

    /**
     * The Java domain type this producer puts at {@code env.getSource()} for its return type's
     * child datafetchers. The validator's structural-equality check rides on the
     * {@link DomainReturnType} sealed arm; relaxing the per-permit answer breaks the
     * uniform-domain-return-type invariant that lets the generator commit to a single Java
     * source type per child-field coord at emit time.
     */
    DomainReturnType domainReturnType();

    /** Anchor for "this permit has no concrete Java class on offer" — the unreached generic. */
    ClassName OBJECT_CLASS = ClassName.get(Object.class);
    /** Anchor for permits whose value is a scalar {@code String} (encoded NodeId carriers, etc.). */
    ClassName STRING_CLASS = ClassName.get(String.class);

    /**
     * Peels common single-arg container shapes ({@code Optional}, {@code CompletableFuture},
     * {@code List}, {@code Set}, {@code Collection}, {@code Result}) one level deep, returning
     * the inner element {@link ClassName} or {@link #OBJECT_CLASS} when the inner type is not a
     * bare class. Mirrors {@code RecordBindingResolver.peelReturnElement} on the javapoet axis;
     * used by {@code @service}-backed permits to derive their {@link DomainReturnType.Plain}
     * payload from {@link MethodRef#returnType()} without classloading.
     */
    static ClassName peelToClassName(TypeName t) {
        if (t instanceof ClassName cn) return cn;
        if (t instanceof ParameterizedTypeName ptn) {
            String raw = ptn.rawType().canonicalName();
            boolean unwrap =
                raw.equals("java.util.Optional")
                || raw.equals("java.util.concurrent.CompletableFuture")
                || raw.equals("java.util.List")
                || raw.equals("java.util.Set")
                || raw.equals("java.util.Collection")
                || raw.equals("org.jooq.Result");
            if (unwrap && ptn.typeArguments().size() == 1) {
                return peelToClassName(ptn.typeArguments().get(0));
            }
            return ptn.rawType();
        }
        return OBJECT_CLASS;
    }
}
