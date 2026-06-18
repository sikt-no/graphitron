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
 * <p>R204 / R279 slice 4: every leaf in {@link RootField} and {@link ChildField} answers
 * {@link #domainReturnType()} with the Java domain type its emitted resolver puts at
 * {@code env.getSource()} for the return type's child datafetchers. The builder's group-by step
 * over the classified field registry compares the answers across producers reaching the same SDL
 * return type; disagreement on the {@link DomainReturnType} sealed arm is recorded on the
 * {@link no.sikt.graphitron.rewrite.GraphitronSchema} as a
 * {@link Rejection.AuthorError.MultiProducerDomainTypeDisagreement}, which
 * {@code GraphitronSchemaValidator.validateUniformDomainReturnType} surfaces as a build error
 * (slice 4 retired the post-pass that previously demoted the producers to
 * {@link GraphitronField.UnclassifiedField}).
 */
public sealed interface OutputField extends GraphitronField permits RootField, ChildField {

    /**
     * The Java domain type this producer puts at {@code env.getSource()} for its return type's
     * child datafetchers. The uniform-domain-return-type check rides on the
     * {@link DomainReturnType} sealed arm; relaxing the per-permit answer breaks the
     * invariant that lets the generator commit to a single Java source type per child-field coord
     * at emit time.
     */
    DomainReturnType domainReturnType();

    /**
     * The {@code source} dimension (R316): the field's <em>arrival endpoint</em>, a wrapper around a
     * {@link SourceShape} whose arm is the arrival cardinality. Defaulted per arrival ({@link QueryField}
     * → {@link Source.Root.Query}, {@link MutationField} → {@link Source.Root.Mutation}, {@link ChildField}
     * → {@link Source.Child}); the {@code Query} / {@code Mutation} split under {@link Source.Root} is the
     * legality gate over {@link #intent()} (write intents only on {@code Mutation}, {@code NodeResolve}
     * only on {@code Query}). The arm is the emit-strategy dispatch ({@link Source.Child} → DataLoader,
     * {@link Source.Root} / {@link Source.OnlyChild} → direct).
     */
    Source source();

    /**
     * The retired {@code carrier} dimension (R299), derived from {@link #source()} during the R316
     * slice-2 additive cutover so the R281 corpus keeps classifying unchanged until slice 4 migrates it
     * onto {@code source()}. {@link Carrier} and {@link SourceCardinality} retire with that cutover; new
     * readers should consume {@link #source()} directly.
     */
    default Carrier carrier() {
        return switch (source()) {
            case Source.Root.Query ignored -> new Carrier.Query();
            case Source.Root.Mutation ignored -> new Carrier.Mutation();
            case Source.OnlyChild(var shape) -> new Carrier.Source(shape, SourceCardinality.One);
            case Source.Child(var shape) -> new Carrier.Source(shape, SourceCardinality.Many);
        };
    }

    /**
     * The {@code intent} dimension (R299): the operation kind this field classifies to. Derived from
     * the leaf's identity plus the slots it already carries (the {@code DmlKind} discriminator on the
     * record-backed DML carriers); gated by {@link #carrier()}. R290 materialises this on the field
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
     * Re-fetch (the appendix's {@code RF}): the target {@code @table} must be re-projected from keys
     * held at the source, because the field holds a domain record rather than the projected columns.
     * <strong>Derived</strong> from {@link Mapping#Table} combined with <em>holds-records</em>, not
     * switched on leaf identity. "Holds records" is two cases on the catalog-vs-domain split: the
     * source <em>received</em> a record (a {@link Source.OnlyChild} / {@link Source.Child} arm with
     * {@link SourceShape#Record}), or a service / DML-write intent <em>produced</em> one mid-field.
     * Either way the field re-projects the
     * table by correlating the record's keys to the catalog rows (mechanically a {@code VALUES(idx,
     * key...)} join with {@code ORDER BY idx}).
     *
     * <p>Re-fetch is orthogonal to {@link #intent()} (R305): a field that re-fetches keeps its own
     * intent. A record-source {@code RecordTableField} carrier (the former {@code SingleRecordTableField}) keys off a producer record while its intent stays
     * {@code Fetch}; this is the single home of the re-fetch predicate the service/DML fetcher arms
     * used to each re-decide from their own leaf type (R290). {@code GraphitronSchemaValidator} mirrors
     * it against the generator's actual re-fetch dispatch so the derivation and the emitter cannot
     * drift.
     *
     * <p>Catalog {@link Intent#Fetch} reads off a {@link SourceShape#Table} source read the table
     * directly (no producer round-trip); producers that map to {@link Mapping#Record} /
     * {@link Mapping#Column} hand back the consumed shape directly. Neither re-fetches.
     */
    default boolean requiresReFetch() {
        if (mapping() != Mapping.Table) {
            return false;
        }
        boolean receivedRecord = switch (source()) {
            case Source.OnlyChild(var shape) -> shape == SourceShape.Record;
            case Source.Child(var shape) -> shape == SourceShape.Record;
            case Source.Root ignored -> false;
        };
        boolean producedRecord = switch (intent()) {
            case QueryService, MutationService, Insert, Update, Upsert, Delete -> true;
            default -> false;
        };
        return receivedRecord || producedRecord;
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
