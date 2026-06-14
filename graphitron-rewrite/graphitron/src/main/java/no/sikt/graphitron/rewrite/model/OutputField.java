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

    /**
     * The {@code carrier} dimension (R299): the GraphQL parent-type category this field is defined on,
     * which <em>is</em> its field type. Defaulted per carrier root ({@link QueryField} →
     * {@link Carrier#Query}, {@link MutationField} → {@link Carrier#Mutation}, {@link ChildField} →
     * {@link Carrier#Source}); it is the legality gate over {@link #intent()} (write intents only on
     * {@code Mutation}, {@code NodeResolve} only on {@code Query}, {@code Nesting} only on
     * {@code Source}).
     */
    Carrier carrier();

    /**
     * The {@code intent} dimension (R299): the operation kind this field classifies to. Derived from
     * the leaf's identity plus the slots it already carries (the {@code DmlKind} discriminator on the
     * {@code @record} DML carriers); gated by {@link #carrier()}. R290 materialises this on the field
     * as the single home of the leaf-to-intent verdict the corpus harness asserts.
     */
    Intent intent();

    /**
     * The {@code mapping} dimension (R281): what domain object this field's value <em>is</em> (the
     * build-vs-consume / catalog-vs-service split). Derived from the leaf's identity plus its slots
     * (table-vs-connection off the return wrapper, encoded-vs-projected off the DML return expression).
     * R290 materialises this on the field.
     */
    Mapping mapping();

    /**
     * Re-fetch (the appendix's {@code RF}): a developer-{@code @service} or DML-write producer that
     * yields a catalog-{@link Mapping#Table} shape needs a follow-up SELECT to project the
     * {@code @table}, because the producer hands back a record/result rather than the projected
     * columns. <strong>Derived</strong> from {@link #intent()} × {@link #mapping()}, not switched on
     * leaf identity: a service or DML-write intent landing on a {@code Table} mapping. This is the
     * single home of the re-fetch predicate the service/DML fetcher arms used to each re-decide from
     * their own leaf type (R290); {@code GraphitronSchemaValidator} mirrors it against the generator's
     * actual re-fetch dispatch so the derivation and the emitter cannot drift.
     *
     * <p>Catalog {@link Intent#Fetch} reads (which read the table directly) and service/DML producers
     * that map to {@link Mapping#Record} / {@link Mapping#Column} are not re-fetches: the former needs
     * no producer round-trip, the latter hands back the consumed shape directly.
     */
    default boolean requiresReFetch() {
        if (mapping() != Mapping.Table) {
            return false;
        }
        return switch (intent()) {
            case QueryService, MutationService, Insert, Update, Upsert, Delete -> true;
            default -> false;
        };
    }

    /**
     * {@link Mapping#TableConnection} when the return wrapper is a Relay connection, else
     * {@link Mapping#Table}. The catalog-bound table mapping shared by every table-targeting leaf's
     * {@link #mapping()}.
     */
    static Mapping tableMapping(ReturnTypeRef.TableBoundReturnType returnType) {
        return returnType.wrapper() instanceof FieldWrapper.Connection ? Mapping.TableConnection : Mapping.Table;
    }

    /**
     * Polymorphic (interface/union/node) results are catalog-bound over participant tables, so they
     * map to {@link Mapping#Table} ({@link Mapping#TableConnection} when paginated) with the
     * participant set carried in a derived slot rather than as a distinct mapping value.
     */
    static Mapping polyMapping(ReturnTypeRef.PolymorphicReturnType returnType) {
        return returnType.wrapper() instanceof FieldWrapper.Connection ? Mapping.TableConnection : Mapping.Table;
    }

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
