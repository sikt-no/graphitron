package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;

import java.util.List;

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
 * The {@code source} dimension: the field's <em>arrival endpoint</em>, a wrapper around a
     * {@link SourceShape} whose arm is the arrival cardinality. A <em>storage-free, derived</em> view of
     * the field's identity plus the {@code parentArrival} the caller supplies ({@link QueryField} →
     * {@link Source.Root.Query}, {@link MutationField} → {@link Source.Root.Mutation}, both ignoring the
     * argument since a root is the empty product; {@link ChildField} → {@link Source.OnlyChild} when the
     * parent's arrival is {@link Arrival#ONE}, else {@link Source.Child}). The {@code Query} /
     * {@code Mutation} split under {@link Source.Root} is the legality gate over {@link #operation()}
     * (write operations only on {@code Mutation}, {@link Operation.NodeResolve} only on {@code Query}).
     * The arm is the emit-strategy dispatch ({@link Source.Child} → DataLoader,
     * {@link Source.Root} / {@link Source.OnlyChild} → direct).
     *
 * <p>Arrival is a parent-typename-grain fact: every field on one parent folds the same arm,
     * so it is passed in rather than stored per-leaf (a parent-grain fact copied to child grain is the
     * derived-fact drift smell). Consumers read the fold through {@link GraphitronSchema#sourceOf}, which
     * threads the pre-computed {@code ArrivalIndex}; a leaf holding no ancestor fact cannot compute its
     * own arm.
     */
    Source source(Arrival parentArrival);

    /**
 * The {@code operation} dimension: the verb this field <em>performs</em>, a sealed
     * {@link Operation} arm carrying its own payload. Built by the leaf producers from the slots they
     * already carry ({@code QueryField} / {@code MutationField} / {@code ChildField} switch on leaf
     * identity); the verb-axis primitive (R316 retired the leaf-to-{@code intent} verdict).
     */
    Operation operation();

    /**
     * Builds the read-family {@link Operation} for a leaf with a resolved return wrapper:
     * {@link Operation.Paginate} (carrying the pagination window) when the wrapper is a Relay
     * connection, else {@link Operation.Fetch}. The "paginated" verb thus lives on the operation axis
     * (this {@code Paginate} vs {@code Fetch} split) while the connection <em>shape</em> lives on the
     * target axis, the decomposition of the fused {@code TableConnection} mapping.
     */
    static Operation readOperation(ReturnTypeRef returnType, List<WhereFilter> filters,
                                   OrderBySpec orderBy, PaginationSpec pagination) {
        if (returnType.wrapper() instanceof FieldWrapper.Connection) {
            return new Operation.Paginate(filters, orderBy, pagination);
        }
        return new Operation.Fetch(filters, orderBy);
    }

    /**
     * A bare {@link Operation.Fetch} with no filter surface: a column / scalar projection off an
     * already-arrived source. Its column-ness is a {@link Target} shape fact, not an operation fact.
     */
    static Operation bareFetch() {
        return new Operation.Fetch(List.of(), new OrderBySpec.None());
    }

    /** The {@link Operation.ServiceCall} for a child {@code @service} leaf (reflected {@link MethodRef}). */
    static Operation serviceCall(MethodRef method) {
        return new Operation.ServiceCall(new Operation.ServiceCall.Call.ReflectedMethod(method));
    }

    /** The {@link Operation.ServiceCall} for a root {@code @service} leaf (R238 {@link ServiceMethodCall}). */
    static Operation serviceCall(ServiceMethodCall call) {
        return new Operation.ServiceCall(new Operation.ServiceCall.Call.StructuredCall(call));
    }

    /**
 * The {@code target} dimension: the field's <em>projection endpoint</em>, a
     * {@link Target} wrapper ({@link Target.Single} / {@link Target.List}) around the
     * {@link TargetShape} it projects. Built by the leaf producers from the return wrapper plus the
     * leaf's shape slot; the projection-axis primitive (R316 retired the leaf-to-{@code mapping}
     * verdict).
     */
    Target target();

    /**
     * Re-fetch (the appendix's {@code RF}): the target {@code @table} must be re-projected from keys
     * held at the source, because the field holds a domain record rather than the projected columns.
     * <strong>Derived</strong> from a bare catalog {@link TargetShape.Table} target combined with
     * <em>holds-records</em>, not switched on leaf identity. "Holds records" is two cases on the
     * catalog-vs-domain split: the source <em>received</em> a record (a {@link ChildField} whose
     * {@link ChildField#sourceShape()} is {@link SourceShape#Record}, which the {@link Source.OnlyChild} /
     * {@link Source.Child} arm wraps regardless of arrival), or a service / DML-write
     * {@link Operation} <em>produced</em> one mid-field. Either way the field re-projects the table by
     * correlating the record's keys to the catalog rows (mechanically a {@code VALUES(idx, key...)} join
     * with {@code ORDER BY idx}).
     *
 * <p>Re-fetch is orthogonal to the {@link #operation()} verb: a field that re-fetches keeps
     * its own operation. A record-sourced {@code BatchedTableField} carrier (the former
     * {@code SingleRecordTableField}) keys off a producer record while its operation stays
     * {@link Operation.Fetch}; this is the single home of the re-fetch predicate the service/DML fetcher
 * arms used to each re-decide from their own leaf type. {@code GraphitronSchemaValidator}
     * mirrors it against the generator's actual re-fetch dispatch so the derivation and the emitter
     * cannot drift.
     *
     * <p>The guard is the bare {@link TargetShape.Table} shape, not the {@link TargetShape.Connection}
     * container that wraps it when paginated: a connection-shaped table field paginates rather than
     * re-projecting in this derivation's sense (the decomposed {@code TableConnection} mapping). A
     * catalog {@link Operation.Fetch} off a {@link SourceShape#Table} source reads the table directly
     * (no producer round-trip); producers whose target is a {@link TargetShape.Record} /
     * {@link TargetShape.Field} / {@link TargetShape.Column} hand back the consumed shape directly.
     * Neither re-fetches.
     */
    default boolean requiresReFetch() {
        if (!(target().shape() instanceof TargetShape.Table)) {
            return false;
        }
        // Read the received-record fact off the leaf's own source shape rather than off
        // source(): arrival (OnlyChild vs Child) is arrival-agnostic here (the retired switch treated
        // both nested arms identically), so requiresReFetch does not need the ancestor arrival the
        // arm now depends on. A RootField has no source shape and never received a record.
        boolean receivedRecord = this instanceof ChildField cf && cf.sourceShape() == SourceShape.Record;
        boolean producedRecord = switch (operation()) {
            case Operation.ServiceCall ignored -> true;
            case Operation.Insert ignored -> true;
            case Operation.Update ignored -> true;
            case Operation.Upsert ignored -> true;
            case Operation.Delete ignored -> true;
            default -> false;
        };
        return receivedRecord || producedRecord;
    }

    /**
     * Site-level reentry (R314): this coordinate's own emit includes the keyed re-query — the
     * {@code VALUES(idx, key...)} join re-projecting the target {@code @table} from keys held at
     * the source, with PK self-identity as the degenerate correlation. Distinct from
     * {@link #requiresReFetch()}, which is <em>value-level</em> ("this field's value is
     * re-projected somewhere"): a root {@code @service} field returning a {@code @table} type
     * hands the produced record straight through and its re-projection is realized by the
     * downstream child fetchers' {@code $fields}, so the value-level fact is true while no
     * re-query is emitted at the root site. The child {@code @service} arm, the record-sourced
     * batched arms, {@link ChildField.RecordTableMethodField}, and the projected DML arms all
     * emit the re-query at their own site.
     *
     * <p>Every site-level consumer — the reentry emit dispatch, the
     * {@link no.sikt.graphitron.rewrite.methodgraph.MethodCommandRegistry}'s covered-family
     * boundary, the validate-time reentry guard — reads this predicate rather than recomputing
     * {@code requiresReFetch() && !rootServicePassthrough} per site (two consumers evaluating
     * the same compound predicate over model fields is the drift {@code requiresReFetch}'s own
     * single-homing exists to prevent).
     */
    default boolean emitsKeyedReQuery() {
        if (!requiresReFetch()) {
            return false;
        }
        // The root service passthrough: ServiceCall on a RootField re-projects downstream,
        // never at its own site. Every other re-fetching coordinate (child ServiceCall,
        // record-sourced child, DML write) owns its keyed re-query.
        return !(operation() instanceof Operation.ServiceCall) || this instanceof ChildField;
    }

    /**
     * Wraps a {@link TargetShape} in the {@link Target} wrapper its GraphQL return wrapper dictates: a
     * Relay connection becomes {@code Single(Connection(shape))} (the connection is a single value whose
     * many-ness rides its {@code edges} / {@code nodes} fields), a list wrapper becomes
     * {@link Target.List}, and a single wrapper becomes {@link Target.Single}. The catalog-bound table /
     * polymorphic builder shared by every table-targeting leaf's {@link #target()}.
     */
    static Target wrap(FieldWrapper wrapper, TargetShape shape) {
        return switch (wrapper) {
            case FieldWrapper.Connection ignored -> new Target.Single(new TargetShape.Connection(shape));
            case FieldWrapper.List ignored -> new Target.List(shape);
            case FieldWrapper.Single ignored -> new Target.Single(shape);
        };
    }

    /**
     * A {@link Target.Single} of {@code shape}: the default wrapper for a leaf that carries no return
     * wrapper (a scalar column / property projection). The leaf does not model its own output
     * cardinality, so {@code Single} is the faithful read; the wrapper-fold invariant test (slice 5)
     * is where any list-shaped scalar leaf would surface.
     */
    static Target single(TargetShape shape) {
        return new Target.Single(shape);
    }

    /**
     * {@link Target.List} when the return wrapper is list-shaped, else {@link Target.Single}, never
     * {@link TargetShape.Connection}. The wrapper builder for the Java-side shapes
     * ({@link TargetShape.Record} / {@link TargetShape.Field}) and bare {@link TargetShape.Column}
     * reads, whose target shape is flat (never {@link TargetShape.Connection}) regardless of wrapper: a
     * Relay connection is a catalog-table shape, so it never wraps these.
     */
    static Target listOrSingle(FieldWrapper wrapper, TargetShape shape) {
        return wrapper.isList() ? new Target.List(shape) : new Target.Single(shape);
    }

    /**
     * The DML {@link Target}: the {@link DmlReturnExpression} arm encodes both the wrapper
     * (single vs list) and the shape (encoded-id {@link TargetShape.Column} vs projected
     * {@link TargetShape.Table}) directly, so the target reads from one switch without a
     * {@link FieldWrapper} lookup.
     */
    static Target dmlTarget(DmlReturnExpression expr) {
        return switch (expr) {
            case DmlReturnExpression.EncodedSingle ignored -> new Target.Single(new TargetShape.Column());
            case DmlReturnExpression.EncodedList ignored -> new Target.List(new TargetShape.Column());
            case DmlReturnExpression.ProjectedSingle ignored -> new Target.Single(new TargetShape.Table());
            case DmlReturnExpression.ProjectedList ignored -> new Target.List(new TargetShape.Table());
            // A discriminated-interface return re-projects the shared table just like a
            // projected @table return (Record / List<Record>); only the follow-up SELECT differs.
            case DmlReturnExpression.DiscriminatedSingle ignored -> new Target.Single(new TargetShape.Table());
            case DmlReturnExpression.DiscriminatedList ignored -> new Target.List(new TargetShape.Table());
        };
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
