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
     * The {@code operation} dimension (R316): the verb this field <em>performs</em>, a sealed
     * {@link Operation} arm carrying its own payload. Built by the leaf producers from the slots they
     * already carry ({@code QueryField} / {@code MutationField} / {@code ChildField} switch on leaf
     * identity); the new primitive of the verb axis, replacing the leaf-to-{@link Intent} verdict.
     */
    Operation operation();

    /**
     * The retired {@code intent} dimension (R299), derived from {@link #operation()} during the R316
     * slice-3 additive cutover so the R281 corpus keeps classifying unchanged until slice 4 migrates it
     * onto {@code operation()}. The read/write split {@link Operation.ServiceCall} collapses is
     * recovered from {@link #source()} (the {@link Source.Root.Query} / {@link Source.Root.Mutation}
     * legality gate), not from leaf identity, so the collapse is real and not shadowed. {@link Intent}
     * retires with that cutover; new readers should consume {@link #operation()} directly.
     */
    default Intent intent() {
        return switch (operation()) {
            case Operation.Fetch ignored -> Intent.Fetch;
            case Operation.Paginate ignored -> Intent.Fetch;
            case Operation.Lookup ignored -> Intent.Lookup;
            case Operation.ServiceCall ignored -> switch (source()) {
                case Source.Root.Mutation ignored2 -> Intent.MutationService;
                default -> Intent.QueryService;
            };
            case Operation.Count ignored -> Intent.Count;
            case Operation.Facet ignored -> Intent.Facet;
            case Operation.Nest ignored -> Intent.Nesting;
            case Operation.NodeResolve ignored -> Intent.NodeResolve;
            case Operation.EntityResolve ignored -> Intent.EntityResolve;
            case Operation.Insert ignored -> Intent.Insert;
            case Operation.Upsert ignored -> Intent.Upsert;
            case Operation.Update ignored -> Intent.Update;
            case Operation.Delete ignored -> Intent.Delete;
            case Operation.UpdateMatching ignored -> Intent.UpdateMatching;
            case Operation.DeleteMatching ignored -> Intent.DeleteMatching;
        };
    }

    /**
     * Builds the read-family {@link Operation} for a leaf with a resolved return wrapper:
     * {@link Operation.Paginate} (carrying the pagination window) when the wrapper is a Relay
     * connection, else {@link Operation.Fetch}. The "paginated" verb thus lives on the operation axis
     * (this {@code Paginate} vs {@code Fetch} split) while the connection <em>shape</em> lives on the
     * target axis, the decomposition of the fused {@code Mapping.TableConnection}.
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
     * The {@code target} dimension (R316): the field's <em>projection endpoint</em>, a
     * {@link Target} wrapper ({@link Target.Single} / {@link Target.List}) around the
     * {@link TargetShape} it projects. Built by the leaf producers from the return wrapper plus the
     * leaf's shape slot; the new primitive of the projection axis, replacing the leaf-to-{@link Mapping}
     * verdict.
     */
    Target target();

    /**
     * The retired {@code mapping} dimension (R281), derived from {@link #target()} during the R316
     * slice-3 additive cutover so the R281 corpus keeps classifying unchanged until slice 4 migrates it
     * onto {@code target()}. The polymorphic shapes ({@link TargetShape.Interface} /
     * {@link TargetShape.Union}) are catalog-bound today, so they derive {@link Mapping#Table}; the
     * {@link TargetShape.Connection} container derives {@link Mapping#TableConnection} (the decomposed
     * {@code Mapping.TableConnection}). {@link Mapping} retires with that cutover; new readers should
     * consume {@link #target()} directly.
     */
    default Mapping mapping() {
        return switch (target().shape()) {
            case TargetShape.Connection ignored -> Mapping.TableConnection;
            case TargetShape.Column ignored -> Mapping.Column;
            case TargetShape.Field ignored -> Mapping.Field;
            case TargetShape.Record ignored -> Mapping.Record;
            case TargetShape.Table ignored -> Mapping.Table;
            case TargetShape.Interface ignored -> Mapping.Table;
            case TargetShape.Union ignored -> Mapping.Table;
        };
    }

    /**
     * Re-fetch (the appendix's {@code RF}): the target {@code @table} must be re-projected from keys
     * held at the source, because the field holds a domain record rather than the projected columns.
     * <strong>Derived</strong> from a bare catalog {@link TargetShape.Table} target combined with
     * <em>holds-records</em>, not switched on leaf identity. "Holds records" is two cases on the
     * catalog-vs-domain split: the source <em>received</em> a record (a {@link Source.OnlyChild} /
     * {@link Source.Child} arm with {@link SourceShape#Record}), or a service / DML-write
     * {@link Operation} <em>produced</em> one mid-field. Either way the field re-projects the table by
     * correlating the record's keys to the catalog rows (mechanically a {@code VALUES(idx, key...)} join
     * with {@code ORDER BY idx}).
     *
     * <p>Re-fetch is orthogonal to the {@link #operation()} verb (R305): a field that re-fetches keeps
     * its own operation. A record-source {@code RecordTableField} carrier (the former
     * {@code SingleRecordTableField}) keys off a producer record while its operation stays
     * {@link Operation.Fetch}; this is the single home of the re-fetch predicate the service/DML fetcher
     * arms used to each re-decide from their own leaf type (R290). {@code GraphitronSchemaValidator}
     * mirrors it against the generator's actual re-fetch dispatch so the derivation and the emitter
     * cannot drift.
     *
     * <p>The guard is the bare {@link TargetShape.Table} shape, not the {@link TargetShape.Connection}
     * container that wraps it when paginated: a connection-shaped table field paginates rather than
     * re-projecting in this derivation's sense (the decomposed {@code Mapping.TableConnection}). A
     * catalog {@link Operation.Fetch} off a {@link SourceShape#Table} source reads the table directly
     * (no producer round-trip); producers whose target is a {@link TargetShape.Record} /
     * {@link TargetShape.Field} / {@link TargetShape.Column} hand back the consumed shape directly.
     * Neither re-fetches.
     */
    default boolean requiresReFetch() {
        if (!(target().shape() instanceof TargetShape.Table)) {
            return false;
        }
        boolean receivedRecord = switch (source()) {
            case Source.OnlyChild(var shape) -> shape == SourceShape.Record;
            case Source.Child(var shape) -> shape == SourceShape.Record;
            case Source.Root ignored -> false;
        };
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
     * reads, whose {@link #mapping()} is flat (never {@code TableConnection}) regardless of wrapper: a
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
