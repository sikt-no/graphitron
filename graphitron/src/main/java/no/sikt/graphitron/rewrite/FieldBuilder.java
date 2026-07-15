package no.sikt.graphitron.rewrite;

import graphql.language.SourceLocation;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLUnionType;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.lint.LintFix;
import no.sikt.graphitron.rewrite.lint.LintRule;
import no.sikt.graphitron.rewrite.model.AccessorResolution;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ChildField.ColumnField;
import no.sikt.graphitron.rewrite.model.ChildField.ColumnReferenceField;
import no.sikt.graphitron.rewrite.model.ChildField.ParticipantColumnReferenceField;
import no.sikt.graphitron.rewrite.model.ChildField.ComputedField;
import no.sikt.graphitron.rewrite.model.ChildField.ErrorsField;
import no.sikt.graphitron.rewrite.model.ChildField.InterfaceField;
import no.sikt.graphitron.rewrite.model.ChildField.NestingField;
import no.sikt.graphitron.rewrite.model.ChildField.PropertyField;
import no.sikt.graphitron.rewrite.model.ChildField.RecordField;
import no.sikt.graphitron.rewrite.model.ChildField.RecordLookupTableField;
import no.sikt.graphitron.rewrite.model.ChildField.RecordTableField;
import no.sikt.graphitron.rewrite.model.ChildField.ServiceRecordField;
import no.sikt.graphitron.rewrite.model.ChildField.ServiceTableField;
import no.sikt.graphitron.rewrite.model.ChildField.TableField;
import no.sikt.graphitron.rewrite.model.ChildField.TableInterfaceField;
import no.sikt.graphitron.rewrite.model.ChildField.TableMethodField;
import no.sikt.graphitron.rewrite.model.ChildField.UnionField;
import no.sikt.graphitron.rewrite.model.AccessorRef;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.DialectRequirement;
import no.sikt.graphitron.rewrite.model.DmlKind;
import no.sikt.graphitron.rewrite.model.ErrorChannel;
import no.sikt.graphitron.rewrite.model.SqlDialectFamily;
import no.sikt.graphitron.rewrite.model.ErrorChannelWalkerError;
import no.sikt.graphitron.rewrite.model.OutcomeType;
import no.sikt.graphitron.rewrite.model.DmlReturnExpression;
import no.sikt.graphitron.rewrite.model.InputColumnBinding;
import no.sikt.graphitron.rewrite.model.InputColumnBindingGroup;
import no.sikt.graphitron.rewrite.model.InputField;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType;
import no.sikt.graphitron.rewrite.model.GraphitronType.InterfaceType;
import no.sikt.graphitron.rewrite.model.GraphitronType.NodeType;
import no.sikt.graphitron.rewrite.model.GraphitronType.ResultType;
import no.sikt.graphitron.rewrite.model.GraphitronType.RootType;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableBackedType;
import no.sikt.graphitron.rewrite.model.GraphitronType.TableInterfaceType;
import no.sikt.graphitron.rewrite.model.GraphitronType.UnionType;
import no.sikt.graphitron.rewrite.model.HelperRef;
import no.sikt.graphitron.rewrite.model.JoinSlot;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.On;
import no.sikt.graphitron.rewrite.model.LoaderRegistration;
import no.sikt.graphitron.rewrite.model.LookupMapping;
import no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.RoutineChain;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.PaginationSpec;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.rewrite.model.ParentCorrelation;
import no.sikt.graphitron.rewrite.model.ParticipantFilters;
import no.sikt.graphitron.rewrite.model.ParticipantRef;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.ServiceCarrierShapeError;
import no.sikt.graphitron.rewrite.model.SourceKey;
import no.sikt.graphitron.rewrite.model.TableExpr;
import no.sikt.graphitron.rewrite.model.TableRef;

import no.sikt.graphitron.rewrite.model.BodyParam;
import no.sikt.graphitron.rewrite.model.CallParam;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ConditionFilter;
import no.sikt.graphitron.rewrite.model.FkTargetConditionFilter;
import no.sikt.graphitron.rewrite.model.GeneratedConditionFilter;
import no.sikt.graphitron.rewrite.model.WhereFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

import static no.sikt.graphitron.rewrite.BuildContext.ARG_CLASS_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_CONTEXT_ARGUMENTS;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_ARG_MAPPING;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_METHOD;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_PATH;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_TYPE;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_TYPE_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_AS_CONNECTION;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_CONDITION;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_SOURCE_ROW;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_EXTERNAL_FIELD;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_FIELD;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_LOOKUP_KEY;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_MULTITABLE_REFERENCE;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_MUTATION;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_NODE_ID;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_NOT_GENERATED;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_ORDER_BY;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_REFERENCE;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_REFERENCE_FOR;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_SERVICE;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_SPLIT_QUERY;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_ROUTINE;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_TABLE_METHOD;
import static no.sikt.graphitron.rewrite.BuildContext.argString;
import static no.sikt.graphitron.rewrite.BuildContext.argStringList;
import static no.sikt.graphitron.rewrite.BuildContext.asMap;
import static no.sikt.graphitron.rewrite.BuildContext.baseTypeName;
import static no.sikt.graphitron.rewrite.BuildContext.candidateHint;
import static no.sikt.graphitron.rewrite.BuildContext.locationOf;

/**
 * Classifies all fields in the schema into the {@link GraphitronField} hierarchy.
 *
 * <p>Reads directives ({@code @service}, {@code @reference}, {@code @field}, {@code @nodeId},
 * {@code @externalField}, {@code @tableMethod}, {@code @mutation}, {@code @splitQuery},
 * {@code @lookupKey}, {@code @defaultOrder}, {@code @orderBy}, {@code @condition}) to determine
 * the correct field variant. Downstream code works exclusively with the produced
 * {@link GraphitronField} values.
 */
class FieldBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(FieldBuilder.class);

    /**
     * R451 — the deferral prose for the single-node {@code @routine} on a Mutation root field
     * (no {@code @reference} hop), landed by {@link #classifyMutationField}'s top check. The
     * multi-node chain classifies for real since R451 ({@link #classifyMutationRoutineChain},
     * landing {@code MutationRoutineWriteField}); the single-node shape has no post-commit table
     * to re-read from, so its result-shape story is carried by the {@code
     * routine-write-result-shapes} follow-up alongside procedures and scalar / void routines —
     * a recognised capability gap, not an authoring error.
     */
    private static final String MUTATION_SINGLE_NODE_ROUTINE_DEFERRAL =
        "@routine on a Mutation field without a @reference hop has no post-commit table to "
        + "re-read the response from; the single-node result shape (void / scalar / OUT-parameter "
        + "binding) is a recognised capability gap, not an authoring error, and does not emit yet";

    private final BuildContext ctx;
    private final ServiceCatalog svc;
    private final ServiceDirectiveResolver serviceResolver;
    private final TableMethodDirectiveResolver tableMethodResolver;
    private final RoutineDirectiveResolver routineResolver;
    private final ExternalFieldDirectiveResolver externalFieldResolver;
    private final LookupKeyDirectiveResolver lookupKeyResolver;
    private final OrderByResolver orderByResolver;
    private final LookupMappingResolver lookupMappingResolver;
    private final PaginationResolver paginationResolver;
    private final ConditionResolver conditionResolver;
    private final InputFieldResolver inputFieldResolver;
    private final MutationInputResolver mutationInputResolver;
    private final EnumMappingResolver enumMappingResolver;
    private final SourceRowDirectiveResolver sourceRowResolver;

    /**
     * R178: post-construction reference for the producer-binding map. {@link TypeBuilder} is
     * built before {@link FieldBuilder} (so it can populate its binding state via
     * {@code prepareForWalk()}); the schema builder injects the reference here once both builders
     * exist. Read at field-classify time via {@link #dmlEmittedBinding} to decide whether a
     * payload-returning DML mutation's child fields should classify against the producer's
     * inner {@link no.sikt.graphitron.rewrite.model.ProducerBinding.DmlEmitted}.
     */
    private TypeBuilder typeBuilder;

    FieldBuilder(BuildContext ctx, ServiceCatalog svc) {
        this.ctx = ctx;
        this.svc = svc;
        this.enumMappingResolver = new EnumMappingResolver(ctx);
        this.serviceResolver = new ServiceDirectiveResolver(ctx, svc, this, new InputBeanResolver(ctx));
        this.tableMethodResolver = new TableMethodDirectiveResolver(ctx, svc, this);
        this.routineResolver = new RoutineDirectiveResolver(ctx, this);
        this.externalFieldResolver = new ExternalFieldDirectiveResolver(ctx, svc, this);
        this.lookupKeyResolver = new LookupKeyDirectiveResolver();
        this.orderByResolver = new OrderByResolver(ctx);
        this.lookupMappingResolver = new LookupMappingResolver();
        this.paginationResolver = new PaginationResolver();
        this.conditionResolver = new ConditionResolver(ctx, svc);
        this.inputFieldResolver = new InputFieldResolver(ctx);
        this.mutationInputResolver = new MutationInputResolver(ctx, conditionResolver, enumMappingResolver);
        this.sourceRowResolver = new SourceRowDirectiveResolver(ctx, this);
    }

    /**
     * R178: wires the post-construction {@link TypeBuilder} reference so the unified-path
     * classifier can query producer bindings (currently {@code dmlEmittedBinding}). Called
     * exactly once from {@link GraphitronSchemaBuilder#buildSchema} before the classification walk;
     * {@link TypeBuilder#prepareForWalk()} populates the binding state before any field classifies,
     * and field classification runs during the walk, strictly after both.
     */
    void setTypeBuilder(TypeBuilder typeBuilder) {
        this.typeBuilder = typeBuilder;
    }

    /**
     * R178: resolves the optional {@link no.sikt.graphitron.rewrite.model.ProducerBinding.DmlEmitted}
     * binding for an SDL payload type. Returns empty until {@link #setTypeBuilder} has been
     * called (pre-classify) or for payload SDL types not observed as a DML mutation's payload.
     */
    java.util.Optional<no.sikt.graphitron.rewrite.model.ProducerBinding.DmlEmitted> dmlEmittedBinding(
            String sdlTypeName) {
        return typeBuilder == null ? java.util.Optional.empty() : typeBuilder.dmlEmittedBinding(sdlTypeName);
    }

    /**
     * R178 step 2b sibling to {@link #dmlEmittedBinding}: resolves the
     * {@link no.sikt.graphitron.rewrite.model.ProducerBinding.ServiceEmitted} binding for an
     * SDL payload type observed as the return of an {@code @service}-carrier mutation field.
     */
    java.util.Optional<no.sikt.graphitron.rewrite.model.ProducerBinding.ServiceEmitted> serviceEmittedBinding(
            String sdlTypeName) {
        return typeBuilder == null ? java.util.Optional.empty() : typeBuilder.serviceEmittedBinding(sdlTypeName);
    }

    // ===== Shared resolution helpers =====

    /**
     * Extracts the first {@link MethodRef.Param.Sourced} parameter from the given method, or
     * {@code null} when the method has no such parameter.
     *
     * <p>A {@code null} result means the service method lacks the required {@code Sources}
     * parameter — the validator will surface this as an error before code generation runs.
     */
    private static MethodRef.Param.Sourced extractSourced(MethodRef method) {
        return method.params().stream()
            .filter(p -> p instanceof MethodRef.Param.Sourced)
            .map(p -> (MethodRef.Param.Sourced) p)
            .findFirst()
            .orElse(null);
    }

    /**
     * Builds the service-table-field's {@link SourceKey} from the resolved {@code @service}
     * method's {@code Sources} parameter and the field's table-bound return type. Reads
     * {@code wrap} and {@code columns} directly off {@code sourced}.
     */
    private static SourceKey buildServiceTableSourceKey(
            MethodRef.Param.Sourced sourced, ReturnTypeRef.TableBoundReturnType rt) {
        return new SourceKey(
            sourced.columns(),
            sourced.wrap(),
            rt.wrapper().isList() ? SourceKey.Cardinality.MANY : SourceKey.Cardinality.ONE,
            new SourceKey.Reader.ServiceTableRecord(rt.table().recordClass()));
    }

    /**
     * Builds the service-record-field's {@link SourceKey} from the resolved {@code @service}
     * method's {@code Sources} parameter and the field's (untyped) return type.
     */
    private static SourceKey buildServiceRecordSourceKey(
            MethodRef.Param.Sourced sourced, ReturnTypeRef rt) {
        return new SourceKey(
            sourced.columns(),
            sourced.wrap(),
            rt.wrapper().isList() ? SourceKey.Cardinality.MANY : SourceKey.Cardinality.ONE,
            new SourceKey.Reader.ServiceUntypedRecord());
    }

    /**
     * Builds the {@link LoaderRegistration} for a service-backed child field. Dispatch is
     * always {@link LoaderRegistration.Dispatch#LOAD_ONE} on the service path (the
     * loadMany / accessor-many shape is record-parent-only); container reads directly off
     * {@code sourced}; {@code valueIsList} follows the field's wrapper.
     */
    private static LoaderRegistration buildServiceLoaderRegistration(
            MethodRef.Param.Sourced sourced, ReturnTypeRef rt) {
        return new LoaderRegistration(
            rt.wrapper().isList(),
            sourced.container(),
            LoaderRegistration.Dispatch.LOAD_ONE);
    }

    /**
     * Outcome of {@link #resolveTableFieldComponents}. Two terminal arms the caller exhausts
     * with a switch or {@code instanceof}; mirrors the per-resolver {@code Resolved} shapes the
     * orchestrator threads together.
     */
    private sealed interface TableFieldComponents {
        record Ok(List<WhereFilter> filters, OrderBySpec orderBy, PaginationSpec pagination,
                  LookupMapping lookupMapping) implements TableFieldComponents {}
        record Rejected(Rejection rejection) implements TableFieldComponents {
            public String message() { return rejection.message(); }
        }
    }

    /**
     * Per-field summary of every {@code @nodeId}-decorated leaf reachable from a table-bound
     * field's argument set. Pre-resolved once by {@link #buildNodeIdArgPlan} so the
     * lookup-promotion gate, the {@code @asConnection} rejection, and {@link #classifyArgument}
     * read the same classification rather than each calling
     * {@link NodeIdLeafResolver#resolve} fresh on the same leaf. Replaces
     * {@code findSameTableNodeIdUnderAsConnection}, {@code walkInputTypeForSameTableNodeId},
     * and {@code hasSameTableNodeIdAnywhere}.
     *
     * <p>Two-slot carrier with asymmetric reach: {@code byArgName} is generation-bound (read by
     * {@link #classifyArgument} to produce {@link no.sikt.graphitron.rewrite.model.ArgumentRef}
     * variants whose decode strategy and key columns flow into emitter code); {@code asConnectionGuard}
     * is hygiene-only (consumed exactly once by the {@code @asConnection} guard at
     * {@link #resolveTableFieldComponents} and never reaches a generator). If a third concern
     * lands here later, the right move is to split rather than grow.
     *
     * @param byArgName                  resolved outcome keyed by top-level argument name; covers
     *                                   every top-level argument carrying {@code @nodeId} (any
     *                                   arity, any arm). Non-{@code @nodeId} args are absent.
     * @param firstRequiredSameTableHit  non-{@code null} iff at least one same-table
     *                                   {@code @nodeId} leaf is reached through an entirely
     *                                   non-null path (outer arg + every nested input-field
     *                                   wrapper). Hygiene-only: read solely by the
     *                                   {@code @asConnection} advisory at
     *                                   {@link #resolveTableFieldComponents}, where its presence
     *                                   adds a {@link BuildWarning} on the always-bounded shape
     *                                   and classification continues normally. {@code null}
     *                                   otherwise — including the "all hits are optional" case.
     */
    record NodeIdArgPlan(
            Map<String, NodeIdLeafResolver.Resolved> byArgName,
            SameTableHit firstRequiredSameTableHit) {

        /** Empty plan for non-table-bound fields and fields with no {@code @nodeId} leaves. */
        static final NodeIdArgPlan EMPTY = new NodeIdArgPlan(Map.of(), null);

        /**
         * Carries enough context to build the {@code @asConnection} + required-same-table
         * advisory warn message: the leaf identifier the author wrote, the typeName that
         * resolved to the field's own backing table, and the containing table itself. The
         * "first" in {@link NodeIdArgPlan#firstRequiredSameTableHit} is walk-order context
         * for the message — the semantic claim is {@code ∃ required same-table @nodeId leaf}
         * across the whole arg set, so the warn fires whether the required leaf is declared
         * first or last in the SDL.
         */
        record SameTableHit(String leafName, String refTypeName, String containingTableName) {}
    }

    /**
     * Walks {@code fieldDef}'s argument set once: every {@code @nodeId}-decorated top-level
     * argument resolves into {@code byArgName}; every same-table hit (top-level or nested under
     * arg input-types) is reported with a {@code pathRequired} bit. The first required hit in
     * walk order (top-level args before nested input types) seeds the
     * {@link NodeIdArgPlan.AsConnectionGuard.Required} payload for the rejection message. The
     * predicate is order-independent at the field level: any required hit folds the guard to
     * {@code Required}, regardless of SDL ordering.
     *
     * <p>Required-ness is conjunctive across the path: a leaf is required iff every wrapper
     * from the field's argument-root down to the {@code ID}/{@code [ID]} carrier is non-null.
     * Cycle protection on nested input types uses a scoped {@code LinkedHashSet<String>}
     * (add on entry, remove on return); the previous walk could mark add-only because it
     * short-circuited on the first hit, but the accumulator-driven walk needs sibling
     * subtrees that share an input-type subgraph to each get visited independently.
     */
    private NodeIdArgPlan buildNodeIdArgPlan(GraphQLFieldDefinition fieldDef, TableRef containingTable) {
        var resolver = ctx.nodeIdLeafResolver();
        var byArgName = new java.util.LinkedHashMap<String, NodeIdLeafResolver.Resolved>();
        var firstRequiredHit = new NodeIdArgPlan.SameTableHit[]{null};
        boolean anyHit = false;
        for (var arg : fieldDef.getArguments()) {
            boolean argRequired = arg.getType() instanceof GraphQLNonNull;
            if (arg.hasAppliedDirective(DIR_NODE_ID)) {
                var unwrapped = GraphQLTypeUtil.unwrapAll(arg.getType());
                if (unwrapped instanceof GraphQLNamedType named && "ID".equals(named.getName())) {
                    var resolved = resolver.resolve(arg, arg.getName(), containingTable);
                    byArgName.put(arg.getName(), resolved);
                    if (resolved instanceof NodeIdLeafResolver.Resolved.SameTable st) {
                        anyHit = true;
                        if (argRequired && firstRequiredHit[0] == null) {
                            firstRequiredHit[0] = new NodeIdArgPlan.SameTableHit(
                                arg.getName(), st.refTypeName(), containingTable.tableName());
                        }
                    }
                }
            }
            var argType = GraphQLTypeUtil.unwrapAll(arg.getType());
            if (argType instanceof GraphQLInputObjectType iot) {
                boolean[] sawNested = {false};
                walkInputTypeForSameTableNodeId(
                    resolver, iot, containingTable, argRequired, new java.util.LinkedHashSet<>(),
                    hit -> {
                        sawNested[0] = true;
                        if (hit.required() && firstRequiredHit[0] == null) {
                            firstRequiredHit[0] = hit.hit();
                        }
                    });
                if (sawNested[0]) anyHit = true;
            }
        }
        if (byArgName.isEmpty() && !anyHit) {
            return NodeIdArgPlan.EMPTY;
        }
        return new NodeIdArgPlan(Map.copyOf(byArgName), firstRequiredHit[0]);
    }

    private record SameTableHitWithRequired(NodeIdArgPlan.SameTableHit hit, boolean required) {}

    private void walkInputTypeForSameTableNodeId(
            NodeIdLeafResolver resolver, GraphQLInputObjectType iot, TableRef containingTable,
            boolean pathRequiredSoFar, java.util.LinkedHashSet<String> visited,
            java.util.function.Consumer<SameTableHitWithRequired> hits) {
        if (!visited.add(iot.getName())) return;
        try {
            for (var inputField : iot.getFieldDefinitions()) {
                boolean fieldRequired = inputField.getType() instanceof GraphQLNonNull;
                boolean pathRequired = pathRequiredSoFar && fieldRequired;
                if (inputField.hasAppliedDirective(DIR_NODE_ID)) {
                    var unwrapped = GraphQLTypeUtil.unwrapAll(inputField.getType());
                    if (unwrapped instanceof GraphQLNamedType named && "ID".equals(named.getName())) {
                        var resolved = resolver.resolve(inputField, inputField.getName(), containingTable);
                        if (resolved instanceof NodeIdLeafResolver.Resolved.SameTable st) {
                            hits.accept(new SameTableHitWithRequired(
                                new NodeIdArgPlan.SameTableHit(
                                    inputField.getName(), st.refTypeName(), containingTable.tableName()),
                                pathRequired));
                        }
                    }
                }
                var nestedType = GraphQLTypeUtil.unwrapAll(inputField.getType());
                if (nestedType instanceof GraphQLInputObjectType nestedIot) {
                    walkInputTypeForSameTableNodeId(
                        resolver, nestedIot, containingTable, pathRequired, visited, hits);
                }
            }
        } finally {
            visited.remove(iot.getName());
        }
    }

    private static String formatCtorSignatures(java.lang.reflect.Constructor<?>[] ctors) {
        return java.util.Arrays.stream(ctors)
            .map(c -> c.getDeclaringClass().getSimpleName() + "("
                + java.util.Arrays.stream(c.getParameterTypes())
                    .map(Class::getSimpleName)
                    .collect(Collectors.joining(", "))
                + ")")
            .collect(Collectors.joining("; "));
    }

    /**
     * Outcome of {@link no.sikt.graphitron.rewrite.model.PayloadConstructionShape} resolution
     * for a payload class. {@code Resolved} carries the resolved shape (an
     * {@link no.sikt.graphitron.rewrite.model.PayloadConstructionShape.AllFieldsCtor} or
     * {@link no.sikt.graphitron.rewrite.model.PayloadConstructionShape.MutableBean}). {@code
     * Reject} carries a human-readable reason suitable for surfacing on an
     * {@code UnclassifiedField}.
     */
    sealed interface PayloadConstructionShapeResult {
        record Resolved(no.sikt.graphitron.rewrite.model.PayloadConstructionShape shape)
            implements PayloadConstructionShapeResult {}
        record Reject(String reason) implements PayloadConstructionShapeResult {}
    }

    /**
     * Resolves the {@link no.sikt.graphitron.rewrite.model.PayloadConstructionShape} for a
     * payload class. Predicates run in order:
     *
     * <ol>
     *   <li><b>All-fields-ctor predicate</b>. Records always hit this. Hand-rolled POJOs hit it
     *       when the canonical ctor is unambiguous: either the class declares exactly one
     *       constructor, or the unique ctor whose parameter count matches the SDL field count
     *       is the canonical one. Returns
     *       {@link no.sikt.graphitron.rewrite.model.PayloadConstructionShape.AllFieldsCtor}.</li>
     *   <li><b>Mutable-bean predicate</b>. The class declares a public no-arg constructor
     *       <em>and</em> a Java-bean setter ({@code setX} for SDL field {@code x}) accepting
     *       one parameter for every SDL field. Returns
     *       {@link no.sikt.graphitron.rewrite.model.PayloadConstructionShape.MutableBean}.</li>
     * </ol>
     *
     * <p>When predicate 1 matches it short-circuits the walk and {@code AllFieldsCtor} wins ;
     * this is the canonical-over-bridge precedence (records always present the all-fields ctor;
     * the setter shape is a legacy bridge from {@code graphitron-codegen-parent}). Both shapes
     * yield equivalent payload instances, so there's no construction drift to surface.
     * Consumers who want the setter shape exclusively drop the all-fields ctor from their class.
     * Neither predicate matching is the only rejection mode.
     *
     * <p>{@code sdlFieldNames} is the SDL payload's field names in declaration order; when the
     * payload's SDL type isn't an object (e.g. transient classifier callers without a fully
     * resolved schema fragment), pass {@code null} to skip the setter predicate.
     */
    static PayloadConstructionShapeResult resolvePayloadConstructionShape(
            Class<?> payloadCls, java.util.List<String> sdlFieldNames) {
        var ctors = payloadCls.getDeclaredConstructors();
        if (ctors.length == 0) {
            return new PayloadConstructionShapeResult.Reject(
                "payload class '" + payloadCls.getName() + "' has no declared constructors");
        }
        int sdlFieldCount = sdlFieldNames == null ? -1 : sdlFieldNames.size();
        // Predicate 1: all-fields ctor. A class with a single declared ctor takes the trivial
        // path only when (a) the SDL field count is unknown (legacy callers that pass null),
        // or (b) the ctor's parameter count matches the SDL field count. The arity check
        // matters now that predicate 2 (mutable-bean) is a sibling: a class with only a public
        // no-arg ctor is a setter-shape candidate, not a degenerate "canonical" all-fields ctor.
        java.lang.reflect.Constructor<?> allFieldsCtor = null;
        if (ctors.length == 1) {
            if (sdlFieldCount < 0 || ctors[0].getParameterCount() == sdlFieldCount) {
                allFieldsCtor = ctors[0];
            }
        } else {
            var matches = java.util.Arrays.stream(ctors)
                .filter(c -> c.getParameterCount() == sdlFieldCount)
                .toList();
            if (matches.size() == 1) {
                allFieldsCtor = matches.get(0);
            }
        }
        if (allFieldsCtor != null) {
            return new PayloadConstructionShapeResult.Resolved(
                new no.sikt.graphitron.rewrite.model.PayloadConstructionShape.AllFieldsCtor(allFieldsCtor));
        }
        // Predicate 2: mutable-bean shape (no-arg ctor + per-SDL-field setters).
        var beanResult = tryMutableBean(payloadCls, sdlFieldNames, ctors);
        if (beanResult != null) {
            return beanResult;
        }
        // Neither shape matches.
        String prefix = "payload class '" + payloadCls.getName() + "' has " + ctors.length
            + " declared constructors";
        String guidance = ". Convert the class to a Java record (which always declares one"
            + " canonical constructor), remove the extra constructor(s) so only the all-fields"
            + " constructor remains, or add a public no-arg constructor plus Java-bean setters"
            + " for every SDL field";
        var matches = java.util.Arrays.stream(ctors)
            .filter(c -> c.getParameterCount() == sdlFieldCount)
            .toList();
        if (matches.isEmpty()) {
            return new PayloadConstructionShapeResult.Reject(prefix
                + " but none has parameter count " + sdlFieldCount
                + " matching the SDL field count; the carrier requires a canonical (all-fields)"
                + " constructor or a mutable-bean shape — found: " + formatCtorSignatures(ctors)
                + guidance);
        }
        return new PayloadConstructionShapeResult.Reject(prefix
            + " with parameter count " + sdlFieldCount + " matching the SDL field count;"
            + " the canonical (all-fields) constructor is ambiguous — found: "
            + formatCtorSignatures(ctors) + guidance);
    }

    /**
     * Tries the mutable-bean predicate: a public no-arg constructor plus a Java-bean setter for
     * every SDL field. Returns {@code Resolved(MutableBean)} on match, a structured
     * {@code Reject} when a partial match is detected (no-arg ctor exists but one or more
     * SDL-field setters are missing or mis-typed), or {@code null} when no no-arg ctor exists
     * (so the caller can fall through to the "neither predicate" reject path).
     */
    private static PayloadConstructionShapeResult tryMutableBean(
            Class<?> payloadCls,
            java.util.List<String> sdlFieldNames,
            java.lang.reflect.Constructor<?>[] ctors) {
        if (sdlFieldNames == null || sdlFieldNames.isEmpty()) {
            return null;
        }
        java.lang.reflect.Constructor<?> noArgCtor = java.util.Arrays.stream(ctors)
            .filter(c -> c.getParameterCount() == 0
                && java.lang.reflect.Modifier.isPublic(c.getModifiers()))
            .findFirst()
            .orElse(null);
        if (noArgCtor == null) {
            return null;
        }
        var bindings = new java.util.ArrayList<no.sikt.graphitron.rewrite.model.PayloadConstructionShape.SetterBinding>(
            sdlFieldNames.size());
        for (String sdlFieldName : sdlFieldNames) {
            String setterName = javaBeanSetterName(sdlFieldName);
            var candidates = java.util.Arrays.stream(payloadCls.getMethods())
                .filter(m -> m.getName().equals(setterName) && m.getParameterCount() == 1)
                .toList();
            if (candidates.isEmpty()) {
                return new PayloadConstructionShapeResult.Reject(
                    "payload class '" + payloadCls.getName()
                        + "' has a public no-arg constructor but no setter '" + setterName
                        + "(...)' for SDL field '" + sdlFieldName + "'; the mutable-bean shape"
                        + " requires a Java-bean setter for every SDL field");
            }
            if (candidates.size() > 1) {
                return new PayloadConstructionShapeResult.Reject(
                    "payload class '" + payloadCls.getName()
                        + "' has multiple overloads of '" + setterName + "(...)' for SDL field '"
                        + sdlFieldName + "'; the mutable-bean shape requires a unique single-arg"
                        + " setter");
            }
            var setter = candidates.get(0);
            boolean acceptsOptional = setter.getParameterTypes()[0] == java.util.Optional.class;
            bindings.add(new no.sikt.graphitron.rewrite.model.PayloadConstructionShape.SetterBinding(
                sdlFieldName, setter, acceptsOptional));
        }
        return new PayloadConstructionShapeResult.Resolved(
            new no.sikt.graphitron.rewrite.model.PayloadConstructionShape.MutableBean(
                noArgCtor, bindings));
    }

    /** Java-bean setter name: SDL field "rating" maps to "setRating" (first letter upper-cased). */
    private static String javaBeanSetterName(String sdlFieldName) {
        return "set" + Character.toUpperCase(sdlFieldName.charAt(0))
            + sdlFieldName.substring(1);
    }

    private static String formatAsConnectionSameTableWarning(
            NodeIdArgPlan.SameTableHit hit, String fieldName) {
        return "field '" + fieldName + "': @nodeId(typeName: '" + hit.refTypeName()
            + "') on '" + hit.leafName() + "' (required) resolves to '" + hit.containingTableName()
            + "', the field's own backing table. A required same-table @nodeId leaf"
            + " bounds the result to the input id list, so every page of @asConnection"
            + " would equal the input set. The connection still ships (WHERE pk IN ...)"
            + " but adds no value over a plain list. To silence this warning, make '"
            + hit.leafName() + "' nullable (the filter is applied when ids are supplied,"
            + " omitted otherwise), drop @asConnection from '" + fieldName
            + "', or use a filter argument that resolves to a different table via FK.";
    }

    /**
     * Resolves the filter, order-by, and pagination components for a table-bound list field.
     * Returns a non-null {@code error} when any component fails to resolve.
     *
     * @param returnTypeName the GraphQL return type name (e.g. {@code "Film"}), used to derive
     *                       the {@code *Conditions} class name for any generated filter method
     */
    private TableFieldComponents resolveTableFieldComponents(
            GraphQLFieldDefinition fieldDef, TableRef table, String returnTypeName, NodeIdArgPlan plan) {
        return resolveTableFieldComponents(fieldDef, table, returnTypeName, plan, true);
    }

    /**
     * @param emitAsConnectionAdvisory when {@code false}, the {@code @asConnection} same-table
     *        advisory is suppressed. The multi-table polymorphic path calls this once per
     *        participant table, so it suppresses here and emits the advisory at most once across
     *        participants via {@link #warnAsConnectionSameTable} instead.
     */
    private TableFieldComponents resolveTableFieldComponents(
            GraphQLFieldDefinition fieldDef, TableRef table, String returnTypeName, NodeIdArgPlan plan,
            boolean emitAsConnectionAdvisory) {
        if (emitAsConnectionAdvisory) {
            warnAsConnectionSameTable(fieldDef, plan);
        }
        var classifyErrors = new ArrayList<String>();
        var refs = classifyArguments(fieldDef, table, plan, classifyErrors);
        var rejections = new ArrayList<Rejection>();
        for (String e : classifyErrors) rejections.add(Rejection.structural(e));
        return projectForFilter(refs, fieldDef, table, returnTypeName, rejections);
    }

    /**
     * Emits the {@code @asConnection} + required-same-table-{@code @nodeId} hygiene advisory.
     *
     * <p>@asConnection + a required same-table @nodeId leaf is hygiene-flagged but allowed:
     * the result is always bounded by the mandatory input id list, so the connection's
     * page equals the input set. Production schemas legitimately compose this shape (the
     * wire format is {@code WHERE pk IN (decoded_ids)} with seek pagination on top, which is
     * what consumers expect); the warn surfaces the redundancy without blocking the
     * build. Optional same-table @nodeId leaves are silent — caller-omitted drops the
     * PK-IN filter and paginates the full table; caller-supplied narrows to a bounded
     * set and paginates within it. Required-ness is conjunctive across the path: the
     * warn fires iff the outer arg and every nested input wrapper down to the leaf
     * are all non-null. ∃-required across all hits, not first-hit-wins.
     */
    private void warnAsConnectionSameTable(GraphQLFieldDefinition fieldDef, NodeIdArgPlan plan) {
        if (fieldDef.hasAppliedDirective(DIR_AS_CONNECTION)
                && plan.firstRequiredSameTableHit() != null) {
            ctx.addWarning(BuildWarning.LintFinding.of(
                formatAsConnectionSameTableWarning(plan.firstRequiredSameTableHit(), fieldDef.getName()),
                locationOf(fieldDef),
                LintRule.ASCONNECTION_SAME_TABLE_PK_IN));
        }
    }

    /** Outcome of lowering {@code @field} filters across a multi-table polymorphic field's participants (R363). */
    private sealed interface ParticipantFiltersResult {
        record Ok(List<ParticipantFilters> participantFilters) implements ParticipantFiltersResult {}
        record Rejected(Rejection rejection) implements ParticipantFiltersResult {}
    }

    /**
     * Lowers {@code @field}-mapped filter arguments once per table-bound participant of a multi-table
     * polymorphic root query field, each against the participant's own table so the generated
     * condition method and column constants are table-specific (R363). The condition class is named
     * after the participant ({@code <Participant>Conditions}), not the interface/union, so the per-
     * participant methods do not collide. A column absent from (or type-incompatible on) one
     * participant surfaces as that participant's classifier rejection, failing the build. The
     * {@code @asConnection} same-table advisory is emitted at most once across participants.
     *
     * <p>Developer {@code @condition} filters (R384 phase c) lower through the same per-participant
     * loop: {@link #resolveTableFieldComponents} reflects the {@code @condition} method once per
     * participant, and the branch emitter calls it against each participant's stage-1 alias. The
     * contract is the one every {@code @condition} already carries: a {@code Table<?>}-typed first
     * parameter serves every branch, while a concrete participant-table parameter surfaces a
     * mismatched branch at the consumer's javac (mirroring R379's concrete-parameter semantics).
     */
    private ParticipantFiltersResult lowerParticipantFilters(
            GraphQLFieldDefinition fieldDef, List<ParticipantRef> participants) {
        var result = new ArrayList<ParticipantFilters>();
        boolean advisoryEmitted = false;
        for (var p : participants) {
            if (!(p instanceof ParticipantRef.TableBound tb)) {
                continue; // Unbound participants carry no table to lower filters against.
            }
            var plan = buildNodeIdArgPlan(fieldDef, tb.table());
            if (!advisoryEmitted && fieldDef.hasAppliedDirective(DIR_AS_CONNECTION)
                    && plan.firstRequiredSameTableHit() != null) {
                warnAsConnectionSameTable(fieldDef, plan);
                advisoryEmitted = true;
            }
            var components = resolveTableFieldComponents(fieldDef, tb.table(), tb.typeName(), plan, false);
            if (components instanceof TableFieldComponents.Rejected rj) {
                return new ParticipantFiltersResult.Rejected(rj.rejection());
            }
            var tfc = (TableFieldComponents.Ok) components;
            var unsupported = firstUnsupportedFilterArg(tfc.filters());
            if (unsupported != null) {
                return new ParticipantFiltersResult.Rejected(Rejection.structural(
                    "filter argument '" + unsupported + "' on a multitable interface/union is not "
                    + "supported: the polymorphic branch emitter handles plain scalar, enum, "
                    + "jOOQ-converted (e.g. ID-typed), and @nodeId-decoded column filters plus "
                    + "developer @condition filters (top-level or nested-input), but this argument's "
                    + "extraction is a record/bean-decode shape the branch path does not carry "
                    + "(participant '" + tb.typeName() + "')"));
            }
            result.add(new ParticipantFilters(tb, tfc.filters()));
        }
        return new ParticipantFiltersResult.Ok(List.copyOf(result));
    }

    /**
     * Returns the name of the first filter argument whose extraction the multitable branch emitter
     * ({@code MultiTablePolymorphicEmitter.branchFilterWhere}) cannot emit, or {@code null} if every
     * filter is branch-safe. The branch path reuses the single-table condition-term emission with
     * the enclosing fetcher class's {@code CompositeDecodeHelperRegistry} and the pre-declared lift
     * locals threaded through it (R384 phase 0), and supports {@code Direct}, {@code EnumValueOf},
     * {@code ContextArg}, {@code JooqConvert} (R384 phase a), {@code NodeIdDecodeKeys} (R384
     * phase b), a {@code NestedInputField} whose leaf is itself one of those, and developer
     * {@code @condition} filters (R384 phase c: {@code ConditionFilter} /
     * {@code FkTargetConditionFilter} gate on their per-param extractions like every other filter;
     * the FK-target alias pass and the registry supply their plumbing). {@code InputBean} and the
     * record-decode shapes stay rejected. Rejecting at classify time keeps the failure a clean
     * build error rather than an emitter {@code IllegalStateException} or uncompilable output
     * ("classifier guarantees shape emitter assumptions").
     */
    private static String firstUnsupportedFilterArg(List<WhereFilter> filters) {
        for (var filter : filters) {
            // R384 phase c: developer @condition filters (ConditionFilter / FkTargetConditionFilter)
            // are no longer rejected outright — the phase-0 threading supplies the FK-target alias
            // pass and the decode registry their emission needs, so every filter kind is gated
            // uniformly on its per-param extractions below. The developer method runs once per
            // branch against that participant's stage-1 alias; a method whose Table parameter is
            // declared as a concrete participant table surfaces any mismatch at the consumer's
            // javac (the same contract as R379's concrete-parameter checks), while the common
            // Table<?> signature serves every branch.
            for (var param : filter.callParams()) {
                if (!isBranchSafeExtraction(param.extraction())) {
                    return param.name();
                }
            }
        }
        return null;
    }

    /**
     * The polymorphic branch emitter ({@code MultiTablePolymorphicEmitter.branchFilterWhere}) emits
     * a filter term with the plumbing R384 phase 0 threads through it (the enclosing fetcher
     * class's {@code CompositeDecodeHelperRegistry}, pre-declared {@code <name>Keys} /
     * lifted-outer locals, and FK-target aliases); each arm below states whether the corresponding
     * extraction's emission is covered by that plumbing.
     *
     * <p>R383: a {@code NestedInputField} (a filter delivered through an input object rather than as a
     * top-level argument) is branch-safe exactly when its {@code leaf} transform is. The call-site
     * emitter's {@code NestedInputField} arm ({@code ArgCallEmitter.buildArgExtraction}) emits a
     * self-contained {@code env.getArgument(outer) instanceof Map<?, ?> ...} traversal. A nested
     * plain {@code @field} column carries a {@link CallSiteExtraction.Direct} leaf, an ID-typed one
     * a {@link CallSiteExtraction.JooqConvert} leaf ({@code FieldBuilder.implicitBodyParam}, R384
     * phase a), and a nested {@code @nodeId} field a {@code NodeIdDecodeKeys} leaf (R384 phase b,
     * lifting its decode helper through the threaded registry) — all admitted through the
     * recursion. The condition-method generator ({@code TypeConditionsGenerator}) is
     * extraction-agnostic, so the generated {@code <Participant>Conditions} method is identical
     * whether the value arrives top-level or Map-traversed.
     *
     * <p>Rejecting at classify time keeps the failure a clean build error rather than an emitter
     * {@code IllegalStateException} or uncompilable output ("classifier guarantees shape emitter
     * assumptions").
     */
    private static boolean isBranchSafeExtraction(CallSiteExtraction extraction) {
        return switch (extraction) {
            case CallSiteExtraction.Direct ignored -> true;
            case CallSiteExtraction.EnumValueOf ignored -> true;
            case CallSiteExtraction.ContextArg ignored -> true;
            case CallSiteExtraction.NestedInputField nif -> isBranchSafeExtraction(nif.leaf());
            // R384 phase a: branch-safe. The shared arm now emits the non-deprecated
            // DSL.val(raw, col.getDataType()).getValue() coercion, and the branch path pre-declares
            // the shared <name>Keys local (deduped across participants) ahead of the stage-1 union
            // (MultiTablePolymorphicEmitter.declareFilterPlumbing); the nested-leaf form is a
            // self-contained traversal-plus-coercion expression needing no local at all.
            case CallSiteExtraction.JooqConvert ignored -> true;
            // R384 phase b: branch-safe. The enclosing <Type>Fetchers class's
            // CompositeDecodeHelperRegistry is threaded through branchFilterWhere (phase 0), so a
            // NodeId-decoded filter arg lifts its decode helper onto the class hosting the branch
            // call site — top-level and as a NestedInputField leaf, which the recursion above
            // covers once this arm flips.
            case CallSiteExtraction.NodeIdDecodeKeys ignored -> true;
            // Not branch-safe: mutation-input / record-decode shapes that do not occur as a
            // multitable root-query filter arg. Listed exhaustively with no default on purpose:
            // if a schema ever produces one here, or a new CallSiteExtraction permit is added,
            // this switch fails to compile and forces a deliberate decision at this gate rather
            // than silently rejecting.
            case CallSiteExtraction.NodeIdDecodeRecord ignored -> false;
            case CallSiteExtraction.InputBean ignored -> false;
            case CallSiteExtraction.JooqRecord ignored -> false;
        };
    }

    // ===== Object-return child field classification =====

    /**
     * Classifies a child field on a {@link TableType} parent whose return type is an object, interface,
     * or union — not a scalar or enum. Called after the {@code @tableMethod} check in
     * {@link #classifyChildFieldOnTableType}.
     *
     * <p>P2 handles {@link TableField} and {@link NestingField}. Remaining variants
     * ({@code TableInterfaceField}, {@code InterfaceField}, {@code UnionField}, {@code ServiceField},
     * {@code ComputedField}) are added in P3.
     */
    private GraphitronField classifyObjectReturnChildField(GraphQLFieldDefinition fieldDef, String parentTypeName, TableBackedType parentTableType, Set<String> expandingTypes) {
        String name = fieldDef.getName();
        SourceLocation location = locationOf(fieldDef);
        String rawTypeName = baseTypeName(fieldDef);

        // For connection types the element type is edges.node, not the connection wrapper type.
        String elementTypeName = ctx.isConnectionType(rawTypeName)
            ? ctx.connectionElementTypeName(rawTypeName)
            : rawTypeName;
        // R317 slice 3d — the table-backed fact comes from the pure TableIndex (a fixed point threaded
        // in, not the in-progress registry), so no edge reads a sibling/parent verdict for it. R317
        // slice 3e — the interface / union / result arms below resolve their target through the
        // registry-free look-ahead (TypeBuilder.lookAheadVerdict), not ctx.types, so the field's
        // output target need not have been registered: the precondition for the enter-only single
        // walk, where the target is a not-yet-visited child.
        GraphitronType tableBacked = ctx.tables.forName(elementTypeName).orElse(null);
        GraphitronType elementType = typeBuilder.lookAheadVerdict(elementTypeName);

        if (tableBacked instanceof TableBackedType tbt && !(tableBacked instanceof TableInterfaceType)) {
            var wrapper = buildWrapper(fieldDef);
            // R317 slice 3d — build the TableBoundReturnType from the index verdict's table directly
            // rather than casting ctx.resolveReturnType (which reads the registry). The pure TableIndex
            // keeps a typeId-collided node visible here, whereas validateNodeTypeIdUniqueness has
            // already demoted it in the registry; resolveReturnType would yield a ScalarReturnType and
            // the cast would crash. The collision still hard-fails the build at the validation pass.
            var returnType = new ReturnTypeRef.TableBoundReturnType(elementTypeName, tbt.table(), wrapper);
            var referencePath = ctx.parsePath(fieldDef, name, parentTableType.table().tableName(), returnType.table().tableName(), returnType.table(), wrapper.isList());
            if (referencePath.hasError()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(referencePath.errorMessage()));
            }
            // R379 Check 1: this inline / split projection feeds the terminal alias to a
            // $fields overload typed for the return table (InlineTableFieldEmitter.buildArm), so a
            // terminal hop landing elsewhere would compile to javac-rejected generated code. Reject
            // at build time, formatting the diagnostic from the typed verdict.
            if (referencePath.terminalTargetVerdict() instanceof BuildContext.TerminalTargetVerdict.Mismatch mismatch) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(mismatch.diagnostic()));
            }
            var components = resolveTableFieldComponents(fieldDef, returnType.table(), elementTypeName,
                buildNodeIdArgPlan(fieldDef, returnType.table()));
            if (components instanceof TableFieldComponents.Rejected rj) return new UnclassifiedField(parentTypeName, name, location, fieldDef, rj.rejection());
            var tfc = (TableFieldComponents.Ok) components;
            boolean hasSplitQuery = fieldDef.hasAppliedDirective(DIR_SPLIT_QUERY);
            boolean hasLookupKey  = hasLookupKeyAnywhere(fieldDef);
            boolean isList = returnType.wrapper().isList();
            // Synthesise the step-0 parent correlation once per carrier — both inline and
            // split-rows arms below read this through their @reference-carrying record header.
            // The split-query batch grain is a projection off this arm (R450:
            // ParentCorrelation.parentKeyColumns), so build the correlation first and read the
            // entry columns off it rather than re-deriving them from the path.
            var tbtPcResolution = ctx.buildParentCorrelation(
                referencePath.elements(), parentTableType.table());
            if (tbtPcResolution instanceof BuildContext.ParentCorrelationResolution.AuthorError e) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(e.message()));
            }
            var tbtParentCorrelation = ((BuildContext.ParentCorrelationResolution.Resolved) tbtPcResolution).correlation();
            var parentSplitSource = deriveSplitQuerySource(tbtParentCorrelation, parentTableType.table(), returnType);
            if (hasSplitQuery && hasLookupKey) {
                var lookupResolved = lookupKeyResolver.resolveAtChild(returnType, true);
                if (lookupResolved instanceof LookupKeyDirectiveResolver.Resolved.Rejected r) {
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef, r.rejection());
                }
                return new no.sikt.graphitron.rewrite.model.ChildField.SplitLookupTableField(
                    parentTypeName, name, location, returnType, referencePath.elements(), tfc.filters(), tfc.orderBy(), tfc.pagination(),
                    parentSplitSource.sourceKey(),
                    parentSplitSource.loaderRegistration(),
                    tfc.lookupMapping(),
                    tbtParentCorrelation);
            }
            if (!hasSplitQuery && hasLookupKey) {
                var lookupResolved = lookupKeyResolver.resolveAtChild(returnType, false);
                if (lookupResolved instanceof LookupKeyDirectiveResolver.Resolved.Rejected r) {
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef, r.rejection());
                }
                return new no.sikt.graphitron.rewrite.model.ChildField.LookupTableField(
                    parentTypeName, name, location, returnType, referencePath.elements(), tfc.filters(), tfc.orderBy(), tfc.pagination(),
                    tfc.lookupMapping(),
                    tbtParentCorrelation);
            }
            if (hasSplitQuery) {
                return new no.sikt.graphitron.rewrite.model.ChildField.SplitTableField(
                    parentTypeName, name, location, returnType, referencePath.elements(), tfc.filters(), tfc.orderBy(), tfc.pagination(),
                    parentSplitSource.sourceKey(),
                    parentSplitSource.loaderRegistration(),
                    tbtParentCorrelation);
            }
            if (returnType.wrapper() instanceof FieldWrapper.Connection) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.directiveConflict(
                    List.of("asConnection", "splitQuery"),
                    "@asConnection on inline (non-@splitQuery) TableField is not supported; add @splitQuery for batched connection semantics"));
            }
            return new TableField(parentTypeName, name, location,
                returnType, referencePath.elements(), tfc.filters(), tfc.orderBy(), tfc.pagination(),
                tbtParentCorrelation);
        }

        if (tableBacked instanceof TableInterfaceType tableInterfaceType) {
            var wrapper = buildWrapper(fieldDef);
            var referencePath = ctx.parsePath(fieldDef, name, parentTableType.table().tableName(), tableInterfaceType.table().tableName(), tableInterfaceType.table());
            if (referencePath.hasError()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(referencePath.errorMessage()));
            }
            // R379 Check 1: same $fields(terminalAlias) invariant as the TableBoundReturnType arm.
            if (referencePath.terminalTargetVerdict() instanceof BuildContext.TerminalTargetVerdict.Mismatch mismatch) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(mismatch.diagnostic()));
            }
            var components = resolveTableFieldComponents(fieldDef, tableInterfaceType.table(), elementTypeName,
                buildNodeIdArgPlan(fieldDef, tableInterfaceType.table()));
            if (components instanceof TableFieldComponents.Rejected rj) return new UnclassifiedField(parentTypeName, name, location, fieldDef, rj.rejection());
            var tfc = (TableFieldComponents.Ok) components;
            var joinPathError = validateSingleHopFkJoin(referencePath.elements(), name);
            if (joinPathError != null) return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(joinPathError));
            var knownValues = knownDiscriminatorValues(tableInterfaceType);
            return new TableInterfaceField(parentTypeName, name, location,
                new ReturnTypeRef.TableBoundReturnType(elementTypeName, tableInterfaceType.table(), wrapper),
                tableInterfaceType.discriminatorColumn(), knownValues, tableInterfaceType.participants(),
                referencePath.elements(), tfc.filters(), tfc.orderBy(), tfc.pagination());
        }

        if (elementType instanceof InterfaceType interfaceType) {
            // Per-participant FK auto-discovery from parent table to each participant's table.
            // resolveChildPolymorphicJoinPaths is the R452 gate: it rejects a field-level
            // @reference (which cannot express per-participant joins), a same-table participant,
            // and zero/multi-FK auto-discovery failures, so only the auto-discovered single-hop
            // FK shape reaches the emitter.
            var resolved = resolveChildPolymorphicJoinPaths(fieldDef, name, parentTypeName,
                location, parentTableType.table(), interfaceType.participants(), buildWrapper(fieldDef).isList());
            if (resolved.rejection() != null) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, resolved.rejection());
            }
            var pkCols = parentTableType.table().primaryKeyColumns();
            if (pkCols.isEmpty()) {
                // Validator surfaces this as a structural rejection on the parent type's PK
                // (validateChildMultiTableParentPk); routing through UnclassifiedField here keeps
                // the SourceKey canonical-constructor non-empty invariant unreachable.
                return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                    Rejection.structural("multi-table interface child field requires a non-empty "
                        + "primary key on parent type '" + parentTypeName + "'"));
            }
            no.sikt.graphitron.rewrite.model.SourceKey parentSourceKey = buildTableBackedPolymorphicParentSourceKey(pkCols);
            GraphitronType.ResultType parentResultType =
                new GraphitronType.JooqTableRecordType(parentTypeName, location, null, parentTableType.table());
            return new InterfaceField(parentTypeName, name, location,
                new ReturnTypeRef.PolymorphicReturnType(elementTypeName, buildWrapper(fieldDef)),
                interfaceType.participants(), resolved.paths(), parentSourceKey,
                parentTableType.table(), parentResultType);
        }

        if (elementType instanceof UnionType unionType) {
            var resolved = resolveChildPolymorphicJoinPaths(fieldDef, name, parentTypeName,
                location, parentTableType.table(), unionType.participants(), buildWrapper(fieldDef).isList());
            if (resolved.rejection() != null) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, resolved.rejection());
            }
            var pkCols = parentTableType.table().primaryKeyColumns();
            if (pkCols.isEmpty()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                    Rejection.structural("multi-table union child field requires a non-empty "
                        + "primary key on parent type '" + parentTypeName + "'"));
            }
            no.sikt.graphitron.rewrite.model.SourceKey parentSourceKey = buildTableBackedPolymorphicParentSourceKey(pkCols);
            GraphitronType.ResultType parentResultType =
                new GraphitronType.JooqTableRecordType(parentTypeName, location, null, parentTableType.table());
            return new UnionField(parentTypeName, name, location,
                new ReturnTypeRef.PolymorphicReturnType(elementTypeName, buildWrapper(fieldDef)),
                unionType.participants(), resolved.paths(), parentSourceKey,
                parentTableType.table(), parentResultType);
        }

        // NestingField: a plain object type in the schema with no Graphitron domain classification.
        // Its fields are resolved from the same table context as the parent — classified
        // recursively so nested scalars reach the model as ColumnField (and future arms as
        // their respective leaves). class-backed parents cannot reach here; this path is gated
        // on TableBackedType by classifyChildFieldOnTableType's caller at line 1217.
        //
        // R317 slice 3a — "no domain classification" is decided structurally by
        // TypeBuilder.isDirectivelessNestingTarget (the type pass's null verdict, minus carriers and
        // multi-producer rejections), not by reading ctx.types here. The former
        // `elementType == null || elementType instanceof NestingType` guard read the in-progress
        // registry, so once NestingType registration folds onto this edge a sibling edge embedding the
        // same target would observe the sibling's NestingType. The structural verdict is independent of
        // any sibling edge's registration: both Film.details and FilmList.details classify FilmDetails
        // the same way without either seeing the other.
        if (ctx.schema.getType(elementTypeName) instanceof GraphQLObjectType graphQLObjectType
                && typeBuilder.isDirectivelessNestingTarget(elementTypeName)) {
            var wrapper = buildWrapper(fieldDef);
            if (expandingTypes.contains(elementTypeName)) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.invalidSchema("circular type reference detected while expanding '" + elementTypeName + "'"));
            }
            var newExpanding = new LinkedHashSet<>(expandingTypes);
            newExpanding.add(elementTypeName);
            var nestedFields = new ArrayList<ChildField>();
            for (var nestedDef : graphQLObjectType.getFieldDefinitions()) {
                var nested = classifyChildFieldOnTableType(nestedDef, elementTypeName, parentTableType, newExpanding);
                if (nested instanceof UnclassifiedField unc) {
                    String prefix = "nested type '" + elementTypeName + "' field '" + nestedDef.getName() + "': ";
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef, unc.rejection().prefixedWith(prefix));
                }
                if (nested instanceof ChildField cf) {
                    nestedFields.add(cf);
                } else {
                    // Unreachable by construction: classifyChildFieldOnTableType only emits
                    // ChildField or UnclassifiedField (the latter handled above). Surface a
                    // generator bug at the site rather than fabricating a user-facing rejection.
                    throw new AssertionError(
                        "classifyChildFieldOnTableType returned " + nested.getClass().getSimpleName()
                        + " for nested type '" + elementTypeName
                        + "' field '" + nestedDef.getName() + "'");
                }
            }
            return new NestingField(parentTypeName, name, location,
                new ReturnTypeRef.TableBoundReturnType(elementTypeName, parentTableType.table(), wrapper),
                List.copyOf(nestedFields));
        }

        // A @table parent whose child returns a record-backed/service result type has no way to build that
        // child from the parent's own row. The former ConstructorField passthrough materialised the
        // child from the @table parent's Record; R290 dissolved that leaf as wrong-by-design (no
        // production schema relies on it, and its only coverage was self-referential), so the clash is
        // now a build-time rejection that GraphitronSchemaValidator surfaces. The field needs an
        // explicit producer, or the child type needs a catalog backing.
        if (elementType instanceof ResultType) {
            return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(
                "field '" + name + "' on @table type '" + parentTypeName + "' returns '" + elementTypeName
                + "', a record-backed result type, but carries no producer directive to build it: a @table "
                + "parent cannot construct a record-backed child from its own row. Add @service, @reference, "
                + "@tableMethod, or @externalField on the field, or back '" + elementTypeName + "' with @table."));
        }

        return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural("return type '" + elementTypeName + "' is not a @table, record-backed, interface, or union Graphitron type"));
    }

    // ===== Wrapper helpers =====

    /**
     * Builds a {@link FieldWrapper} from the return type shape of the field (cardinality and
     * nullability only). Ordering is separated into {@link #buildOrderBySpec}.
     *
     * <p>Connection is detected two ways:
     * <ol>
     *   <li><b>Directive-driven:</b> the field has {@code @asConnection} — the schema type is a
     *       bare list {@code [Film]} but the wrapper is {@link FieldWrapper.Connection}.</li>
     *   <li><b>Structural:</b> the return type has an {@code edges.node} pattern (pre-expanded
     *       connection from the schema transform or hand-written).</li>
     * </ol>
     */
    FieldWrapper buildWrapper(GraphQLFieldDefinition fieldDef) {
        return ctx.buildWrapper(fieldDef);
    }


    /**
     * Classifies every GraphQL argument on the field into an {@link ArgumentRef} variant in one
     * pass. Projection into {@link WhereFilter} / {@link OrderBySpec} / {@link PaginationSpec} /
     * {@code LookupMapping} happens in dedicated projector methods, not here.
     *
     * <p>The intent of this method is to localise the "what is this argument for" decision so
     * multiple projections can read the same classification. See
     * {@code docs/argument-resolution.md}.
     *
     * <p>Errors append to {@code errors} but never cause a {@code null} return: every arg maps
     * to a variant. Variants like {@link ArgumentRef.ScalarArg.UnboundArg} and
     * {@link ArgumentRef.UnclassifiedArg} carry a {@code reason} that {@link #projectFilters}
     * surfaces into the field-level {@code errors} list.
     *
     * <p>{@code rt} is the target {@link TableRef} used to resolve scalar column args; every
     * current caller passes the field's resolved table, so this method does not accept
     * {@code null}.
     */
    List<ArgumentRef> classifyArguments(GraphQLFieldDefinition fieldDef, TableRef rt, NodeIdArgPlan plan, List<String> errors) {
        var fieldCondition = ctx.readConditionDirective(fieldDef);
        boolean fieldOverride = fieldCondition != null && fieldCondition.override();
        var refs = new ArrayList<ArgumentRef>();
        for (var arg : fieldDef.getArguments()) {
            refs.add(classifyArgument(fieldDef, arg, rt, fieldOverride, plan, errors));
        }
        return List.copyOf(refs);
    }

    private ArgumentRef classifyArgument(GraphQLFieldDefinition fieldDef, GraphQLArgument arg,
                                         TableRef rt, boolean fieldOverride,
                                         NodeIdArgPlan plan, List<String> errors) {
        String name = arg.getName();
        GraphQLType type = arg.getType();
        boolean nonNull = type instanceof GraphQLNonNull;
        boolean list = GraphQLTypeUtil.unwrapNonNull(type) instanceof GraphQLList;
        String typeName = ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(type)).getName();

        if (arg.hasAppliedDirective(DIR_ORDER_BY)) {
            return classifyOrderByArg(arg, name, typeName, nonNull, list, errors);
        }
        if (paginationResolver.isPaginationArg(name)) {
            ArgumentRef.PaginationArgRef.Role role = switch (name) {
                case "first"  -> ArgumentRef.PaginationArgRef.Role.FIRST;
                case "last"   -> ArgumentRef.PaginationArgRef.Role.LAST;
                case "after"  -> ArgumentRef.PaginationArgRef.Role.AFTER;
                case "before" -> ArgumentRef.PaginationArgRef.Role.BEFORE;
                default       -> throw new IllegalStateException("unreachable: isPaginationArg(" + name + ")");
            };
            return new ArgumentRef.PaginationArgRef(name, typeName, nonNull, list, role);
        }

        Optional<ArgConditionRef> argCondition = switch (conditionResolver.resolveArg(arg)) {
            case ConditionResolver.ArgConditionResult.None n -> Optional.empty();
            case ConditionResolver.ArgConditionResult.Ok ok -> Optional.of(ok.ref());
            case ConditionResolver.ArgConditionResult.Rejected r -> {
                errors.add(r.message());
                yield Optional.empty();
            }
        };

        // Route the arg to an input-shaped classification when the classifier recognises its type
        // as something input-like. TableInputType keeps its dedicated binding resolution.
        // InputType (Pojo / Java record / jOOQ record) and UnclassifiedType (input resolution
        // failed — e.g. FilmKey unresolvable against the surrounding table) both go through the
        // plain-input path so lookup-key search still runs and produces a focused error.
        // R317 slice 3e — registry-free look-ahead at the argument's input type, reachable from this
        // field; reproduces the ctx.types verdict (including a binding-rejected UnclassifiedType)
        // without reading the in-progress registry.
        var resolvedType = typeBuilder.lookAheadVerdict(typeName);
        if (resolvedType instanceof GraphitronType.TableInputType tit) {
            // R144: @lookupKey on INPUT_FIELD_DEFINITION is retired. Query-side @table input args
            // derive their lookup binding set from arg-level @lookupKey on ARGUMENT_DEFINITION:
            // every admissible input field becomes a binding when the arg carries the directive.
            // When it doesn't, no bindings are produced (filter-only flow handled via
            // walkInputFieldConditions).
            List<InputColumnBindingGroup> bindings = arg.hasAppliedDirective(DIR_LOOKUP_KEY)
                ? enumMappingResolver.buildLookupBindings(tit, arg, fieldDef, name, errors)
                : List.of();
            return ArgumentRef.InputTypeArg.TableInputArg.of(
                name, typeName, nonNull, list, tit.table(), bindings, argCondition, tit.inputFields());
        }
        boolean isInputLike = resolvedType instanceof GraphitronType.InputType
            || (resolvedType instanceof GraphitronType.UnclassifiedType
                && ctx.schema.getType(typeName) instanceof GraphQLInputObjectType);
        if (isInputLike) {
            // The plain-input path's per-field errors are silently dropped by projectFilters
            // unless paired with @condition / @lookupKey gates. @notGenerated is a hard policy
            // violation, so reject the whole arg up front via UnclassifiedArg, which sets
            // hadError in projectFilters and propagates as an UnclassifiedField on the
            // surrounding query field.
            if (ctx.schema.getType(typeName) instanceof GraphQLInputObjectType iot) {
                var rejected = iot.getFieldDefinitions().stream()
                    .filter(f -> f.hasAppliedDirective(DIR_NOT_GENERATED))
                    .findFirst();
                if (rejected.isPresent()) {
                    return new ArgumentRef.UnclassifiedArg(name, typeName, nonNull, list,
                        Rejection.structural("input field '" + rejected.get().getName()
                        + "': @notGenerated is no longer supported. Remove the directive; fields must be fully described by the schema."));
                }
                var retiredLookupKey = iot.getFieldDefinitions().stream()
                    .filter(f -> f.hasAppliedDirective(DIR_LOOKUP_KEY))
                    .findFirst();
                if (retiredLookupKey.isPresent()) {
                    return new ArgumentRef.UnclassifiedArg(name, typeName, nonNull, list,
                        Rejection.structural("input field '" + retiredLookupKey.get().getName()
                        + "': @lookupKey on a mutation input field is no longer supported (R144); "
                        + "remove it (the field is a filter by default; the UPDATE SET/WHERE "
                        + "partition is derived from the catalog by the walker). On Query-side "
                        + "@table input args, move @lookupKey to the surrounding ARGUMENT_DEFINITION "
                        + "instead."));
                }
            }
            // R215: thread the call site's cascade override flag into the classifier. The
            // plain-input field-level @condition(override:true) on the enclosing query field is
            // already folded into fieldOverride; the consuming argument's arg-level override
            // composes with it. Either flag flipping true suppresses the implicit predicate for
            // every classified field, so column-miss UnboundFields at this level are admitted by
            // the consumer's walk.
            boolean enclosingOverride = fieldOverride
                || argCondition.map(c -> c.override()).orElse(false);
            return switch (inputFieldResolver.resolve(typeName, rt, enclosingOverride)) {
                case InputFieldResolver.Resolution.Ok ok -> new ArgumentRef.InputTypeArg.PlainInputArg(
                    name, typeName, nonNull, list, argCondition, ok.fields());
                case InputFieldResolver.Resolution.Rejected r -> new ArgumentRef.UnclassifiedArg(
                    name, typeName, nonNull, list, r.rejection());
            };
        }

        // @nodeId-decorated ID arg routes through NodeIdLeafResolver to pick same-table
        // (filter; explicit @lookupKey opts back into lookup shape) vs FK-target (filter)
        // shape. Sits before the legacy implicit scalar-ID arm below, which keeps owning
        // synthesised paths (no @nodeId declared, parent table has nodeId metadata) per scope.
        if ("ID".equals(typeName) && arg.hasAppliedDirective(DIR_NODE_ID)) {
            // Composition rejections: @nodeId is incompatible with @field(name:) (the two
            // target different binding axes — key columns come from the resolved NodeType,
            // not the directive). @lookupKey is rejected only on the FK-target arm (where
            // FK is a filter, not a lookup, so @lookupKey is meaningless); on the same-table
            // arm @lookupKey is a deliberate opt-in that re-enables the N×M lookup shape
            // (handled inside the SameTable arm below).
            if (arg.hasAppliedDirective(DIR_FIELD)) {
                return new ArgumentRef.UnclassifiedArg(name, typeName, nonNull, list,
                    Rejection.structural("@nodeId arg cannot also carry @field(name:); the directives target different"
                    + " binding axes (key columns come from the resolved NodeType, not the"
                    + " @field directive)"));
            }
            var resolved = plan.byArgName().get(name);
            if (resolved == null) {
                resolved = ctx.nodeIdLeafResolver().resolve(arg, name, rt);
            }
            switch (resolved) {
                case NodeIdLeafResolver.Resolved.Rejected r ->
                    { return new ArgumentRef.UnclassifiedArg(name, typeName, nonNull, list, r.rejection()); }
                case NodeIdLeafResolver.Resolved.SameTable st -> {
                    // Same-table @nodeId arg = filter semantics (WHERE pk IN (decoded_ids) /
                    // RowIn for composite PKs). A malformed or wrong-type encoded id throws a
                    // GraphitronClientException via ThrowOnMismatch (R378): a bad filter id is a
                    // client mistake worth surfacing, not silently dropping to "no row matches".
                    // Explicit @lookupKey re-enables the N×M derived-table lookup shape — the only
                    // remaining same-table-arg path into QueryLookupTableField after R106. The
                    // emitter's per-row decode site branches on the NodeIdDecodeKeys arm; both
                    // filter and synthesised-lookup-key paths now throw, distinguished only by the
                    // decode helper's two-branch message.
                    boolean isLookupKey = arg.hasAppliedDirective(DIR_LOOKUP_KEY);
                    var extraction = new CallSiteExtraction.ThrowOnMismatch(st.decodeMethod());
                    var keys = st.keyColumns();
                    if (keys.size() == 1) {
                        return new ArgumentRef.ScalarArg.ColumnArg(
                            name, typeName, nonNull, list, keys.get(0), extraction,
                            argCondition, fieldOverride, isLookupKey, List.of());
                    }
                    return new ArgumentRef.ScalarArg.CompositeColumnArg(
                        name, typeName, nonNull, list, keys, extraction,
                        argCondition, fieldOverride, isLookupKey);
                }
                case NodeIdLeafResolver.Resolved.FkTarget.DirectFk direct -> {
                    // FK-target @nodeId arg = filter semantics. Throw extraction (malformed or
                    // wrong-type ids surface a GraphitronClientException, R378, rather than dropping
                    // silently to "no match"). projectFilters emits BodyParam.In/Eq/RowIn/RowEq
                    // using DirectFk's fkSourceColumns directly — no JOIN, the resolver has already
                    // verified the FK's targetColumns positionally match the NodeType key columns.
                    if (arg.hasAppliedDirective(DIR_LOOKUP_KEY)) {
                        return new ArgumentRef.UnclassifiedArg(name, typeName, nonNull, list,
                            Rejection.structural("@lookupKey is meaningless on an FK-target @nodeId arg; FK-target"
                            + " @nodeId is a filter, not a lookup"));
                    }
                    var extraction = new CallSiteExtraction.ThrowOnMismatch(direct.decodeMethod());
                    var keys = direct.keyColumns();
                    if (keys.size() == 1) {
                        return new ArgumentRef.ScalarArg.ColumnReferenceArg(
                            name, typeName, nonNull, list, keys.get(0), direct.joinPath(),
                            direct.liftedSourceColumns(),
                            extraction, argCondition, fieldOverride);
                    }
                    return new ArgumentRef.ScalarArg.CompositeColumnReferenceArg(
                        name, typeName, nonNull, list, keys, direct.joinPath(),
                        direct.liftedSourceColumns(),
                        extraction, argCondition, fieldOverride);
                }
                case NodeIdLeafResolver.Resolved.FkTarget.TranslatedFk translated -> {
                    // Pathological case (FK targetColumns ≠ NodeType keyColumns; e.g. the
                    // parent_node + child_ref fixture). Emission requires JOIN-with-translation
                    // and is deferred until output-side JOIN-with-projection emission ships.
                    return new ArgumentRef.UnclassifiedArg(name, typeName, nonNull, list,
                        Rejection.structural(translatedFkRejectionReason(translated.refTypeName(), rt.tableName())));
                }
            }
        }

        // Scalar ID arg on a node-type table folds onto a column-shaped
        // carrier with NodeIdDecodeKeys.ThrowOnMismatch. Arity-1 → ColumnArg (single key column);
        // arity ≥ 2 → CompositeColumnArg (per-row decode produces a Record<N>; bindings index
        // positionally). Both arms carry the per-NodeType decode<TypeName> helper.
        //
        // Today's classifier only emits the lookup-key path for both arms (consumed by
        // LookupMappingResolver → ScalarLookupArg / DecodedRecord). Non-lookup-key composite-PK
        // NodeId args (mutation key, top-level filter) need projectFilters wiring that has
        // not yet shipped; surface them as UnclassifiedArg so the gap is visible at validate
        // time rather than silently producing a degenerate path.
        if ("ID".equals(typeName)) {
            Optional<JooqCatalog.NodeIdMetadata> nodeIdMeta = ctx.catalog.nodeIdMetadata(rt.tableName());
            if (nodeIdMeta.isPresent()) {
                boolean isLookupKey = arg.hasAppliedDirective(DIR_LOOKUP_KEY);
                var keyColumns = nodeIdMeta.get().keyColumns();
                // Composite-PK + non-list non-@lookupKey: only the @lookupKey path is wired;
                // mutation-key and top-level filter paths are not yet supported. Non-list arity-1
                // falls through to ColumnArg below.
                if (keyColumns.size() > 1 && !isLookupKey) {
                    return new ArgumentRef.UnclassifiedArg(name, typeName, nonNull, list,
                        Rejection.structural("scalar @nodeId arg targeting a composite-PK NodeType is only wired for "
                        + "@lookupKey; mutation-key and top-level filter paths are not yet supported"));
                }
                // List + arity-1 without @lookupKey is not yet wired (would be a top-level
                // filter use case); falls through to column-name resolution which fails cleanly.
                if (list && keyColumns.size() == 1 && !isLookupKey) {
                    // Fall through to column-name resolution.
                } else {
                    var decodeMethod = ctx.resolveDecodeHelperForTable(
                        rt.tableName(), nodeIdMeta.get().typeId(), keyColumns);
                    if (decodeMethod == null) {
                        return new ArgumentRef.UnclassifiedArg(name, typeName, nonNull, list,
                            Rejection.structural("@nodeId arg: unable to resolve decode helper for table '" + rt.tableName() + "'"));
                    }
                    var extraction = new CallSiteExtraction.ThrowOnMismatch(decodeMethod);
                    if (keyColumns.size() == 1) {
                        return new ArgumentRef.ScalarArg.ColumnArg(
                            name, typeName, nonNull, list, keyColumns.get(0), extraction,
                            argCondition, fieldOverride, isLookupKey, List.of());
                    }
                    return new ArgumentRef.ScalarArg.CompositeColumnArg(
                        name, typeName, nonNull, list, keyColumns, extraction,
                        argCondition, fieldOverride, isLookupKey);
                }
            }
        }

        // R380 — scalar arg carrying @reference(path:) reaching a column on a *joined* table.
        // Unlike the @nodeId FK-target arm above (which lifts to local FK columns and emits no
        // join), a plain @reference filter resolves the column against the *terminal* table and
        // emits a correlated EXISTS. Read the path before the local findColumn so the column never
        // mis-binds against the field's own table. v1 supports FK-derived paths only; a condition-join
        // hop is deferred (mirrors FkTargetConditionEmitter and the output-side @reference stub).
        if (arg.hasAppliedDirective(DIR_REFERENCE)) {
            // R435 made @reference repeatable so field-level applications compose the table
            // chain; order-composition has no meaning on an argument, so repetition here is a
            // conflict, not a chain.
            if (arg.getAppliedDirectives(DIR_REFERENCE).size() > 1) {
                return new ArgumentRef.UnclassifiedArg(name, typeName, nonNull, list,
                    Rejection.directiveConflict(List.of(DIR_REFERENCE),
                        "repeated @reference on an argument is not supported — ordered chain "
                        + "composition applies only to output field definitions; compose the chain "
                        + "on the field instead"));
            }
            var refPath = ctx.parsePath(arg, name, rt.tableName(), null);
            if (refPath.hasError()) {
                return new ArgumentRef.UnclassifiedArg(name, typeName, nonNull, list,
                    Rejection.structural("argument '" + name + "': " + refPath.errorMessage()));
            }
            if (refPath.elements().stream().anyMatch(h -> !(h instanceof JoinStep.Hop hh && hh.on() instanceof On.ColumnPairs))) {
                return new ArgumentRef.UnclassifiedArg(name, typeName, nonNull, list,
                    Rejection.structural(referenceFilterConditionJoinRejection(name)));
            }
            String refColumnName = argString(arg, DIR_FIELD, ARG_NAME).orElse(name);
            var refCol = svc.resolveColumnForReference(refColumnName, refPath.elements(), rt);
            if (refCol.isEmpty()) {
                return new ArgumentRef.ScalarArg.UnboundArg(
                    name, typeName, nonNull, list, refColumnName,
                    "no column '" + refColumnName + "' reachable via @reference path from table '"
                        + rt.tableName() + "'");
            }
            var refColumnRef = refCol.get();
            String refEnumClassName;
            switch (enumMappingResolver.validateEnumFilter(typeName, refColumnRef)) {
                case EnumMappingResolver.EnumValidation.NotEnum n -> refEnumClassName = null;
                case EnumMappingResolver.EnumValidation.Valid v -> refEnumClassName = v.fqcn();
                case EnumMappingResolver.EnumValidation.Mismatch m -> {
                    errors.add(m.message());
                    return new ArgumentRef.UnclassifiedArg(name, typeName, nonNull, list,
                        Rejection.structural("enum filter validation failed for column '" + refColumnRef.sqlName() + "'"));
                }
            }
            CallSiteExtraction refExtraction = enumMappingResolver.deriveExtraction(typeName, refColumnRef, refEnumClassName);
            boolean refIsLookupKey = arg.hasAppliedDirective(DIR_LOOKUP_KEY);
            return new ArgumentRef.ScalarArg.ColumnArg(
                name, typeName, nonNull, list, refColumnRef, refExtraction,
                argCondition, fieldOverride, refIsLookupKey, refPath.elements());
        }

        // Scalar arg: bind to column
        String columnName = argString(arg, DIR_FIELD, ARG_NAME).orElse(name);
        var col = ctx.catalog.findColumn(rt.tableName(), columnName);
        if (col.isEmpty()) {
            return new ArgumentRef.ScalarArg.UnboundArg(
                name, typeName, nonNull, list, columnName,
                "column '" + columnName + "' could not be resolved in table '" + rt.tableName() + "'"
                    + candidateHint(columnName, ctx.catalog.columnJavaNamesOf(rt.tableName())));
        }
        var columnRef = new ColumnRef(col.get().sqlName(), col.get().javaName(), col.get().columnClass(), col.get().columnType());
        String enumClassName;
        switch (enumMappingResolver.validateEnumFilter(typeName, columnRef)) {
            case EnumMappingResolver.EnumValidation.NotEnum n -> enumClassName = null;
            case EnumMappingResolver.EnumValidation.Valid v -> enumClassName = v.fqcn();
            case EnumMappingResolver.EnumValidation.Mismatch m -> {
                errors.add(m.message());
                // Emit UnclassifiedArg so projectFilters surfaces the structural failure (even
                // though the mismatch is already an error; keeping this consistent keeps the
                // classify-never-returns-null invariant).
                return new ArgumentRef.UnclassifiedArg(name, typeName, nonNull, list,
                    Rejection.structural("enum filter validation failed for column '" + columnRef.sqlName() + "'"));
            }
        }
        CallSiteExtraction extraction = enumMappingResolver.deriveExtraction(typeName, columnRef, enumClassName);
        boolean isLookupKey = arg.hasAppliedDirective(DIR_LOOKUP_KEY);
        return new ArgumentRef.ScalarArg.ColumnArg(
            name, typeName, nonNull, list, columnRef, extraction, argCondition, fieldOverride, isLookupKey, List.of());
    }

    /**
     * Shared rejection text for a scalar {@code @reference} filter path that traverses a
     * non-foreign-key (condition-join) hop. v1 emits the correlated EXISTS through FK hops
     * only; the {@code condition:} reference form is still a runtime-throwing stub on the output
     * side, so a reference *filter* path through it is rejected at validate time. Asserted on by
     * the Surface-2 condition-join rejection test and mirrored by the validator.
     */
    static String referenceFilterConditionJoinRejection(String argName) {
        return "argument '" + argName + "': @reference filter path traverses a condition-join "
            + "(non-foreign-key) hop, which is not yet supported; reference filters emit a "
            + "foreign-key correlated subquery and require every hop to resolve to a foreign key";
    }

    private ArgumentRef classifyOrderByArg(GraphQLArgument arg, String name, String typeName,
                                           boolean nonNull, boolean list, List<String> errors) {
        var rawType = ctx.schema.getType(typeName);
        if (!(rawType instanceof GraphQLInputObjectType inputType)) {
            return new ArgumentRef.UnclassifiedArg(name, typeName, nonNull, list,
                Rejection.structural("@orderBy argument type '" + typeName + "' is not an input type"));
        }
        String sortFieldName = null;
        String directionFieldName = null;
        for (var field : inputType.getFieldDefinitions()) {
            var fieldType = GraphQLTypeUtil.unwrapNonNull(field.getType());
            if (!(fieldType instanceof GraphQLEnumType enumType)) continue;
            boolean isSortEnum = enumType.getValues().stream()
                .anyMatch(v -> v.hasAppliedDirective("order") || v.hasAppliedDirective("index"));
            if (isSortEnum) {
                if (sortFieldName != null) {
                    return new ArgumentRef.UnclassifiedArg(name, typeName, nonNull, list,
                        Rejection.structural("@orderBy input type '" + typeName + "' must have exactly one sort enum field, but found multiple"));
                }
                sortFieldName = field.getName();
            } else {
                if (directionFieldName != null) {
                    return new ArgumentRef.UnclassifiedArg(name, typeName, nonNull, list,
                        Rejection.structural("@orderBy input type '" + typeName + "' must have exactly one direction field, but found multiple"));
                }
                directionFieldName = field.getName();
            }
        }
        if (sortFieldName == null) {
            return new ArgumentRef.UnclassifiedArg(name, typeName, nonNull, list,
                Rejection.structural("@orderBy input type '" + typeName + "' has no sort enum field (no enum values with @order)"));
        }
        if (directionFieldName == null) {
            return new ArgumentRef.UnclassifiedArg(name, typeName, nonNull, list,
                Rejection.structural("@orderBy input type '" + typeName + "' has no direction field"));
        }
        return new ArgumentRef.OrderByArg(name, typeName, nonNull, list, sortFieldName, directionFieldName);
    }

    /**
     * Runs the full filter / orderBy / pagination projection for a table-bound field, using
     * {@link #classifyArguments} output as the single source of truth about each argument.
     * Replaces the legacy three-pass model ({@code buildFilters} / {@code buildOrderBySpec} /
     * {@code buildPaginationSpec}) with one classification + one projection step. See
     * {@code docs/argument-resolution.md}.
     */
    private TableFieldComponents projectForFilter(List<ArgumentRef> refs, GraphQLFieldDefinition fieldDef,
                                                  TableRef rt, String returnTypeName, List<Rejection> errors) {
        var filters = projectFilters(refs, fieldDef, rt, returnTypeName, errors);
        if (filters == null) return new TableFieldComponents.Rejected(foldRejections(errors));
        ConditionFilter fieldCondition;
        switch (conditionResolver.resolveField(fieldDef)) {
            case ConditionResolver.FieldConditionResult.None n -> fieldCondition = null;
            case ConditionResolver.FieldConditionResult.Ok ok -> fieldCondition = ok.filter();
            case ConditionResolver.FieldConditionResult.Rejected r -> {
                errors.add(r.rejection());
                return new TableFieldComponents.Rejected(foldRejections(errors));
            }
        }
        if (fieldCondition != null) {
            var withField = new ArrayList<>(filters);
            withField.add(fieldCondition);
            filters = List.copyOf(withField);
        }
        var orderByResolved = orderByResolver.resolve(refs, fieldDef, rt.tableName());
        if (orderByResolved instanceof OrderByResolver.Resolved.Rejected r) {
            errors.add(r.rejection());
            return new TableFieldComponents.Rejected(foldRejections(errors));
        }
        OrderBySpec orderBy = ((OrderByResolver.Resolved.Ok) orderByResolved).spec();
        var lookupMapping = lookupMappingResolver.resolve(refs, rt);
        // LookupField invariant: if any @lookupKey is present, the mapping must be non-empty.
        // ColumnMapping must have at least one arg. Scalar NodeId @lookupKey args fold onto
        // ColumnMapping carrying ScalarLookupArg with NodeIdDecodeKeys.ThrowOnMismatch.
        boolean emptyMapping = switch (lookupMapping) {
            case ColumnMapping cm -> cm.args().isEmpty();
        };
        if (hasLookupKeyAnywhere(fieldDef) && emptyMapping) {
            // Prefer the specific binding-failure reason (e.g. @lookupKey on a @reference field)
            // when buildLookupBindings recorded one; fall back to the generic empty-mapping error.
            Rejection r = errors.isEmpty()
                ? Rejection.structural("@lookupKey is declared but no argument resolved to a lookup column")
                : foldRejections(errors);
            return new TableFieldComponents.Rejected(r);
        }
        return new TableFieldComponents.Ok(filters, orderBy, paginationResolver.resolve(refs, fieldDef), lookupMapping);
    }

    /**
     * Folds a list of typed {@link Rejection}s into a single one. Single-entry lists forward the
     * typed value directly so structured payloads (e.g. {@link Rejection.AuthorError.UnknownName})
     * survive end-to-end to {@code UnclassifiedField.rejection}; multi-entry lists collapse to a
     * joined {@link Rejection#structural} since no single typed arm can carry multiple distinct
     * structured payloads.
     */
    private static Rejection foldRejections(List<Rejection> errors) {
        if (errors.size() == 1) return errors.get(0);
        return Rejection.structural(errors.stream()
            .map(Rejection::message)
            .collect(Collectors.joining("; ")));
    }

    /**
     * Resolves the Java type that a {@link BodyParam} must carry given its extraction strategy
     * and target column. Extracted from the old {@code buildFilters} switch so projection can
     * derive {@link BodyParam#javaType} without re-classifying the argument.
     */
    private static String javaTypeFor(CallSiteExtraction extraction, ColumnRef column) {
        return switch (extraction) {
            case CallSiteExtraction.EnumValueOf ev -> ev.enumClassName();
            case CallSiteExtraction.JooqConvert ignored -> column.columnClass();
            case CallSiteExtraction.Direct ignored -> column.columnClass();
            case CallSiteExtraction.ContextArg ignored -> column.columnClass();
            case CallSiteExtraction.NestedInputField ignored -> column.columnClass();
            case CallSiteExtraction.NodeIdDecodeKeys ignored -> column.columnClass();
            case CallSiteExtraction.InputBean ignored -> column.columnClass();
            // NodeIdDecodeRecord is an input-bean field leaf, never a BodyParam extraction; like the
            // InputBean arm above it cannot reach a column-bound predicate, so the column's own type
            // is the trivially-correct (unreached) answer.
            case CallSiteExtraction.NodeIdDecodeRecord ignored -> column.columnClass();
            // R311: JooqRecord is a top-level @service param extraction, never a column-bound BodyParam;
            // unreached here, same trivially-correct answer as the NodeIdDecodeRecord arm above.
            case CallSiteExtraction.JooqRecord ignored -> column.columnClass();
        };
    }

    /**
     * Projects the classified arguments into a {@link WhereFilter} list for a table-bound field.
     *
     * <p>{@link ArgumentRef.OrderByArg} and {@link ArgumentRef.PaginationArgRef} are skipped
     * (handled by {@link OrderByResolver} / {@link PaginationResolver}).
     * {@link ArgumentRef.UnclassifiedArg} and {@link ArgumentRef.ScalarArg.UnboundArg} add to
     * {@code errors}. For {@link ArgumentRef.InputTypeArg} variants the arg-level, input-field-level
     * {@code @condition} predicates and (for {@code @table} inputs) implicit column-equality
     * predicates on un-annotated fields are emitted via {@link #walkInputFieldConditions}.
     * Returns {@code null} when any filter classification fails.
     *
     * <p>All column-bound scalar args and implicit column-equality predicates from {@code @table}
     * input fields are grouped into a single {@link GeneratedConditionFilter} entry. The condition
     * class is named {@code <returnTypeName>Conditions} and the method {@code <fieldName>Condition}.
     */
    private List<WhereFilter> projectFilters(List<ArgumentRef> refs, GraphQLFieldDefinition fieldDef,
                                             TableRef rt, String returnTypeName, List<Rejection> errors) {
        var bodyParams = new ArrayList<BodyParam>();
        var argConditions = new ArrayList<WhereFilter>();
        boolean hadError = false;
        var fieldCond = ctx.readConditionDirective(fieldDef);
        boolean fieldOverride = fieldCond != null && fieldCond.override();
        for (var ref : refs) {
            switch (ref) {
                case ArgumentRef.OrderByArg ignored -> {}                     // handled by OrderByResolver
                case ArgumentRef.PaginationArgRef ignored -> {}               // handled by PaginationResolver
                case ArgumentRef.InputTypeArg.TableInputArg tia -> {
                    // Arg-level @condition and field-level @condition predicates.
                    tia.argCondition().ifPresent(ac -> argConditions.add(ac.filter()));
                    // Implicit predicates are suppressed when any ancestor carries override:true.
                    boolean enclosingOverride = fieldOverride
                        || tia.argCondition().map(ArgConditionRef::override).orElse(false);
                    var lookupBoundNames = new java.util.HashSet<String>();
                    for (var g : tia.fieldBindings()) {
                        switch (g) {
                            case InputColumnBindingGroup.MapGroup mg -> {
                                for (var b : mg.bindings()) lookupBoundNames.add(b.fieldName());
                            }
                            case InputColumnBindingGroup.DecodedRecordGroup drg ->
                                lookupBoundNames.add(drg.sourceFieldName());
                        }
                    }
                    var implicitParams = new ArrayList<BodyParam>();
                    int errorsBefore = errors.size();
                    // Distinguish real-@table inputs from promoted-plain inputs in error prose.
                    // A plain input type used by a single @table-bound query field is promoted to
                    // TableInputType (TypeBuilder.buildInputType); from the author's perspective
                    // it's still a "plain input type", so the rejection prose reflects the SDL view.
                    boolean hasTableDirective = ctx.schema.getType(tia.typeName())
                            instanceof graphql.schema.GraphQLInputObjectType iot
                        && iot.hasAppliedDirective(BuildContext.DIR_TABLE);
                    String tiaSummary = hasTableDirective
                        ? "@table input '" + tia.typeName() + "'"
                        : "plain input type '" + tia.typeName() + "'";
                    walkInputFieldConditions(tia.fields(), tia.name(), List.of(),
                        enclosingOverride, tia.nonNull(),
                        lookupBoundNames, implicitParams, argConditions, errors,
                        tia.inputTable(), tiaSummary);
                    if (errors.size() > errorsBefore) hadError = true;
                    bodyParams.addAll(implicitParams);
                }
                case ArgumentRef.InputTypeArg.PlainInputArg pia -> {
                    // Structurally identical to the TableInputArg branch above; the only
                    // deltas are no @lookupKey binding set (plain inputs don't carry
                    // LookupKeyFields) and seeding override from pia.argCondition().
                    pia.argCondition().ifPresent(ac -> argConditions.add(ac.filter()));
                    boolean enclosingOverride = fieldOverride
                        || pia.argCondition().map(ArgConditionRef::override).orElse(false);
                    var implicitParams = new ArrayList<BodyParam>();
                    int errorsBefore = errors.size();
                    walkInputFieldConditions(pia.fields(), pia.name(), List.of(),
                        enclosingOverride, pia.nonNull(),
                        Set.of(), implicitParams, argConditions, errors, rt,
                        "plain input type '" + pia.typeName() + "'");
                    if (errors.size() > errorsBefore) hadError = true;
                    bodyParams.addAll(implicitParams);
                }
                case ArgumentRef.UnclassifiedArg u -> {
                    errors.add(u.rejection().prefixedWith("argument '" + u.name() + "': "));
                    hadError = true;
                }
                case ArgumentRef.ScalarArg.UnboundArg u -> {
                    errors.add(Rejection.structural("argument '" + u.name() + "': " + u.reason()));
                    hadError = true;
                }
                case ArgumentRef.ScalarArg.ColumnArg ca -> {
                    boolean autoSuppressed = ca.suppressedByFieldOverride()
                        || (ca.argCondition().isPresent() && ca.argCondition().get().override());
                    // Lookup-key args are consumed by LookupMappingResolver → LookupMapping and
                    // emitted via VALUES+JOIN by LookupValuesJoinEmitter. They must not appear
                    // as GeneratedConditionFilter bodyParams.
                    if (!autoSuppressed && !ca.isLookupKey()) {
                        String javaType = javaTypeFor(ca.extraction(), ca.column());
                        BodyParam.ColumnPredicate inner = ca.list()
                            ? new BodyParam.In(ca.name(), ca.column(), javaType, ca.nonNull(), ca.extraction())
                            : new BodyParam.Eq(ca.name(), ca.column(), javaType, ca.nonNull(), ca.extraction());
                        // R380: a non-empty joinPath means @reference reached the terminal column on
                        // a joined table — wrap the predicate in a correlated EXISTS. Empty path is
                        // the local-column case (today's behavior); the column already binds to the
                        // field's own table.
                        bodyParams.add(ca.joinPath().isEmpty()
                            ? inner
                            : new BodyParam.RemoteColumnPredicate(ca.joinPath(), inner));
                    }
                    ca.argCondition().ifPresent(ac -> argConditions.add(ac.filter()));
                }
                case ArgumentRef.ScalarArg.CompositeColumnArg cca -> {
                    boolean autoSuppressed = cca.suppressedByFieldOverride()
                        || (cca.argCondition().isPresent() && cca.argCondition().get().override());
                    // Composite-PK NodeId scalar args reach this branch with isLookupKey == false
                    // when @nodeId(typeName: T) targets the field's own table without an explicit
                    // @lookupKey (the same-table arm synthesises isLookupKey: true via
                    // classifyArgument; non-lookup-key composite-PK args are top-level filter
                    // args under @condition / @field paths). Project to BodyParam.RowEq (scalar)
                    // / RowIn (list) using the carrier's column tuple and NodeIdDecodeKeys
                    // extraction; LookupMappingResolver consumes the isLookupKey branch separately.
                    if (!autoSuppressed && !cca.isLookupKey()) {
                        bodyParams.add(cca.list()
                            ? new BodyParam.RowIn(cca.name(), cca.columns(), cca.nonNull(), cca.extraction())
                            : new BodyParam.RowEq(cca.name(), cca.columns(), cca.nonNull(), cca.extraction()));
                    }
                    cca.argCondition().ifPresent(ac -> argConditions.add(ac.filter()));
                }
                case ArgumentRef.ScalarArg.ColumnReferenceArg cra -> {
                    // FK-target arm. The carrier's column is the target NodeType's key column;
                    // joinPath[0] holds the FK whose sourceColumns sit on the field's own
                    // containing table. When the FK targetColumns positionally match the NodeType
                    // key columns (the simple direct-FK case), emit the predicate against the FK
                    // source columns directly — no JOIN needed, the decoded keys feed
                    // BodyParam.Eq / In against table.<fkSourceColumn>.
                    //
                    // The pathological case (FK targetColumns ≠ NodeType keyColumns; e.g. the
                    // parent_node + child_ref fixture where the FK targets a non-PK unique
                    // column) is rejected at classify time, so this arm only sees the simple
                    // case. JOIN-with-translation emission for the pathological case is deferred
                    // until output-side JOIN-with-projection emission ships.
                    boolean autoSuppressed = cra.suppressedByFieldOverride()
                        || (cra.argCondition().isPresent() && cra.argCondition().get().override());
                    if (!autoSuppressed) {
                        // Read the resolver's liftedSourceColumns directly — the column tuple on
                        // the parent's own table positionally aligned with the decoded NodeType
                        // keys. Length-1 single-hop or length-≥2 identity-carrying chain lift to
                        // the same shape; chain length is purely a classifier-time concept.
                        ColumnRef liftedColumn = cra.liftedSourceColumns().get(0);
                        String javaType = javaTypeFor(cra.extraction(), liftedColumn);
                        bodyParams.add(cra.list()
                            ? new BodyParam.In(cra.name(), liftedColumn, javaType, cra.nonNull(), cra.extraction())
                            : new BodyParam.Eq(cra.name(), liftedColumn, javaType, cra.nonNull(), cra.extraction()));
                    }
                    cra.argCondition().ifPresent(ac -> argConditions.add(ac.filter()));
                }
                case ArgumentRef.ScalarArg.CompositeColumnReferenceArg ccra -> {
                    // FK-target composite arm. Analogous to ColumnReferenceArg but with a
                    // RowEq / RowIn predicate against the lifted source-column tuple on the
                    // parent's own table. Same direct-FK precondition (terminal hop's target
                    // columns positionally match NodeType keys); intermediate hops, if any, are
                    // constrained by the lift predicate. Pathological cases are rejected at
                    // classify time.
                    boolean autoSuppressed = ccra.suppressedByFieldOverride()
                        || (ccra.argCondition().isPresent() && ccra.argCondition().get().override());
                    if (!autoSuppressed) {
                        List<ColumnRef> liftedColumns = ccra.liftedSourceColumns();
                        bodyParams.add(ccra.list()
                            ? new BodyParam.RowIn(ccra.name(), liftedColumns, ccra.nonNull(), ccra.extraction())
                            : new BodyParam.RowEq(ccra.name(), liftedColumns, ccra.nonNull(), ccra.extraction()));
                    }
                    ccra.argCondition().ifPresent(ac -> argConditions.add(ac.filter()));
                }
            }
        }
        if (hadError) return null;

        var filters = new ArrayList<WhereFilter>();
        if (!bodyParams.isEmpty()) {
            String conditionsClassName = ctx.ctx().outputPackage() + ".conditions." + returnTypeName + "Conditions";
            String methodName = fieldDef.getName() + "Condition";
            var callParams = bodyParams.stream()
                .map(bp -> new CallParam(bp.name(), bp.extraction(), bp.list(), bodyParamCallTypeName(bp)))
                .toList();
            filters.add(new GeneratedConditionFilter(conditionsClassName, methodName, rt, callParams, List.copyOf(bodyParams)));
        }
        filters.addAll(argConditions);
        return List.copyOf(filters);
    }

    /**
     * Recursively collects explicit {@code @condition} filters and implicit column-equality
     * predicates from a list of {@link InputField}s.
     *
     * <p><b>Explicit conditions</b> — {@link InputField.ColumnField},
     * {@link InputField.ColumnReferenceField}, and {@link InputField.NestingField} all carry an
     * optional {@code condition}. When present, the filter is rewrapped with a
     * {@link CallSiteExtraction.NestedInputField} extraction and added to {@code out}.
     *
     * <p><b>Implicit conditions</b> — every un-annotated {@link InputField.ColumnField} and
     * {@link InputField.ColumnReferenceField} that carries no {@code @condition} annotation, is
     * not already consumed by a {@code @lookupKey} binding, and is not suppressed by an enclosing
     * {@code override: true}, gets an implicit column-equality predicate, a {@link BodyParam}
     * with a {@link CallSiteExtraction.NestedInputField} extraction, added to
     * {@code implicitBodyParams}. {@code implicitBodyParams} is required non-null at entry
     * (enforced by {@link java.util.Objects#requireNonNull}); R205 unified the
     * {@link ArgumentRef.InputTypeArg.TableInputArg} and
     * {@link ArgumentRef.InputTypeArg.PlainInputArg} branches so both emit implicit predicates
     * symmetrically. Fields that carry an explicit {@code @condition} (any override value) never
     * also emit an implicit predicate.
     *
     * <p>{@code enclosingOverride} propagates the override flag down through
     * {@link InputField.NestingField} children: once set to {@code true} it stays {@code true}
     * for all descendants, suppressing their implicit predicates.
     *
     * <p>{@code lookupBoundNames} is the set of field names already consumed as
     * {@code @lookupKey} bindings on the enclosing {@code TableInputArg}. These must not also
     * emit an implicit condition (the VALUES+JOIN path owns them).
     *
     * <p>{@code outerArgName} is the top-level field-argument name (e.g. {@code "filter"}).
     * {@code pathPrefix} is the list of Map keys from {@code outerArgName} down to the parent of
     * {@code fields}; empty at the top level.
     *
     * <p>{@code effectiveNonNull} is the conjunction of every enclosing link's declared
     * nullability — the top-level argument and every {@code NestingField} on the path. The
     * value passed to {@link BodyParam}'s {@code nonNull} slot at each leaf is
     * {@code effectiveNonNull && field.nonNull()}; the recursion into a {@link InputField.NestingField}
     * carries {@code effectiveNonNull && nf.nonNull()} forward. The {@code BodyParam.nonNull}
     * slot carries the effective nullability the emitter relies on: the call-site extraction
     * emitted for {@code NestedInputField} parameters cascades through {@code instanceof} checks
     * and returns {@code null} whenever any level is missing, so the emitter is allowed to omit
     * the runtime null guard only when every enclosing level is statically non-null.
     */
    private void walkInputFieldConditions(
            List<InputField> fields, String outerArgName, List<String> pathPrefix,
            boolean enclosingOverride, boolean effectiveNonNull, Set<String> lookupBoundNames,
            List<BodyParam> implicitBodyParams,
            List<WhereFilter> out,
            List<Rejection> walkRejections,
            TableRef resolvingTable,
            String containerSummary) {
        requireNonNull(implicitBodyParams, "implicitBodyParams");
        requireNonNull(walkRejections, "walkRejections");
        for (var f : fields) {
            var leafPath = new ArrayList<>(pathPrefix);
            leafPath.add(f.name());
            switch (f) {
                case InputField.ColumnField cf -> {
                    cf.condition().ifPresent(c -> out.add(conditionResolver.rewrapForNested(c.filter(), outerArgName, leafPath)));
                    if (!enclosingOverride
                            && cf.condition().isEmpty()
                            && !lookupBoundNames.contains(cf.name())) {
                        // The leaf extraction (Direct or NodeIdDecodeKeys.*) flows through to the
                        // BodyParam via NestedInputField(outer, path, leaf), so the call-site
                        // emitter applies the per-element decode chain on the Map traversal result.
                        implicitBodyParams.add(implicitBodyParam(
                            cf.column(), cf.name(), cf.typeName(),
                            effectiveNonNull && cf.nonNull(), cf.list(),
                            cf.extraction(), outerArgName, leafPath));
                    }
                }
                case InputField.ColumnReferenceField rf -> {
                    // R330: an FK-target @nodeId field's @condition method expects the FK-target
                    // table X, not the input's own table, so the plain ConditionFilter (whose Table
                    // slot the emitter fills with the root `table` local) would hand the method the
                    // wrong table and fail at consumer compile. Wrap it in an FkTargetConditionFilter
                    // carrying the FK correlation so QueryConditionsGenerator emits a correlated
                    // EXISTS that hands the method an alias for X. An empty joinPath means start
                    // table == target table (the column lives on the parent's own table), so `table`
                    // is genuinely correct and the plain ConditionFilter stands.
                    rf.condition().ifPresent(c -> {
                        var rewrapped = conditionResolver.rewrapForNested(c.filter(), outerArgName, leafPath);
                        out.add(rf.joinPath().isEmpty()
                            ? rewrapped
                            : new FkTargetConditionFilter(rewrapped,
                                ((JoinStep.HasTargetTable) rf.joinPath().get(rf.joinPath().size() - 1)).targetTable(),
                                rf.joinPath(), rf.liftedSourceColumns(), List.of(rf.column())));
                    });
                    if (!enclosingOverride
                            && rf.condition().isEmpty()
                            && !lookupBoundNames.contains(rf.name())) {
                        // Predicate fires against liftedSourceColumns — the column tuple on the
                        // parent's own table positionally aligned with the decoded NodeType keys
                        // (nodeId direct-fk, single-hop or identity-carrying multi-hop), or the
                        // resolved reference column for plain @reference. Single source of truth.
                        // R380: for a plain @reference (Direct extraction) reaching a column on a
                        // joined table, liftedSourceColumns().get(0) is the *terminal* column, so
                        // wrap the predicate in a RemoteColumnPredicate (correlated EXISTS). The
                        // @nodeId-lift case (NodeIdDecodeKeys extraction) binds locally and stays
                        // unwrapped — see remoteIfReferenceJoin's discrimination note.
                        var inner = implicitBodyParam(
                            rf.liftedSourceColumns().get(0), rf.name(), rf.typeName(),
                            effectiveNonNull && rf.nonNull(), rf.list(),
                            rf.extraction(), outerArgName, leafPath);
                        implicitBodyParams.add(remoteIfReferenceJoin(inner, rf.extraction(), rf.joinPath()));
                    }
                }
                case InputField.NestingField nf -> {
                    nf.condition().ifPresent(c -> out.add(conditionResolver.rewrapForNested(c.filter(), outerArgName, leafPath)));
                    boolean nestOverride = enclosingOverride
                        || nf.condition().map(ArgConditionRef::override).orElse(false);
                    walkInputFieldConditions(nf.fields(), outerArgName, leafPath,
                        nestOverride, effectiveNonNull && nf.nonNull(),
                        lookupBoundNames, implicitBodyParams, out, walkRejections,
                        resolvingTable, containerSummary);
                }
                case InputField.CompositeColumnField ccf -> {
                    ccf.condition().ifPresent(c -> out.add(conditionResolver.rewrapForNested(c.filter(), outerArgName, leafPath)));
                    if (!enclosingOverride
                            && ccf.condition().isEmpty()
                            && !lookupBoundNames.contains(ccf.name())) {
                        implicitBodyParams.add(compositeImplicitBodyParam(
                            ccf.columns(), ccf.name(),
                            effectiveNonNull && ccf.nonNull(), ccf.list(),
                            ccf.extraction(), outerArgName, leafPath));
                    }
                }
                case InputField.CompositeColumnReferenceField ccrf -> {
                    // R330: composite-key FK-target @nodeId + @condition is the common consumer
                    // shape (composite NodeType keys are the norm). Same lift as the single-column
                    // ColumnReferenceField arm: a non-empty joinPath means the developer method
                    // expects the FK-target table X, so wrap in an FkTargetConditionFilter and the
                    // emitter produces a correlated EXISTS whose correlation ANDs every composite-FK
                    // slot (JoinPathEmitter.emitCorrelationWhere). Empty joinPath keeps the plain
                    // ConditionFilter (start table == target table, so `table` is correct).
                    ccrf.condition().ifPresent(c -> {
                        var rewrapped = conditionResolver.rewrapForNested(c.filter(), outerArgName, leafPath);
                        out.add(ccrf.joinPath().isEmpty()
                            ? rewrapped
                            : new FkTargetConditionFilter(rewrapped,
                                ((JoinStep.HasTargetTable) ccrf.joinPath().get(ccrf.joinPath().size() - 1)).targetTable(),
                                ccrf.joinPath(), ccrf.liftedSourceColumns(), ccrf.columns()));
                    });
                    if (!enclosingOverride
                            && ccrf.condition().isEmpty()
                            && !lookupBoundNames.contains(ccrf.name())) {
                        // Composite reference is nodeId-only today (per record javadoc), so the
                        // extraction is always NodeIdDecodeKeys and remoteIfReferenceJoin keeps it
                        // local (lifted FK-child columns on the parent's own table). The wrap is
                        // applied symmetrically with the single-column arm so that if a composite
                        // plain @reference ever appears (Direct extraction over a terminal tuple)
                        // it routes to a RowEq/RowIn-inner RemoteColumnPredicate without re-editing.
                        var inner = compositeImplicitBodyParam(
                            ccrf.liftedSourceColumns(), ccrf.name(),
                            effectiveNonNull && ccrf.nonNull(), ccrf.list(),
                            ccrf.extraction(), outerArgName, leafPath);
                        implicitBodyParams.add(remoteIfReferenceJoin(inner, ccrf.extraction(), ccrf.joinPath()));
                    }
                }
                case InputField.UnboundField uf -> {
                    // R215: UnboundField is the no-column-bound carrier. Per condition-cascade
                    // docs (manual/how-to/migrating-from-legacy.adoc,
                    // "behavior-divergence-condition-cascade") every @condition the author
                    // writes produces SQL; the override flag controls only the *implicit*
                    // column predicate, which UnboundField doesn't have. So the cascade case
                    // (inner @condition under outer override:true) still fires the inner method.
                    //
                    // Reject at the consumer outside the cascade when either the shape is
                    // structurally malformed (condition.isPresent() && !override on a no-column
                    // field; override:false implies the implicit predicate is required to
                    // compose, and there's no column to bind) or the field has no filter
                    // contribution at all (condition.isEmpty()). The first arm is the
                    // validator's job per spec but the validator's plain-input walk doesn't
                    // exist yet (R221: validator-walks-plain-input-unbound-fields); the
                    // consumer-side reject acts as a safety net for plain inputs until R221
                    // lands. @table inputs are caught by GraphitronSchemaValidator.validateInputUnboundField
                    // at the directive's location independent of this path.
                    boolean rejectAtConsumer = !enclosingOverride
                        && (uf.condition().isEmpty()
                            || !uf.condition().get().override());
                    if (rejectAtConsumer) {
                        walkRejections.add(unboundFieldConsumerRejection(uf, resolvingTable, containerSummary));
                    } else {
                        uf.condition().ifPresent(c -> out.add(
                            conditionResolver.rewrapForNested(c.filter(), outerArgName, leafPath)));
                    }
                }
            }
        }
    }

    /**
     * R215 consumer-side rejection for an {@link InputField.UnboundField} reached at a call site
     * with no enclosing {@code @condition(override: true)} cascade. The field carries no column
     * binding and either no {@code @condition} of its own or one with {@code override:false}
     * (the latter is also caught by the validator at the directive's location). The rejection
     * prose names the field as {@code <parentTypeName>.<name>} so log readers can locate it
     * without consulting the surrounding UnclassifiedField context.
     *
     * <p>When {@link InputField.UnboundField#attemptedColumnName()} is non-null and a resolving
     * table is in hand, the rejection lifts to {@link Rejection.AuthorError.UnknownName} with the
     * attempted column name and the Levenshtein candidates against the table; LSP fix-its and
     * watch-mode formatters consume the structured payload. Otherwise (no attempt, or no table
     * context) it folds to a structural rejection.
     */
    private Rejection unboundFieldConsumerRejection(InputField.UnboundField uf, TableRef resolvingTable,
                                                    String containerSummary) {
        String attempted = uf.attemptedColumnName();
        String summary = containerSummary + ": input field '" + uf.name() + "'";
        if (attempted != null && resolvingTable != null) {
            return Rejection.unknownColumn(summary, attempted,
                ctx.catalog.columnSqlNamesOf(resolvingTable.tableName()));
        }
        if (attempted == null) {
            return Rejection.structural(summary
                + ": has no column binding and no @condition; the field cannot resolve "
                + "without an enclosing @condition(override: true) cascade");
        }
        return Rejection.structural(summary + ": no column '" + attempted
            + "' found on the resolving table");
    }

    /**
     * Shared rejection text for the {@code TranslatedFk} arm. The message naming is asserted on
     * by {@code NodeIdPipelineTest.ArgumentFkTargetNodeIdCase.FK_TARGET_PATHOLOGICAL_KEY_MISMATCH_DEFERRED}
     * and the parallel input-field-side case via the substrings {@code "FK's target columns do
     * not positionally match"} and {@code "deferred"}.
     */
    static String translatedFkRejectionReason(String refTypeName, String containingTableName) {
        return "@nodeId(typeName: '" + refTypeName + "') FK-target on table '"
            + containingTableName + "': the FK's target columns do not positionally"
            + " match NodeType '" + refTypeName + "''s key columns,"
            + " so emission requires JOIN-with-translation."
            + " This pathological case is deferred until output-side"
            + " JOIN-with-projection emission ships.";
    }

    /**
     * Builds a {@link BodyParam} for an implicit column-equality predicate on a {@code @table}
     * input field. The extraction is {@link CallSiteExtraction.NestedInputField} so the fetcher
     * call site traverses the argument Map to reach the leaf value. GraphQL {@code ID} scalars
     * are delivered as {@code String} by graphql-java; all other types use the column's own
     * Java class.
     */
    private static BodyParam implicitBodyParam(ColumnRef column, String fieldName,
                                               String graphqlTypeName, boolean nonNull, boolean list,
                                               CallSiteExtraction leaf,
                                               String outerArgName, List<String> leafPath) {
        // R384 phase a: align the nested @field leaf with the top-level conversion semantics
        // (EnumMappingResolver.deriveExtraction) — a nested ID-typed @field over a plain column
        // coerces through the column's DataType via a JooqConvert leaf instead of the hardcoded
        // Direct leaf BuildContext builds, so a converted column's wire String reaches the
        // condition method as the column's Java type on the nested path exactly as it does
        // top-level. @nodeId leaves branch earlier in BuildContext and arrive here as
        // NodeIdDecodeKeys, so this substitution never touches them.
        if (leaf instanceof CallSiteExtraction.Direct && "ID".equals(graphqlTypeName)) {
            leaf = new CallSiteExtraction.JooqConvert(column.javaName());
        }
        // For NodeId-decoded and JooqConvert leaves the post-coercion value is the column's typed
        // Java class (single scalar or List of it). For Direct, ID scalars stay String; everything
        // else takes the column's Java type.
        boolean coercedLeaf = leaf instanceof CallSiteExtraction.NodeIdDecodeKeys
            || leaf instanceof CallSiteExtraction.JooqConvert;
        String javaType = coercedLeaf
            ? column.columnClass()
            : ("ID".equals(graphqlTypeName) ? String.class.getName() : column.columnClass());
        var nested = new CallSiteExtraction.NestedInputField(outerArgName, leafPath, leaf);
        return list
            ? new BodyParam.In(fieldName, column, javaType, nonNull, nested)
            : new BodyParam.Eq(fieldName, column, javaType, nonNull, nested);
    }

    /**
     * Implicit body param for composite-key inputs ({@link InputField.CompositeColumnField}
     * or {@link InputField.CompositeColumnReferenceField}). Always pairs with a
     * {@link CallSiteExtraction.NodeIdDecodeKeys} leaf — the only multi-column extraction
     * arm. Body emission lands on {@link BodyParam.RowEq} (scalar) or
     * {@link BodyParam.RowIn} (list); the parameter type is the typed
     * {@code Row<N><T1, ..., TN>} (or {@code List<Row<N><...>>}) computed at emit time from
     * {@code columns}, and the call-site extraction projects each decoded record's typed
     * {@code valuesRow()} into it.
     */
    private static BodyParam compositeImplicitBodyParam(List<ColumnRef> columns, String fieldName,
                                                        boolean nonNull, boolean list,
                                                        CallSiteExtraction.NodeIdDecodeKeys leaf,
                                                        String outerArgName, List<String> leafPath) {
        var nested = new CallSiteExtraction.NestedInputField(outerArgName, leafPath, leaf);
        return list
            ? new BodyParam.RowIn(fieldName, columns, nonNull, nested)
            : new BodyParam.RowEq(fieldName, columns, nonNull, nested);
    }

    /**
     * R380: wraps an implicit predicate in a {@link BodyParam.RemoteColumnPredicate} when the
     * reference carrier is a plain {@code @reference} ({@link CallSiteExtraction.Direct} extraction)
     * reaching a column on a <em>joined</em> table; otherwise returns {@code inner} unchanged.
     *
     * <p>The discrimination is load-bearing. {@code ColumnReferenceField} /
     * {@code CompositeColumnReferenceField} are produced for two cases whose
     * {@code liftedSourceColumns} mean different tables:
     * <ul>
     *   <li><b>{@code @nodeId} FK-target (DirectFk)</b> — {@link CallSiteExtraction.NodeIdDecodeKeys}
     *       extraction; the lifted columns are FK-child columns on the parent's <em>own</em> table,
     *       positionally aligned with the decoded NodeType keys. The predicate is correctly bound to
     *       the local {@code table} (the lift means no join is needed), so this stays unwrapped.</li>
     *   <li><b>plain {@code @reference}</b> — {@link CallSiteExtraction.Direct} extraction; the
     *       lifted column is the <em>terminal</em> column on the joined table, so it must go through
     *       the correlated EXISTS.</li>
     * </ul>
     * The {@code Direct}-vs-{@code NodeIdDecodeKeys} extraction split is the cleanest available
     * discriminator (chosen per the Spec; recorded here so the two {@code liftedSourceColumns}
     * meanings stay un-conflated). An empty {@code joinPath} means the column is local (start table
     * == terminal table), so it is also left unwrapped.
     */
    private static BodyParam remoteIfReferenceJoin(BodyParam inner,
            CallSiteExtraction referenceExtraction, List<JoinStep> joinPath) {
        if (referenceExtraction instanceof CallSiteExtraction.Direct
                && !joinPath.isEmpty()
                && inner instanceof BodyParam.ColumnPredicate cp) {
            return new BodyParam.RemoteColumnPredicate(joinPath, cp);
        }
        return inner;
    }

    /**
     * Per-variant {@link CallParam#typeName()} for a {@link BodyParam}. Eq/In carry their own
     * {@code javaType} string; row-shape variants synthesize {@code "org.jooq.Row<N>"} from the
     * column tuple (the {@code CallParam.typeName} slot is unused on the NodeId-decode call-site
     * path that row-shape body params take, but a non-null, accurate string keeps the contract
     * tight).
     */
    private static String bodyParamCallTypeName(BodyParam bp) {
        return switch (bp) {
            case BodyParam.Eq eq -> eq.javaType();
            case BodyParam.In in -> in.javaType();
            case BodyParam.RowEq req -> "org.jooq.Row" + req.columns().size();
            case BodyParam.RowIn rin -> "org.jooq.Row" + rin.columns().size();
            // A remote predicate's call-site type is the inner predicate's: the value arrives the
            // same way (env.getArgument), only the SQL shape differs.
            case BodyParam.RemoteColumnPredicate r -> bodyParamCallTypeName(r.inner());
        };
    }

    // ===== Field classification =====

    GraphitronField classifyField(GraphQLFieldDefinition fieldDef, String parentTypeName, GraphitronType parentType) {
        return classifyField(fieldDef, parentTypeName, parentType, null);
    }

    GraphitronField classifyField(GraphQLFieldDefinition fieldDef, String parentTypeName, GraphitronType parentType,
            Class<?> parentBackingClass) {
        var result = classifyFieldInner(fieldDef, parentTypeName, parentType, parentBackingClass);
        // R458 placement gate: @referenceFor is only meaningful on a multi-table interface/union
        // child field (the only site resolveChildPolymorphicJoinPaths reads it). On any other field
        // the directive is silently ignored by classification, so a field that carries it yet
        // classifies successfully as something other than an InterfaceField / UnionField (a plain
        // field, a single-table discriminated polymorphic field, a root field, …) is a misplacement.
        // A genuine multi-table polymorphic child whose route failed is an UnclassifiedField carrying
        // its own route rejection, which is left intact.
        if (fieldDef.hasAppliedDirective(DIR_REFERENCE_FOR)
                && !(result instanceof UnclassifiedField)
                && !(result instanceof InterfaceField)
                && !(result instanceof UnionField)) {
            return new UnclassifiedField(parentTypeName,
                fieldDef.getName(), locationOf(fieldDef), fieldDef, Rejection.structural(
                    "Field '" + parentTypeName + "." + fieldDef.getName() + "': @referenceFor is only "
                    + "valid on a multi-table interface/union child field. It states a per-participant "
                    + "join path, which has no meaning on this field (single-table discriminated "
                    + "polymorphic fields, non-polymorphic fields, and root fields have no participant "
                    + "set to bind a path to). Remove the @referenceFor directive."));
        }
        return result;
    }

    private GraphitronField classifyFieldInner(GraphQLFieldDefinition fieldDef, String parentTypeName, GraphitronType parentType,
            Class<?> parentBackingClass) {
        String name = fieldDef.getName();
        SourceLocation location = locationOf(fieldDef);

        // R449 D1 — the root position is read once here and feeds both the hoisted D2 query
        // conflict detector below and the R435 chain interception's Query-only root arm, so no
        // second string-comparison site appears (the fuller fix, lifting RootType into
        // Query/Mutation/Subscription so dispatch is exhaustive rather than string-compared, is a
        // model-cleanup follow-up out of R449's scope).
        boolean isRoot = parentType instanceof RootType;
        boolean isQueryRoot = isRoot && parentTypeName.equals("Query");
        boolean isMutationRoot = isRoot && parentTypeName.equals("Mutation");

        // @notGenerated is no longer supported. Reject any application before conflict detection
        // so the user sees the no-longer-supported reason rather than a misleading "conflict with
        // @service" message when both directives are present.
        if (fieldDef.hasAppliedDirective(DIR_NOT_GENERATED)) {
            return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.directiveConflict(
                List.of(DIR_NOT_GENERATED),
                "@notGenerated is no longer supported. Remove the directive; fields must be fully described by the schema."));
        }

        // @multitableReference is no longer supported. Reject any application before conflict
        // detection so the user sees the no-longer-supported reason rather than a misleading
        // "conflict with @service" message when both directives are present.
        if (fieldDef.hasAppliedDirective(DIR_MULTITABLE_REFERENCE)) {
            return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.directiveConflict(
                List.of(DIR_MULTITABLE_REFERENCE),
                "@multitableReference is no longer supported. Remove the directive; the rewrite generates multi-table interface dispatch from @discriminate / @discriminator without an explicit multitable-reference path. For a per-participant join path that auto-discovery cannot derive, use @referenceFor(type:, path:) instead (one application per participant)."));
        }

        if (!(parentType instanceof RootType)) {
            var conflict = detectChildFieldConflict(fieldDef);
            if (conflict != null) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, conflict);
            }
        }

        // R449 D2 — the query conflict detector, hoisted from classifyQueryField so it runs before
        // the R435 chain interception below (one detector site per position: child above, Query
        // here). @service @routine on a Query field now rejects as a DirectiveConflict at every
        // shape — single-node and multi-node chain alike — where the interception would otherwise
        // silently route the multi-node chain to the routine classifier. Guarded by D1's single
        // Query-position read.
        if (isQueryRoot) {
            var conflict = detectQueryFieldConflict(fieldDef);
            if (conflict != null) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, conflict);
            }
        }

        // R435 — order-significant @routine / @reference composition. The ordered field-level
        // applications compose the field's table chain, and this is the only pass that reads
        // their order. Root chains land QueryRoutineTableField (the single-node R300 shape via
        // classifyRootField's @routine branch, routine-then-hops via classifyRootRoutineChain);
        // child chains with a routine node land ChildField.TableField via the chain walker
        // (classifyChildRoutineChain — head, mid-chain, or terminus routine position); child
        // chains of repeated @reference applications carry no routine node and fall through to
        // the ordinary classification (parsePath concatenates their elements). Misorderings
        // reject as AuthorError at classify time. Chain rule and shapes are documented in
        // docs/manual/reference/directives/routine.adoc; remaining fetch-form breadth is
        // roadmap/routine-chain-fetch-form-breadth.md.
        var chainDirectives = chainDirectiveNames(fieldDef);
        long routineApplications = chainDirectives.stream().filter(DIR_ROUTINE::equals).count();
        if (routineApplications > 0 || chainDirectives.size() > 1) {
            // R451 — a multi-node Mutation chain carrying @routine is the routine write arm (the
            // routine call commits before the follow-up query) and classifies for real via
            // classifyMutationRoutineChain below, landing MutationRoutineWriteField. It shares the
            // root-head and multi-routine rules with the Query chain: at root only a routine can
            // supply the chain's head, on Mutation exactly as on Query.
            boolean isMutationWriteChain = isMutationRoot && routineApplications > 0 && chainDirectives.size() > 1;
            // The root-head rule (and the root chain classifiers below, whose leaves' source()
            // asserts the matching root) applies to Query chains and Mutation write chains alike;
            // Subscription chains fall through to classifyRootField's generic Deferred.
            if ((isQueryRoot || isMutationWriteChain) && !DIR_ROUTINE.equals(chainDirectives.get(0))) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(
                    "a root field's table chain must start with @routine — directives compose the chain in "
                    + "written order, and at root only a routine can supply the chain's head (move @routine first)"));
            }
            // A second routine node needs the multi-lateral emit and stays typed Deferred, at
            // root and child positions alike.
            if (routineApplications > 1) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.deferred(
                    "a table chain with more than one routine node classifies but does not emit yet",
                    "routine-chain-fetch-form-breadth"));
            }
            if (isQueryRoot && chainDirectives.size() > 1) {
                // Root-head rule above guarantees the chain starts with @routine here: the
                // routine-then-hops chain.
                return classifyRootRoutineChain(fieldDef, parentTypeName, name, location);
            }
            if (isMutationWriteChain) {
                return classifyMutationRoutineChain(fieldDef, parentTypeName, name, location);
            }
            if (!isRoot && routineApplications == 1) {
                return classifyChildRoutineChain(fieldDef, parentTypeName, parentType, name, location);
            }
            // Remaining shapes fall through: the Query single-node chain (classifyQueryField's
            // @routine branch), the Mutation single-node @routine (classifyMutationField's top
            // check, a typed Deferred carried by routine-write-result-shapes) and
            // Mutation/Subscription non-routine or Subscription routine chains (their
            // classifyRootField stories), and child chains of repeated @reference applications with
            // no routine node, whose concatenated path the ordinary classification below consumes
            // like any longer @reference path.
        }

        if (parentType instanceof RootType rootType) {
            return classifyRootField(fieldDef, parentTypeName);
        }
        if (parentType instanceof TableBackedType tbt && !(parentType instanceof TableInterfaceType)) {
            return classifyChildFieldOnTableType(fieldDef, parentTypeName, tbt, Set.of());
        }
        if (parentType instanceof ResultType resultType) {
            return classifyChildFieldOnResultType(fieldDef, parentTypeName, resultType, parentBackingClass);
        }
        if (parentType instanceof ErrorType) {
            return classifyChildFieldOnErrorType(fieldDef, parentTypeName);
        }

        return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural("parent type is unclassified"));
    }

    /**
     * Classifies a root routine chain (R435): {@code @routine} supplies the chain's head (the
     * FROM source; the root-head rule upstream guarantees it is the first application), each
     * subsequent {@code @reference} application contributes hops in authored order, and the
     * terminus must be the field's {@code @table} type (the chain-level verdict,
     * {@link #routineChainVerdict}). The hop out of the routine
     * result keys by the name-matched target key ({@code BuildContext
     * .synthesizeNameMatchedJoin}, gated on the catalog's table-valued-function fact); later hops
     * ride the ordinary FK / condition machinery. Lands
     * {@link QueryField.QueryRoutineTableField} carrying the {@code (start, hops)} chain; the
     * R300 single-node shape is the degenerate chain with no {@code @reference} applications
     * ({@code hops = []}).
     *
     * <p>Ordering note: like the R300 single-node root, the chain root carries no ordering
     * surface ({@code QueryRoutineTableField} is not a {@code SqlGeneratingField}); an
     * {@code @defaultOrder} surface over the catalog terminus is recorded as pending in the plan.
     */
    private GraphitronField classifyRootRoutineChain(GraphQLFieldDefinition fieldDef,
            String parentTypeName, String name, SourceLocation location) {
        return switch (walkRoutineChain(fieldDef, parentTypeName, name, /*headTable=*/null)) {
            case ChainWalk.Rejected r ->
                new UnclassifiedField(parentTypeName, name, location, fieldDef, r.rejection());
            case ChainWalk.Ok walk -> {
                var verdict = routineChainVerdict(name, walk.tb().returnType(),
                    walk.terminusTable(), walk.terminusIsRoutine());
                if (verdict != null) {
                    yield new UnclassifiedField(parentTypeName, name, location, fieldDef, verdict);
                }
                yield new QueryField.QueryRoutineTableField(parentTypeName, name, location,
                    walk.tb().returnType(),
                    new RoutineChain(
                        new TableExpr.RoutineCall(walk.tb().routine(), walk.tb().resultTable()),
                        walk.steps()));
            }
        };
    }

    /**
     * Classifies a Mutation root routine chain (R451): the same walk and chain-level verdicts as
     * {@link #classifyRootRoutineChain} (the root-head rule upstream guarantees {@code @routine}
     * supplies the head; {@link #walkRoutineChain} composes the hops;
     * {@link #routineChainVerdict} applies the terminus and Connection rules), landing
     * {@link MutationField.MutationRoutineWriteField} — the routine call is the write and commits
     * before the follow-up query re-reads the terminus.
     *
     * <p>One write-only verdict on top: hop 0 must join by {@link On.ColumnPairs}, with no
     * per-hop {@code filter}. The write fetcher's step 2 anchors the post-commit re-read on hop
     * 0's table keyed by the captured routine columns, so a condition-joined or filtered hop 0
     * (whose predicate references the routine alias, absent from step 2) has no derivable re-read
     * anchor; it lands a typed {@code Deferred} with an empty planSlug (the R435 precedent for a
     * shape someone may file a follow-up for) rather than reaching the leaf. Hops past 0 keep
     * their filters — both aliases are in scope in step 2's SELECT.
     *
     * <p>The chain's non-empty {@code hops} is the caller's guarantee
     * ({@code chainDirectives.size() > 1} implies at least one {@code @reference} hop); the leaf's
     * compact constructor re-asserts it mechanically.
     */
    private GraphitronField classifyMutationRoutineChain(GraphQLFieldDefinition fieldDef,
            String parentTypeName, String name, SourceLocation location) {
        return switch (walkRoutineChain(fieldDef, parentTypeName, name, /*headTable=*/null)) {
            case ChainWalk.Rejected r ->
                new UnclassifiedField(parentTypeName, name, location, fieldDef, r.rejection());
            case ChainWalk.Ok walk -> {
                var verdict = routineChainVerdict(name, walk.tb().returnType(),
                    walk.terminusTable(), walk.terminusIsRoutine());
                if (verdict != null) {
                    yield new UnclassifiedField(parentTypeName, name, location, fieldDef, verdict);
                }
                if (!walk.steps().isEmpty() && walk.steps().get(0) instanceof JoinStep.Hop hop0
                        && (!(hop0.on() instanceof On.ColumnPairs) || hop0.filter() != null)) {
                    yield new UnclassifiedField(parentTypeName, name, location, fieldDef,
                        Rejection.deferred(
                            "a Mutation routine chain whose first hop joins by condition or carries "
                            + "a filter has no derivable post-commit re-read anchor (the predicate "
                            + "references the routine alias, which must not appear in the follow-up "
                            + "query) and does not emit yet",
                            ""));
                }
                yield new MutationField.MutationRoutineWriteField(parentTypeName, name, location,
                    walk.tb().returnType(),
                    new RoutineChain(
                        new TableExpr.RoutineCall(walk.tb().routine(), walk.tb().resultTable()),
                        walk.steps()));
            }
        };
    }

    /**
     * Classifies a child-positioned table chain containing exactly one routine node (R435): the
     * ordered applications compose the chain over one running source starting at the implicit
     * head (the parent's table). The routine node may sit at the head (routine-then-hops), the
     * terminus (hops-then-routine, where {@code columnMapping} binds against the previous hop's
     * node rather than the implicit head), or strictly between hops (the sandwich); in every
     * position it joins as CROSS JOIN LATERAL, correlated through its call arguments. Lands
     * {@link ChildField.TableField} riding the inline correlated-multiset machinery, or
     * {@link ChildField.SplitTableField} when {@code @splitQuery} forces the batched keyed
     * re-query anchor (the batch key for a routine-headed chain is the routine's column-bound
     * inputs; see {@link #deriveSplitQuerySource}); the single-application case is the
     * degenerate chain {@code [Hop(RoutineCall, Lateral)]}.
     *
     * <p>{@code @lookupKey} composition and non-table-backed parents stay typed
     * {@code Deferred} until their emitters land.
     */
    private GraphitronField classifyChildRoutineChain(GraphQLFieldDefinition fieldDef,
            String parentTypeName, GraphitronType parentType, String name, SourceLocation location) {
        // The walk needs the implicit head to start from, so this gate precedes it.
        if (!(parentType instanceof TableBackedType tbt) || parentType instanceof TableInterfaceType) {
            return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.deferred(
                "a child-positioned @routine under a non-table-backed parent classifies "
                + "but does not emit yet",
                "routine-chain-fetch-form-breadth"));
        }
        return switch (walkRoutineChain(fieldDef, parentTypeName, name, tbt.table())) {
            case ChainWalk.Rejected r ->
                new UnclassifiedField(parentTypeName, name, location, fieldDef, r.rejection());
            case ChainWalk.Ok walk -> {
                var verdict = routineChainVerdict(name, walk.tb().returnType(),
                    walk.terminusTable(), walk.terminusIsRoutine());
                if (verdict != null) {
                    yield new UnclassifiedField(parentTypeName, name, location, fieldDef, verdict);
                }
                if (hasLookupKeyAnywhere(fieldDef)) {
                    yield new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.deferred(
                        "@lookupKey on a routine-backed child field classifies but does not emit yet",
                        "routine-chain-fetch-form-breadth"));
                }
                boolean hasSplitQuery = fieldDef.hasAppliedDirective(DIR_SPLIT_QUERY);
                // @splitQuery forces the batched keyed re-query anchor, which needs a key. A
                // routine-headed chain's batch key is the routine's column-bound inputs (design
                // note on deriveSplitQuerySource); an uncorrelated routine head has none, so the
                // directive demands a key the field's shape cannot supply — a contradiction,
                // not a capability gap. (Broadcast-to-all would be a different anchor than
                // @splitQuery names; if ever wanted, that is a separate capability.)
                boolean lateralHead = walk.steps().get(0) instanceof JoinStep.Hop h
                    && h.on() instanceof On.Lateral;
                // Build the step-0 correlation first; the split batch grain is a projection off
                // it (R450: ParentCorrelation.parentKeyColumns), so it must exist before the grain
                // is derived and the uncorrelated-routine check below can read the derived key.
                var pcResolution = ctx.buildParentCorrelation(walk.steps(), tbt.table());
                if (pcResolution instanceof BuildContext.ParentCorrelationResolution.AuthorError ae) {
                    yield new UnclassifiedField(parentTypeName, name, location, fieldDef,
                        Rejection.structural(ae.message()));
                }
                var pc = ((BuildContext.ParentCorrelationResolution.Resolved) pcResolution).correlation();
                var splitSource = hasSplitQuery
                    ? deriveSplitQuerySource(pc, tbt.table(), walk.tb().returnType())
                    : null;
                if (hasSplitQuery && lateralHead && splitSource.sourceKey().columns().isEmpty()) {
                    yield new UnclassifiedField(parentTypeName, name, location, fieldDef,
                        Rejection.directiveConflict(List.of(DIR_SPLIT_QUERY, DIR_ROUTINE),
                            "@splitQuery on an uncorrelated routine-backed field has nothing to "
                            + "key the batch on (no columnMapping binds a parent column); every "
                            + "parent would receive identical rows — drop @splitQuery or bind a "
                            + "parent column via columnMapping"));
                }
                // Ordering: @orderBy is rejected upstream (typed Deferred), so the only order
                // surface is @defaultOrder, resolved against the chain's terminus. A
                // routine-terminus chain requires it (a TVF result table carries no primary
                // key, so the PK fallback lands None and a list-shaped field fails the
                // deterministic-order validator); a catalog terminus orders like any table.
                var orderResolved = orderByResolver.resolve(List.of(), fieldDef,
                    walk.tb().returnType().table().tableName());
                if (orderResolved instanceof OrderByResolver.Resolved.Rejected orderRejected) {
                    yield new UnclassifiedField(parentTypeName, name, location, fieldDef, orderRejected.rejection());
                }
                var orderBy = ((OrderByResolver.Resolved.Ok) orderResolved).spec();
                if (hasSplitQuery) {
                    yield new no.sikt.graphitron.rewrite.model.ChildField.SplitTableField(
                        parentTypeName, name, location, walk.tb().returnType(), walk.steps(),
                        List.of(), orderBy, null,
                        splitSource.sourceKey(), splitSource.loaderRegistration(), pc);
                }
                yield new TableField(parentTypeName, name, location, walk.tb().returnType(),
                    walk.steps(), List.of(), orderBy, null, pc);
            }
        };
    }

    /**
     * Result of {@link #walkRoutineChain}: the chain's resolved steps plus the routine node's
     * resolution, or the first rejection. {@code steps} contains the routine node as a lateral
     * {@link JoinStep.Hop} wherever a previous node exists (every child position); a root
     * chain's head has none, so the root walk yields the {@code @reference} hops only and the
     * caller carries the routine as {@code QueryRoutineTableField.start}.
     */
    private sealed interface ChainWalk {
        record Ok(List<JoinStep> steps, RoutineDirectiveResolver.Resolved.TableBound tb)
                implements ChainWalk {
            /** The chain's last node — the routine result when no hop follows the routine. */
            TableRef terminusTable() {
                return steps.isEmpty() ? tb.resultTable()
                    : ((JoinStep.Hop) steps.getLast()).targetTable();
            }
            /** Whether the chain's last node is the routine node. */
            boolean terminusIsRoutine() {
                return steps.isEmpty()
                    || ((JoinStep.Hop) steps.getLast()).target() instanceof TableExpr.RoutineCall;
            }
        }
        record Rejected(Rejection rejection) implements ChainWalk {}
    }

    /**
     * Walks a field's ordered chain-directive applications over one running source (R435) — the
     * single order-significant read shared by the root and child chain classifiers.
     * {@code headTable} is the implicit head: the parent's {@code @table} at child positions,
     * {@code null} at root (where the root-head rule upstream guarantees the walk starts at the
     * {@code @routine} application). {@code @reference} applications parse through
     * {@link BuildContext#parseChainSegment} with chain-wide {@code fieldName + "_" + N}
     * aliasing; the {@code @routine} application resolves with the running source as its
     * previous node ({@code columnMapping} binds against it) and contributes the lateral
     * routine hop. Exactly one {@code @routine} application is the caller's guarantee
     * (multi-routine chains land typed {@code Deferred} upstream).
     */
    private ChainWalk walkRoutineChain(GraphQLFieldDefinition fieldDef, String parentTypeName,
            String name, TableRef headTable) {
        var applications = fieldDef.getAppliedDirectives().stream()
            .filter(d -> DIR_ROUTINE.equals(d.getName()) || DIR_REFERENCE.equals(d.getName()))
            .toList();
        boolean isList = buildWrapper(fieldDef).isList();
        var steps = new ArrayList<JoinStep>();
        TableRef runningSource = headTable;
        RoutineDirectiveResolver.Resolved.TableBound tb = null;
        int stepIndex = 0;
        for (int i = 0; i < applications.size(); i++) {
            var application = applications.get(i);
            if (DIR_ROUTINE.equals(application.getName())) {
                var resolved = routineResolver.resolve(parentTypeName, fieldDef,
                    /*isRoot=*/headTable == null,
                    runningSource == null ? null : runningSource.tableName());
                if (resolved instanceof RoutineDirectiveResolver.Resolved.Rejected r) {
                    return new ChainWalk.Rejected(r.rejection());
                }
                tb = (RoutineDirectiveResolver.Resolved.TableBound) resolved;
                if (runningSource != null) {
                    // A previous node exists: the routine joins in as a lateral hop, correlated
                    // through its call arguments against that node.
                    steps.add(new JoinStep.Hop(
                        new TableExpr.RoutineCall(tb.routine(), tb.resultTable()),
                        new On.Lateral(), runningSource, null, name + "_" + stepIndex));
                    stepIndex++;
                }
                runningSource = tb.resultTable();
            } else {
                boolean endsChain = i == applications.size() - 1;
                // The target name feeds only terminal-{condition:} target resolution, and a
                // chain-terminal @reference segment always follows the routine node (the
                // root-head rule at root; the walk order at child), so tb is resolved here.
                String targetSqlTableName = endsChain ? tb.returnType().table().tableName() : null;
                var segment = ctx.parseChainSegment(application, name,
                    runningSource == null ? null : runningSource.tableName(),
                    targetSqlTableName, isList, stepIndex, endsChain);
                if (segment.hasError()) {
                    return new ChainWalk.Rejected(Rejection.structural(segment.errorMessage()));
                }
                steps.addAll(segment.hops());
                stepIndex += segment.hops().size();
                runningSource = ((JoinStep.HasTargetTable) segment.hops().getLast()).targetTable();
            }
        }
        return new ChainWalk.Ok(List.copyOf(steps), tb);
    }

    /**
     * The chain-level composition verdicts (R435), evaluated once over the <em>landed</em>
     * chain: the terminus rule (the chain's last node must be the field's {@code @table} type)
     * and the Connection fork — a routine-terminus chain can never support keyset pagination
     * (the FK-less routine result carries no ordering contract), so it rejects as
     * {@code DirectiveConflict}; a catalog-terminus chain could support it later, so it lands
     * typed {@code Deferred} with an empty planSlug until someone files that follow-up item.
     * Returns {@code null} when the chain passes. Deciding here keeps the routine-node resolver
     * position-agnostic; the leaf compact constructors re-assert the terminus mechanically.
     */
    private static Rejection routineChainVerdict(String fieldName,
            ReturnTypeRef.TableBoundReturnType returnType, TableRef terminusTable,
            boolean terminusIsRoutine) {
        if (returnType.wrapper() instanceof FieldWrapper.Connection) {
            return terminusIsRoutine
                ? Rejection.directiveConflict(List.of(DIR_ROUTINE),
                    "a routine-terminus chain does not support Connection return types — keyset "
                    + "pagination needs an ordering contract the routine result does not carry; use [T] or T instead")
                : Rejection.deferred(
                    "Connection pagination over a catalog-terminus chain containing a routine node "
                    + "is not yet supported", "");
        }
        if (!terminusTable.denotesSameTableAs(returnType.table())) {
            return Rejection.structural(terminusIsRoutine
                ? "@routine could not be resolved — the field's @table type ('" + returnType.table().tableName()
                    + "') does not match the routine's result table ('" + terminusTable.tableName() + "')"
                : new BuildContext.TerminalTargetVerdict.Mismatch(fieldName, terminusTable.tableName(),
                    returnType.table().tableName()).diagnostic());
        }
        return null;
    }

    private GraphitronField classifyChildFieldOnErrorType(GraphQLFieldDefinition fieldDef, String parentTypeName) {
        String name = fieldDef.getName();
        SourceLocation location = locationOf(fieldDef);
        if (isScalarOrEnum(fieldDef)) {
            return new PropertyField(parentTypeName, name, location, name, null, null);
        }
        return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.invalidSchema("fields on @error types must be scalar or enum"));
    }

    /**
     * Lift attempt for an {@code errors}-shaped field whose resolved return type is
     * {@link ReturnTypeRef.PolymorphicReturnType} (a union of, or interface implemented by,
     * {@code @error} types). Returns:
     *
     * <ul>
     *   <li>{@link ErrorsField} when every member of the polymorphic type is an
     *       {@code @error} type and the field-level nullability rule is satisfied.</li>
     *   <li>{@link UnclassifiedField} with a precise reason when the shape signals an
     *       errors channel (at least one member is {@code @error}) but a structural rule
     *       fails: mixed {@code @error} / non-{@code @error} members, or a non-null list field.</li>
     *   <li>{@code null} when no member is {@code @error} : the carrier is not an errors
     *       channel; the caller's existing polymorphic-not-supported rejection fires.</li>
     * </ul>
     *
     * <p>The detection is structural, not name-based: any field whose return type is a
     * polymorphic-of-{@code @error} shape lifts here, regardless of whether the schema
     * author named it {@code errors} or something else. See {@code error-handling-parity.md}
     * §2a / §2b for the lift rules and the gating-on-all-{@code @error} predicate.
     */
    GraphitronField liftToErrorsField(GraphQLFieldDefinition fieldDef, String parentTypeName,
                                              ReturnTypeRef.PolymorphicReturnType returnType) {
        return liftToErrorsField(fieldDef, parentTypeName, returnType, null);
    }

    /**
     * R178 step 2 overload taking the parent's reflected backing class. Threaded by
     * {@link #classifyChildFieldOnResultType} so the transport selector can probe the parent
     * class for an accessor matching the errors-shaped SDL field. The 3-arg overload above
     * stays for the {@code ServiceDirectiveResolver} caller, where the parent's backing class
     * is not in scope; that caller falls back to {@code accessorMatchesErrors = false}, which
     * matches today's transport for the unannotated default-name path on payloads with no
     * developer-supplied class.
     */
    GraphitronField liftToErrorsField(GraphQLFieldDefinition fieldDef, String parentTypeName,
                                              ReturnTypeRef.PolymorphicReturnType returnType,
                                              Class<?> parentBackingClass) {
        String name = fieldDef.getName();
        SourceLocation location = locationOf(fieldDef);

        var schemaType = ctx.schema.getType(returnType.returnTypeName());
        java.util.List<String> memberNames = switch (schemaType) {
            case GraphQLUnionType union -> union.getTypes().stream().map(GraphQLNamedType::getName).toList();
            case GraphQLInterfaceType iface ->
                ctx.schema.getImplementations(iface).stream().map(GraphQLObjectType::getName).toList();
            case null, default -> java.util.List.of();
        };

        java.util.List<ErrorType> errorTypes = new java.util.ArrayList<>();
        java.util.List<String> nonErrorMembers = new java.util.ArrayList<>();
        for (String memberName : memberNames) {
            // R317 slice 3d — the error-member fact comes from the pure ErrorIndex (a fixed point, not
            // the in-progress registry); a member two hops below the field being classified no longer
            // forces that deep type to be registered first. Present ⇒ error member; absent ⇒ non-error.
            var errorType = ctx.errors.forName(memberName);
            if (errorType.isPresent()) {
                errorTypes.add(errorType.get());
            } else {
                nonErrorMembers.add(memberName);
            }
        }

        // No @error members: not an errors channel; let the caller's existing reject fire.
        if (errorTypes.isEmpty()) {
            return null;
        }

        // Mixed members: structural rejection. Schema author signalled an errors channel
        // (at least one @error member) but added non-@error members; not supported and not
        // planned (§2b "all-@error predicate").
        if (!nonErrorMembers.isEmpty()) {
            return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural("errors-shaped field on polymorphic '" + returnType.returnTypeName()
                    + "' must have every member declared @error; non-@error member(s): "
                    + String.join(", ", nonErrorMembers)));
        }

        // Field-level nullability (§2b): the errors field must be a nullable list. A non-list
        // shape (single) or a non-null list (`[X]!` / `[X!]!`) is rejected.
        if (!(returnType.wrapper() instanceof FieldWrapper.List list)) {
            return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural("errors-shaped field of @error type(s) must be a list; declared as a single value. "
                    + "Use [" + returnType.returnTypeName() + "] or [" + returnType.returnTypeName() + "!]"));
        }
        if (!list.listNullable()) {
            // R275: typed NonNullableErrorsField arm (mirror of NonNullableSuccessProjectionField).
            // On the Outcome success arm the errors field resolves null, so a non-null list would
            // raise NonNullableFieldWasNullError and drop the sibling data field; errors fields must
            // be nullable. Typed so the LSP projects the stable graphitron.error-channel.* code.
            return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                new ErrorChannelWalkerError.NonNullableErrorsField(parentTypeName, name));
        }

        // R178 step 2 / Phase 4: transport selection runs through {@link #selectErrorsTransport}
        // (the unit-tier rule table pinned by {@link ErrorsTransportSelectionTest}). The active-
        // channel gate ahead of the rule firing reads the R96 producer-binding maps: a parent
        // is treated as having an active error channel iff a payload-returning producer
        // (DmlEmitted or ServiceEmitted) is bound to it. Non-producer-bound parents (plain
        // class-backed types reachable as service / query returns whose errors-shaped field is a
        // developer-owned slot) fall back to PayloadAccessor regardless of the rule's output.
        var transport = transportForParent(fieldDef, name, parentTypeName, parentBackingClass);
        return new ErrorsField(parentTypeName, name, location, errorTypes, transport);
    }

    /**
     * R244: whether {@code payloadTypeName} is the (unwrapped) return type of a root {@code @service}
     * field on Query or Mutation. Read directly off the assembled schema so it is independent of
     * field-classification order; the payload-side {@code WrapperArm} transport and the producing
     * field's {@link ErrorChannel.Mapped} channel agree because both derive from this same fact.
     */
    private boolean isRootServiceProducedPayload(String payloadTypeName) {
        // R329 — delegate to the single producer of this fact on BuildContext, shared with
        // TypeBuilder.carrierBinding's composite-carrier gate, so the payload-side WrapperArm
        // transport and the carrier recognition cannot drift.
        return ctx.isServiceProducedPayload(payloadTypeName);
    }

    private ChildField.Transport transportForParent(GraphQLFieldDefinition fieldDef,
            String errorsFieldName, String parentTypeName, Class<?> parentBackingClass) {
        // Active-channel gate: a parent qualifies as carrying an error channel iff a producer-
        // returning mutation is bound to it (DML or @service). Non-producer-bound parents (plain
        // class-backed types reachable as service / query returns whose errors-shaped field is a
        // developer-owned slot, or orphan carrier-shaped types unreachable through any producer)
        // fall back to PayloadAccessor.
        // R244: an @service-produced outcome type rides the typed Outcome wrapper, so its errors
        // field reads ErrorList.errors() off the Outcome source (WrapperArm), not a payload accessor
        // or localContext. This is the payload-side counterpart of resolveServiceOutcomeChannel's
        // ErrorChannel.Mapped on the producing @service field; the two flip together. The signal is
        // "this payload type is returned by a root @service field" (read straight off the schema,
        // order-independent), not the ServiceEmitted producer binding, which only grounds the
        // @table-data-field carrier shape and is absent for the class-backed scalar payloads in scope.
        // DML payloads keep their LocalContext/PayloadAccessor selection (out of scope this slice).
        if (isRootServiceProducedPayload(parentTypeName)) {
            return new ChildField.Transport.WrapperArm();
        }
        boolean activeChannel = dmlEmittedBinding(parentTypeName).isPresent()
            || serviceEmittedBinding(parentTypeName).isPresent();
        if (!activeChannel) {
            return new ChildField.Transport.PayloadAccessor();
        }
        var parsed = FieldSourceSigil.parseArgFieldNameRef(fieldDef, DIR_FIELD, ARG_NAME);
        boolean accessorMatchesErrors = probeErrorsAccessor(parentBackingClass, errorsFieldName);
        return selectErrorsTransport(parsed, accessorMatchesErrors);
    }

    /**
     * R178 step 2: probe the parent's reflected class for an accessor matching the SDL
     * errors-shaped field name. Returns {@code false} when the parent has no developer-
     * supplied class or when no accessor matches.
     *
     * <p>The expected return type is {@code java.util.List} — errors-shaped fields are
     * list-typed by structural rule (validated upstream in {@link #liftToErrorsField}).
     * The candidate-order is {@link ClassAccessorResolver.CandidateOrder#RECORD_FIRST}
     * so a Java-record component accessor wins over a getter-prefixed lookup; this matches
     * the resolution preference graphql-java's {@code PropertyDataFetcher} uses against
     * record-shaped payloads.
     */
    private static boolean probeErrorsAccessor(Class<?> parentBackingClass, String accessorBaseName) {
        if (parentBackingClass == null) return false;
        var resolution = ClassAccessorResolver.resolve(
            parentBackingClass,
            accessorBaseName,
            java.util.List.class,
            new ClassAccessorResolver.PerArgument(java.util.List.of()),
            ClassAccessorResolver.CandidateOrder.RECORD_FIRST);
        return resolution instanceof AccessorResolution.Resolved;
    }

    /**
     * R178 errors-field transport selection (commit 3A scaffolding; wired up in 3B).
     *
     * <p>Pure decision: given the parsed {@code @field(name:)} value on an SDL errors-shaped
     * field and whether the parent payload's backing class exposes a matching {@code errors}
     * accessor, returns the {@link ChildField.Transport} arm per the R178 errors-field
     * defaulting rule:
     *
     * <ul>
     *   <li>{@code @field(name: "$errors")} → {@link ChildField.Transport.LocalContext}.
     *       Explicit opt-in to the {@code env.getLocalContext()} transport; no accessor
     *       lookup runs even when the parent class exposes a matching member.</li>
     *   <li>{@code @field(name: "<literal>")} (explicit non-sigil, including
     *       {@code @field(name: "errors")}) → {@link ChildField.Transport.PayloadAccessor}.
     *       The caller is responsible for validating that the literal resolves to a real
     *       accessor on the parent class; the localContext fallback is not applied to
     *       explicit names.</li>
     *   <li>{@code @field(name: "$source")} → {@link ChildField.Transport.PayloadAccessor}.
     *       The {@code $source} sigil on an errors-shaped field is rejected upstream
     *       (errors-shaped fields are by definition polymorphic-of-{@code @error}, not
     *       passthrough identity); this fall-through is unreachable in practice and exists
     *       only to keep the switch exhaustive.</li>
     *   <li>No {@code @field} directive, parent class exposes an {@code errors} accessor →
     *       {@link ChildField.Transport.PayloadAccessor}.</li>
     *   <li>No {@code @field} directive, no matching accessor →
     *       {@link ChildField.Transport.LocalContext}. The default-name fallback that lets
     *       payloads without a developer-authored {@code errors} property still receive
     *       typed errors via {@code env.getLocalContext()}.</li>
     *   <li>{@link FieldSourceSigil.ParseResult.UnknownSigil} →
     *       {@link ChildField.Transport.PayloadAccessor}. Unknown sigils are rejected
     *       upstream by the directive validator; this fall-through is unreachable and
     *       exists for switch exhaustiveness.</li>
     * </ul>
     *
     * <p>Package-private for the unit-tier rule-table pin
     * {@code ErrorsTransportSelectionTest}; 3B wires this into a {@code FieldBuilder} site
     * adapter that reads {@code @field(name:)} from the SDL field and queries
     * {@code ClassAccessorResolver} for the {@code errors} accessor against the parent
     * class resolved by R96's reflection walk.
     */
    static ChildField.Transport selectErrorsTransport(
            FieldSourceSigil.ParseResult parsed, boolean accessorMatchesErrors) {
        return switch (parsed) {
            case FieldSourceSigil.ParseResult.Ok ok -> switch (ok.ref()) {
                case FieldSourceSigil.FieldNameRef.LocalContext ignored ->
                    new ChildField.Transport.LocalContext();
                case FieldSourceSigil.FieldNameRef.BareName ignored ->
                    new ChildField.Transport.PayloadAccessor();
                case FieldSourceSigil.FieldNameRef.UpstreamRoot ignored ->
                    new ChildField.Transport.PayloadAccessor();
            };
            case FieldSourceSigil.ParseResult.Absent ignored ->
                accessorMatchesErrors
                    ? new ChildField.Transport.PayloadAccessor()
                    : new ChildField.Transport.LocalContext();
            case FieldSourceSigil.ParseResult.UnknownSigil ignored ->
                new ChildField.Transport.PayloadAccessor();
        };
    }

    // ===== Carrier classifier: ErrorChannel resolution =====

    /**
     * Outcome of the carrier-side {@code ErrorChannel} resolution. Three terminal states:
     *
     * <ul>
     *   <li>{@link NoChannel} — the payload has no {@code errors}-shaped field; the carrier
     *       proceeds with {@code Optional.empty()} and the emitter wraps the body in
     *       {@code ErrorRouter.redact}.</li>
     *   <li>{@link Channel} — the payload has an {@code errors}-shaped field and the
     *       developer-supplied payload class's all-fields constructor matches the contract.</li>
     *   <li>{@link Reject} — the payload has an {@code errors}-shaped field but the
     *       constructor shape rejects (missing class, multiple matching constructors, no
     *       errors slot, multiple errors slots). The caller turns this into an
     *       {@code UnclassifiedField} with the carried reason.</li>
     * </ul>
     */
    private sealed interface ErrorChannelResult {
        record NoChannel() implements ErrorChannelResult {}
        record Channel(ErrorChannel channel) implements ErrorChannelResult {}
        record Reject(String reason) implements ErrorChannelResult {}
    }

    private static final ErrorChannelResult NO_CHANNEL = new ErrorChannelResult.NoChannel();

    /**
     * R244 result of resolving an {@code @service} outcome field's channel onto the typed
     * {@code Outcome} wrapper transport. Three terminal states mirroring {@link ErrorChannelResult},
     * except the channel arm is the new {@link ErrorChannel.Mapped} and the reject arm carries the
     * <em>typed</em> {@link ErrorChannelWalkerError} (preserved into {@code UnclassifiedField} so the
     * LSP projector keeps its wire {@code code}, the way {@code buildServiceField} already preserves
     * {@code ServiceMethodCallError}).
     */
    private sealed interface ServiceOutcomeResult {
        record NoChannel() implements ServiceOutcomeResult {}
        record Channel(ErrorChannel.Mapped channel) implements ServiceOutcomeResult {}
        record Reject(Rejection.AuthorError rejection) implements ServiceOutcomeResult {}
    }

    /**
     * R244: resolves the {@link ErrorChannel.Mapped} carrier for an {@code @service} outcome field,
     * replacing the {@code PayloadClass} construction path for these fields. Finds the payload's
     * single errors field (reusing {@link BuildContext#detectErrorsFieldShape}), enforces the
     * nullable-success-projection invariant
     * ({@link ErrorChannelWalkerError.NonNullableSuccessProjectionField}), then runs the
     * {@link no.sikt.graphitron.rewrite.walker.ErrorChannelWalker} over the classified
     * {@link OutcomeType} to run the channel-level rules + accessor coverage and stamp the
     * mappings-constant name. A payload with no errors field is not an outcome type ({@code NoChannel}).
     */
    private ServiceOutcomeResult resolveServiceOutcomeChannel(ReturnTypeRef returnType) {
        if (!(returnType instanceof ReturnTypeRef.ResultReturnType result)) {
            return new ServiceOutcomeResult.NoChannel();
        }
        if (!(ctx.schema.getType(result.returnTypeName()) instanceof GraphQLObjectType payloadObj)) {
            return new ServiceOutcomeResult.NoChannel();
        }
        List<ErrorType> mappedErrorTypes = null;
        GraphQLFieldDefinition errorsFieldDef = null;
        for (var f : payloadObj.getFieldDefinitions()) {
            var detected = ctx.detectErrorsFieldShape(f);
            if (detected != null) {
                mappedErrorTypes = detected;
                errorsFieldDef = f;
                break;
            }
        }
        if (mappedErrorTypes == null) {
            return new ServiceOutcomeResult.NoChannel();
        }
        // The errors field's own nullability (it must be a nullable list) is enforced where the
        // errors field is classified, in liftToErrorsField -> NonNullableErrorsField; a non-null
        // errors field surfaces there as an UnclassifiedField on the errors field itself, so it is
        // not re-checked here. This sibling check owns only the success-projection (data) field.
        // Nullable-success-projection invariant: a non-null data field resolves null on the
        // ErrorList arm and bubbles NonNullableFieldWasNullError up, dropping the sibling errors
        // field. Enforced here (classify time) where the SDL nullability is visible.
        for (var f : payloadObj.getFieldDefinitions()) {
            if (f == errorsFieldDef) continue;
            if (graphql.schema.GraphQLTypeUtil.isNonNull(f.getType())) {
                return new ServiceOutcomeResult.Reject(
                    new ErrorChannelWalkerError.NonNullableSuccessProjectionField(
                        result.returnTypeName(), f.getName()));
            }
        }
        // R317 slice 3e — registry-free look-ahead at the payload's data type; reproduces the
        // ctx.types verdict (including a producer-bound carrier) without reading the in-progress registry.
        if (!(typeBuilder.lookAheadVerdict(result.returnTypeName()) instanceof GraphitronType.ResultType backing)) {
            return new ServiceOutcomeResult.NoChannel();
        }
        var errorsLocation = errorsFieldDef.getDefinition() != null
            ? errorsFieldDef.getDefinition().getSourceLocation() : null;
        var errorsField = new ChildField.ErrorsField(result.returnTypeName(), errorsFieldDef.getName(),
            errorsLocation, mappedErrorTypes, new ChildField.Transport.WrapperArm());
        // successProjection is left empty here, not "no data fields": the walker reads only
        // errorsField off the OutcomeType, and the nullable-success-projection invariant is already
        // enforced inline above (the loop at line ~2091) where SDL nullability is visible. Populating
        // it would let the nullability check move onto the carrier; that consolidation is deferred.
        var outcomeType = new OutcomeType(backing, errorsField, List.of());
        var walkerResult = new no.sikt.graphitron.rewrite.walker.ErrorChannelWalker()
            .walk(outcomeType, ctx.schema, ctx.codegenLoader(), this::mapGraphQLTypeToReflectType);
        return switch (walkerResult) {
            case no.sikt.graphitron.rewrite.model.WalkerResult.Ok<ErrorChannel.Mapped> ok ->
                new ServiceOutcomeResult.Channel(ok.carrier());
            case no.sikt.graphitron.rewrite.model.WalkerResult.Err<ErrorChannel.Mapped> err ->
                new ServiceOutcomeResult.Reject(err.errors().getFirst());
        };
    }

    /**
     * Resolves the carrier-side {@link ErrorChannel} for a fetcher-emitting field. Walks the
     * payload type's GraphQL field defs to find an
     * {@code errors}-shaped field (structural match by polymorphic-of-all-{@code @error} list
     * shape), then reflects on the developer-supplied payload class to identify the
     * canonical-constructor errors slot and capture each parameter's default literal.
     *
     * <p>Channel detection is structural (not name-based) and reuses the {@link #liftToErrorsField}
     * predicate so the carrier classifier and the child classifier agree on what counts as an
     * {@code ErrorsField}. The first matching field on the payload populates {@code mappedErrorTypes};
     * a second matching field is rejected at child classification time, not here.
     *
     * <p>The errors slot is identified positionally: the SDL {@code ErrorsField}'s
     * declaration index in the payload type maps directly to the canonical-constructor
     * parameter at the same index. Records preserve declaration order; hand-rolled payload
     * classes are expected to expose a canonical constructor matching SDL field order. The
     * slot's element type is {@code Object} (sources mix matched throwables and
     * {@code GraphQLError}s; the only universal supertype is {@code Object}); the only
     * structural check on the parameter is that it is a List/Iterable so the catch arm's
     * synthesized payload-factory lambda compiles.
     */
    private ErrorChannelResult resolveErrorChannel(ReturnTypeRef returnType) {
        // Channel detection runs against class-backed payloads; @table payloads can in principle
        // carry an errors field too, but synthesizing a payload-factory there requires shape
        // machinery (jOOQ Record → application record) that's outside §2c. Surfaces as
        // NoChannel until that lift is designed; the §3 redact arm is the fallback.
        if (!(returnType instanceof ReturnTypeRef.ResultReturnType result)) {
            return NO_CHANNEL;
        }
        if (result.fqClassName() == null) {
            return NO_CHANNEL;
        }

        var payloadGqlType = ctx.schema.getType(result.returnTypeName());
        if (!(payloadGqlType instanceof GraphQLObjectType payloadObj)) {
            return NO_CHANNEL;
        }

        // Walk the payload's SDL fields in declaration order to find the errors-shaped field.
        // The SDL declaration index of that field is the canonical-constructor's errors-slot
        // index per the §2c positional rule (records preserve declaration order; hand-rolled
        // payload classes are expected to expose a canonical constructor matching SDL order).
        List<ErrorType> mappedErrorTypes = null;
        int errorsFieldIndex = -1;
        var sdlFields = payloadObj.getFieldDefinitions();
        for (int i = 0; i < sdlFields.size(); i++) {
            var detected = ctx.detectErrorsFieldShape(sdlFields.get(i));
            if (detected != null) {
                mappedErrorTypes = detected;
                errorsFieldIndex = i;
                break;
            }
        }
        if (mappedErrorTypes == null) {
            return NO_CHANNEL;
        }

        // §1 channel-level reject rules (rules 7 and 9). Run before payload-class reflection so
        // a misconfigured channel is reported on its own terms rather than masked by a
        // constructor-shape rejection downstream.
        String channelReject = checkChannelLevelHandlerRules(mappedErrorTypes);
        if (channelReject != null) {
            return new ErrorChannelResult.Reject(channelReject);
        }
        // §3 rule 8: duplicate match-criteria across the flattened handler list.
        String dupReject = checkDuplicateMatchCriteria(mappedErrorTypes);
        if (dupReject != null) {
            return new ErrorChannelResult.Reject(dupReject);
        }
        // §2c: per-(channel, @error type, handler) source-class accessor reflection check.
        // Walks each declared SDL field on each @error type and verifies the handler's source
        // class exposes a PropertyDataFetcher-visible accessor. path and message are populated
        // by per-@error-type synthesised DataFetchers and are exempt.
        String accessorReject = checkErrorTypeSourceAccessors(mappedErrorTypes);
        if (accessorReject != null) {
            return new ErrorChannelResult.Reject(accessorReject);
        }

        Class<?> payloadCls;
        try {
            payloadCls = Class.forName(result.fqClassName(), false, ctx.codegenLoader());
        } catch (ClassNotFoundException e) {
            return new ErrorChannelResult.Reject(
                "payload class '" + result.fqClassName() + "' could not be loaded");
        }

        // Records always declare a single canonical constructor; hand-rolled class-backed POJOs may
        // declare extras (e.g. a no-arg constructor alongside the all-fields one). The helper
        // disambiguates by matching parameter count to the SDL field count, then falls back to
        // the mutable-bean predicate (no-arg ctor + per-SDL-field setters). AllFieldsCtor wins
        // when both shapes match (canonical-over-bridge precedence).
        var sdlFieldNames = sdlFields.stream()
            .map(graphql.schema.GraphQLFieldDefinition::getName)
            .toList();
        var shapeResult = resolvePayloadConstructionShape(payloadCls, sdlFieldNames);
        if (shapeResult instanceof PayloadConstructionShapeResult.Reject r) {
            return new ErrorChannelResult.Reject(r.reason());
        }
        var shape = ((PayloadConstructionShapeResult.Resolved) shapeResult).shape();

        // ClassName.get walks getEnclosingClass(), so a nested payload resolves to Outer.Nested
        // (JLS-legal) rather than the binary Outer$Nested that bestGuess would emit verbatim and
        // that javac cannot resolve; payloadCls is already loaded above.
        var payloadClassName = ClassName.get(payloadCls);
        String mappingsConstantName = toScreamingSnake(payloadCls.getSimpleName());

        return switch (shape) {
            case no.sikt.graphitron.rewrite.model.PayloadConstructionShape.AllFieldsCtor afc ->
                buildErrorChannelCtorArm(result, afc, errorsFieldIndex, mappedErrorTypes,
                    payloadClassName, mappingsConstantName);
            case no.sikt.graphitron.rewrite.model.PayloadConstructionShape.MutableBean mb ->
                buildErrorChannelBeanArm(result, mb, errorsFieldIndex, mappedErrorTypes,
                    payloadClassName, mappingsConstantName);
        };
    }

    private static ErrorChannelResult buildErrorChannelCtorArm(
            ReturnTypeRef.ResultReturnType result,
            no.sikt.graphitron.rewrite.model.PayloadConstructionShape.AllFieldsCtor afc,
            int errorsFieldIndex,
            List<ErrorType> mappedErrorTypes,
            ClassName payloadClassName,
            String mappingsConstantName) {
        var ctor = afc.ctor();
        var parameters = ctor.getParameters();
        var genericParameterTypes = ctor.getGenericParameterTypes();
        // §2c positional errors-slot rule: the SDL ErrorsField's declaration index maps directly
        // to the constructor parameter at the same index. The slot's element type is Object;
        // the only structural check left is that the parameter is a List/Iterable so the catch
        // arm's synthesized payload-factory lambda compiles.
        if (errorsFieldIndex >= parameters.length) {
            return new ErrorChannelResult.Reject(
                "payload class '" + result.fqClassName()
                    + "' has fewer constructor parameters (" + parameters.length
                    + ") than the SDL field declaration order requires; the errors-shaped SDL "
                    + "field at index " + errorsFieldIndex + " has no matching ctor parameter");
        }
        if (!Iterable.class.isAssignableFrom(parameters[errorsFieldIndex].getType())) {
            return new ErrorChannelResult.Reject(
                "payload class '" + result.fqClassName()
                    + "' parameter at index " + errorsFieldIndex + " is "
                    + parameters[errorsFieldIndex].getType().getName()
                    + "; the errors slot must be a List/Iterable so the catch arm's "
                    + "synthesized payload-factory lambda compiles");
        }
        var defaultedSlots = collectDefaultedSlots(parameters, genericParameterTypes, errorsFieldIndex);
        var errorsSlot = new no.sikt.graphitron.rewrite.model.ErrorsSlot.CtorParameterIndex(errorsFieldIndex);
        return new ErrorChannelResult.Channel(new ErrorChannel.PayloadClass(
            mappedErrorTypes, payloadClassName, errorsSlot, defaultedSlots, mappingsConstantName));
    }

    private static ErrorChannelResult buildErrorChannelBeanArm(
            ReturnTypeRef.ResultReturnType result,
            no.sikt.graphitron.rewrite.model.PayloadConstructionShape.MutableBean mb,
            int errorsFieldIndex,
            List<ErrorType> mappedErrorTypes,
            ClassName payloadClassName,
            String mappingsConstantName) {
        var bindings = mb.bindings();
        var errorsBinding = bindings.get(errorsFieldIndex);
        var errorsSetter = errorsBinding.setter();
        if (!Iterable.class.isAssignableFrom(errorsSetter.getParameterTypes()[0])) {
            return new ErrorChannelResult.Reject(
                "payload class '" + result.fqClassName() + "' setter '" + errorsSetter.getName()
                    + "' accepts " + errorsSetter.getParameterTypes()[0].getName()
                    + "; the errors-slot setter must accept List/Iterable so the catch arm's "
                    + "synthesized payload-factory compiles");
        }
        var nonBoundSetters = collectNonBoundSetters(bindings, errorsFieldIndex);
        var errorsSlot = new no.sikt.graphitron.rewrite.model.ErrorsSlot.SetterMethod(
            errorsSetter, nonBoundSetters);
        // defaultedSlots is the ctor-arm carrier; under the setter shape the non-bound
        // setters with their defaults travel on the slot variant, and the carrier list is empty.
        return new ErrorChannelResult.Channel(new ErrorChannel.PayloadClass(
            mappedErrorTypes, payloadClassName, errorsSlot, java.util.List.of(),
            mappingsConstantName));
    }

    private static List<no.sikt.graphitron.rewrite.model.NonBoundSetter> collectNonBoundSetters(
            List<no.sikt.graphitron.rewrite.model.PayloadConstructionShape.SetterBinding> bindings,
            int boundIndex) {
        var out = new java.util.ArrayList<no.sikt.graphitron.rewrite.model.NonBoundSetter>(
            bindings.size() - 1);
        for (int i = 0; i < bindings.size(); i++) {
            if (i == boundIndex) continue;
            var b = bindings.get(i);
            out.add(new no.sikt.graphitron.rewrite.model.NonBoundSetter(
                b.setter(), defaultLiteralFor(b.setter().getParameterTypes()[0])));
        }
        return out;
    }

    /**
     * SDL field names for the named GraphQL object type in declaration order, or {@code null}
     * when the schema doesn't resolve to an object type. Used by
     * {@link #resolvePayloadConstructionShape} to drive the mutable-bean predicate's
     * per-SDL-field setter lookup.
     */
    private java.util.List<String> sdlFieldNames(String returnTypeName) {
        return ctx.schema.getType(returnTypeName) instanceof GraphQLObjectType obj
            ? obj.getFieldDefinitions().stream()
                .map(graphql.schema.GraphQLFieldDefinition::getName)
                .toList()
            : null;
    }

    /**
     * Channel-level reject rules from §1's parse-time table that span multiple {@code @error}
     * types in the same channel. Runs after the carrier classifier has resolved
     * {@code mappedErrorTypes}; returns a non-null reason string when a rule fires, or
     * {@code null} when the channel is well-formed.
     *
     * <ul>
     *   <li>Rule 7: more than one {@code VALIDATION} handler across the channel's flattened
     *       handler list. VALIDATION is a single fan-out target per payload, and two
     *       {@code VALIDATION}-marked {@code @error} types would compete for the same slot.</li>
     *   <li>Rule 8 (§3): two handlers of the same variant in the channel's flattened handler
     *       list with identical match-criteria. The runtime's source-order {@code findFirst}
     *       on {@code MAPPINGS} would make the second mapping unreachable, so the duplicate
     *       is an author mistake. Intra-variant only: an {@code ExceptionHandler(SQLException)}
     *       and a {@code SqlStateHandler("23503")} discriminate on different fields and are
     *       intentionally allowed to overlap (§3 source-order resolves which {@code @error}
     *       type wins).</li>
     * </ul>
     *
     * <p>(Rule 9, the {@code ValidationViolationGraphQLException}-shadowing check, retired
     * with the native Jakarta validation chunk: validation runs as a wrapper pre-execution
     * step (§5) and never reaches the dispatch arm, so no shadowing window remains.)
     */
    static String checkChannelLevelHandlerRules(List<ErrorType> mappedErrorTypes) {
        // Rule 7 (multiple VALIDATION handlers). Single implementation lives in the R244 walker's
        // internal helper; this method delegates so the DML path and the legacy PayloadClass path
        // share one body with the ErrorChannelWalker (no drift during the additive window). Commit 4
        // rewires the remaining callers onto ChannelRuleChecks directly and deletes this delegator.
        return no.sikt.graphitron.rewrite.walker.internal.ChannelRuleChecks
            .checkMultiValidation(mappedErrorTypes);
    }

    /**
     * Rule 8 (§3): rejects a channel whose flattened handler list contains two intra-variant
     * handlers with identical match-criteria. The criteria tuples per variant:
     *
     * <ul>
     *   <li>{@link ErrorType.ExceptionHandler}: {@code (exceptionClassName, matches)}</li>
     *   <li>{@link ErrorType.SqlStateHandler}: {@code (sqlState, matches)}</li>
     *   <li>{@link ErrorType.VendorCodeHandler}: {@code (vendorCode, matches)}</li>
     * </ul>
     *
     * <p>Tuple equality treats absent {@code matches} as a distinct value from any present
     * {@code matches}. {@link ErrorType.ValidationHandler} is excluded; rule 7 already caps it
     * at one per channel and it has no discriminator. Cross-variant overlap is intentionally
     * allowed: a channel with {@code ExceptionHandler(SQLException)} and
     * {@code SqlStateHandler("23503")} is the canonical "specific arm before fallback" pattern,
     * and §3's source-order rule resolves which {@code @error} type the runtime emits.
     *
     * <p>Returns the first reason encountered, or {@code null} when no duplicates exist. The
     * reason names both colliding {@code @error} types (the same type appears twice when the
     * duplicate is within one type's {@code handlers} array). Closes the legacy gap where
     * {@code ExceptionStrategyConfigurationGenerator} silently allowed duplicates.
     */
    static String checkDuplicateMatchCriteria(List<ErrorType> mappedErrorTypes) {
        // Rule 8 (duplicate intra-variant match-criteria). Delegates to the same single
        // implementation as rule 7; see checkChannelLevelHandlerRules.
        return no.sikt.graphitron.rewrite.walker.internal.ChannelRuleChecks
            .checkDuplicateMatchCriteria(mappedErrorTypes);
    }

    /**
     * Per-(channel, @error type, handler) source-class accessor reflection check (§2c).
     *
     * <p>For each {@code (mappedErrorType, handler)} pair in the channel, looks up each
     * declared SDL field on the {@code @error} type against the handler's source class via
     * {@link ClassAccessorResolver}. The source class is determined by handler kind:
     *
     * <ul>
     *   <li>{@link ErrorType.ExceptionHandler}: the resolved Java class named by
     *       {@code exceptionClassName}.</li>
     *   <li>{@link ErrorType.SqlStateHandler} / {@link ErrorType.VendorCodeHandler}:
     *       {@code java.sql.SQLException} (the universal supertype these variants match in
     *       the cause chain).</li>
     *   <li>{@link ErrorType.ValidationHandler}: {@code graphql.GraphQLError} (the wrapper's
     *       pre-execution Jakarta validation step produces one per violation).</li>
     * </ul>
     *
     * <p>{@code path} and {@code message} are exempt: the rewrite emits a synthesised
     * per-{@code @error}-type {@code DataFetcher} for each, so they don't route through the
     * source class's accessors. Every other declared field must resolve through
     * {@code PropertyDataFetcher} convention on the source class; an unresolved field surfaces
     * the carrier as {@code UnclassifiedField} naming the offending {@code @error} type,
     * handler discriminator, field, and source class.
     *
     * <p>Returns the first reject reason encountered, or {@code null} when every field on
     * every handler resolves cleanly. The classifier source classes ({@code SQLException},
     * {@code GraphQLError}, the {@code ExceptionHandler} className) are resolved at parse time
     * via {@code Class.forName} on {@code ctx.codegenLoader()}; a load failure on a
     * {@code GENERIC} className was already rejected at parse time by
     * {@code TypeBuilder.validateExceptionClass}, so this check trusts those classes to load.
     */
    private String checkErrorTypeSourceAccessors(List<ErrorType> mappedErrorTypes) {
        for (ErrorType errorType : mappedErrorTypes) {
            var sdlType = ctx.schema.getType(errorType.name());
            if (!(sdlType instanceof GraphQLObjectType errorObj)) {
                continue;
            }
            var extraFields = errorObj.getFieldDefinitions().stream()
                .filter(f -> !"path".equals(f.getName()) && !"message".equals(f.getName()))
                .toList();
            if (extraFields.isEmpty()) {
                continue;
            }
            for (var handler : errorType.handlers()) {
                Class<?> sourceClass = resolveHandlerSourceClass(handler);
                if (sourceClass == null) {
                    continue;
                }
                for (var sdlField : extraFields) {
                    var expectedReturn = mapGraphQLTypeToReflectType(sdlField.getType());
                    String accessorBase = errorType.accessorBaseFor(sdlField.getName());
                    var resolution = ClassAccessorResolver.resolve(
                        sourceClass,
                        accessorBase,
                        expectedReturn,
                        new ClassAccessorResolver.PerArgument(java.util.List.of()),
                        ClassAccessorResolver.CandidateOrder.POJO_FIRST);
                    if (resolution instanceof AccessorResolution.Rejected r) {
                        String remap = accessorBase.equals(sdlField.getName()) ? ""
                            : " (remapped to '" + accessorBase + "' by @field)";
                        return "@error type '" + errorType.name() + "' field '"
                            + sdlField.getName() + "'" + remap + " cannot be populated from handler "
                            + describeHandler(handler) + " source class '"
                            + sourceClass.getName() + "': " + r.reason();
                    }
                }
            }
        }
        return null;
    }

    /**
     * The runtime source class for an {@code @error} {@link ErrorType.Handler} variant. Returns
     * {@code null} when the source class cannot be loaded on the codegen classloader (a
     * {@code GENERIC} className would have been rejected at parse time by
     * {@code TypeBuilder.validateExceptionClass}, so this path is only reached for the
     * fixed-FQN cases where a missing class indicates a broken classifier classpath).
     */
    private Class<?> resolveHandlerSourceClass(ErrorType.Handler handler) {
        String fqn = switch (handler) {
            case ErrorType.ExceptionHandler eh -> eh.exceptionClassName();
            case ErrorType.SqlStateHandler ignored -> "java.sql.SQLException";
            case ErrorType.VendorCodeHandler ignored -> "java.sql.SQLException";
            case ErrorType.ValidationHandler ignored -> "graphql.GraphQLError";
        };
        try {
            return Class.forName(fqn, false, ctx.codegenLoader());
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    /** Short fingerprint for a {@link ErrorType.Handler} used in reject reasons. */
    private static String describeHandler(ErrorType.Handler handler) {
        return switch (handler) {
            case ErrorType.ExceptionHandler eh -> "{handler: GENERIC, className: \""
                + eh.exceptionClassName() + "\"}";
            case ErrorType.SqlStateHandler sh -> "{handler: DATABASE, sqlState: \""
                + sh.sqlState() + "\"}";
            case ErrorType.VendorCodeHandler vh -> "{handler: DATABASE, code: \""
                + vh.vendorCode() + "\"}";
            case ErrorType.ValidationHandler ignored -> "{handler: VALIDATION}";
        };
    }

    /**
     * Builds the {@link no.sikt.graphitron.rewrite.model.DefaultedSlot} list for every
     * constructor parameter except the bound slot at {@code boundSlotIndex}. Used by
     * {@link #resolveErrorChannel} (errors slot) to capture per-non-bound-slot default
     * literals from one reflection pass.
     */
    private static List<no.sikt.graphitron.rewrite.model.DefaultedSlot> collectDefaultedSlots(
            java.lang.reflect.Parameter[] parameters,
            java.lang.reflect.Type[] genericParameterTypes,
            int boundSlotIndex) {
        var slots = new java.util.ArrayList<no.sikt.graphitron.rewrite.model.DefaultedSlot>(
            parameters.length - 1);
        for (int i = 0; i < parameters.length; i++) {
            if (i == boundSlotIndex) continue;
            var p = parameters[i];
            String paramName = p.isNamePresent() ? p.getName() : ("arg" + i);
            no.sikt.graphitron.javapoet.TypeName paramTypeName =
                no.sikt.graphitron.javapoet.TypeName.get(genericParameterTypes[i]);
            slots.add(new no.sikt.graphitron.rewrite.model.DefaultedSlot(
                i, paramName, paramTypeName, defaultLiteralFor(p.getType())));
        }
        return slots;
    }

    /** Language-default literal for a non-errors constructor parameter slot. */
    private static String defaultLiteralFor(Class<?> rawType) {
        if (!rawType.isPrimitive()) return "null";
        if (rawType == boolean.class) return "false";
        if (rawType == char.class) return "'\\0'";
        if (rawType == long.class) return "0L";
        if (rawType == float.class) return "0.0f";
        if (rawType == double.class) return "0.0";
        return "0";  // byte / short / int
    }

    /**
     * Converts a Java identifier (e.g. a class's simple name) to {@code SCREAMING_SNAKE_CASE}.
     * {@code FilmPayload} → {@code FILM_PAYLOAD}, {@code BehandleSakPayload} →
     * {@code BEHANDLE_SAK_PAYLOAD}.
     *
     * <p>The §3 dedup logic (hash-suffixing colliding shapes with different mappings) lands
     * with {@code ErrorMappings} emission; this helper produces the unsuffixed base name.
     */
    private static String toScreamingSnake(String s) {
        if (s == null || s.isEmpty()) return s;
        var sb = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (i > 0 && Character.isUpperCase(c) && !Character.isUpperCase(s.charAt(i - 1))) {
                sb.append('_');
            }
            sb.append(Character.toUpperCase(c));
        }
        return sb.toString();
    }

    /**
     * Carrier-classifier helper: resolves the {@link ErrorChannel}
     * for {@code returnType}, then dispatches to {@code builder} with the resolved channel
     * (wrapped in {@link Optional}). When the resolution rejects, returns an
     * {@link UnclassifiedField} with the carried reason; the caller never sees the rejection
     * shape.
     *
     * <p>Used at the construction site of each {@code WithErrorChannel} variant to keep the
     * channel-resolution logic out of the variant-specific arms.
     */
    private GraphitronField buildWithChannel(
            ReturnTypeRef returnType, String parentTypeName, String fieldName,
            SourceLocation location, GraphQLFieldDefinition fieldDef,
            java.util.function.Function<Optional<ErrorChannel>, GraphitronField> builder) {
        return switch (resolveErrorChannel(returnType)) {
            case ErrorChannelResult.NoChannel ignored -> builder.apply(Optional.empty());
            case ErrorChannelResult.Channel c -> builder.apply(Optional.of(c.channel()));
            case ErrorChannelResult.Reject r -> new UnclassifiedField(parentTypeName, fieldName, location, fieldDef, Rejection.structural(r.reason()));
        };
    }

    /**
     * Variant of {@link #buildWithChannel} for child {@code @service} variants and root + child
     * {@code @tableMethod} variants: resolves the {@link ErrorChannel} against the field's return
     * type, runs the §4 declared-checked-exception match check against the resolved channel, and
     * forwards the channel to {@code builder}. Replaces the six call sites that previously passed
     * {@code Optional.empty()} for the channel because their variants didn't carry an
     * {@code errorChannel} slot; the lift adds the slot uniformly so the §4 check runs against a
     * populated channel wherever the field's payload declares an {@code errors} field.
     *
     * <p>Sibling of {@link #buildServiceField}: that helper additionally runs the
     * service-return strict-equality check against the SDL payload type, rejecting service
     * methods whose return type does not match the field's declared payload. Child
     * {@code @service} and {@code @tableMethod} variants don't need that check (their return
     * shapes are pinned by the catalog's strict-return guarantee), so this helper omits it.
     *
     * <p>Both helpers call into the same {@link #checkDeclaredCheckedExceptions} utility.
     */
    private GraphitronField buildMethodBackedWithChannel(
            ReturnTypeRef returnType, no.sikt.graphitron.rewrite.model.MethodRef method,
            String parentTypeName, String fieldName,
            SourceLocation location, GraphQLFieldDefinition fieldDef,
            java.util.function.Function<Optional<ErrorChannel>, GraphitronField> builder) {
        Optional<ErrorChannel> channel;
        switch (resolveErrorChannel(returnType)) {
            case ErrorChannelResult.NoChannel ignored -> channel = Optional.empty();
            case ErrorChannelResult.Channel c -> channel = Optional.of(c.channel());
            case ErrorChannelResult.Reject r -> {
                return new UnclassifiedField(parentTypeName, fieldName, location, fieldDef, Rejection.structural(r.reason()));
            }
        }
        String reason = checkDeclaredCheckedExceptions(method, channel);
        if (reason != null) {
            return new UnclassifiedField(parentTypeName, fieldName, location, fieldDef, Rejection.structural(reason));
        }
        return builder.apply(channel);
    }

    /**
     * Variant of {@link #buildWithChannel} for service-backed root fields: resolves the
     * {@link ErrorChannel} (catch-arm dispatch recipe), runs the declared-checked-exception
     * match check against the resolved channel, and verifies the service method's reflected
     * return type matches the SDL payload type. Used by the four service field variants
     * ({@code MutationServiceTableField}, {@code MutationServiceRecordField},
     * {@code QueryServiceTableField}, {@code QueryServiceRecordField}); a rejection on any of
     * the three checks surfaces as {@code UnclassifiedField}.
     *
     * <p>The strict-return check runs only when the field resolves to a class-backed
     * payload ({@code ResultReturnType} with a non-null {@code fqClassName}); other return
     * shapes (table-bound, pojo-result, scalar) are screened upstream by
     * {@code ServiceCatalog.reflectServiceMethod} and {@code ServiceDirectiveResolver}.
     */
    private GraphitronField buildServiceField(
            ReturnTypeRef returnType, no.sikt.graphitron.rewrite.model.MethodRef method,
            String parentTypeName, String fieldName,
            SourceLocation location, GraphQLFieldDefinition fieldDef,
            java.util.function.BiFunction<Optional<ErrorChannel>, no.sikt.graphitron.rewrite.model.ServiceMethodCall, GraphitronField> builder) {
        // R244: @service outcome fields classify to ErrorChannel.Mapped via the ErrorChannelWalker
        // (the Outcome wrapper transport) rather than PayloadClass construction. NoChannel when the
        // payload carries no errors field.
        Optional<ErrorChannel> channel;
        switch (resolveServiceOutcomeChannel(returnType)) {
            case ServiceOutcomeResult.NoChannel ignored -> channel = Optional.empty();
            case ServiceOutcomeResult.Channel c -> channel = Optional.of(c.channel());
            case ServiceOutcomeResult.Reject r -> {
                return new UnclassifiedField(parentTypeName, fieldName, location, fieldDef, r.rejection());
            }
        }
        String exceptionsReason = checkDeclaredCheckedExceptions(method, channel);
        if (exceptionsReason != null) {
            return new UnclassifiedField(parentTypeName, fieldName, location, fieldDef, Rejection.structural(exceptionsReason));
        }
        // R308: the @service carrier shape verdict — the single authority for whether a list-returning
        // carrier admits. It fires even when the payload's ResultReturnType.fqClassName is null (the a1
        // silent admit, where producerBindLevel's NoBind left it unbound and the return-match gate
        // below short-circuits), and its typed ProducerArrivalMismatch pre-empts the two misleading
        // record-handoff rejections checkServiceReturnMatchesPayload would otherwise produce.
        java.util.Optional<SourceKey.Cardinality> verdictProducerArrival;
        switch (scanServiceCarrierShape(returnType, method, parentTypeName, fieldName)) {
            case BuildContext.ServiceCarrierShape.Reject r ->
                { return new UnclassifiedField(parentTypeName, fieldName, location, fieldDef, r.error()); }
            case BuildContext.ServiceCarrierShape.Coherent c ->
                verdictProducerArrival = java.util.Optional.of(c.producerArrival());
            case BuildContext.ServiceCarrierShape.NotApplicable ignored ->
                verdictProducerArrival = java.util.Optional.empty();
        }
        String returnTypeReason = checkServiceReturnMatchesPayload(returnType, method, verdictProducerArrival);
        if (returnTypeReason != null) {
            return new UnclassifiedField(parentTypeName, fieldName, location, fieldDef, Rejection.structural(returnTypeReason));
        }
        // R238: project the resolved MethodRef.Service onto a ServiceMethodCall carrier.
        var walkerResult = new no.sikt.graphitron.rewrite.walker.ServiceMethodCallWalker()
            .walk(fieldDef, (no.sikt.graphitron.rewrite.model.MethodRef.Service) method);
        return switch (walkerResult) {
            case no.sikt.graphitron.rewrite.model.WalkerResult.Ok<no.sikt.graphitron.rewrite.model.ServiceMethodCall> ok ->
                builder.apply(channel, ok.carrier());
            case no.sikt.graphitron.rewrite.model.WalkerResult.Err<no.sikt.graphitron.rewrite.model.ServiceMethodCall> err ->
                // R238: preserve the typed ServiceMethodCallError as the UnclassifiedField's
                // rejection so the LSP Diagnostic projector can read its lspCode() and set the
                // wire `code` field. Collapsing to Rejection.structural would erase the typed
                // arm and lose the stable wire code that editor extensions key on.
                new UnclassifiedField(parentTypeName, fieldName, location, fieldDef,
                    err.errors().getFirst());
        };
    }

    /**
     * Strict-equality check on the service method's return type against the SDL payload type
     * for class-backed payloads. Returns a rejection reason when the method's
     * reflected return type does not equal the SDL payload type (or {@code List<Payload>} for
     * list-cardinality fields); returns {@code null} otherwise.
     *
     * <p>Per-field wiring projects SDL fields off the parent's domain return, so the success
     * arm is universal passthrough: the service method must return the SDL payload class
     * directly. This check makes the constraint explicit at classify time rather than at
     * runtime via a ClassCastException.
     *
     * <p>R329 — for a two-level record-composite carrier ({@link TypeBuilder.CarrierBinding.ClassBacked}),
     * the payload's backing class names the per-element composite, and the method returns
     * {@code List<composite>} (or {@code composite}) keyed on whether the producer must yield a
     * collection. R308 — that cardinality is no longer re-derived here from the carrier / data-field
     * wrappers; it is the {@code verdictProducerArrival} the shape verdict
     * ({@link BuildContext.ServiceCarrierShape.Coherent#producerArrival()}) decided once at the
     * carrier-arrival home and carried down. This check now does only its residual job, the producer
     * <em>element-type</em> comparison. When there is no carrier verdict ({@code NotApplicable}: a plain
     * class-backed {@code @service} payload that is not a producer-backed carrier) the arrival is the
     * return's own SDL wrapper. A single-cardinality shape whose producer returns a {@code List} (or
     * vice versa) is the cardinality near-miss, named here.
     */
    private String checkServiceReturnMatchesPayload(
            ReturnTypeRef returnType, no.sikt.graphitron.rewrite.model.MethodRef method,
            java.util.Optional<SourceKey.Cardinality> verdictProducerArrival) {
        if (!(returnType instanceof ReturnTypeRef.ResultReturnType result)) return null;
        if (result.fqClassName() == null) return null;
        boolean isList = verdictProducerArrival
            .map(arrival -> arrival == SourceKey.Cardinality.MANY)
            .orElseGet(() -> returnType.wrapper().isList());
        // R370: build the expected payload ClassName structurally, not via bestGuess over the
        // binary fqClassName. A nested backing class has a `$`-qualified binary name
        // (Outer$Nested); bestGuess splits only on `.` and would carry it as a single simple name,
        // whereas method.returnType() is TypeName.get(genericReturnType) with the enclosing
        // structure resolved (Outer.Nested). The two ClassNames would then never compare equal for
        // a nested carrier even when the method returns exactly the payload type, spuriously
        // rejecting it. fqClassName() is the classloader's binary-name contract, so Class.forName
        // loads it; ClassName.get(Class) then walks the enclosing structure the same way
        // method.returnType() does. Top-level classes are unchanged.
        ClassName payloadClassName;
        try {
            payloadClassName = ClassName.get(
                Class.forName(result.fqClassName(), false, ctx.codegenLoader()));
        } catch (ClassNotFoundException e) {
            payloadClassName = ClassName.bestGuess(result.fqClassName());
        }
        no.sikt.graphitron.javapoet.TypeName sdlPayloadTypeName = isList
            ? no.sikt.graphitron.javapoet.ParameterizedTypeName.get(
                ClassName.get("java.util", "List"), payloadClassName)
            : payloadClassName;
        if (method.returnType().equals(sdlPayloadTypeName)) return null;
        return "@service method '" + method.className() + "." + method.methodName()
            + "' must return '" + sdlPayloadTypeName + "' to match the field's "
            + "declared payload type — got '" + method.returnType() + "'";
    }

    /**
     * R308 — the single classify-time shape verdict for an {@code @service} carrier field, over the
     * triple (carrier field wrapper, {@code @service} producer return shape, payload data-field
     * wrapper). It is the sole authority for whether a <em>list-returning</em> carrier admits; a
     * single carrier is always coherent and left to its existing classification, so every coherent
     * shape's model and emit stay byte-for-byte unchanged.
     *
     * <p>Reads three axes, each from its one home: carrier arrival straight off the carrier field's
     * SDL wrapper here; producer arrival from the typed fact decided once at the R96 reflection
     * boundary ({@link TypeBuilder#serviceCarrierProducerArrival}); data-field arrival from the
     * canonical {@link BuildContext#scanStructuralServiceCarrierPayload} shape. This replaces the
     * uncoordinated wrapper reads that previously decided list-carrier admission (the
     * {@code RecordBindingResolver.producerBindLevel} {@code NoBind}-silent-drop, and the
     * carrier-wrapper read inside {@link #checkServiceReturnMatchesPayload}), none of which ever saw
     * the whole triple.
     *
     * <p>A list carrier rejects when the producer returns a single value
     * ({@link ServiceCarrierShapeError.ProducerArrivalMismatch}: graphql-java cannot iterate a single
     * value into the {@code [Payload]} list), or when the data field is itself a list
     * ({@link ServiceCarrierShapeError.DataFieldArrivalConflict}: the flat producer list is consumed
     * element-by-element into the carrier, so a single value reaches each payload and cannot populate a
     * list data field, the per-element {@code ClassCastException}). That conflict fires for both a
     * {@code @table}-element and a class-backed record-composite ({@code RecordElement}) data field, the
     * two element kinds a list carrier admits; only an ID-element data field re-nests per element and
     * stays coherent.
     */
    private BuildContext.ServiceCarrierShape scanServiceCarrierShape(
            ReturnTypeRef returnType, no.sikt.graphitron.rewrite.model.MethodRef method,
            String parentTypeName, String fieldName) {
        if (typeBuilder == null) return new BuildContext.ServiceCarrierShape.NotApplicable();
        String payloadSdl = returnType.returnTypeName();
        if (payloadSdl == null) return new BuildContext.ServiceCarrierShape.NotApplicable();
        // Only a producer-backed carrier gets a verdict; a non-carrier @service return falls through
        // to its existing classification untouched.
        if (typeBuilder.carrierBinding(payloadSdl) instanceof TypeBuilder.CarrierBinding.NotACarrier) {
            return new BuildContext.ServiceCarrierShape.NotApplicable();
        }
        // Carrier arrival: the one home is the carrier field's own SDL wrapper.
        SourceKey.Cardinality carrierArrival = returnType.wrapper().isList()
            ? SourceKey.Cardinality.MANY : SourceKey.Cardinality.ONE;
        // The cardinality the SDL shape requires the producer's return to have — carried on Coherent so
        // the downstream return-type match reads this one fact instead of re-deriving it. A collection
        // is required when the carrier is a list ([Payload], one composite per element) or when an R329
        // class-backed record-composite data field is itself a list (a single carrier whose data field
        // projects the whole producer list). This is the read checkServiceReturnMatchesPayload used to
        // do for itself; it now lives once, here, at the carrier-arrival home.
        SourceKey.Cardinality requiredProducerArrival =
            carrierArrival == SourceKey.Cardinality.MANY || recordCompositeDataFieldIsList(payloadSdl)
                ? SourceKey.Cardinality.MANY : SourceKey.Cardinality.ONE;
        // A single carrier is always coherent: the producer's return (single value or collection) is
        // the single payload's source and the data field consumes it. Every existing @service carrier
        // shape is single-arrival, so this arm leaves them byte-for-byte unchanged.
        if (carrierArrival == SourceKey.Cardinality.ONE) {
            return new BuildContext.ServiceCarrierShape.Coherent(requiredProducerArrival);
        }
        // List carrier [Payload]: graphql-java iterates the producer's return into the list, so each
        // element becomes one payload. Producer arrival must be a collection (MANY); an absent fact
        // (no @service producer observed for this payload) reads as ONE and rejects, which is correct
        // — a list carrier with no collection producer cannot be filled.
        SourceKey.Cardinality producerArrival = typeBuilder
            .serviceCarrierProducerArrival(parentTypeName, fieldName)
            .orElse(SourceKey.Cardinality.ONE);
        if (producerArrival == SourceKey.Cardinality.ONE) {
            return new BuildContext.ServiceCarrierShape.Reject(
                new ServiceCarrierShapeError.ProducerArrivalMismatch(
                    payloadSdl, parentTypeName, fieldName, carrierArrival, producerArrival,
                    method.className(), method.methodName()));
        }
        // Producer is a collection. A data field that is itself a list cannot be demultiplexed out of
        // the flat producer list: the list is consumed element-by-element to build the [Payload]
        // carrier, so a single value (one record for a @table element, one composite for a class-backed
        // RecordElement) reaches each payload and cannot also populate a list data field — the
        // @table element crashes on the per-element key-extraction cast to Iterable, the RecordElement
        // on the source-passthrough cast of one composite to List<Composite> (FetcherEmitter's
        // buildRecordCompositeFetcherValue). Filling it would need a producer returning List<List<…>>
        // (one inner list per carrier element), which the single-level producer cannot provide. Both
        // are the a2 conflict; only the ID element re-nests per element and stays coherent.
        if (ctx.scanStructuralServiceCarrierPayload(payloadSdl)
                instanceof BuildContext.DmlPayloadScan.Admit admit
                && GraphQLTypeUtil.unwrapNonNull(admit.dataField().getType()) instanceof GraphQLList
                && (admit.element() instanceof BuildContext.DmlElementKind.Table
                    || admit.element() instanceof BuildContext.DmlElementKind.RecordElement)) {
            String dataFieldElementType =
                ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(admit.dataField().getType())).getName();
            return new BuildContext.ServiceCarrierShape.Reject(
                new ServiceCarrierShapeError.DataFieldArrivalConflict(
                    payloadSdl, parentTypeName, fieldName,
                    admit.dataField().getName(), dataFieldElementType,
                    carrierArrival, SourceKey.Cardinality.MANY));
        }
        return new BuildContext.ServiceCarrierShape.Coherent(requiredProducerArrival);
    }

    /**
     * R308 / R329 — true when the payload is a class-backed record-composite carrier whose data field
     * is itself a list ({@code Payload { results: [Result] }}). Under a <em>single</em> carrier this is
     * the coherent R329 shape whose single payload's list data field projects the whole producer list,
     * so it requires the {@code @service} producer to return a collection; the carrier-arrival home uses
     * this to compute {@code requiredProducerArrival} once, before the single-carrier early return.
     * (Under a <em>list</em> carrier the same list data field is instead the a2
     * {@link ServiceCarrierShapeError.DataFieldArrivalConflict} reject — one composite per payload
     * element cannot fill a list — so this predicate never gates a list-carrier Coherent verdict.)
     */
    private boolean recordCompositeDataFieldIsList(String payloadSdl) {
        return typeBuilder.carrierBinding(payloadSdl) instanceof TypeBuilder.CarrierBinding.ClassBacked
            && ctx.scanStructuralServiceCarrierPayload(payloadSdl)
                instanceof BuildContext.DmlPayloadScan.Admit admit
            && admit.element() instanceof BuildContext.DmlElementKind.RecordElement
            && GraphQLTypeUtil.unwrapNonNull(admit.dataField().getType()) instanceof GraphQLList;
    }

    /**
     * Declared-checked-exception match check. Returns a reason string when one or more
     * non-exempt checked exceptions on the method's {@code throws} clause are not covered by
     * any handler on the channel; returns {@code null} when every declared checked exception
     * is either exempt or covered. The reason names the offending FQNs and points the schema
     * author at the two fixes (declare an {@code @error} that covers the exception, or remove
     * the {@code throws} clause). When the field has no channel, every non-exempt declared
     * checked exception is unmatched.
     */
    // Instance method (not static) so it can read `ctx.codegenLoader()`; the two callers
    // `buildWithChannel` and `buildServiceField` are on the same class and already hold `ctx`.
    // The explicit-parameter sibling lives at `CheckedExceptionMatcher.unmatched`, which
    // crosses a class boundary.
    private String checkDeclaredCheckedExceptions(
            no.sikt.graphitron.rewrite.model.MethodRef method, Optional<ErrorChannel> channel) {
        var unmatched = CheckedExceptionMatcher.unmatched(
            method.declaredExceptions(), channel, ctx.codegenLoader());
        if (unmatched.isEmpty()) return null;
        return "method '" + method.className() + "." + method.methodName()
            + "' declares checked exception(s) " + unmatched
            + " with no covering @error handler on the field's payload"
            + (channel.isEmpty() ? " (the field has no error channel)" : "")
            + " — declare an @error type whose handler covers each, or remove the throws clause";
    }

    /**
     * Variant of {@link #buildWithChannel} used by {@code DmlTableField} construction sites:
     * resolves the {@link ErrorChannel} (catch-arm dispatch recipe), folds the pre-resolved
     * {@link DmlReturnExpression} arm, and forwards both to {@code builder}. A rejection on the
     * channel side surfaces as {@code UnclassifiedField}; {@code NoChannel} yields an empty
     * channel.
     *
     * <p>Post-R161, the {@code DmlTableField} permits never carry a class-backed return — every
     * {@code ResultReturnType} routes through the {@code Mutation*DmlRecordField} permits, or
     * rejects at {@code MutationInputResolver.validateReturnType} before reaching this builder.
     * {@code resolveErrorChannel} therefore returns {@code NoChannel} by construction here; the
     * call is preserved so the model's slot stays uniformly wired.
     */
    private GraphitronField buildDmlField(
            ReturnTypeRef returnType, String parentTypeName, String fieldName,
            SourceLocation location, GraphQLFieldDefinition fieldDef,
            java.util.function.BiFunction<DmlReturnExpression, Optional<ErrorChannel>, GraphitronField> builder,
            Optional<HelperRef.Encode> encodeReturn) {
        Optional<ErrorChannel> channel;
        switch (resolveErrorChannel(returnType)) {
            case ErrorChannelResult.NoChannel ignored -> channel = Optional.empty();
            case ErrorChannelResult.Channel c -> channel = Optional.of(c.channel());
            case ErrorChannelResult.Reject r -> {
                return new UnclassifiedField(parentTypeName, fieldName, location, fieldDef, Rejection.structural(r.reason()));
            }
        }
        // R406: resolve the return's look-ahead verdict once at this single DML chokepoint. A
        // TableInterfaceType return (single-table @table @discriminate) classifies to a
        // TableBoundReturnType (it is a TableBackedType), so the "is this a discriminated interface?"
        // fork lives here, not per verb; the resolved verdict is threaded into the static
        // buildDmlReturnExpression rather than making it non-static.
        Optional<GraphitronType.TableInterfaceType> interfaceVerdict = Optional.empty();
        if (returnType instanceof ReturnTypeRef.TableBoundReturnType tb
                && typeBuilder.lookAheadVerdict(tb.returnTypeName()) instanceof GraphitronType.TableInterfaceType tit) {
            interfaceVerdict = Optional.of(tit);
        }
        DmlReturnExpression returnExpression = buildDmlReturnExpression(returnType, encodeReturn, interfaceVerdict);
        return builder.apply(returnExpression, channel);
    }

    /**
     * Validates the DML data-table invariant (R75): when a {@code @mutation} field returns a
     * single-record DML carrier, the data field's {@code @table} must equal the DML target
     * table (the input's {@code @table}). Two consumer sites depend on this equality: (a) the
     * mutation fetcher's PK-only {@code RETURNING} clause projects
     * {@code tableInputArg.inputTable().primaryKeyColumns()}, and (b) the data field fetcher's
     * response SELECT builds {@code where(TABLE.PK.in(source.getValues(TABLE.PK)))}. Both
     * sites need the upstream Result's row type to match the data field's element table's PK
     * columns, exactly the equality this check enforces.
     *
     * <p>Returns {@code null} when the tables match; a non-null rejection reason otherwise.
     */
    private static String requireDmlDataTableMatchesInputTable(
            TableRef inputTable, BuildContext.DmlElementKind.Table tbl, DmlKind kind,
            String mutationFieldName, String carrierTypeName) {
        if (inputTable.equals(tbl.table())) {
            return null;
        }
        return "@mutation(typeName: " + kind + ") field '" + mutationFieldName
            + "' returns single-record DML carrier '" + carrierTypeName
            + "' whose data field element type '" + tbl.elementTypeName()
            + "' is bound to table '" + tbl.table().tableName()
            + "', which does not match @table input table '" + inputTable.tableName()
            + "'; payload-returning DML mutations require the data field's table to equal the "
            + "DML's input table";
    }

    /**
     * R159 / R178 Phase 4 — type-match check at the payload-data-field {@code $source}
     * admission site. Runs when an {@code @service} mutation returns a structurally carrier-
     * shaped payload (single {@code @table}-typed data field) whose data field opts into the
     * {@code $source} sigil via {@code @field(name: "$source")}: compares the producer's
     * reflected return {@link no.sikt.graphitron.javapoet.TypeName} against the data table's
     * record class through {@link FieldSourceSigil#sourceSigilTypeMatches}.
     *
     * <p>The detection is structural (consults the payload SDL directly), so no
     * forbidden-directives loop runs at this site.
     *
     * <p>Returns {@code null} when the check passes (or when no carrier shape / no
     * {@code $source} sigil applies); on mismatch, returns the canonical
     * {@link FieldSourceSigil#typeMismatchMessage} for the caller to wrap into an
     * {@link UnclassifiedField}.
     */
    private String checkSourceSigilTypeMatch(
            String returnTypeName,
            no.sikt.graphitron.rewrite.model.MethodRef method) {
        var shape = detectStructuralServicePayloadShape(returnTypeName);
        if (shape == null) return null;
        var dataField = findStructuralPayloadDataField(returnTypeName);
        if (dataField == null) return null;
        var parsed = FieldSourceSigil.parseArgFieldNameRef(dataField, DIR_FIELD, ARG_NAME);
        boolean isSourceSigil = parsed instanceof FieldSourceSigil.ParseResult.Ok ok
            && ok.ref() instanceof FieldSourceSigil.FieldNameRef.UpstreamRoot;
        if (!isSourceSigil) return null;
        var mismatch = FieldSourceSigil.sourceSigilTypeMatches(
            method.returnType(), method.className(), method.methodName(),
            shape.table().recordClass(),
            shape.cardinality() == SourceKey.Cardinality.MANY);
        return mismatch.orElse(null);
    }

    /**
     * R178 Phase 4 — re-walk the payload SDL to retrieve the structurally-detected carrier's
     * data field definition. Mirrors {@link #detectStructuralServicePayloadShape}'s walk but
     * returns the field definition rather than its resolved table/cardinality; both helpers
     * land at the same single {@code @table}-typed data field on payloads that
     * {@link #detectStructuralServicePayloadShape} admits.
     */
    private GraphQLFieldDefinition findStructuralPayloadDataField(String payloadSdlName) {
        if (payloadSdlName == null) return null;
        var payloadType = ctx.schema.getType(payloadSdlName);
        if (!(payloadType instanceof graphql.schema.GraphQLObjectType payloadObj)) return null;
        GraphQLFieldDefinition dataField = null;
        for (var f : payloadObj.getFieldDefinitions()) {
            String unwrappedFieldType = ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(f.getType())).getName();
            // R317 slice 3d — the payload data field is two hops below the field being classified;
            // resolving its table-backed fact from the pure TableIndex (a fixed point) removes the
            // deep in-progress-registry read that blocks the single-walk collapse.
            if (ctx.tables.forName(unwrappedFieldType).isEmpty()) continue;
            if (dataField != null) return null;
            dataField = f;
        }
        return dataField;
    }

    /**
     * R178 Phase 4 — structural detection of a DML payload's error channel. Scans the payload
     * SDL for an errors-shaped field (via {@link BuildContext#detectErrorsFieldShape}), runs
     * the channel-level handler rules (§1 rule 7 and rule 8) inline, and produces either an
     * {@link ErrorChannel.LocalContext} binding, an empty result when the payload has no
     * errors-shaped field, or a rule-violation rejection naming the offending channel.
     */
    private sealed interface StructuralDmlErrorChannel {
        record None() implements StructuralDmlErrorChannel {}
        record Present(ErrorChannel channel) implements StructuralDmlErrorChannel {}
        record RuleViolation(String reason) implements StructuralDmlErrorChannel {}
    }

    private StructuralDmlErrorChannel detectStructuralDmlErrorChannel(String payloadSdlName) {
        if (payloadSdlName == null) return new StructuralDmlErrorChannel.None();
        var payloadType = ctx.schema.getType(payloadSdlName);
        if (!(payloadType instanceof graphql.schema.GraphQLObjectType payloadObj)) {
            return new StructuralDmlErrorChannel.None();
        }
        for (var f : payloadObj.getFieldDefinitions()) {
            var errorTypes = ctx.detectErrorsFieldShape(f);
            if (errorTypes == null) continue;
            String rule7 = checkChannelLevelHandlerRules(errorTypes);
            if (rule7 != null) {
                return new StructuralDmlErrorChannel.RuleViolation(
                    "errors-shaped carrier field '" + f.getName() + "': " + rule7);
            }
            String rule8 = checkDuplicateMatchCriteria(errorTypes);
            if (rule8 != null) {
                return new StructuralDmlErrorChannel.RuleViolation(
                    "errors-shaped carrier field '" + f.getName() + "': " + rule8);
            }
            return new StructuralDmlErrorChannel.Present(
                new ErrorChannel.LocalContext(errorTypes, BuildContext.toScreamingSnake(payloadSdlName)));
        }
        return new StructuralDmlErrorChannel.None();
    }

    /**
     * R178 step 3: structural strict-return check for {@code @service} mutations whose payload
     * has no resolved backing class on the SDL payload type. Inspects the
     * payload SDL directly (single {@code @table}-typed data field) and compares the method's
     * reflected return type against the expected {@code XRecord} (Cardinality.ONE) /
     * {@code List<XRecord>} (Cardinality.MANY) shape.
     *
     * <p>The check is restricted to unbacked payloads because ClassBacked payloads have their
     * own diagnostic path through {@link #buildServiceField}'s surviving legacy-equality
     * check, which produces a diagnostic citing the SDL payload's reflected class (not the
     * inner table's record class). The SettKvotesporsmal regression pinned this split:
     * ClassBacked payloads route through {@code buildServiceField}'s "must return
     * '&lt;PayloadClass&gt;'" reject; unbacked payloads route through this structural
     * strict-return check.
     *
     * <p>Returns a non-null rejection string when the payload is unbacked, structurally a
     * single-{@code @table}-data-field carrier, and the method's return type does not equal
     * exactly {@code XRecord} or {@code List<XRecord>}; {@code null} otherwise. Non-carrier
     * payloads (zero or multiple {@code @table}-typed data fields), ClassBacked payloads, and
     * methods whose return matches the expected shape all short-circuit to {@code null}.
     */
    private String classifyServicePayloadProducer(
            no.sikt.graphitron.rewrite.model.ReturnTypeRef.ResultReturnType returnType,
            no.sikt.graphitron.rewrite.model.MethodRef method) {
        if (returnType.fqClassName() != null) return null;
        return classifyServicePayloadProducerByName(returnType.returnTypeName(), method);
    }

    /**
     * R276: the carrier strict-return check keyed by SDL type name, so it runs whether the
     * {@code @service} return resolved to a Result (a backed payload) or a Scalar (a carrier-shaped
     * payload that did not bind because the producer return did not match). A carrier shape whose
     * producer return does not equal {@code XRecord} / {@code List<XRecord>} never binds (RootService
     * and ServiceEmitted both require the match), so it reaches the Scalar arm as a NestingType;
     * this check rejects it with the expected-return diagnostic rather than silently admitting a
     * mismatched producer.
     */
    private String classifyServicePayloadProducerByName(
            String returnTypeName, no.sikt.graphitron.rewrite.model.MethodRef method) {
        var shape = detectStructuralServicePayloadShape(returnTypeName);
        if (shape == null) return null;
        var target = shape.table();
        var cardinality = shape.cardinality();
        no.sikt.graphitron.javapoet.TypeName expectedReturnType = cardinality == SourceKey.Cardinality.ONE
            ? target.recordClass()
            : no.sikt.graphitron.javapoet.ParameterizedTypeName.get(
                ClassName.get(java.util.List.class), target.recordClass());
        if (!expectedReturnType.equals(method.returnType())) {
            return "method '" + method.methodName() + "' in class '" + method.className()
                + "' must return '" + expectedReturnType + "' to match the field's declared return type"
                + " — got '" + method.returnType() + "'";
        }
        return null;
    }

    /**
     * R275 — composes the rejection reason for an {@code @service} mutation whose carrier-shaped
     * SDL-Object return type never registered (no reflection binding, no
     * producer-backed carrier promotion). Classifying such a field would emit a type reference to
     * a type the model dropped, producing an invalid assembled schema. The ID-element arm is the
     * {@code fjernSakTagg}-family shape: {@code @nodeId}-from-record encoding is supported on
     * {@code @service} carriers, so reaching this arm means the producer binding failed to
     * ground (typically a record-class or cardinality mismatch against the
     * {@code @nodeId(typeName:)} target's table); the reason restates the grounding contract.
     */
    private String orphanServiceCarrierReason(String payloadTypeName, String fieldName,
            BuildContext.DmlPayloadScan.Admit admit) {
        String base = "@service mutation field '" + fieldName + "' returns '" + payloadTypeName
            + "', a carrier-shaped SDL Object type that did not classify; the type would be "
            + "dropped from the schema and the field's type reference would dangle. ";
        if (admit.element() instanceof BuildContext.DmlElementKind.IdElement) {
            return base + "The payload's data field ('" + admit.dataField().getName()
                + "') is an ID-element carrier, but no @service producer binding grounds it. "
                + "An ID-element @service carrier requires @nodeId(typeName: T) on the data "
                + "field, where T is a @node @table type whose jOOQ record class matches the "
                + "service method's return (the record for a single ID, List<record> for a "
                + "list) — the node id is then encoded straight off the returned record(s) "
                + "with no re-fetch.";
        }
        return base + "The payload scans as a single-record carrier over data field '"
            + admit.dataField().getName() + "', but no @service producer binding grounds it; "
            + "check that the service method's return type matches the data field's "
            + "record class and cardinality.";
    }

    /**
     * R178 step 3: structural detection for an {@code @service}-carrier shape on an unbacked
     * SDL payload. Mirrors {@code RecordBindingResolver.groundServicePayloadBinding}'s payload
     * SDL walk: scans the payload object's field definitions, looks for exactly one
     * {@code @table}-typed data field (the field's element type is a GraphQL Object carrying
     * the {@code @table} directive, resolved through {@code ctx.types} as a TableBackedType),
     * and returns the resolved table plus the cardinality derived from the data field's wrapper.
     * Returns {@code null} for shapes the carrier mold doesn't admit (zero or multiple
     * {@code @table}-typed fields, non-Object payload, unresolved inner table).
     *
     * <p>The detection is deliberately structural; the SettKvotesporsmal bug's mechanism (a
     * forbidden-directives loop over the carrier shape) cannot fire from this site.
     */
    private record StructuralServicePayloadShape(TableRef table, SourceKey.Cardinality cardinality) {}

    private StructuralServicePayloadShape detectStructuralServicePayloadShape(String payloadSdlName) {
        if (payloadSdlName == null) return null;
        var payloadType = ctx.schema.getType(payloadSdlName);
        if (!(payloadType instanceof graphql.schema.GraphQLObjectType payloadObj)) return null;
        GraphQLFieldDefinition dataField = null;
        TableRef table = null;
        SourceKey.Cardinality cardinality = null;
        for (var f : payloadObj.getFieldDefinitions()) {
            String unwrappedFieldType = ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(f.getType())).getName();
            // R317 slice 3d — payload data field two hops below; table-backed fact from the pure
            // TableIndex (a fixed point) removes the deep in-progress-registry read.
            if (!(ctx.tables.forName(unwrappedFieldType).orElse(null) instanceof GraphitronType.TableBackedType tbt)) continue;
            if (dataField != null) return null;
            dataField = f;
            table = tbt.table();
            cardinality = GraphQLTypeUtil.unwrapNonNull(f.getType()) instanceof GraphQLList
                ? SourceKey.Cardinality.MANY : SourceKey.Cardinality.ONE;
        }
        if (dataField == null) return null;
        return new StructuralServicePayloadShape(table, cardinality);
    }

    /**
     * R156 / R275 — sealed result of resolving the NodeId encoder for an ID-element carrier
     * data field against the table the carrier acts on (the DELETE input {@code @table} or the
     * {@code @service} producer's record table). One resolver, two consumers: the rejection
     * arms carry the typed failure so each call site derives its own diagnostic wording from
     * the arm instead of re-walking the predicates (the pre-R275 shape was an
     * {@code Optional}-returning resolver plus a parallel string-returning validator that had
     * to stay in lockstep).
     */
    private sealed interface IdEncoderResolution {
        /** Encoder resolved; {@code nodeType} is the registration it came from. */
        record Resolved(NodeType nodeType) implements IdEncoderResolution {}
        /** {@code @nodeId(typeName:)} names a type that is not a registered {@code @node}. */
        record UnknownNodeType(String typeName) implements IdEncoderResolution {}
        /** The named NodeType's table differs from the table the carrier acts on. */
        record TableMismatch(NodeType nodeType) implements IdEncoderResolution {}
        /** Implicit form (no {@code @nodeId(typeName:)}) and no {@code @node} covers the table. */
        record NoNodeForTable() implements IdEncoderResolution {}
        /**
         * Implicit form, but the table backs several {@code @node} types, so "the encoder for this
         * table" has no single answer. {@code nodeTypeNames} are the candidates (sorted) for the
         * disambiguation hint.
         */
        record Ambiguous(java.util.List<String> nodeTypeNames) implements IdEncoderResolution {}
    }

    /**
     * R156 / R275 — resolve the NodeId encoder for an ID-element carrier field. Two recognition
     * forms:
     * <ul>
     *   <li><b>Implicit</b>: no {@code @nodeId} directive on the carrier field, or {@code @nodeId}
     *       without {@code typeName}. The encoder is {@code tableToMatch}'s {@code @node}
     *       registration.</li>
     *   <li><b>Explicit</b>: {@code @nodeId(typeName: "<NodeType>")}. The named NodeType's table
     *       must equal {@code tableToMatch} — an {@code @nodeId} that pins to a different table
     *       rejects (returning IDs of a different entity than the carrier acted on would be a
     *       silent contract break).</li>
     * </ul>
     */
    private static IdEncoderResolution resolveCarrierIdEncoder(
            BuildContext ctx, GraphQLFieldDefinition dataField, TableRef tableToMatch) {
        String tableSqlName = tableToMatch.tableName();
        if (dataField.hasAppliedDirective(DIR_NODE_ID)) {
            Optional<String> explicitTypeName = argString(dataField, DIR_NODE_ID, ARG_TYPE_NAME);
            if (explicitTypeName.isPresent()) {
                // R317 slice 2 — the by-name node index replaces the keyed ctx.types lookup.
                var targetNode = ctx.nodes.forName(explicitTypeName.get());
                if (targetNode.isEmpty()) {
                    return new IdEncoderResolution.UnknownNodeType(explicitTypeName.get());
                }
                NodeType targetNodeType = targetNode.get();
                // R358: sameTable (case-insensitive) — the carrier's table (tableSqlName) can arrive
                // in jOOQ's lowercase casing on the @service record-composite path while
                // targetNodeType's verbatim @table is a UPPERCASE Oracle-style name.
                if (!targetNodeType.table().sameTable(tableSqlName)) {
                    return new IdEncoderResolution.TableMismatch(targetNodeType);
                }
                return new IdEncoderResolution.Resolved(targetNodeType);
            }
        }
        // R317 slice 2 — the by-table node index (one-to-many) replaces the whole-registry scan.
        // A table may back several @node types; the implicit form resolves only when exactly one
        // covers it (zero -> NoNodeForTable, multiple -> Ambiguous, both surfaced by the callers'
        // *IdEncoderError renderers).
        var nodesOnTable = ctx.nodes.forTable(tableSqlName);
        return switch (nodesOnTable.size()) {
            case 0 -> new IdEncoderResolution.NoNodeForTable();
            case 1 -> new IdEncoderResolution.Resolved(nodesOnTable.get(0));
            default -> new IdEncoderResolution.Ambiguous(
                nodesOnTable.stream().map(NodeType::name).sorted().toList());
        };
    }

    /**
     * R317 — diagnostic for a bare-{@code ID}-returning mutation whose input {@code @table} backs
     * multiple {@code @node} types, so the implicit "encoder for this table" has no single answer.
     * These direct-return sites carry no {@code @nodeId} disambiguator (unlike the carrier path),
     * so the remedy is a typed {@code @node} return rather than a bare {@code ID}.
     */
    private static String ambiguousImplicitNodeError(String fieldDesc, String tableSqlName, List<NodeType> nodes) {
        String names = nodes.stream().map(NodeType::name).sorted().collect(Collectors.joining(", "));
        return fieldDesc + " returns ID but table '" + tableSqlName + "' backs multiple @node types ("
            + names + "), so the node-id encoder is ambiguous; return the specific @node type instead "
            + "of a bare ID";
    }

    /**
     * R156 — DELETE-carrier diagnostic wording for the {@link IdEncoderResolution} rejection
     * arms (test fixtures pin these messages). Returns {@code null} for {@code Resolved}.
     */
    private static String deleteIdEncoderError(
            IdEncoderResolution resolution, TableRef inputTable, String mutationName) {
        String inputTableSqlName = inputTable.tableName();
        return switch (resolution) {
            case IdEncoderResolution.Resolved ignored -> null;
            case IdEncoderResolution.UnknownNodeType u ->
                "@mutation(typeName: DELETE) field '" + mutationName
                    + "' carrier data field carries @nodeId(typeName: \"" + u.typeName()
                    + "\") but no @node type by that name exists in the schema";
            case IdEncoderResolution.TableMismatch m ->
                "@mutation(typeName: DELETE) field '" + mutationName
                    + "' carrier data field's @nodeId encoder pins to table '"
                    + m.nodeType().table().tableName()
                    + "', which does not match the @table input table '" + inputTableSqlName
                    + "'; returning IDs of a different entity than the DML acted on is not "
                    + "supported (drop the @nodeId(typeName:) argument to use the input "
                    + "@table's @node encoder implicitly, or move the carrier field to a "
                    + "mutation whose input @table matches the encoder's table)";
            case IdEncoderResolution.NoNodeForTable ignored ->
                "@mutation(typeName: DELETE) field '" + mutationName
                    + "' returns ID-element data on a carrier but no @node type is declared for "
                    + "table '" + inputTableSqlName + "'; annotate the input @table's SDL type with "
                    + "@node, or use a @table-element data field";
            case IdEncoderResolution.Ambiguous a ->
                "@mutation(typeName: DELETE) field '" + mutationName
                    + "' returns implicit ID-element data but the @table input table '"
                    + inputTableSqlName + "' backs multiple @node types ("
                    + String.join(", ", a.nodeTypeNames())
                    + "), so the encoder is ambiguous; add @nodeId(typeName:) to the carrier data "
                    + "field to pick which one to encode";
        };
    }

    /**
     * R275 — {@code @service}-carrier diagnostic wording for the {@link IdEncoderResolution}
     * rejection arms; the sibling of {@link #deleteIdEncoderError} with the input-{@code @table}
     * vocabulary replaced by the producer-record vocabulary. Returns {@code null} for
     * {@code Resolved}.
     */
    private static String serviceIdEncoderError(
            IdEncoderResolution resolution, TableRef producerTable, String payloadTypeName, String fieldName) {
        String producerTableSqlName = producerTable.tableName();
        return switch (resolution) {
            case IdEncoderResolution.Resolved ignored -> null;
            case IdEncoderResolution.UnknownNodeType u ->
                "@service-carrier payload field '" + payloadTypeName + "." + fieldName
                    + "' carries @nodeId(typeName: \"" + u.typeName()
                    + "\") but no @node type by that name exists in the schema";
            case IdEncoderResolution.TableMismatch m ->
                "@service-carrier payload field '" + payloadTypeName + "." + fieldName
                    + "'s @nodeId encoder pins to table '" + m.nodeType().table().tableName()
                    + "', which does not match the @service producer's record table '"
                    + producerTableSqlName
                    + "'; returning IDs of a different entity than the service produced is not "
                    + "supported (point @nodeId(typeName:) at the @node type backed by the "
                    + "producer's record table)";
            case IdEncoderResolution.NoNodeForTable ignored ->
                "@service-carrier payload field '" + payloadTypeName + "." + fieldName
                    + "' is an ID-element data field but no @node type is declared for the "
                    + "producer's record table '" + producerTableSqlName
                    + "'; annotate that table's SDL type with @node and point "
                    + "@nodeId(typeName:) at it";
            case IdEncoderResolution.Ambiguous a ->
                "@service-carrier payload field '" + payloadTypeName + "." + fieldName
                    + "' is an implicit ID-element data field but the producer's record table '"
                    + producerTableSqlName + "' backs multiple @node types ("
                    + String.join(", ", a.nodeTypeNames())
                    + "), so the encoder is ambiguous; add @nodeId(typeName:) to pick which one to "
                    + "encode";
        };
    }

    /**
     * Folds the pre-validated {@code returnType}, {@code encodeReturn} (populated for ID
     * returns that resolve to a {@code @node} type), and {@code interfaceVerdict} (present when the
     * TableBound return is a single-table discriminated interface, R406) into the single
     * {@link DmlReturnExpression} arm the DML emitter pattern-matches on. Total over the post-R161
     * admitted return-type set (Scalar-ID / TableBound / discriminated-interface, single or list);
     * unreachable on anything else
     * ({@code MutationInputResolver.validateReturnType} already rejected list-payload returns
     * and the @mutation classifier routes class-backed returns to {@code MutationDmlRecordField}).
     */
    private static DmlReturnExpression buildDmlReturnExpression(
            ReturnTypeRef returnType,
            Optional<HelperRef.Encode> encodeReturn,
            Optional<GraphitronType.TableInterfaceType> interfaceVerdict) {
        boolean isList = returnType.wrapper().isList();
        if (returnType instanceof ReturnTypeRef.ScalarReturnType s && "ID".equals(s.returnTypeName())) {
            HelperRef.Encode enc = encodeReturn.orElseThrow(() -> new AssertionError(
                "DML mutation with ID return type missing encode helper; classifier should have rejected this"));
            return isList ? new DmlReturnExpression.EncodedList(enc) : new DmlReturnExpression.EncodedSingle(enc);
        }
        if (returnType instanceof ReturnTypeRef.TableBoundReturnType tb) {
            // R406: a single-table discriminated interface return re-projects through the
            // discriminator path rather than the concrete-type $fields projection; carry the
            // read-side discrimination data (same as R405's *ServiceTableInterfaceField).
            if (interfaceVerdict.isPresent()) {
                var tit = interfaceVerdict.get();
                var knownValues = knownDiscriminatorValues(tit);
                return isList
                    ? new DmlReturnExpression.DiscriminatedList(tit.name(), tit.discriminatorColumn(), knownValues, tit.participants())
                    : new DmlReturnExpression.DiscriminatedSingle(tit.name(), tit.discriminatorColumn(), knownValues, tit.participants());
            }
            return isList
                ? new DmlReturnExpression.ProjectedList(tb.returnTypeName())
                : new DmlReturnExpression.ProjectedSingle(tb.returnTypeName());
        }
        throw new AssertionError(
            "DML mutation return type '" + returnType.returnTypeName()
                + "' should have been rejected by Invariant #14 (validateReturnType)");
    }

    // ===== Root field classification (P5) =====

    private GraphitronField classifyRootField(GraphQLFieldDefinition fieldDef, String parentTypeName) {
        if (parentTypeName.equals("Mutation")) {
            return classifyMutationField(fieldDef, parentTypeName);
        }
        if (parentTypeName.equals("Query")) {
            return classifyQueryField(fieldDef, parentTypeName);
        }
        return new UnclassifiedField(parentTypeName, fieldDef.getName(), locationOf(fieldDef), fieldDef,
            Rejection.deferred("fields on '" + parentTypeName + "' (Subscription is not supported)", ""));
    }

    /**
     * Resolves the participant set for a multitable interface/union return type, used by the
     * route (a) {@code @service}-polymorphic classify arms. Reads the same participant list that
     * {@link QueryField.QueryInterfaceField} / {@link QueryField.QueryUnionField} carry, off the
     * resolved {@link InterfaceType} / {@link UnionType} verdict. Returns an empty list when the
     * name resolves to neither (defensive; the resolver only reaches a polymorphic arm for an
     * interface/union return type).
     */
    private List<no.sikt.graphitron.rewrite.model.ParticipantRef> polymorphicParticipants(String typeName) {
        var verdict = typeBuilder.lookAheadVerdict(typeName);
        if (verdict instanceof InterfaceType it) return it.participants();
        if (verdict instanceof UnionType ut) return ut.participants();
        return List.of();
    }

    /**
     * R365 route (a) supports a {@code @service} field returning a multitable <em>interface</em>
     * only. A {@code @service} returning a union is permanently unsupported (union polymorphism is a
     * generated-query-path capability; the service path never grew it), so reject it as an author
     * error rather than emit record-class dispatch over union members.
     */
    private GraphitronField rejectServiceUnionReturn(String parentTypeName, String name,
            SourceLocation location, GraphQLFieldDefinition fieldDef, String returnTypeName) {
        return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(
            "@service must return a multitable interface; returning a union ('" + returnTypeName
            + "') is not supported"));
    }

    private GraphitronField classifyQueryField(GraphQLFieldDefinition fieldDef, String parentTypeName) {
        String name = fieldDef.getName();
        SourceLocation location = locationOf(fieldDef);

        // R449 D2 — the query conflict detector moved to classifyField, hoisted before the R435
        // chain interception so @service @routine on a multi-node chain rejects instead of routing
        // to the routine classifier. One detector site per position; no call here.

        if (fieldDef.hasAppliedDirective(DIR_SERVICE)) {
            return switch (serviceResolver.resolve(parentTypeName, fieldDef, List.of())) {
                case ServiceDirectiveResolver.Resolved.Rejected r ->
                    new UnclassifiedField(parentTypeName, name, location, fieldDef, r.rejection());
                case ServiceDirectiveResolver.Resolved.ErrorsLifted e -> e.field();
                case ServiceDirectiveResolver.Resolved.TableBound tb -> {
                    // R405: a @service returning a single-table discriminated interface
                    // (TableInterfaceType) resolves through this table-bound arm (a TableInterfaceType
                    // is a TableBackedType). Build the single-table service-interface variant carrying
                    // the read-side discrimination data straight off the verdict, rather than deferring.
                    if (typeBuilder.lookAheadVerdict(tb.returnType().returnTypeName()) instanceof TableInterfaceType tit) {
                        yield buildServiceField(tb.returnType(), tb.method(), parentTypeName, name, location, fieldDef, (ch, smc) ->
                            new QueryField.QueryServiceTableInterfaceField(parentTypeName, name, location, tb.returnType(),
                                tit.discriminatorColumn(), knownDiscriminatorValues(tit), tit.participants(), smc, ch));
                    }
                    yield buildServiceField(tb.returnType(), tb.method(), parentTypeName, name, location, fieldDef, (ch, smc) ->
                        new QueryField.QueryServiceTableField(parentTypeName, name, location, tb.returnType(), smc, ch));
                }
                case ServiceDirectiveResolver.Resolved.Result r ->
                    buildServiceField(r.returnType(), r.method(), parentTypeName, name, location, fieldDef, (ch, smc) ->
                        new QueryField.QueryServiceRecordField(parentTypeName, name, location, r.returnType(), smc, ch));
                case ServiceDirectiveResolver.Resolved.Scalar s ->
                    buildServiceField(s.returnType(), s.method(), parentTypeName, name, location, fieldDef, (ch, smc) ->
                        new QueryField.QueryServiceRecordField(parentTypeName, name, location, s.returnType(), smc, ch));
                case ServiceDirectiveResolver.Resolved.Polymorphic p -> {
                    if (typeBuilder.lookAheadVerdict(p.returnType().returnTypeName()) instanceof UnionType) {
                        yield rejectServiceUnionReturn(parentTypeName, name, location, fieldDef, p.returnType().returnTypeName());
                    }
                    yield buildServiceField(p.returnType(), p.method(), parentTypeName, name, location, fieldDef, (ch, smc) ->
                        new QueryField.QueryServicePolymorphicField(parentTypeName, name, location, p.returnType(),
                            polymorphicParticipants(p.returnType().returnTypeName()), smc, ch));
                }
            };
        }

        // Relay-style node fetchers are recognised by signature, not by name. Any Query
        // field whose element type is the `Node` interface is a node fetcher: single
        // cardinality routes to QueryNodeField, list cardinality to QueryNodesField.
        // Federation subgraphs commonly expose extra node-by-id entry points under
        // distinct names (e.g. `internalOpptakNode(id: ID): Node @inaccessible`); name-
        // based dispatch alone would misclassify those as QueryInterfaceField.
        if (baseTypeName(fieldDef).equals("Node") && typeBuilder.lookAheadVerdict("Node") instanceof InterfaceType) {
            var wrapper = buildWrapper(fieldDef);
            var returnType = new ReturnTypeRef.PolymorphicReturnType("Node", wrapper);
            if (wrapper instanceof FieldWrapper.List) {
                return new QueryField.QueryNodesField(parentTypeName, name, location, returnType);
            }
            return new QueryField.QueryNodeField(parentTypeName, name, location, returnType);
        }

        // Resolve the field's backing table name early so resolveTableFieldComponents has a
        // pre-built NodeIdArgPlan to share with the lookup classification arm. After R106,
        // the gate is purely "explicit @lookupKey opt-in"; same-table @nodeId no longer
        // promotes to a lookup unless paired with @lookupKey (whose presence shows up in
        // hasLookupKeyAnywhere).
        String lookupTypeName = baseTypeName(fieldDef);
        var lookupReturnType = ctx.resolveReturnType(lookupTypeName, buildWrapper(fieldDef));
        NodeIdArgPlan lookupPlan = lookupReturnType instanceof ReturnTypeRef.TableBoundReturnType tableBound
            ? buildNodeIdArgPlan(fieldDef, tableBound.table())
            : NodeIdArgPlan.EMPTY;
        if (hasLookupKeyAnywhere(fieldDef)) {
            return switch (lookupKeyResolver.resolveAtRoot(lookupReturnType)) {
                case LookupKeyDirectiveResolver.Resolved.Rejected r ->
                    new UnclassifiedField(parentTypeName, name, location, fieldDef, r.rejection());
                case LookupKeyDirectiveResolver.Resolved.Ok ok -> {
                    var tb = ok.returnType();
                    // The plan was built against `lookupReturnType.table()` which equals `tb.table()`
                    // for the lookup-promotion path (resolveAtRoot.Ok preserves the table); reuse
                    // it rather than rebuilding.
                    var components = resolveTableFieldComponents(fieldDef, tb.table(), lookupTypeName, lookupPlan);
                    yield switch (components) {
                        case TableFieldComponents.Rejected rj ->
                            new UnclassifiedField(parentTypeName, name, location, fieldDef, rj.rejection());
                        case TableFieldComponents.Ok tfc ->
                            new QueryField.QueryLookupTableField(parentTypeName, name, location, tb, tfc.filters(), tfc.orderBy(), tfc.pagination(),
                                tfc.lookupMapping());
                    };
                }
            };
        }

        if (fieldDef.hasAppliedDirective(DIR_TABLE_METHOD)) {
            return switch (tableMethodResolver.resolve(parentTypeName, fieldDef, true)) {
                case TableMethodDirectiveResolver.Resolved.Rejected r ->
                    new UnclassifiedField(parentTypeName, name, location, fieldDef, r.rejection());
                case TableMethodDirectiveResolver.Resolved.TableBound tb ->
                    buildMethodBackedWithChannel(tb.returnType(), tb.method(),
                        parentTypeName, name, location, fieldDef,
                        ch -> new QueryField.QueryTableMethodTableField(parentTypeName, name, location, tb.returnType(), tb.method(), ch));
            };
        }

        if (fieldDef.hasAppliedDirective(DIR_ROUTINE)) {
            // The R300 single-node chain — the degenerate root chain with no @reference
            // applications (hops empty, the routine result is the terminus).
            return classifyRootRoutineChain(fieldDef, parentTypeName, name, location);
        }

        String rawTypeName = baseTypeName(fieldDef);
        String elementTypeName = ctx.isConnectionType(rawTypeName) ? ctx.connectionElementTypeName(rawTypeName) : rawTypeName;
        // R317 slice 3d — the table-backed fact comes from the pure TableIndex (a fixed point, not the
        // in-progress registry). R317 slice 3e — the interface / union arms below resolve their target
        // through the registry-free look-ahead (TypeBuilder.lookAheadVerdict), not ctx.types, so the
        // target need not have been registered yet (the enter-only single-walk precondition).
        GraphitronType tableBacked = ctx.tables.forName(elementTypeName).orElse(null);
        GraphitronType elementType = typeBuilder.lookAheadVerdict(elementTypeName);

        if (tableBacked instanceof TableBackedType tbt && !(tableBacked instanceof TableInterfaceType)) {
            var wrapper = buildWrapper(fieldDef);
            // R317 slice 3d — build the TableBoundReturnType from the index verdict's table directly
            // rather than casting ctx.resolveReturnType (which reads the registry). The pure TableIndex
            // keeps a typeId-collided node visible here, whereas validateNodeTypeIdUniqueness has
            // already demoted it in the registry, so resolveReturnType would yield a ScalarReturnType
            // and the cast would crash. The field resolves cleanly against the index verdict; the
            // collision still hard-fails the build at validateNodeTypeIdUniqueness before generation,
            // so passing-fixture output is unchanged.
            var returnType = new ReturnTypeRef.TableBoundReturnType(elementTypeName, tbt.table(), wrapper);
            var components = resolveTableFieldComponents(fieldDef, returnType.table(), elementTypeName,
                buildNodeIdArgPlan(fieldDef, returnType.table()));
            if (components instanceof TableFieldComponents.Rejected rj) return new UnclassifiedField(parentTypeName, name, location, fieldDef, rj.rejection());
            var tfc = (TableFieldComponents.Ok) components;
            return new QueryField.QueryTableField(parentTypeName, name, location, returnType, tfc.filters(), tfc.orderBy(), tfc.pagination());
        }
        if (tableBacked instanceof TableInterfaceType tableInterfaceType) {
            var wrapper = buildWrapper(fieldDef);
            var components = resolveTableFieldComponents(fieldDef, tableInterfaceType.table(), elementTypeName,
                buildNodeIdArgPlan(fieldDef, tableInterfaceType.table()));
            if (components instanceof TableFieldComponents.Rejected rj) return new UnclassifiedField(parentTypeName, name, location, fieldDef, rj.rejection());
            var tfc = (TableFieldComponents.Ok) components;
            var knownValues = knownDiscriminatorValues(tableInterfaceType);
            return new QueryField.QueryTableInterfaceField(parentTypeName, name, location,
                new ReturnTypeRef.TableBoundReturnType(elementTypeName, tableInterfaceType.table(), wrapper),
                tableInterfaceType.discriminatorColumn(), knownValues, tableInterfaceType.participants(),
                tfc.filters(), tfc.orderBy(), tfc.pagination());
        }
        if (elementType instanceof InterfaceType interfaceType) {
            var lowered = lowerParticipantFilters(fieldDef, interfaceType.participants());
            if (lowered instanceof ParticipantFiltersResult.Rejected rj) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, rj.rejection());
            }
            return new QueryField.QueryInterfaceField(parentTypeName, name, location,
                new ReturnTypeRef.PolymorphicReturnType(elementTypeName, buildWrapper(fieldDef)),
                interfaceType.participants(),
                ((ParticipantFiltersResult.Ok) lowered).participantFilters());
        }
        if (elementType instanceof UnionType unionType) {
            var lowered = lowerParticipantFilters(fieldDef, unionType.participants());
            if (lowered instanceof ParticipantFiltersResult.Rejected rj) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, rj.rejection());
            }
            return new QueryField.QueryUnionField(parentTypeName, name, location,
                new ReturnTypeRef.PolymorphicReturnType(elementTypeName, buildWrapper(fieldDef)),
                unionType.participants(),
                ((ParticipantFiltersResult.Ok) lowered).participantFilters());
        }

        return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural("return type '" + elementTypeName + "' is not a @table, interface, or union Graphitron type; " +
            "@service, @lookupKey, and @tableMethod are all absent"));
    }

    private GraphitronField classifyMutationField(GraphQLFieldDefinition fieldDef, String parentTypeName) {
        String name = fieldDef.getName();
        SourceLocation location = locationOf(fieldDef);

        // R451 — only the single-node degenerate chain reaches here (the multi-node chain
        // classifies for real in classifyField's interception, landing MutationRoutineWriteField).
        // With no @reference hop there is no post-commit table to re-read the response from, so
        // the single-node shape stays a typed Deferred carried by the result-shapes follow-up
        // (see MUTATION_SINGLE_NODE_ROUTINE_DEFERRAL) rather than letting this method's generic
        // "both absent" fallback bury the actual cause.
        if (fieldDef.hasAppliedDirective(DIR_ROUTINE)) {
            return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                Rejection.deferred(MUTATION_SINGLE_NODE_ROUTINE_DEFERRAL, "routine-write-result-shapes"));
        }

        if (fieldDef.hasAppliedDirective(DIR_SERVICE) && fieldDef.hasAppliedDirective(DIR_MUTATION)) {
            return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.directiveConflict(
                List.of(DIR_SERVICE, DIR_MUTATION),
                "@" + DIR_SERVICE + ", @" + DIR_MUTATION + " are mutually exclusive"));
        }

        if (fieldDef.hasAppliedDirective(DIR_SERVICE)) {
            return switch (serviceResolver.resolve(parentTypeName, fieldDef, List.of())) {
                case ServiceDirectiveResolver.Resolved.Rejected r ->
                    new UnclassifiedField(parentTypeName, name, location, fieldDef, r.rejection());
                case ServiceDirectiveResolver.Resolved.ErrorsLifted e -> e.field();
                case ServiceDirectiveResolver.Resolved.TableBound tb -> {
                    // R405: single-table discriminated interface return on the @service mutation path,
                    // the mutation twin of the query arm above.
                    if (typeBuilder.lookAheadVerdict(tb.returnType().returnTypeName()) instanceof TableInterfaceType tit) {
                        yield buildServiceField(tb.returnType(), tb.method(), parentTypeName, name, location, fieldDef, (ch, smc) ->
                            new MutationField.MutationServiceTableInterfaceField(parentTypeName, name, location, tb.returnType(),
                                tit.discriminatorColumn(), knownDiscriminatorValues(tit), tit.participants(), smc, ch));
                    }
                    yield buildServiceField(tb.returnType(), tb.method(), parentTypeName, name, location, fieldDef, (ch, smc) ->
                        new MutationField.MutationServiceTableField(parentTypeName, name, location, tb.returnType(), smc, ch));
                }
                case ServiceDirectiveResolver.Resolved.Result r -> {
                    // R159: when the @service mutation returns a payload type whose
                    // data field opts into the $source sigil, verify the producer's reflected
                    // return type matches the SDL element's backing class. The check is colocated
                    // here because the producer's MethodRef is in scope; the rejection flows
                    // through the existing UnclassifiedField -> ValidationError -> LSP path.
                    String sourceSigilError = checkSourceSigilTypeMatch(r.returnType().returnTypeName(), r.method());
                    if (sourceSigilError != null) {
                        yield new UnclassifiedField(parentTypeName, name, location, fieldDef,
                            Rejection.structural(sourceSigilError));
                    }
                    // R178 step 3: @service-payload strict-return check detects the payload shape
                    // directly from the payload SDL. The check fires only for unbacked payloads
                    // (no resolved backing class); ClassBacked payloads route through the surviving
                    // legacy-equality check inside buildServiceField, which produces the
                    // payload-class diagnostic. This split is the SettKvotesporsmal bug's
                    // structural fix: ClassBacked payloads get a diagnostic citing the payload
                    // class, not the inner table's record class.
                    String servicePayloadError = classifyServicePayloadProducer(r.returnType(), r.method());
                    if (servicePayloadError != null) {
                        yield new UnclassifiedField(parentTypeName, name, location, fieldDef,
                            Rejection.structural(servicePayloadError));
                    }
                    yield buildServiceField(r.returnType(), r.method(), parentTypeName, name, location, fieldDef, (ch, smc) ->
                        new MutationField.MutationServiceRecordField(parentTypeName, name, location, r.returnType(), smc, ch));
                }
                case ServiceDirectiveResolver.Resolved.Scalar s -> {
                    // R276: a carrier-shaped return that did not bind (the producer return did not
                    // match XRecord / List<XRecord>) resolves to a Scalar over a NestingType;
                    // reject it here so a mismatched producer is an author error, not a silent admit.
                    // Run the $source-sigil check first (mirrors the Result arm), so a $source-typed
                    // data field gets the more specific $source diagnostic; otherwise the generic
                    // carrier strict-return diagnostic applies.
                    String sourceSigilError = checkSourceSigilTypeMatch(s.returnType().returnTypeName(), s.method());
                    if (sourceSigilError != null) {
                        yield new UnclassifiedField(parentTypeName, name, location, fieldDef,
                            Rejection.structural(sourceSigilError));
                    }
                    String carrierError = classifyServicePayloadProducerByName(s.returnType().returnTypeName(), s.method());
                    if (carrierError != null) {
                        yield new UnclassifiedField(parentTypeName, name, location, fieldDef,
                            Rejection.structural(carrierError));
                    }
                    // R275 / R317 slice 3c: a carrier-shaped SDL-Object return reaching the Scalar
                    // arm with no producer binding is an orphan carrier — the structural scan
                    // recognizes it as a single-data-field carrier, but it neither bound (by producer
                    // reflection) nor promoted as a producer-backed carrier, so the type is dropped
                    // from the model and the field's emitted typeRef would dangle (graphql-java
                    // assembly fails with "type X not found in schema"). This is an edge-decidable
                    // orphan: the producing edge owns the rejection and produces UnclassifiedField
                    // directly here. The orphan predicate is registry-free — it reads
                    // {@code typeBuilder.carrierTableBinding}, the same scan + producer-binding fixed
                    // point that registerProducerBackedCarrier registers from, never the in-progress
                    // type registry. A carrier-shaped scan Admit with a null carrierTableBinding is
                    // definitionally an orphan (Admit + a bound producer would have registered a
                    // JooqTableRecordType at the producing edge before this field classified, so the
                    // resolver would have yielded Result, not Scalar); evaluating the orphan verdict
                    // without a ctx.types read keeps it order-independent ahead of the slice-4
                    // collapse. Non-carrier orphan shapes (scan Reject / NotApplicable) may be rescued
                    // by a later nesting / connection edge, so they are not edge-decidable; the
                    // shape-agnostic backstop (GraphitronSchemaBuilder.rejectDanglingTypeReferences)
                    // catches them after the walk. This arm runs first so recognized carriers get the
                    // richer, shape-specific guidance.
                    String payloadName = s.returnType().returnTypeName();
                    if (ctx.schema.getType(payloadName) instanceof graphql.schema.GraphQLObjectType
                            && typeBuilder.carrierBinding(payloadName) instanceof TypeBuilder.CarrierBinding.NotACarrier
                            && ctx.scanStructuralServiceCarrierPayload(payloadName)
                                instanceof BuildContext.DmlPayloadScan.Admit admit) {
                        yield new UnclassifiedField(parentTypeName, name, location, fieldDef,
                            Rejection.structural(orphanServiceCarrierReason(payloadName, name, admit)));
                    }
                    yield buildServiceField(s.returnType(), s.method(), parentTypeName, name, location, fieldDef, (ch, smc) ->
                        new MutationField.MutationServiceRecordField(parentTypeName, name, location, s.returnType(), smc, ch));
                }
                case ServiceDirectiveResolver.Resolved.Polymorphic p -> {
                    if (typeBuilder.lookAheadVerdict(p.returnType().returnTypeName()) instanceof UnionType) {
                        yield rejectServiceUnionReturn(parentTypeName, name, location, fieldDef, p.returnType().returnTypeName());
                    }
                    yield buildServiceField(p.returnType(), p.method(), parentTypeName, name, location, fieldDef, (ch, smc) ->
                        new MutationField.MutationServicePolymorphicField(parentTypeName, name, location, p.returnType(),
                            polymorphicParticipants(p.returnType().returnTypeName()), smc, ch));
                }
            };
        }

        if (fieldDef.hasAppliedDirective(DIR_MUTATION)) {
            DmlKind kind;
            switch (mutationInputResolver.parseDmlKind(fieldDef)) {
                case MutationInputResolver.DmlKindResult.Absent a -> kind = null;
                case MutationInputResolver.DmlKindResult.Kind k -> kind = k.kind();
                case MutationInputResolver.DmlKindResult.Unknown u -> {
                    List<String> dmlCandidates = Arrays.stream(DmlKind.values())
                        .map(Enum::name).toList();
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.unknownDmlKind(
                        "unknown @mutation(typeName:) value '" + u.raw() + "'",
                        u.raw(), dmlCandidates));
                }
            }
            if (kind != null) {
                // R457 — @mutation(table:) is wired only for the verbs in TABLE_ARG_SUPPORTED_VERBS
                // (a one-element {DELETE} set today). On any other verb it is an unimplemented
                // classification; silently ignoring an author-written directive argument is the
                // green-build-wrong-intent failure mode the axioms forbid, so reject loudly with a
                // typed, sealed rejection (stable LSP code). The classifier and `mvn graphitron:validate`
                // read the same set (validate runs this classifier), so a future generalisation is a
                // single edit point here.
                if (MutationInputResolver.parseMutationTableArg(fieldDef).isPresent()
                        && !TABLE_ARG_SUPPORTED_VERBS.contains(kind)) {
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                        new no.sikt.graphitron.rewrite.model.MutationTableArgError.UnsupportedVerb(
                            kind.name(),
                            TABLE_ARG_SUPPORTED_VERBS.stream().map(Enum::name).sorted().toList()));
                }

                // R246 / R258: every @mutation(typeName: UPDATE) classifies through the
                // UpdateRowsWalker, not MutationInputResolver. Branch here on leaf identity (the
                // return type) before the shared resolveInput call: the direct-@table/ID-return
                // shape goes to classifyUpdateTableField (R246), the payload-returning shape
                // (ResultReturnType) to classifyUpdatePayloadField (R258). Both forks build the
                // slim InputArgRef + UpdateRows carrier and never read @value; intercepting before
                // resolveInput keeps that resolver's retired UPDATE-specific @value-partition /
                // PK-coverage rules from firing on either path.
                if (kind == DmlKind.UPDATE) {
                    // multiRow: true on UPDATE is rejected outright; the broadcast semantics has no
                    // replacement path under R246 (cover a PK or UK to express an UPDATE). DELETE,
                    // by contrast, admits multiRow as the DeleteRows.Broadcast arm (see below).
                    if (MutationInputResolver.parseMultiRow(fieldDef)) {
                        return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                            Rejection.deferred("@mutation(typeName: UPDATE) with multiRow: true is not yet supported", ""));
                    }
                    ReturnTypeRef updateReturnType = ctx.resolveReturnType(baseTypeName(fieldDef), buildWrapper(fieldDef));
                    if (updateReturnType instanceof ReturnTypeRef.ResultReturnType rrt) {
                        return classifyUpdatePayloadField(fieldDef, parentTypeName, name, location, rrt);
                    }
                    return classifyUpdateTableField(fieldDef, parentTypeName, name, location, updateReturnType);
                }

                // R266: every @mutation(typeName: DELETE) classifies through the DeleteRowsWalker,
                // mirroring R246/R258 for UPDATE. Branch on leaf identity before resolveInput: the
                // payload-returning shape (ResultReturnType) to classifyDeletePayloadField, the
                // direct-@table/ID-return shape to classifyDeleteTableField. Both forks build the
                // slim InputArgRef + DeleteRows carrier; carving DELETE off resolveInput retires the
                // last live @value consumer (R188). Unlike UPDATE, DELETE does not reject multiRow
                // here — the walker turns it into the DeleteRows.Broadcast arm.
                if (kind == DmlKind.DELETE) {
                    ReturnTypeRef deleteReturnType = ctx.resolveReturnType(baseTypeName(fieldDef), buildWrapper(fieldDef));
                    if (deleteReturnType instanceof ReturnTypeRef.ResultReturnType rrt) {
                        return classifyDeletePayloadField(fieldDef, parentTypeName, name, location, rrt);
                    }
                    return classifyDeleteTableField(fieldDef, parentTypeName, name, location, deleteReturnType);
                }

                ArgumentRef.InputTypeArg.TableInputArg tia;
                switch (mutationInputResolver.resolveInput(fieldDef, kind)) {
                    case MutationInputResolver.Resolved.Ok ok -> tia = ok.tia();
                    case MutationInputResolver.Resolved.Rejected r -> {
                        return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(r.message()));
                    }
                }

                String rawReturn = baseTypeName(fieldDef);
                ReturnTypeRef returnType = ctx.resolveReturnType(rawReturn, buildWrapper(fieldDef));

                String returnTypeError = MutationInputResolver.validateReturnType(returnType, kind, tia.list(), ctx);
                if (returnTypeError != null) {
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(returnTypeError));
                }

                // R178 Phase 4: payload-returning DML mutations classify through structural
                // detection of the payload SDL (single non-errors-shaped data field; element
                // kind: @table / record-backed / ID), dispatching on the DmlElementKind permit returned
                // by scanStructuralDmlPayload. Only INSERT / UPSERT reach here: UPDATE (R246/R258)
                // and DELETE (R266) are intercepted before resolveInput and classify through their
                // walker-driven payload classifiers (classifyUpdatePayloadField /
                // classifyDeletePayloadField), which own the DELETE-specific IdElement / Table
                // reclassify the walkers cannot re-derive.
                if (returnType instanceof ReturnTypeRef.ResultReturnType rrt) {
                    var scan = ctx.scanStructuralDmlPayload(rrt.returnTypeName());
                    if (scan instanceof BuildContext.DmlPayloadScan.Reject scanReject) {
                        return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(scanReject.reason()));
                    }
                    if (scan instanceof BuildContext.DmlPayloadScan.Admit admit) {
                        // INSERT / UPSERT. UPDATE (R246/R258) and DELETE (R266) are intercepted
                        // before resolveInput, so the only kinds reaching this scan are INSERT and
                        // UPSERT, whose payload data field must be @table-element (the ID-element
                        // PK-echo permit is DELETE-only; record-element needs @service).
                        {
                            switch (admit.element()) {
                                case BuildContext.DmlElementKind.Table tbl -> {
                                    String tableMismatch = requireDmlDataTableMatchesInputTable(
                                        tia.inputTable(), tbl, kind, name, rrt.returnTypeName());
                                    if (tableMismatch != null) {
                                        return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(tableMismatch));
                                    }
                                    var dmlChannelResult = detectStructuralDmlErrorChannel(rrt.returnTypeName());
                                    if (dmlChannelResult instanceof StructuralDmlErrorChannel.RuleViolation rv) {
                                        return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(rv.reason()));
                                    }
                                    Optional<ErrorChannel> dmlChannel = (dmlChannelResult instanceof StructuralDmlErrorChannel.Present p)
                                        ? Optional.of(p.channel()) : Optional.empty();
                                    if (tia.list()) {
                                        if (kind == DmlKind.UPSERT) {
                                            return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(
                                                "@mutation(typeName: UPSERT) with bulk @table input and a list-"
                                                + "shaped data field on the carrier is deferred to R145 "
                                                + "(mutation-cardinality-safety-upsert); use INSERT or UPDATE, or "
                                                + "use a single-record carrier with single @table input"));
                                        }
                                        return new MutationField.MutationBulkDmlRecordField(
                                            parentTypeName, name, location, rrt, tia, kind, dmlChannel);
                                    }
                                    return new MutationField.MutationDmlRecordField(
                                        parentTypeName, name, location, rrt, tia, kind, dmlChannel);
                                }
                                case BuildContext.DmlElementKind.RecordElement re -> {
                                    return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(
                                        "@mutation(typeName: " + kind + ") field '" + name
                                        + "' returns single-record carrier '" + rrt.returnTypeName()
                                        + "' with a record-element data field ('" + re.fieldName()
                                        + "'); DML mutations require an @table-element or ID-scalar data field. Use a "
                                        + "@service mutation for record-element carriers, or change the data field's element "
                                        + "type to the input table's @table type / ID"));
                                }
                                case BuildContext.DmlElementKind.IdElement ignored -> {
                                    return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(
                                        "single-record carrier '" + rrt.returnTypeName()
                                        + "' has data field of element type ID, which is the PK-echo permit "
                                        + "(post-image == primary key) and is admitted only on "
                                        + "@mutation(typeName: DELETE) carriers. On @mutation(typeName: "
                                        + kind + ") the post-image is richer; use a @table-element data field "
                                        + "or a record-backed element data field instead."));
                                }
                            }
                        }
                    }
                }

                Optional<HelperRef.Encode> encodeReturn = Optional.empty();
                if (returnType instanceof ReturnTypeRef.ScalarReturnType s && "ID".equals(s.returnTypeName())) {
                    String tableSqlName = tia.inputTable().tableName();
                    // R317 slice 2 — the one-to-many by-table node index in place of the registry
                    // scan; the implicit ID encoder is well-defined only for a single-node table.
                    var nodesOnTable = ctx.nodes.forTable(tableSqlName);
                    if (nodesOnTable.isEmpty()) {
                        return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural("@mutation field '" + name + "' returns ID but no @node type is declared for table '"
                                + tableSqlName + "'; annotate the type with @node or use a @table return type"));
                    }
                    if (nodesOnTable.size() > 1) {
                        return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(
                            ambiguousImplicitNodeError("@mutation field '" + name + "'", tableSqlName, nodesOnTable)));
                    }
                    encodeReturn = Optional.of(nodesOnTable.get(0).encodeMethod());
                }

                Optional<HelperRef.Encode> enc = encodeReturn;
                return switch (kind) {
                    case INSERT -> buildDmlField(returnType, parentTypeName, name, location, fieldDef,
                        (rex, ch) -> new MutationField.MutationInsertTableField(parentTypeName, name, location, rex,
                            DialectRequirement.None.INSTANCE, tia, ch),
                        enc);
                    case UPDATE -> throw new IllegalStateException(
                        "R246 / R258: every UPDATE is intercepted before resolveInput — the "
                        + "direct-@table/ID-return shape by classifyUpdateTableField, the payload-"
                        + "returning shape by classifyUpdatePayloadField; the final-switch UPDATE arm "
                        + "is unreachable for field '" + name + "'");
                    case DELETE -> throw new IllegalStateException(
                        "R266: every DELETE is intercepted before resolveInput — the "
                        + "direct-@table/ID-return shape by classifyDeleteTableField, the payload-"
                        + "returning shape by classifyDeletePayloadField; the final-switch DELETE arm "
                        + "is unreachable for field '" + name + "'");
                    // R63: UPSERT rejects only the Oracle family. jOOQ silently translates
                    // INSERT ... ON CONFLICT to Oracle MERGE INTO, whose concurrency and RETURNING
                    // semantics differ from PostgreSQL; other dialects throw their own error rather
                    // than mistranslate, so only Oracle needs gating. The reason string is the
                    // request-time message the emitter renders into the guard.
                    case UPSERT -> buildDmlField(returnType, parentTypeName, name, location, fieldDef,
                        (rex, ch) -> new MutationField.MutationUpsertTableField(parentTypeName, name, location, rex,
                            new DialectRequirement.RejectsFamily(SqlDialectFamily.ORACLE,
                                "@mutation(typeName: UPSERT) is not supported on Oracle: jOOQ would translate "
                                    + "INSERT ... ON CONFLICT to MERGE INTO, whose concurrency and RETURNING "
                                    + "semantics differ from PostgreSQL. Graphitron targets PostgreSQL."),
                            tia, ch),
                        enc);
                };
            }
        }

        return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural("@" + DIR_SERVICE + " and @" + DIR_MUTATION + " are both absent on this mutation field"));
    }

    /**
     * R246 — classifies a direct-@table/ID-return {@code @mutation(typeName: UPDATE)} field into a
     * {@link MutationField.MutationUpdateTableField}. Runs the whole-arg pre-checks the walker is
     * not the right layer to diagnose (multiRow rejection, the single {@code @table} arg resolution,
     * the {@code @argCondition}-on-arg rejection), builds the slim {@link InputArgRef} arg surface
     * directly from the resolved {@code @table} input type, then runs {@code UpdateRowsWalker} for
     * the PK-or-UK identification and SET/WHERE partition. A pre-check failure or a walker
     * {@code Err} surfaces as an {@link UnclassifiedField} carrying the typed rejection (the field
     * is excluded from the classified set); the walker's typed {@code UpdateRowsError} arms are
     * preserved verbatim so the LSP projector can read their {@code lspCode()}.
     */
    private GraphitronField classifyUpdateTableField(
            GraphQLFieldDefinition fieldDef, String parentTypeName, String name,
            SourceLocation location, ReturnTypeRef returnType) {
        GraphitronType.TableInputType foundTit;
        no.sikt.graphitron.rewrite.model.InputArgRef inputArg;
        switch (resolveDmlWalkerInputArg(fieldDef, parentTypeName, name, location)) {
            case DmlWalkerInputArgResolution.Rejected r -> { return r.field(); }
            case DmlWalkerInputArgResolution.Resolved ok -> { foundTit = ok.tit(); inputArg = ok.inputArg(); }
            // R457 — UPDATE has no field-relative write-target path; a non-@table input rejects
            // exactly as it did before the RawArg arm existed.
            case DmlWalkerInputArgResolution.RawArg raw -> {
                return rawArgUpdateRejection(parentTypeName, name, location, fieldDef, raw);
            }
        }
        boolean list = inputArg.list();

        // Invariant #14/#15 return-type validation (shared with the resolveInput path).
        String returnTypeError = MutationInputResolver.validateReturnType(returnType, DmlKind.UPDATE, list, ctx);
        if (returnTypeError != null) {
            return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(returnTypeError));
        }

        // ID-return encode resolution (mirrors the shared DML path at the @mutation classifier).
        Optional<HelperRef.Encode> encodeReturn = Optional.empty();
        if (returnType instanceof ReturnTypeRef.ScalarReturnType s && "ID".equals(s.returnTypeName())) {
            String tableSqlName = foundTit.table().tableName();
            // R317 slice 2 — the one-to-many by-table node index in place of the registry scan;
            // the implicit ID encoder is well-defined only for a single-node table.
            var nodesOnTable = ctx.nodes.forTable(tableSqlName);
            if (nodesOnTable.isEmpty()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(
                    "@mutation field '" + name + "' returns ID but no @node type is declared for table '"
                    + tableSqlName + "'; annotate the type with @node or use a @table return type"));
            }
            if (nodesOnTable.size() > 1) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(
                    ambiguousImplicitNodeError("@mutation field '" + name + "'", tableSqlName, nodesOnTable)));
            }
            encodeReturn = Optional.of(nodesOnTable.get(0).encodeMethod());
        }

        // R246 walker: PK-or-UK identification + SET/WHERE partition over the already-classified
        // input fields (the translator concession; see UpdateRowsWalker). Cardinality-independent
        // (R342): the bulk vs single-row split is the emitter's, driven by inputArg.list() below.
        var walkerResult = new no.sikt.graphitron.rewrite.walker.UpdateRowsWalker()
            .walk(fieldDef, foundTit.table(), foundTit.inputFields(), ctx.catalog, inputArg.name());
        var enc = encodeReturn;
        // R63: the bulk arm emits UPDATE ... FROM (VALUES ...), a Postgres extension jOOQ silently
        // emulates with semantics drift on other dialects, so it requires the Postgres family;
        // single-row UPDATE has no dialect constraint. The bulk-vs-single split is the emitter's
        // (driven by inputArg.list()); this reads the same bit to pick the arm at construction.
        DialectRequirement updateDialect = list
            ? new DialectRequirement.RequiresFamily(SqlDialectFamily.POSTGRES,
                "@mutation(typeName: UPDATE) with a listed @table input requires PostgreSQL; "
                    + "the UPDATE ... FROM (VALUES ...) form is a Postgres extension. "
                    + "Use a single-row input for portability.")
            : DialectRequirement.None.INSTANCE;
        return switch (walkerResult) {
            case no.sikt.graphitron.rewrite.model.WalkerResult.Ok<no.sikt.graphitron.rewrite.model.UpdateRows> ok ->
                buildDmlField(returnType, parentTypeName, name, location, fieldDef,
                    (rex, ch) -> new MutationField.MutationUpdateTableField(
                        parentTypeName, name, location, rex, updateDialect, inputArg, ok.carrier(), ch),
                    enc);
            case no.sikt.graphitron.rewrite.model.WalkerResult.Err<no.sikt.graphitron.rewrite.model.UpdateRows> err ->
                new UnclassifiedField(parentTypeName, name, location, fieldDef, err.errors().getFirst());
        };
    }

    /**
     * R457 — the {@code @mutation} verbs whose classifier reads {@code @mutation(table:)} as a
     * field-relative write target. A one-element {@code {DELETE}} set today; the classifier's
     * unsupported-verb guard and `mvn graphitron:validate` (which runs the classifier) both read it,
     * so generalising the parameter to another verb is a single edit here.
     */
    private static final Set<DmlKind> TABLE_ARG_SUPPORTED_VERBS = Set.of(DmlKind.DELETE);

    /**
     * Outcome of {@link #resolveDmlWalkerInputArg}: the resolved {@code @table}-input arg surface, a
     * raw (non-{@code @table}) input arg surface, or a typed rejection.
     *
     * <p>R457 — {@code RawArg} makes "the single input arg is not a {@code TableInputType}" a normal
     * outcome rather than an immediate structural reject. UPDATE callers translate it to today's
     * rejection verbatim (byte-identical behaviour); the DELETE classifiers own the fallback that
     * resolves the write target from {@code @mutation(table:)} and re-derives the input fields against
     * it. Carries only the slim arg facts plus the {@link GraphitronType.InputType} verdict (whose
     * {@code schemaType()} yields the raw {@link graphql.schema.GraphQLInputObjectField}s), never a
     * synthesized {@code TableInputType}.
     */
    private sealed interface DmlWalkerInputArgResolution {
        record Resolved(GraphitronType.TableInputType tit,
                        no.sikt.graphitron.rewrite.model.InputArgRef inputArg) implements DmlWalkerInputArgResolution {}
        record RawArg(String argName, String argTypeName, boolean list,
                      GraphitronType.InputType inputType) implements DmlWalkerInputArgResolution {}
        record Rejected(GraphitronField field) implements DmlWalkerInputArgResolution {}
    }

    /**
     * R246 / R258 / R266 — shared pre-walker resolution for the four walker-driven UPDATE / DELETE
     * classifiers ({@link #classifyUpdateTableField}, {@link #classifyUpdatePayloadField},
     * {@link #classifyDeleteTableField}, {@link #classifyDeletePayloadField}). Resolves the single
     * {@code @table} input argument into the slim {@link no.sikt.graphitron.rewrite.model.InputArgRef}
     * arg surface, applying the arg-shape rejections (more-than-one-arg, non-{@code @table} arg,
     * {@code @condition}-on-arg). These are the argument's property rather than per-field
     * admissibility; none of these classifiers calls {@code MutationInputResolver.resolveInput}.
     *
     * <p>{@code multiRow} handling is the caller's concern, since it diverges by verb: UPDATE
     * rejects it outright (the dispatch does so before calling this), DELETE turns it into the
     * {@link no.sikt.graphitron.rewrite.model.DeleteRows.Broadcast} arm (the walker does so).
     *
     * <p>R457 — this method stays verb-agnostic. "The single input arg is not a
     * {@code TableInputType}" is now a <em>normal</em> outcome ({@link DmlWalkerInputArgResolution.RawArg})
     * rather than an immediate structural reject, because a DELETE can carry its write target on
     * {@code @mutation(table:)} instead of on the input's {@code @table}. The verb-divergent handling
     * of that arm lives in the callers: UPDATE translates it back to today's rejection verbatim, the
     * DELETE classifiers own the field-relative fallback. A genuinely non-input-object argument (a
     * scalar/enum), more-than-one input argument, and {@code @condition}-on-arg remain structural
     * rejections here for both verbs.
     */
    private DmlWalkerInputArgResolution resolveDmlWalkerInputArg(
            GraphQLFieldDefinition fieldDef, String parentTypeName, String name, SourceLocation location) {
        // Resolve the single input argument's slim surface, rejecting the shape constraints that are
        // the arg's property rather than per-field admissibility.
        GraphitronType foundInput = null;
        String argName = null;
        String argTypeName = null;
        boolean list = false;
        for (var arg : fieldDef.getArguments()) {
            var argType = arg.getType();
            boolean argList = GraphQLTypeUtil.unwrapNonNull(argType) instanceof GraphQLList;
            String typeName = ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(argType)).getName();
            // R317 slice 3e — registry-free look-ahead at the @mutation arg's input type. A @table
            // input classifies as TableInputType; a plain input object as the sibling InputType
            // (PojoInputType et al) — both are input objects with a schemaType(), but neither is a
            // subtype of the other. A non-input-object argument (a scalar/enum) is the shape error.
            var resolvedType = typeBuilder.lookAheadVerdict(typeName);
            if (!(resolvedType instanceof GraphitronType.TableInputType)
                    && !(resolvedType instanceof GraphitronType.InputType)) {
                return new DmlWalkerInputArgResolution.Rejected(new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(
                    "@mutation fields only accept @table input arguments; found '" + arg.getName()
                    + "' of type '" + typeName + "'")));
            }
            if (arg.hasAppliedDirective(BuildContext.DIR_CONDITION)) {
                return new DmlWalkerInputArgResolution.Rejected(new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(
                    "@condition on a @mutation field argument is not supported")));
            }
            if (foundInput != null) {
                return new DmlWalkerInputArgResolution.Rejected(new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(
                    "@mutation field has more than one @table input argument")));
            }
            foundInput = resolvedType;
            argName = arg.getName();
            argTypeName = typeName;
            list = argList;
        }
        if (foundInput == null) {
            return new DmlWalkerInputArgResolution.Rejected(new UnclassifiedField(parentTypeName, name, location, fieldDef,
                Rejection.structural("no @table input argument found on @mutation field")));
        }
        if (foundInput instanceof GraphitronType.TableInputType tit) {
            var inputArg = new no.sikt.graphitron.rewrite.model.InputArgRef(
                argName, argTypeName, tit.table(), list);
            return new DmlWalkerInputArgResolution.Resolved(tit, inputArg);
        }
        // No @table on the input: a normal outcome for DELETE (the write target comes from
        // @mutation(table:)); UPDATE translates this back to today's "only accept @table" rejection.
        return new DmlWalkerInputArgResolution.RawArg(argName, argTypeName, list, (GraphitronType.InputType) foundInput);
    }

    /**
     * R457 — the "@mutation fields only accept @table input arguments" rejection an UPDATE classifier
     * produces when it meets a {@link DmlWalkerInputArgResolution.RawArg} (a non-{@code @table} input
     * object). Byte-identical to the message {@link #resolveDmlWalkerInputArg} emitted for the same
     * shape before R457 made the arm a normal outcome, so UPDATE behaviour is unchanged.
     */
    private GraphitronField rawArgUpdateRejection(
            String parentTypeName, String name, SourceLocation location, GraphQLFieldDefinition fieldDef,
            DmlWalkerInputArgResolution.RawArg raw) {
        return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(
            "@mutation fields only accept @table input arguments; found '" + raw.argName()
            + "' of type '" + raw.argTypeName() + "'"));
    }

    /**
     * R258 — classifies a payload-returning {@code @mutation(typeName: UPDATE)} field into a
     * {@link MutationField.MutationUpdatePayloadField} (single) or
     * {@link MutationField.MutationBulkUpdatePayloadField} (bulk). Combines the structural-DML-
     * payload machinery the record-carrier classifier uses (the payload scan, the data-field
     * {@code @table}-equality check, the error-channel detection) with the {@code UpdateRowsWalker}
     * the direct-return UPDATE uses (PK-or-UK identification + SET/WHERE partition), so no UPDATE
     * path reads {@code @value}. The data field's {@code RecordTableField} (former SingleRecordTableField) classification is
     * grounded independently by {@code RecordBindingResolver.groundDmlMutationField} (which reads
     * {@code @mutation(typeName:)} straight off the SDL), so no per-field reclassify is needed here.
     * A pre-check failure or a walker {@code Err} surfaces as an {@link UnclassifiedField}; the
     * walker's typed {@code UpdateRowsError} arm is preserved verbatim for the LSP projector.
     */
    private GraphitronField classifyUpdatePayloadField(
            GraphQLFieldDefinition fieldDef, String parentTypeName, String name,
            SourceLocation location, ReturnTypeRef.ResultReturnType returnType) {
        GraphitronType.TableInputType foundTit;
        no.sikt.graphitron.rewrite.model.InputArgRef inputArg;
        switch (resolveDmlWalkerInputArg(fieldDef, parentTypeName, name, location)) {
            case DmlWalkerInputArgResolution.Rejected r -> { return r.field(); }
            case DmlWalkerInputArgResolution.Resolved ok -> { foundTit = ok.tit(); inputArg = ok.inputArg(); }
            // R457 — UPDATE has no field-relative write-target path; a non-@table input rejects
            // exactly as it did before the RawArg arm existed.
            case DmlWalkerInputArgResolution.RawArg raw -> {
                return rawArgUpdateRejection(parentTypeName, name, location, fieldDef, raw);
            }
        }

        // Invariant #14/#15 return-type validation (shared with the resolveInput path).
        String returnTypeError = MutationInputResolver.validateReturnType(returnType, DmlKind.UPDATE, inputArg.list(), ctx);
        if (returnTypeError != null) {
            return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(returnTypeError));
        }

        // Structural DML-payload scan: admit a plain SDL Object wrapping exactly one @table-element
        // data field; reject record-element / ID-element shapes (mirrors the non-DELETE arms of the
        // shared record-carrier classifier). ID-element is the DELETE-only PK-echo permit; record-
        // element needs @service.
        var scan = ctx.scanStructuralDmlPayload(returnType.returnTypeName());
        if (scan instanceof BuildContext.DmlPayloadScan.Reject scanReject) {
            return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(scanReject.reason()));
        }
        var admit = (BuildContext.DmlPayloadScan.Admit) scan;
        var element = admit.element();
        if (element instanceof BuildContext.DmlElementKind.RecordElement re) {
            return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(
                "@mutation(typeName: " + DmlKind.UPDATE + ") field '" + name
                + "' returns single-record carrier '" + returnType.returnTypeName()
                + "' with a record-element data field ('" + re.fieldName()
                + "'); DML mutations require an @table-element or ID-scalar data field. Use a "
                + "@service mutation for record-element carriers, or change the data field's element "
                + "type to the input table's @table type / ID"));
        }
        if (element instanceof BuildContext.DmlElementKind.IdElement) {
            return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(
                "single-record carrier '" + returnType.returnTypeName()
                + "' has data field of element type ID, which is the PK-echo permit "
                + "(post-image == primary key) and is admitted only on "
                + "@mutation(typeName: DELETE) carriers. On @mutation(typeName: "
                + DmlKind.UPDATE + ") the post-image is richer; use a @table-element data field "
                + "or a record-backed element data field instead."));
        }
        var tbl = (BuildContext.DmlElementKind.Table) element;
        String tableMismatch = requireDmlDataTableMatchesInputTable(
            inputArg.table(), tbl, DmlKind.UPDATE, name, returnType.returnTypeName());
        if (tableMismatch != null) {
            return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(tableMismatch));
        }
        var dmlChannelResult = detectStructuralDmlErrorChannel(returnType.returnTypeName());
        if (dmlChannelResult instanceof StructuralDmlErrorChannel.RuleViolation rv) {
            return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(rv.reason()));
        }
        Optional<ErrorChannel> dmlChannel = (dmlChannelResult instanceof StructuralDmlErrorChannel.Present p)
            ? Optional.of(p.channel()) : Optional.empty();

        // R246 walker: PK-or-UK identification + SET/WHERE partition over the already-classified
        // input fields. On Err, preserve the typed UpdateRowsError arm verbatim. Cardinality-independent
        // (R342): the bulk vs single-row split is the emitter's, driven by inputArg.list() below.
        var walkerResult = new no.sikt.graphitron.rewrite.walker.UpdateRowsWalker()
            .walk(fieldDef, foundTit.table(), foundTit.inputFields(), ctx.catalog, inputArg.name());
        var channel = dmlChannel;
        return switch (walkerResult) {
            case no.sikt.graphitron.rewrite.model.WalkerResult.Ok<no.sikt.graphitron.rewrite.model.UpdateRows> ok -> {
                if (inputArg.list()) {
                    yield new MutationField.MutationBulkUpdatePayloadField(
                        parentTypeName, name, location, returnType, inputArg, ok.carrier(), channel);
                }
                yield new MutationField.MutationUpdatePayloadField(
                    parentTypeName, name, location, returnType, inputArg, ok.carrier(), channel);
            }
            case no.sikt.graphitron.rewrite.model.WalkerResult.Err<no.sikt.graphitron.rewrite.model.UpdateRows> err ->
                new UnclassifiedField(parentTypeName, name, location, fieldDef, err.errors().getFirst());
        };
    }

    /**
     * Outcome of {@link #resolveDeleteWriteTarget}: the resolved DELETE write target (the jOOQ table,
     * the input fields resolved against it, and the slim {@link InputArgRef} arg surface) or a typed
     * rejection. R457 — the precedence and diagnostics that produce it are DELETE-only, so they live
     * here rather than in the verb-agnostic {@link #resolveDmlWalkerInputArg}.
     */
    private sealed interface DeleteWriteTarget {
        record Resolved(TableRef writeTarget, List<InputField> inputFields,
                        no.sikt.graphitron.rewrite.model.InputArgRef inputArg) implements DeleteWriteTarget {}
        record Rejected(GraphitronField field) implements DeleteWriteTarget {}
    }

    /**
     * R457 — resolves a {@code @mutation(typeName: DELETE)} field's write target and the input fields
     * against it, by the DELETE precedence: {@code @mutation(table:)} (the preferred, field-relative
     * override), then the input type's {@code @table} (the deprecated migration bridge). There is
     * deliberately <em>no</em> return-derived rung: a DELETE cannot carry its table on the return type
     * (the row is gone after the statement, so a {@code @table} return is rejected upstream; see
     * R287), so every DELETE that lacks {@code @table} on its input must name the table with
     * {@code @mutation(table:)}.
     *
     * <p>An input {@code @table} that disagrees with {@code @mutation(table:)} is silently outranked,
     * never cross-checked: R332 already nudges the input directive's removal, and promoting a
     * directive the deprecation path wants migrated quietly into a build-breaking conflict participant
     * would invert that.
     *
     * <p>On the field-derived path (write target from {@code @mutation(table:)}, no {@code @table} on
     * the input), the input fields are resolved through {@link TypeBuilder#resolveInputFields} (shared
     * with {@code buildTableInputType}) and then the {@code validateTableInputType} input-field
     * rejections are mirrored at this call site, since a field-derived input never lands in that
     * registry walk (the R330 validator-mirror obligation).
     */
    private DeleteWriteTarget resolveDeleteWriteTarget(
            GraphQLFieldDefinition fieldDef, String parentTypeName, String name, SourceLocation location) {
        // 1. Arg surface. A @table input arrives as Resolved (tit); a non-@table input as RawArg.
        GraphitronType.TableInputType tit = null;
        GraphitronType.InputType rawInput = null;
        String argName;
        String argTypeName;
        boolean list;
        switch (resolveDmlWalkerInputArg(fieldDef, parentTypeName, name, location)) {
            case DmlWalkerInputArgResolution.Rejected r -> { return new DeleteWriteTarget.Rejected(r.field()); }
            case DmlWalkerInputArgResolution.Resolved ok -> {
                tit = ok.tit();
                argName = ok.inputArg().name();
                argTypeName = ok.inputArg().inputTypeName();
                list = ok.inputArg().list();
            }
            case DmlWalkerInputArgResolution.RawArg raw -> {
                rawInput = raw.inputType();
                argName = raw.argName();
                argTypeName = raw.argTypeName();
                list = raw.list();
            }
        }

        // 2. @mutation(table:) — the preferred, field-relative write target (R457 rung 2).
        Optional<TableRef> mutationTable = Optional.empty();
        var tableArg = MutationInputResolver.parseMutationTableArg(fieldDef);
        if (tableArg.isPresent()) {
            var resolved = svc.resolveTable(tableArg.get());
            if (resolved.isEmpty()) {
                return new DeleteWriteTarget.Rejected(new UnclassifiedField(parentTypeName, name, location, fieldDef,
                    ctx.unknownTableRejection(tableArg.get())));
            }
            mutationTable = resolved;
        }

        // 3. Precedence: @mutation(table:) (preferred) > input @table (deprecated migration bridge).
        TableRef writeTarget;
        if (mutationTable.isPresent()) {
            writeTarget = mutationTable.get();
        } else if (tit != null) {
            writeTarget = tit.table();
        } else {
            // No live source resolved. Lead the message with the preferred replacement (R457).
            return new DeleteWriteTarget.Rejected(new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(
                "@mutation(typeName: DELETE) field '" + name + "' has no write target: name the table "
                + "to delete from with @mutation(table: \"<table>\") on this field (preferred), or "
                + "annotate the input type '" + argTypeName + "' with @table (deprecated). A DELETE "
                + "cannot derive its table from the return type — the row is gone after the statement, "
                + "so a @table return is not supported (see R287).")));
        }

        // 4. Input fields against the write target.
        List<InputField> inputFields;
        if (tit != null && writeTarget.equals(tit.table())) {
            // The input's @table already resolved its fields against this same table, and the registry
            // TableInputType walk enforces the validator-side input-field rejections on it. Reuse both.
            inputFields = tit.inputFields();
        } else {
            // Field-derived path: resolve the raw input fields against the field-named table, then
            // mirror the validator's input-field rejections here (R330 validator-mirror obligation) —
            // a field-derived input never lands in GraphitronSchemaValidator.validateTableInputType.
            var schemaInput = tit != null ? tit.schemaType() : rawInput.schemaType();
            var fieldsResolution = typeBuilder.resolveInputFields(argTypeName, schemaInput.getFieldDefinitions(), writeTarget);
            if (fieldsResolution instanceof TypeBuilder.InputFieldsResolution.Failed failed) {
                return new DeleteWriteTarget.Rejected(new UnclassifiedField(parentTypeName, name, location, fieldDef,
                    Rejection.structural(failed.reason())));
            }
            inputFields = ((TypeBuilder.InputFieldsResolution.Resolved) fieldsResolution).fields();
            var mirrored = GraphitronSchemaValidator.collectInputFieldRejections(inputFields);
            if (!mirrored.isEmpty()) {
                return new DeleteWriteTarget.Rejected(new UnclassifiedField(parentTypeName, name, location, fieldDef,
                    mirrored.getFirst().rejection()));
            }
        }

        var inputArg = new no.sikt.graphitron.rewrite.model.InputArgRef(argName, argTypeName, writeTarget, list);
        return new DeleteWriteTarget.Resolved(writeTarget, inputFields, inputArg);
    }

    /**
     * R266 — classifies a direct-@table/ID-return {@code @mutation(typeName: DELETE)} field into a
     * {@link MutationField.MutationDeleteTableField}. The DELETE analogue of
     * {@link #classifyUpdateTableField}: resolves the write target and input fields (R457 precedence:
     * {@code @mutation(table:)}, then the input's {@code @table}), validates the return type, resolves
     * the ID-return encoder, then runs {@code DeleteRowsWalker} for the PK-or-UK identification.
     * Unlike UPDATE, {@code multiRow: true} is admitted (the walker turns it into the
     * {@link no.sikt.graphitron.rewrite.model.DeleteRows.Broadcast} arm). A pre-check failure or a
     * walker {@code Err} surfaces as an {@link UnclassifiedField} carrying the typed
     * {@code DeleteRowsError} arm verbatim for the LSP projector.
     */
    private GraphitronField classifyDeleteTableField(
            GraphQLFieldDefinition fieldDef, String parentTypeName, String name,
            SourceLocation location, ReturnTypeRef returnType) {
        TableRef writeTarget;
        List<InputField> inputFields;
        no.sikt.graphitron.rewrite.model.InputArgRef inputArg;
        switch (resolveDeleteWriteTarget(fieldDef, parentTypeName, name, location)) {
            case DeleteWriteTarget.Rejected r -> { return r.field(); }
            case DeleteWriteTarget.Resolved ok -> {
                writeTarget = ok.writeTarget(); inputFields = ok.inputFields(); inputArg = ok.inputArg();
            }
        }
        boolean list = inputArg.list();

        // Invariant #14/#15 return-type validation (shared with the resolveInput path).
        String returnTypeError = MutationInputResolver.validateReturnType(returnType, DmlKind.DELETE, list, ctx);
        if (returnTypeError != null) {
            return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(returnTypeError));
        }

        // ID-return encode resolution (mirrors the shared DML path at the @mutation classifier).
        Optional<HelperRef.Encode> encodeReturn = Optional.empty();
        if (returnType instanceof ReturnTypeRef.ScalarReturnType s && "ID".equals(s.returnTypeName())) {
            String tableSqlName = writeTarget.tableName();
            // R317 slice 2 — the one-to-many by-table node index in place of the registry scan;
            // the implicit ID encoder is well-defined only for a single-node table.
            var nodesOnTable = ctx.nodes.forTable(tableSqlName);
            if (nodesOnTable.isEmpty()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(
                    "@mutation(typeName: DELETE) field '" + name + "' returns ID but no @node type is "
                    + "declared for table '" + tableSqlName + "'; annotate the type with @node"));
            }
            if (nodesOnTable.size() > 1) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(
                    ambiguousImplicitNodeError("@mutation(typeName: DELETE) field '" + name + "'", tableSqlName, nodesOnTable)));
            }
            encodeReturn = Optional.of(nodesOnTable.get(0).encodeMethod());
        }

        // R266 walker: PK-or-UK identification over the already-classified input fields, with
        // multiRow opting into the Broadcast arm (the translator concession; see DeleteRowsWalker).
        boolean multiRow = MutationInputResolver.parseMultiRow(fieldDef);
        var walkerResult = new no.sikt.graphitron.rewrite.walker.DeleteRowsWalker()
            .walk(fieldDef, writeTarget, inputFields, ctx.catalog, multiRow, inputArg.name());
        var enc = encodeReturn;
        return switch (walkerResult) {
            case no.sikt.graphitron.rewrite.model.WalkerResult.Ok<no.sikt.graphitron.rewrite.model.DeleteRows> ok ->
                buildDmlField(returnType, parentTypeName, name, location, fieldDef,
                    // R63: DELETE emits a portable statement on every dialect graphitron targets, so
                    // it carries no dialect constraint (the bulk row-tuple IN form is standard SQL).
                    (rex, ch) -> new MutationField.MutationDeleteTableField(
                        parentTypeName, name, location, rex, DialectRequirement.None.INSTANCE,
                        inputArg, ok.carrier(), ch),
                    enc);
            case no.sikt.graphitron.rewrite.model.WalkerResult.Err<no.sikt.graphitron.rewrite.model.DeleteRows> err ->
                new UnclassifiedField(parentTypeName, name, location, fieldDef, err.errors().getFirst());
        };
    }

    /**
     * R266 — classifies a payload-returning {@code @mutation(typeName: DELETE)} field into a
     * {@link MutationField.MutationDeletePayloadField} (single) or
     * {@link MutationField.MutationBulkDeletePayloadField} (bulk). The DELETE analogue of
     * {@link #classifyUpdatePayloadField}, but it retains DELETE's structural-payload reclassify
     * (the IdElement PK-echo and Table PK-only RETURNING projection the walkers cannot re-derive),
     * which the inline shared-path DELETE arm used to own. Only the input-side WHERE source moved to
     * the {@code DeleteRowsWalker}; the data-field carrier projection is unchanged. A pre-check
     * failure or a walker {@code Err} surfaces as an {@link UnclassifiedField}; the walker's typed
     * {@code DeleteRowsError} arm is preserved verbatim for the LSP projector.
     */
    private GraphitronField classifyDeletePayloadField(
            GraphQLFieldDefinition fieldDef, String parentTypeName, String name,
            SourceLocation location, ReturnTypeRef.ResultReturnType returnType) {
        TableRef writeTarget;
        List<InputField> inputFields;
        no.sikt.graphitron.rewrite.model.InputArgRef inputArg;
        switch (resolveDeleteWriteTarget(fieldDef, parentTypeName, name, location)) {
            case DeleteWriteTarget.Rejected r -> { return r.field(); }
            case DeleteWriteTarget.Resolved ok -> {
                writeTarget = ok.writeTarget(); inputFields = ok.inputFields(); inputArg = ok.inputArg();
            }
        }

        // Invariant #14/#15 return-type validation (shared with the resolveInput path).
        String returnTypeError = MutationInputResolver.validateReturnType(returnType, DmlKind.DELETE, inputArg.list(), ctx);
        if (returnTypeError != null) {
            return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(returnTypeError));
        }

        // Structural DML-payload scan, then the DELETE-specific per-field reclassify (IdElement PK-
        // echo / Table PK-only RETURNING). This is the inline shared-path DELETE arm, ported here
        // and re-sourced to inputArg.table(); only the WHERE source moves to the DeleteRows carrier.
        var scan = ctx.scanStructuralDmlPayload(returnType.returnTypeName());
        if (scan instanceof BuildContext.DmlPayloadScan.Reject scanReject) {
            return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(scanReject.reason()));
        }
        var admit = (BuildContext.DmlPayloadScan.Admit) scan;
        var dataField = admit.dataField();
        var element = admit.element();
        var wrapper = ctx.buildWrapper(dataField);
        var dataFieldLocation = locationOf(dataField);

        // R266 walker: PK-or-UK identification over the already-classified input fields, multiRow
        // opting into the Broadcast arm. Run it before the per-field reclassify so an under-keyed
        // input rejects without leaving an orphaned data-field carrier in the registry — matching the
        // legacy ordering where resolveInput's PK-coverage check rejected before the reclassify ran.
        boolean multiRow = MutationInputResolver.parseMultiRow(fieldDef);
        var walkerResult = new no.sikt.graphitron.rewrite.walker.DeleteRowsWalker()
            .walk(fieldDef, writeTarget, inputFields, ctx.catalog, multiRow, inputArg.name());
        if (walkerResult instanceof no.sikt.graphitron.rewrite.model.WalkerResult.Err<no.sikt.graphitron.rewrite.model.DeleteRows> err) {
            return new UnclassifiedField(parentTypeName, name, location, fieldDef, err.errors().getFirst());
        }
        var deleteRows = ((no.sikt.graphitron.rewrite.model.WalkerResult.Ok<no.sikt.graphitron.rewrite.model.DeleteRows>) walkerResult).carrier();

        switch (element) {
            case BuildContext.DmlElementKind.IdElement ignored -> {
                var encoderResolution = resolveCarrierIdEncoder(ctx, dataField, inputArg.table());
                String encoderError = deleteIdEncoderError(encoderResolution, inputArg.table(), name);
                if (encoderError != null) {
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(encoderError));
                }
                var encoder = ((IdEncoderResolution.Resolved) encoderResolution).nodeType().encodeMethod();
                var returnType_id = new ReturnTypeRef.ScalarReturnType("ID", wrapper);
                var coords = graphql.schema.FieldCoordinates.coordinates(returnType.returnTypeName(), dataField.getName());
                var carrier = new ChildField.SingleRecordIdFieldFromReturning(
                    returnType.returnTypeName(), dataField.getName(), dataFieldLocation, returnType_id,
                    new no.sikt.graphitron.rewrite.model.CallSiteCompaction.NodeIdEncodeKeys(encoder));
                // R276: the @mutation DELETE classifier owns the final carrier; accept any provisional.
                ctx.fieldRegistry.reclassify(coords, carrier, null);
            }
            case BuildContext.DmlElementKind.Table tbl -> {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(
                    "@mutation(typeName: DELETE) carrier '" + returnType.returnTypeName()
                    + "': @table-element data field '" + dataField.getName() + "' (element type '"
                    + tbl.elementTypeName() + "') is not supported. The row is gone after the statement, "
                    + "and RETURNING carries only the primary key, so a full @table projection is "
                    + "impossible. Use an ID-typed data field (type ID or [ID!] with @nodeId either "
                    + "implicit by the input @table's @node registration or explicit on the field), "
                    + "which echoes the deleted primary keys."));
            }
            case BuildContext.DmlElementKind.RecordElement ignored -> {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(
                    "R156: record-element data field on DELETE is not supported; use @service for "
                    + "record-element carriers or an @table-element / ID-scalar data field for DML carriers"));
            }
        }

        var dmlChannelResult = detectStructuralDmlErrorChannel(returnType.returnTypeName());
        if (dmlChannelResult instanceof StructuralDmlErrorChannel.RuleViolation rv) {
            return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(rv.reason()));
        }
        Optional<ErrorChannel> dmlChannel = (dmlChannelResult instanceof StructuralDmlErrorChannel.Present p)
            ? Optional.of(p.channel()) : Optional.empty();

        if (inputArg.list()) {
            return new MutationField.MutationBulkDeletePayloadField(
                parentTypeName, name, location, returnType, inputArg, deleteRows, dmlChannel);
        }
        return new MutationField.MutationDeletePayloadField(
            parentTypeName, name, location, returnType, inputArg, deleteRows, dmlChannel);
    }

    /**
     * Returns {@code true} when {@code @lookupKey} appears on any direct argument of the field,
     * or on any field within an input-type argument (recursively). This is the field-level
     * classification signal — which specific argument carries it has no semantic significance.
     */
    private boolean hasLookupKeyAnywhere(GraphQLFieldDefinition fieldDef) {
        for (var arg : fieldDef.getArguments()) {
            if (arg.hasAppliedDirective(DIR_LOOKUP_KEY)) return true;
            String argTypeName = ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(arg.getType())).getName();
            if (ctx.schema.getType(argTypeName) instanceof GraphQLInputObjectType inputType) {
                if (inputTypeHasLookupKey(inputType, 0)) return true;
            }
        }
        return false;
    }

    private boolean inputTypeHasLookupKey(GraphQLInputObjectType inputType, int depth) {
        if (depth > 10) return false; // guard against pathological nesting
        for (var field : inputType.getFieldDefinitions()) {
            if (field.hasAppliedDirective(DIR_LOOKUP_KEY)) return true;
            String fieldTypeName = ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(field.getType())).getName();
            if (ctx.schema.getType(fieldTypeName) instanceof GraphQLInputObjectType nested) {
                if (inputTypeHasLookupKey(nested, depth + 1)) return true;
            }
        }
        return false;
    }

    // ===== Conflict detection helpers =====
    // Each method returns a human-readable reason string when mutually exclusive directives are
    // found together, or {@code null} when no conflict exists. Callers produce an
    // {@link UnclassifiedField} or {@link GraphitronType.UnclassifiedType} carrying the reason,
    // which the validator then reports as a standard error.


    /**
     * R449 D2 — the verdict for an unordered pair of classification-claiming directives. Two
     * source-claiming directives conflict by default; the table below ({@link #pairVerdict})
     * records the two exceptions {@code @routine} draws. The sealed shape lets
     * {@link #reduceDirectiveConflict} switch exhaustively.
     */
    private sealed interface PairVerdict permits PairVerdict.Conflict, PairVerdict.Deferred, PairVerdict.Composes {
        /** Two source-claiming directives that cannot co-occur. The default for any pair. */
        record Conflict() implements PairVerdict {}
        /** A recognised-but-unsupported combination, signposting {@code planSlug}. */
        record Deferred(String planSlug) implements PairVerdict {}
        /** A combination that legitimately composes (no rejection). */
        record Composes() implements PairVerdict {}
    }

    /**
     * R449 D2 — the pairwise conflict verdict for an unordered pair of classification-claiming
     * directives. Two source-claiming directives conflict by default; this records the exceptions
     * {@code @routine} draws: {@code @routine} × {@code @lookupKey} is a capability gap (typed
     * {@code Deferred} on R447's {@code routine-chain-fetch-form-breadth}, the root extension of
     * R435's shipped child verdict), and {@code @routine} × {@code @splitQuery} composes (shipped
     * R435). {@link #reduceDirectiveConflict} projects this over the directives present at a
     * position; making it a table rather than a slot count with a carve-out is what lets the
     * reducer evaluate every pair (so {@code @routine @lookupKey @service} rejects the
     * {@code @service} conflicts rather than short-circuiting on the {@code @routine} × {@code
     * @lookupKey} defer).
     */
    private static PairVerdict pairVerdict(String a, String b) {
        var pair = Set.of(a, b);
        if (pair.equals(Set.of(DIR_ROUTINE, DIR_LOOKUP_KEY))) {
            return new PairVerdict.Deferred("routine-chain-fetch-form-breadth");
        }
        if (pair.equals(Set.of(DIR_ROUTINE, DIR_SPLIT_QUERY))) {
            return new PairVerdict.Composes();
        }
        return new PairVerdict.Conflict();
    }

    /**
     * R449 D2 — reduces the classification-claiming directives present at a position to a single
     * verdict via the pairwise {@link #pairVerdict} table. Enumerates every unordered pair, looks
     * up its verdict, and reduces with Conflict-dominates-Deferred precedence: any {@code Conflict}
     * pair yields a {@link Rejection.InvalidSchema.DirectiveConflict} naming the participating
     * directives; absent a conflict, the first {@code Deferred} pair yields its typed
     * {@link Rejection.Deferred}; all-{@code Composes} (or fewer than two present) yields
     * {@code null}. The precedence (rather than short-circuiting on the first non-{@code Composes}
     * pair) is what closes the three-directive hole a pre-count carve-out would reintroduce.
     */
    private Rejection reduceDirectiveConflict(List<String> present) {
        var conflicting = new LinkedHashSet<String>();
        String deferredSlug = null;
        for (int i = 0; i < present.size(); i++) {
            for (int j = i + 1; j < present.size(); j++) {
                switch (pairVerdict(present.get(i), present.get(j))) {
                    case PairVerdict.Conflict ignored -> {
                        conflicting.add(present.get(i));
                        conflicting.add(present.get(j));
                    }
                    case PairVerdict.Deferred d -> {
                        if (deferredSlug == null) deferredSlug = d.planSlug();
                    }
                    case PairVerdict.Composes ignored -> { }
                }
            }
        }
        if (!conflicting.isEmpty()) {
            var names = List.copyOf(conflicting);
            String at = names.stream().map(n -> "@" + n).collect(Collectors.joining(", "));
            return Rejection.directiveConflict(names, at + " are mutually exclusive");
        }
        if (deferredSlug != null) {
            // The only Deferred pair the table mints is @routine × @lookupKey.
            return Rejection.deferred(
                "@" + DIR_ROUTINE + " with @" + DIR_LOOKUP_KEY
                + " on a root field classifies but does not emit yet",
                deferredSlug);
        }
        return null;
    }

    /**
     * Returns a {@link Rejection} when the child-field classification-claiming directives present
     * conflict (via {@link #reduceDirectiveConflict}), or {@code null} when at most one is present
     * or the combination composes.
     *
     * <p>Note: {@code @reference} is a path-annotation directive, not a classification directive,
     * so it composes with any of these and is not listed. {@code @routine} <em>is</em> a
     * classification directive (R449 D2): a valid child {@code @routine} chain carries only
     * {@code @routine} (+ {@code @reference}), so it stays a single slot here; {@code @routine}
     * combined with any other source-claiming directive conflicts.
     */
    private Rejection detectChildFieldConflict(GraphQLFieldDefinition fieldDef) {
        var present = new ArrayList<String>();
        if (fieldDef.hasAppliedDirective(DIR_SERVICE))        present.add(DIR_SERVICE);
        if (fieldDef.hasAppliedDirective(DIR_EXTERNAL_FIELD)) present.add(DIR_EXTERNAL_FIELD);
        if (fieldDef.hasAppliedDirective(DIR_TABLE_METHOD))   present.add(DIR_TABLE_METHOD);
        if (fieldDef.hasAppliedDirective(DIR_NODE_ID))        present.add(DIR_NODE_ID);
        if (fieldDef.hasAppliedDirective(DIR_ROUTINE))        present.add(DIR_ROUTINE);
        return reduceDirectiveConflict(present);
    }

    /**
     * Returns a {@link Rejection} when the query-field classification-claiming directives present
     * conflict or compose to a capability gap (via {@link #reduceDirectiveConflict}), or
     * {@code null}. The set is {@code @service}, {@code @lookupKey} (detected anywhere on the
     * argument surface), {@code @tableMethod}, and {@code @routine} (R449 D2); {@code @routine} ×
     * {@code @lookupKey} lands the typed {@code Deferred} that extends R435's child verdict to root.
     */
    private Rejection detectQueryFieldConflict(GraphQLFieldDefinition fieldDef) {
        var present = new ArrayList<String>();
        if (fieldDef.hasAppliedDirective(DIR_SERVICE))      present.add(DIR_SERVICE);
        if (hasLookupKeyAnywhere(fieldDef))                 present.add(DIR_LOOKUP_KEY);
        if (fieldDef.hasAppliedDirective(DIR_TABLE_METHOD)) present.add(DIR_TABLE_METHOD);
        if (fieldDef.hasAppliedDirective(DIR_ROUTINE))      present.add(DIR_ROUTINE);
        return reduceDirectiveConflict(present);
    }

    /**
     * R275: whether the carrier payload type declares an {@code errors} field (structural match
     * via {@link BuildContext#detectErrorsFieldShape}). This is the condition under which an
     * {@code @service} carrier's producer routes through the typed {@code Outcome} wrapper (the
     * mutation field's {@link ErrorChannel.Mapped} channel from
     * {@link #resolveServiceOutcomeChannel}); the data field uses it to record its
     * {@link SourceKey.Reader.SourceEnvelope}. Mirrors the errors-field detection in
     * {@code resolveServiceOutcomeChannel} so the consumer-side envelope and the producer-side
     * channel agree on the same structural signal.
     */
    private boolean carrierPayloadHasErrorsField(String payloadTypeName) {
        if (!(ctx.schema.getType(payloadTypeName) instanceof GraphQLObjectType payloadObj)) {
            return false;
        }
        for (var f : payloadObj.getFieldDefinitions()) {
            if (ctx.detectErrorsFieldShape(f) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * R305: the former {@code SingleRecordTableField} payload carriers (DML write and
     * {@code @service} producer) collapse into {@link ChildField.RecordTableField} — a
     * source=target re-fetch. The producer hands back the target table's record on
     * {@code env.getSource()}; the field re-projects the {@code @table} by correlating the record's
     * primary key to the catalog rows. Modeled as the {@code @sourceRow} leaf shape: a single
     * {@link JoinStep.LiftedHop} over the target PK (each {@link JoinSlot.LifterSlot} folds the
     * source side and target side onto one {@link ColumnRef}, the source=target key fact),
     * {@link SourceKey.Reader.ColumnRead} (the data-fetcher reads the PK off the source record via
     * the enclosing type's {@code ResultType}), {@link SourceKey.Wrap.Row} (the DataLoader key is a
     * {@code RowN} PK tuple), and the {@code @sourceRow} {@link LoaderRegistration} constant. The
     * source envelope ({@code DIRECT} vs {@code OUTCOME_SUCCESS}) is derived at the type level by the
     * generator ({@code sourceIsOutcome}), not carried on the key. R305 batches every re-fetch field
     * (source cardinality {@code Many}); the single-source case is a correct one-element batch.
     */
    private ChildField buildPayloadCarrierRecordTableField(
            String parentTypeName, String name, SourceLocation location,
            ReturnTypeRef.TableBoundReturnType tb) {
        TableRef targetTable = tb.table();
        List<ColumnRef> pkColumns = targetTable.primaryKeyColumns();
        boolean isList = tb.wrapper().isList();
        SourceKey.Cardinality cardinality = isList
            ? SourceKey.Cardinality.MANY
            : SourceKey.Cardinality.ONE;
        SourceKey sourceKey = new SourceKey(
            pkColumns,
            new SourceKey.Wrap.Row(),
            cardinality,
            new SourceKey.Reader.ProducedRecordRead());
        // ONE: a single produced record -> one key, loader.load. MANY: a held collection -> one key
        // per element, loader.loadMany (one re-projected row per key). valueIsList is false either
        // way: each PK key resolves to exactly one target row.
        LoaderRegistration loaderRegistration = new LoaderRegistration(
            false,
            LoaderRegistration.Container.POSITIONAL_LIST,
            isList ? LoaderRegistration.Dispatch.LOAD_MANY : LoaderRegistration.Dispatch.LOAD_ONE);
        // Source=target re-fetch: PK self-identity is the degenerate case of the FK pairing, the
        // hop-less OnLiftedSlots correlation (R431; formerly a single LiftedHop in the joinPath).
        var parentCorrelation = new ParentCorrelation.OnLiftedSlots(targetTable, pkColumns);
        return new ChildField.RecordTableField(
            parentTypeName, name, location, tb, List.of(),
            List.of(), new OrderBySpec.None(), null,
            sourceKey, loaderRegistration, parentCorrelation);
    }

    private GraphitronField classifyChildFieldOnResultType(GraphQLFieldDefinition fieldDef, String parentTypeName,
            ResultType parentResultType, Class<?> parentBackingClass) {
        String name = fieldDef.getName();
        SourceLocation location = locationOf(fieldDef);

        // R178 (step 1, DML-only cutover): a child field on a DML-emitted payload classifies
        // through the unified path against the producer's inner @table. The parent's source at
        // runtime is the DML fetcher's RecordN<PK> (single) or Result<RecordN<PK>> (bulk); the
        // child reads its key columns off that source via SourceKey.Wrap.Row +
        // Reader.ColumnRead, which is shape-agnostic with respect to whether the parent is a
        // sparse RecordN<PK> or a full @table record (parent.get(<column>) works on both).
        //
        // Step 1 admits only @table-typed children on DML-emitted parents (the SettKvotesporsmal
        // bug fixture and FilmPayload / FilmsPayload / FilmCreateLocalContextPayload all have
        // this shape). Errors-shaped children fall through to the existing liftToErrorsField
        // path; class-backed and scalar children on DML payloads are deferred to step 2.
        var dmlEmitted = dmlEmittedBinding(parentTypeName);
        if (dmlEmitted.isPresent()) {
            var binding = dmlEmitted.get();
            String rawTypeName0 = baseTypeName(fieldDef);
            String elementTypeName0 = ctx.isConnectionType(rawTypeName0)
                ? ctx.connectionElementTypeName(rawTypeName0) : rawTypeName0;
            var resolvedReturnType = ctx.resolveReturnType(elementTypeName0, buildWrapper(fieldDef));
            if (resolvedReturnType instanceof ReturnTypeRef.PolymorphicReturnType p) {
                var lift = liftToErrorsField(fieldDef, parentTypeName, p, parentBackingClass);
                if (lift != null) return lift;
            }
            if (resolvedReturnType instanceof ReturnTypeRef.TableBoundReturnType tb) {
                if (!binding.tableRef().equals(tb.table())) {
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(
                        "@mutation payload field '" + parentTypeName + "." + name
                        + "' returns @table '" + tb.table().tableName()
                        + "' which does not match the input @table '" + binding.tableRef().tableName()
                        + "'; payload-returning DML mutations require child @table-bound fields to bind to the input table"));
                }
                // R305: the DML payload carrier collapses into RecordTableField — a source=target
                // re-fetch. The producer hands back the RETURNING record on env.getSource(); the
                // field re-projects the @table by correlating the record's PK to the catalog rows.
                // The source envelope (DIRECT here) is derived at the type level by the generator
                // (sourceIsOutcome), not carried on the key.
                return buildPayloadCarrierRecordTableField(parentTypeName, name, location, tb);
            }
            // Non-@table, non-polymorphic children on DML payloads fall through to existing
            // arms below. For step 1 the relevant fixtures don't exercise this fall-through.
        }

        // R178 step 2b: @service-carrier sibling. The producer is an @service method returning
        // XRecord (single) or List<XRecord> (bulk), observed as ProducerBinding.ServiceEmitted
        // by R96's structural detection. The classifier-side dispatch mirrors the DmlEmitted
        // arm above: R305 builds a RecordTableField source=target re-fetch carrier (see
        // buildPayloadCarrierRecordTableField). The source-read shape (typed XRecord vs sparse
        // RecordN<PK>) and the envelope (DIRECT vs OUTCOME_SUCCESS) are resolved by the generator
        // at the type level, not carried on the SourceKey.
        var serviceEmitted = serviceEmittedBinding(parentTypeName);
        if (serviceEmitted.isPresent()) {
            var binding = serviceEmitted.get();
            String rawTypeName1 = baseTypeName(fieldDef);
            String elementTypeName1 = ctx.isConnectionType(rawTypeName1)
                ? ctx.connectionElementTypeName(rawTypeName1) : rawTypeName1;
            var resolvedReturnType = ctx.resolveReturnType(elementTypeName1, buildWrapper(fieldDef));
            if (resolvedReturnType instanceof ReturnTypeRef.PolymorphicReturnType p) {
                var lift = liftToErrorsField(fieldDef, parentTypeName, p, parentBackingClass);
                if (lift != null) return lift;
            }
            if (resolvedReturnType instanceof ReturnTypeRef.TableBoundReturnType tb) {
                if (!binding.tableRef().equals(tb.table())) {
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(
                        "@service-carrier payload field '" + parentTypeName + "." + name
                        + "' returns @table '" + tb.table().tableName()
                        + "' which does not match the @service producer's inner @table '"
                        + binding.tableRef().tableName()
                        + "'; payload-returning @service-carrier mutations require the data field's "
                        + "@table to equal the producer's reflected return-element record class's table"));
                }
                // R275: @splitQuery on the carrier data field is structurally redundant — the
                // emit below already resolves the field through a PK-keyed follow-up SELECT off
                // the producer's record. Same advisory family as the class-backed-parent redundancy.
                warnIfSplitQueryOnRecordParent(fieldDef, parentTypeName, name, location);
                // R305: the @service payload carrier collapses into RecordTableField — a
                // source=target re-fetch, the same shape as the DML carrier above. The producer
                // hands back the target XRecord on env.getSource() (bare, or wrapped in
                // Outcome.Success when the payload carries an errors field); the field re-projects
                // the @table by correlating the record's PK to the catalog rows. The source
                // envelope (DIRECT vs OUTCOME_SUCCESS) is derived at the type level by the generator
                // (sourceIsOutcome = hasWrapperArmErrors), not carried on the key.
                return buildPayloadCarrierRecordTableField(parentTypeName, name, location, tb);
            }
            // R275 requirement 2: ID-element data field on an @service carrier — the opptak
            // fjernSakTagg/fjernSakTagger @nodeId-from-record shape. The encoder resolution
            // re-asserts that @nodeId(typeName:)'s NodeType pins to the producer's table
            // (the binding grounded the table from the same typeName's SDL @table, so a
            // mismatch here means T carries @table but registered under a different table,
            // or is not @node at all). The node-key columns are read straight off the
            // producer's in-memory record(s) and encoded — no follow-up SELECT, so the shape
            // is deletion-safe by construction.
            if (resolvedReturnType instanceof ReturnTypeRef.ScalarReturnType scalarReturn
                    && "ID".equals(elementTypeName1)
                    && fieldDef.hasAppliedDirective(DIR_NODE_ID)) {
                var encoderResolution = resolveCarrierIdEncoder(ctx, fieldDef, binding.tableRef());
                String encoderError = serviceIdEncoderError(encoderResolution, binding.tableRef(), parentTypeName, name);
                if (encoderError != null) {
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(encoderError));
                }
                var nodeType = ((IdEncoderResolution.Resolved) encoderResolution).nodeType();
                var idCardinality = scalarReturn.wrapper().isList()
                    ? SourceKey.Cardinality.MANY
                    : SourceKey.Cardinality.ONE;
                // Same envelope signal as the @table-element sibling above: an errors-bearing
                // carrier routes the producer through the typed Outcome wrapper, so the id
                // fetcher narrows Outcome.Success before encoding.
                var idEnvelope = carrierPayloadHasErrorsField(parentTypeName)
                    ? SourceKey.Reader.SourceEnvelope.OUTCOME_SUCCESS
                    : SourceKey.Reader.SourceEnvelope.DIRECT;
                var idSourceKey = new SourceKey(
                    nodeType.nodeKeyColumns(),
                    new SourceKey.Wrap.TableRecord(binding.tableRef().recordClass()),
                    idCardinality,
                    new SourceKey.Reader.ResultRowWalk(idEnvelope));
                return new ChildField.SingleRecordIdField(parentTypeName, name, location,
                    scalarReturn, binding.tableRef(), idSourceKey,
                    new no.sikt.graphitron.rewrite.model.CallSiteCompaction.NodeIdEncodeKeys(nodeType.encodeMethod()));
            }
        }

        // R329: the @service record-composite carrier's data field — a source-passthrough projection
        // of the producer's in-memory composite record(s) (single or list arrival). The parent payload
        // classifies as a class-backed ResultType (the per-element composite class) via
        // TypeBuilder.carrierBinding's ClassBacked arm; this field is the carrier's single non-@table
        // object data field, recognized by the same scanStructuralServiceCarrierPayload RecordElement
        // Admit the binding consumes (one recognizer, two consumers). Intercepted here, before the
        // generic object arm below, so the passthrough does not fall through to recordFieldOrUnclassified
        // — which would reject it for the absence of a same-named accessor on the composite. The
        // element result type's @field-mapped @table children resolve through the standard
        // record-backed path; only this data field is the new leaf.
        if (typeBuilder.carrierBinding(parentTypeName) instanceof TypeBuilder.CarrierBinding.ClassBacked
                && ctx.scanStructuralServiceCarrierPayload(parentTypeName)
                    instanceof BuildContext.DmlPayloadScan.Admit compositeAdmit
                && compositeAdmit.element() instanceof BuildContext.DmlElementKind.RecordElement
                && compositeAdmit.dataField().getName().equals(name)) {
            String rawCompositeType = baseTypeName(fieldDef);
            String compositeElementType = ctx.isConnectionType(rawCompositeType)
                ? ctx.connectionElementTypeName(rawCompositeType) : rawCompositeType;
            var compositeReturn = ctx.resolveReturnType(compositeElementType, buildWrapper(fieldDef));
            if (compositeReturn instanceof ReturnTypeRef.ResultReturnType rrt && rrt.fqClassName() != null) {
                // The source envelope (DIRECT vs OUTCOME_SUCCESS) is carried on the leaf rather than
                // recomputed at emit: an errors-bearing carrier routes the producer through the typed
                // Outcome wrapper, so the passthrough narrows Outcome.Success before projecting.
                var envelope = carrierPayloadHasErrorsField(parentTypeName)
                    ? SourceKey.Reader.SourceEnvelope.OUTCOME_SUCCESS
                    : SourceKey.Reader.SourceEnvelope.DIRECT;
                return new ChildField.RecordCompositeField(parentTypeName, name, location, rrt, envelope);
            }
        }

        // @tableMethod on a class-backed parent — DTO-parent shape, produces RecordTableMethodField.
        // Fires BEFORE the @sourceRow branch because both directives may coexist (their roles
        // are complementary: @sourceRow provides the batch-key lifter; @tableMethod provides the
        // developer's static jOOQ table method). When @sourceRow is absent, the parent must be
        // backed by a jOOQ TableRecord so the FK source-key can be auto-derived from the catalog.
        if (fieldDef.hasAppliedDirective(DIR_TABLE_METHOD)) {
            warnIfSplitQueryOnRecordParent(fieldDef, parentTypeName, name, location);
            var tableMethodResolved = tableMethodResolver.resolve(parentTypeName, fieldDef, /*isRoot=*/false);
            if (tableMethodResolved instanceof TableMethodDirectiveResolver.Resolved.Rejected r) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, r.rejection());
            }
            var tmTb = (TableMethodDirectiveResolver.Resolved.TableBound) tableMethodResolved;
            var tbReturn = tmTb.returnType();
            String targetTableName = tbReturn.table().tableName();
            String rawTypeName0 = baseTypeName(fieldDef);
            String elementTypeName0 = ctx.isConnectionType(rawTypeName0)
                ? ctx.connectionElementTypeName(rawTypeName0) : rawTypeName0;

            SourceKey sourceKey;
            LoaderRegistration loaderRegistration;
            List<JoinStep> joinPath;
            ParentCorrelation.OnLiftedSlots lifted = null;
            if (fieldDef.hasAppliedDirective(DIR_SOURCE_ROW)) {
                var sr = sourceRowResolver.resolve(parentTypeName, fieldDef, parentResultType, elementTypeName0);
                if (sr instanceof SourceRowDirectiveResolver.Resolved.Rejected rj) {
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef, rj.rejection());
                }
                var ok = (SourceRowDirectiveResolver.Resolved.Ok) sr;
                sourceKey = ok.sourceKey();
                loaderRegistration = ok.loaderRegistration();
                joinPath = ok.joinPath();
                lifted = ok.lifted();
            } else {
                String parentSqlTableName = parentResultType instanceof GraphitronType.JooqTableRecordType jtr
                        && jtr.table() != null
                    ? jtr.table().tableName() : null;
                var tmPath = ctx.parsePath(fieldDef, name, parentSqlTableName, targetTableName, tbReturn.table(), buildWrapper(fieldDef).isList());
                if (tmPath.hasError()) {
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(tmPath.errorMessage()));
                }
                var fkSource = deriveFkRecordParentSource(tmPath.elements(), parentResultType, tbReturn);
                if (fkSource == null) {
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(
                        "@tableMethod on a record-backed parent requires either a typed jOOQ TableRecord backing "
                        + "(so the FK to the @tableMethod return-type table can be auto-derived from the catalog), "
                        + "or @sourceRow(className: ..., method: ...) to lift the batch key manually. Parent '"
                        + parentTypeName + "' has neither."));
                }
                sourceKey = fkSource.sourceKey();
                loaderRegistration = fkSource.loaderRegistration();
                joinPath = tmPath.elements();
            }

            if (!joinPath.isEmpty() && joinPath.getLast() instanceof JoinStep.Hop lastFk
                && lastFk.on() instanceof On.ColumnPairs
                && !lastFk.targetTable().sameTable(targetTableName)) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(
                    "@tableMethod @reference path: last hop lands on '" + lastFk.targetTable().tableName()
                    + "' but @tableMethod's return type is bound to table '" + targetTableName + "'"));
            }
            var capturedJoinPath = joinPath;
            var capturedSourceKey = sourceKey;
            var capturedLoaderRegistration = loaderRegistration;
            // class-backed-parent carrier: the surface SDL parent has no @table binding, so a
            // condition-join (or hop-0-filter) first hop has no parent table to anchor the source
            // argument. parentTable=null routes the parent-anchor (OnParentJoin) arm to AuthorError,
            // mirroring RecordTableField / RecordLookupTableField. Filter-less FK-derived
            // first hops produce ParentCorrelation.OnFkSlots and don't consult parentTable; the
            // @sourceRow leaf-PK shape arrives pre-resolved as OnLiftedSlots (R431).
            var rtmPcResolution = lifted != null
                ? new BuildContext.ParentCorrelationResolution.Resolved(lifted)
                : ctx.buildParentCorrelation(joinPath, /* parentTable= */ null);
            if (rtmPcResolution instanceof BuildContext.ParentCorrelationResolution.AuthorError e) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(e.message()));
            }
            var rtmParentCorrelation = ((BuildContext.ParentCorrelationResolution.Resolved) rtmPcResolution).correlation();
            return buildMethodBackedWithChannel(tbReturn, tmTb.method(),
                parentTypeName, name, location, fieldDef,
                ch -> new ChildField.RecordTableMethodField(parentTypeName, name, location, tbReturn,
                    capturedJoinPath, tmTb.method(), capturedSourceKey, capturedLoaderRegistration, ch,
                    rtmParentCorrelation));
        }

        // @sourceRow is owned by its dedicated resolver from this point onward: the resolver
        // validates the parent shape, the directive payload, the lifter's signature, and the
        // @reference composition; non-table returns surface a directive-specific rejection here
        // rather than being silently dropped by the PropertyField / RecordField branches below.
        if (fieldDef.hasAppliedDirective(DIR_SOURCE_ROW)) {
            // @splitQuery on a @sourceRow class-backed-parent field is structurally redundant: the
            // lifter-keyed DataLoader already opens a new scope. Fire the advisory before the
            // resolver's rejection guards so an unrelated invariant failure (bad lifter signature,
            // wrong arity, missing parent backing class, etc.) doesn't suppress it ; the developer
            // who wrote both directives needs to see both diagnostics, not just whichever fires
            // first.
            warnIfSplitQueryOnRecordParent(fieldDef, parentTypeName, name, location);
            String rawTypeName = baseTypeName(fieldDef);
            String elementTypeName = ctx.isConnectionType(rawTypeName) ? ctx.connectionElementTypeName(rawTypeName) : rawTypeName;
            var sourceRowResult = sourceRowResolver.resolve(parentTypeName, fieldDef, parentResultType, elementTypeName);
            if (sourceRowResult instanceof SourceRowDirectiveResolver.Resolved.Rejected rj) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, rj.rejection());
            }
            var ok = (SourceRowDirectiveResolver.Resolved.Ok) sourceRowResult;
            var components = resolveTableFieldComponents(fieldDef, ok.tbReturnType().table(), elementTypeName,
                buildNodeIdArgPlan(fieldDef, ok.tbReturnType().table()));
            if (components instanceof TableFieldComponents.Rejected rj) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, rj.rejection());
            }
            var tfc = (TableFieldComponents.Ok) components;
            // joinPath: empty + OnLiftedSlots for LifterLeafKeyed (no @reference; R431), the
            // resolved FK chain for LifterPathKeyed (@reference present). The resolver already
            // constructs the right shape and surfaces it as ok.joinPath() / ok.lifted().
            List<JoinStep> joinPath = ok.joinPath();
            // class-backed-parent carriers: the surface SDL parent type has no @table binding, so a
            // condition-join first hop has no parent table to anchor against and routes to
            // AuthorError. The FK-derived arm produces ParentCorrelation.OnFkSlots and doesn't
            // consult parentTable; the leaf-PK shape arrives pre-resolved as OnLiftedSlots.
            var srPcResolution = ok.lifted() != null
                ? new BuildContext.ParentCorrelationResolution.Resolved(ok.lifted())
                : ctx.buildParentCorrelation(joinPath, /* parentTable= */ null);
            if (srPcResolution instanceof BuildContext.ParentCorrelationResolution.AuthorError e) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(e.message()));
            }
            var srParentCorrelation = ((BuildContext.ParentCorrelationResolution.Resolved) srPcResolution).correlation();
            if (hasLookupKeyAnywhere(fieldDef)) {
                return new RecordLookupTableField(parentTypeName, name, location, ok.tbReturnType(), joinPath,
                    tfc.filters(), tfc.orderBy(), tfc.pagination(), ok.sourceKey(), ok.loaderRegistration(), tfc.lookupMapping(),
                    srParentCorrelation);
            }
            return new RecordTableField(parentTypeName, name, location, ok.tbReturnType(), joinPath,
                tfc.filters(), tfc.orderBy(), tfc.pagination(), ok.sourceKey(), ok.loaderRegistration(),
                srParentCorrelation);
        }

        if (fieldDef.hasAppliedDirective(DIR_SERVICE)) {
            var resolved = serviceResolver.resolve(parentTypeName, fieldDef, List.of());
            if (resolved instanceof ServiceDirectiveResolver.Resolved.Rejected r) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, r.rejection());
            }
            if (resolved instanceof ServiceDirectiveResolver.Resolved.ErrorsLifted e) {
                return e.field();
            }
            var servicePath = ctx.parsePath(fieldDef, name, null, null);
            if (servicePath.hasError()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(servicePath.errorMessage()));
            }
            return switch ((ServiceDirectiveResolver.Resolved.Success) resolved) {
                case ServiceDirectiveResolver.Resolved.TableBound tb -> {
                    var sourced = extractSourced(tb.method());
                    var sk = sourced == null ? null : buildServiceTableSourceKey(sourced, tb.returnType());
                    var lr = sourced == null ? null : buildServiceLoaderRegistration(sourced, tb.returnType());
                    yield buildMethodBackedWithChannel(tb.returnType(), tb.method(),
                        parentTypeName, name, location, fieldDef,
                        ch -> new ServiceTableField(parentTypeName, name, location, tb.returnType(),
                            servicePath.elements(), List.of(), new OrderBySpec.None(), null,
                            tb.method(), sk, lr, ch));
                }
                // @service on a class-backed parent returning scalar/record is DEFERRED:
                // deriving the batch key would require lifting through the parent chain to the
                // rooted @table whose PK provides the key columns, which is its own design
                // problem (parallel to interface-union dispatch).
                case ServiceDirectiveResolver.Resolved.Result r ->
                    new UnclassifiedField(parentTypeName, name, location, fieldDef,
                        Rejection.deferred(
                            "@service on a record-backed parent is not yet supported; the batch key "
                            + "must be lifted through the parent chain to the rooted @table",
                            "service-record-field"));
                case ServiceDirectiveResolver.Resolved.Scalar s ->
                    new UnclassifiedField(parentTypeName, name, location, fieldDef,
                        Rejection.deferred(
                            "@service on a record-backed parent is not yet supported; the batch key "
                            + "must be lifted through the parent chain to the rooted @table",
                            "service-record-field"));
                // R365 route (a) restores polymorphic returns on root @service fields only; a
                // child @service on a class-backed parent returning an interface/union is doubly
                // out of scope (record-backed-parent batch key + polymorphic dispatch).
                case ServiceDirectiveResolver.Resolved.Polymorphic p ->
                    new UnclassifiedField(parentTypeName, name, location, fieldDef,
                        Rejection.deferred(
                            "child @service returning a polymorphic type (interface/union) is not yet supported"
                            + " — route (a) restores it on root @service fields only",
                            "polymorphic-entity-service-return"));
            };
        }

        if (isScalarOrEnum(fieldDef)) {
            String columnName = fieldDef.hasAppliedDirective(DIR_FIELD)
                ? argString(fieldDef, DIR_FIELD, ARG_NAME).orElse(name)
                : name;
            var accessor = resolveRecordAccessor(fieldDef, columnName, parentResultType, parentBackingClass);
            return switch (accessor) {
                case AccessorResolution.Rejected r ->
                    new UnclassifiedField(parentTypeName, name, location, fieldDef,
                        Rejection.accessorMismatch(r.reason()));
                case AccessorResolution.Resolved ok ->
                    new PropertyField(parentTypeName, name, location, columnName,
                        resolveColumnOnJooqTableRecord(columnName, parentResultType), ok);
                case null ->
                    new PropertyField(parentTypeName, name, location, columnName,
                        resolveColumnOnJooqTableRecord(columnName, parentResultType), null);
            };
        }

        // Object return type on a result-mapped parent.
        String rawTypeName = baseTypeName(fieldDef);
        String elementTypeName = ctx.isConnectionType(rawTypeName) ? ctx.connectionElementTypeName(rawTypeName) : rawTypeName;
        String columnName = fieldDef.hasAppliedDirective(DIR_FIELD)
            ? argString(fieldDef, DIR_FIELD, ARG_NAME).orElse(name)
            : name;
        // Typed class-backed parents backed by a jOOQ TableRecord anchor the path's starting table,
        // which is how parsePath validates FK direction on each hop. Without it, multi-hop paths
        // through junction tables (e.g. film → film_actor → actor) flip the first hop's traversal
        // direction and spuriously fail to resolve.
        String parentSqlTableName = parentResultType instanceof GraphitronType.JooqTableRecordType jtr && jtr.table() != null
            ? jtr.table().tableName() : null;
        var resolvedReturnType = ctx.resolveReturnType(elementTypeName, buildWrapper(fieldDef));
        // @splitQuery on a class-backed-parent field with a table-bound return is structurally
        // redundant: the parent-record-keyed DataLoader already opens a new scope. Fire the
        // advisory as soon as we know the return type is table-bound, before the path-error /
        // table-field-components / batch-key rejection guards below; an unrelated rejection
        // shouldn't suppress the redundancy advisory.
        if (resolvedReturnType instanceof ReturnTypeRef.TableBoundReturnType) {
            warnIfSplitQueryOnRecordParent(fieldDef, parentTypeName, name, location);
        }
        String targetSqlTableName = resolvedReturnType instanceof ReturnTypeRef.TableBoundReturnType tbt
            ? tbt.table().tableName() : null;
        TableRef targetTableRef = resolvedReturnType instanceof ReturnTypeRef.TableBoundReturnType tbt
            ? tbt.table() : null;
        var objectPath = ctx.parsePath(fieldDef, name, parentSqlTableName, targetSqlTableName, targetTableRef);
        if (objectPath.hasError()) {
            return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(objectPath.errorMessage()));
        }
        return switch (resolvedReturnType) {
            case ReturnTypeRef.TableBoundReturnType tb -> {
                var components = resolveTableFieldComponents(fieldDef, tb.table(), elementTypeName,
                    buildNodeIdArgPlan(fieldDef, tb.table()));
                if (components instanceof TableFieldComponents.Rejected rj) yield new UnclassifiedField(parentTypeName, name, location, fieldDef, rj.rejection());
                var tfc = (TableFieldComponents.Ok) components;
                boolean isLookup = hasLookupKeyAnywhere(fieldDef);
                String fieldKind = isLookup ? "RecordLookupTableField" : "RecordTableField";
                var resolution = resolveRecordParentSource(name, columnName, tb, objectPath.elements(), parentResultType, fieldKind);
                if (resolution instanceof RecordParentSourceResolution.Rejected rj) {
                    yield new UnclassifiedField(parentTypeName, name, location, fieldDef, rj.rejection());
                }
                var resolved = (RecordParentSourceResolution.Resolved) resolution;
                var resolvedJoinPath = resolved.joinPath();
                // The accessor arm resolves to the hop-less OnLiftedSlots correlation with an
                // empty joinPath (R431); the FK arm derives its correlation from the path.
                // class-backed-parent carriers: see the @sourceRow branch above for the parentTable
                // null rationale. The parent-anchor arm (a condition-join first hop, or any hop-0
                // filter — R450) is the only one that consults parentTable, and it routes to
                // AuthorError without one.
                ParentCorrelation resolvedParentCorrelation;
                if (resolved.lifted() != null) {
                    resolvedParentCorrelation = resolved.lifted();
                } else {
                    var resolvedPcResolution = ctx.buildParentCorrelation(resolvedJoinPath, /* parentTable= */ null);
                    if (resolvedPcResolution instanceof BuildContext.ParentCorrelationResolution.AuthorError e) {
                        yield new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(e.message()));
                    }
                    resolvedParentCorrelation = ((BuildContext.ParentCorrelationResolution.Resolved) resolvedPcResolution).correlation();
                }
                if (isLookup) {
                    yield new RecordLookupTableField(parentTypeName, name, location, tb, resolvedJoinPath, tfc.filters(), tfc.orderBy(), tfc.pagination(),
                        resolved.sourceKey(), resolved.loaderRegistration(), tfc.lookupMapping(),
                        resolvedParentCorrelation);
                }
                yield new RecordTableField(parentTypeName, name, location, tb, resolvedJoinPath, tfc.filters(), tfc.orderBy(), tfc.pagination(),
                    resolved.sourceKey(), resolved.loaderRegistration(),
                    resolvedParentCorrelation);
            }
            case ReturnTypeRef.ResultReturnType r -> recordFieldOrUnclassified(
                fieldDef, parentTypeName, name, location, r, columnName, parentResultType, parentBackingClass);
            case ReturnTypeRef.ScalarReturnType s -> recordFieldOrUnclassified(
                fieldDef, parentTypeName, name, location, s, columnName, parentResultType, parentBackingClass);
            case ReturnTypeRef.PolymorphicReturnType p -> {
                var lift = liftToErrorsField(fieldDef, parentTypeName, p, parentBackingClass);
                if (lift != null) yield lift;
                yield classifyRecordParentPolymorphicChild(fieldDef, parentTypeName, name, location,
                    elementTypeName, p, parentResultType);
            }
        };
    }

    private void warnIfSplitQueryOnRecordParent(GraphQLFieldDefinition fieldDef, String parentTypeName,
            String name, SourceLocation location) {
        if (!fieldDef.hasAppliedDirective(DIR_SPLIT_QUERY)) return;
        // Safe deletion fix: @splitQuery takes no arguments, so removing the token is always
        // computable and never touches an SDL reference (R398).
        var fix = LintFix.deleteBareAppliedDirective(
            fieldDef.getAppliedDirective(DIR_SPLIT_QUERY), "Remove the redundant @splitQuery");
        ctx.addWarning(new BuildWarning.LintFinding(
            parentTypeName + "." + name + ": @splitQuery is redundant on a record-backed parent field; "
            + "the record handoff already opens a new DataLoader-backed scope. The directive will be ignored.",
            location,
            LintRule.SPLITQUERY_REDUNDANT_ON_RECORD_PARENT,
            fix));
    }

    /**
     * Resolves the parent-table {@link ColumnRef} for a {@code PropertyField} or {@code RecordField}
     * when the parent is a {@link GraphitronType.JooqTableRecordType} whose backing jOOQ table
     * resolves in the catalog and contains a column with the given SQL name. Returns {@code null}
     * for all other {@link GraphitronType.ResultType} variants, when the {@code JooqTableRecordType}
     * has a {@code null} {@code TableRef} (backing class not loaded at build time), or when the
     * SQL column name doesn't match any column on the resolved table.
     *
     * <p>A non-null result unlocks typed {@code Tables.X.COL} emission in
     * {@link no.sikt.graphitron.rewrite.generators.TypeFetcherGenerator};
     * a null result falls back to untyped {@code DSL.field("col_name")} access.
     */
    private ColumnRef resolveColumnOnJooqTableRecord(String columnName, GraphitronType.ResultType parentResultType) {
        if (!(parentResultType instanceof GraphitronType.JooqTableRecordType jtrt)) return null;
        if (jtrt.table() == null) return null;
        return svc.resolveColumnInTable(columnName, jtrt.table().tableName()).orElse(null);
    }

    /**
     * Resolves an SDL output field's accessor against the parent's backing Java class. Returns
     * {@code null} when reflective accessor resolution does not apply: jOOQ-record-backed parents
     * ({@link GraphitronType.JooqTableRecordType} / {@link GraphitronType.JooqRecordType}) reach
     * their values through column projection, not bean-style accessors;
     * {@link GraphitronType.PojoResultType} with a null {@code fqClassName} falls through to
     * graphql-java's {@code PropertyDataFetcher}. Returns a non-null
     * {@link AccessorResolution.Resolved} or {@link AccessorResolution.Rejected} only when the
     * parent is a {@link GraphitronType.JavaRecordType} or a {@link GraphitronType.PojoResultType}
     * with non-null {@code fqClassName} (the {@code parentBackingClass} threaded from
     * {@link TypeBuilder#recordBackingClasses()}).
     */
    private AccessorResolution resolveRecordAccessor(GraphQLFieldDefinition fieldDef, String accessorBaseName,
            GraphitronType.ResultType parentResultType, Class<?> parentBackingClass) {
        if (parentBackingClass == null) return null;
        if (!(parentResultType instanceof GraphitronType.JavaRecordType
                || parentResultType instanceof GraphitronType.PojoResultType)) return null;
        var order = parentResultType instanceof GraphitronType.JavaRecordType
            ? ClassAccessorResolver.CandidateOrder.RECORD_FIRST
            : ClassAccessorResolver.CandidateOrder.POJO_FIRST;
        java.lang.reflect.Type expectedReturn = mapGraphQLTypeToReflectType(fieldDef.getType());
        ClassAccessorResolver.ParamShape expectedArgs = mapArgsToParamShape(fieldDef);
        return ClassAccessorResolver.resolve(parentBackingClass, accessorBaseName,
            expectedReturn, expectedArgs, order);
    }

    /**
     * Object-arm helper for the class-backed parent paths: resolves the accessor and
     * routes a {@link AccessorResolution.Rejected} through {@link UnclassifiedField} so the
     * {@link RecordField} accessor slot only ever carries a {@link AccessorResolution.Resolved} or
     * {@code null}. Mirrors the scalar-arm switch above.
     */
    private GraphitronField recordFieldOrUnclassified(GraphQLFieldDefinition fieldDef,
            String parentTypeName, String name, SourceLocation location, ReturnTypeRef returnType,
            String columnName, GraphitronType.ResultType parentResultType,
            Class<?> parentBackingClass) {
        var accessor = resolveRecordAccessor(fieldDef, columnName, parentResultType, parentBackingClass);
        return switch (accessor) {
            case AccessorResolution.Rejected r ->
                new UnclassifiedField(parentTypeName, name, location, fieldDef,
                    Rejection.accessorMismatch(r.reason()));
            case AccessorResolution.Resolved ok ->
                new RecordField(parentTypeName, name, location, returnType, columnName,
                    resolveColumnOnJooqTableRecord(columnName, parentResultType), ok);
            case null ->
                new RecordField(parentTypeName, name, location, returnType, columnName,
                    resolveColumnOnJooqTableRecord(columnName, parentResultType), null);
        };
    }

    private ClassAccessorResolver.ParamShape mapArgsToParamShape(GraphQLFieldDefinition fieldDef) {
        var args = fieldDef.getArguments();
        var argShapes = new ArrayList<ClassAccessorResolver.ArgShape>(args.size());
        for (GraphQLArgument arg : args) {
            argShapes.add(new ClassAccessorResolver.ArgShape(arg.getName(),
                mapGraphQLTypeToReflectType(arg.getType())));
        }
        return new ClassAccessorResolver.PerArgument(argShapes);
    }

    /**
     * Maps a graphql-java type (input or output) to a {@link java.lang.reflect.Type} for
     * resolver-side assignability checks. Scalars route through {@code ctx.types}'s
     * {@link GraphitronType.ScalarType} so the classifier's resolution is the single source of
     * truth (the {@code Object} fallback only fires for non-classified types or classes missing
     * from the codegen classloader). Class-backed object types map to their backing
     * class; everything else falls back to {@link Object} (assignability accepts any actual
     * type, so the resolver matches by name and parameter shape only — sufficient for the
     * user-facing-bug case which is the scalar return-type mismatch).
     *
     * <p>List wrappers map to {@code java.util.List} regardless of element type — generics are
     * erased to raw classes for the assignability check.
     */
    private java.lang.reflect.Type mapGraphQLTypeToReflectType(GraphQLType t) {
        GraphQLType current = t;
        int listDepth = 0;
        while (true) {
            if (current instanceof GraphQLNonNull nn) { current = nn.getWrappedType(); continue; }
            if (current instanceof GraphQLList l) { current = l.getWrappedType(); listDepth++; continue; }
            break;
        }
        if (listDepth > 0) return java.util.List.class;
        if (current instanceof GraphQLScalarType s && ctx.types != null) {
            // R317 slice 3e — registry-free look-ahead at the scalar's verdict for the assignability
            // check; resolved at this field's edge, not read from the in-progress registry.
            var classified = typeBuilder.lookAheadVerdict(s.getName());
            if (classified instanceof GraphitronType.ScalarType st
                    && st.resolution().javaType() instanceof no.sikt.graphitron.javapoet.ClassName cn) {
                try { return Class.forName(cn.reflectionName(), false, ctx.codegenLoader()); }
                catch (ClassNotFoundException e) { return Object.class; }
            }
            return Object.class;
        }
        if (current instanceof GraphQLNamedType nt && ctx.types != null) {
            // R317 slice 3e — registry-free look-ahead at the result-backed target (reproduces the
            // ctx.types verdict, carrier included) so the assignability check does not depend on the
            // target having been registered.
            var classified = typeBuilder.lookAheadVerdict(nt.getName());
            String fqcn = switch (classified) {
                case GraphitronType.PojoResultType prt -> prt.fqClassName();
                case GraphitronType.JavaRecordType jrt -> jrt.fqClassName();
                case GraphitronType.JooqTableRecordType jtr -> jtr.fqClassName();
                case GraphitronType.JooqRecordType jr -> jr.fqClassName();
                case null, default -> null;
            };
            if (fqcn != null) {
                try { return Class.forName(fqcn, false, ctx.codegenLoader()); }
                catch (ClassNotFoundException e) { return Object.class; }
            }
        }
        return Object.class;
    }

    /**
     * Builder-internal pair returned by {@link #deriveSplitQuerySource}: the
     * {@link SourceKey} + {@link LoaderRegistration} the {@code SplitTableField} /
     * {@code SplitLookupTableField} constructors take. Pairs the two projections so the producer
     * computes them in one place instead of via two separate calls.
     */
    private record SplitQuerySource(SourceKey sourceKey, LoaderRegistration loaderRegistration) {}

    /**
     * Derives the {@link SourceKey} + {@link LoaderRegistration} for a {@code @table}-parent
     * {@code @splitQuery} field. The batch grain (the {@code parentInput} VALUES columns) is read
     * straight off the already-built step-0 {@code correlation} arm via
     * {@link no.sikt.graphitron.rewrite.model.ParentCorrelation#parentKeyColumns()} (R450), so the
     * grain and the correlation topology are one decision made at one producer:
     *
     * <ul>
     *   <li>{@code OnFkSlots} (filter-less FK first hop) keys by the hop's source-side columns.
     *       For Single (parent-holds-FK) these are the parent's FK columns; for List
     *       (child-holds-FK) {@link BuildContext#resolveFkSlots} orients the slot so the source
     *       side is the parent's <em>referenced</em> columns, which may be a non-PK unique key.
     *       Either way they are exactly the parent columns the emitter's correlation predicate
     *       pairs against, so the VALUES columns and the predicate agree (keying off the parent PK
     *       when the FK references a non-PK column made them disagree and silently returned zero
     *       rows for every parent — R338).</li>
     *   <li>{@code OnParentJoin} (a condition-join first hop, or any hop-0 {@code filter()} — R450)
     *       keys by the parent's own PK columns: the hop-0 predicate reads arbitrary parent
     *       columns, so the parent's identity is part of the fetch's inputs and a coarser key would
     *       hand two distinct parents one shared verdict. The emitter joins {@code parentInput} to
     *       the parent alias on these PK columns.</li>
     *   <li>{@code OnLateralArgs} (a lateral routine first hop — R435) keys on the routine's
     *       column-bound inputs; a routine result is a pure function of its inputs, and the
     *       emitter reads those columns off {@code parentInput} directly inside the call
     *       expression with no correlation JOIN predicate.</li>
     * </ul>
     *
     * <p>Because the key is a projection off the arm rather than a re-read of {@code path.get(0)},
     * it is hop-count agnostic (a multi-hop single-cardinality path keys by the first hop and the
     * emitter bridges the rest) and cannot drift from the topology: adding a second
     * {@code filter() != null} branch here would be two producers evaluating one predicate with
     * nothing binding them, the drift this method's own history (R338) warns against. The
     * empty-joinPath standalone shape carries a {@code null} correlation and falls back to the
     * parent PK.
     *
     * <p>The {@link SourceKey} projection is {@link SourceKey.Wrap.Row} +
     * {@link SourceKey.Reader.ColumnRead} (catalog-FK column read on the parent); the
     * {@link LoaderRegistration} is {@link LoaderRegistration.Container#POSITIONAL_LIST} +
     * {@link LoaderRegistration.Dispatch#LOAD_ONE} (the {@code @splitQuery} loader contract).
     *
     * <p>Sibling of {@link #deriveFkRecordParentSource} — that helper is for record parents and
     * unconditionally reads {@code fk.sourceSideColumns()} because record parents never batch by
     * parent PK.
     */
    private static SplitQuerySource deriveSplitQuerySource(
            no.sikt.graphitron.rewrite.model.ParentCorrelation correlation,
            TableRef parentTable, ReturnTypeRef.TableBoundReturnType returnType) {
        boolean isList = returnType.wrapper().isList();
        // R450: the batch grain is a pure projection off the step-0 correlation arm
        // (ParentCorrelation.parentKeyColumns) — FK-slot columns for OnFkSlots, parent PK for the
        // parent-anchor OnParentJoin arm, routine inputs for OnLateralArgs. Reading it here rather
        // than re-deriving from the path makes "parent-PK grain iff parent-anchor topology"
        // structurally impossible to violate: a hop-0 filter lands OnParentJoin at the single
        // producer (buildParentCorrelation), which is exactly the column set the emitter's
        // correlation predicate pairs against. The empty-joinPath standalone shape carries a null
        // correlation and falls back to the parent PK.
        List<ColumnRef> entryColumns = correlation != null
            ? correlation.parentKeyColumns()
            : parentTable.primaryKeyColumns();
        SourceKey sourceKey = new SourceKey(
            entryColumns,
            new SourceKey.Wrap.Row(),
            isList ? SourceKey.Cardinality.MANY : SourceKey.Cardinality.ONE,
            new SourceKey.Reader.ColumnRead());
        LoaderRegistration loaderRegistration = new LoaderRegistration(
            isList,
            LoaderRegistration.Container.POSITIONAL_LIST,
            LoaderRegistration.Dispatch.LOAD_ONE);
        return new SplitQuerySource(sourceKey, loaderRegistration);
    }

    /**
     * Builds the parent-side {@link SourceKey} for a table-backed multi-table polymorphic child
     * field ({@link InterfaceField} / {@link UnionField} on a {@code @table} parent). The
     * parent's identity is its PK, read directly off {@code env.getSource()} (a typed
     * {@code TableRecord}), so the projection is {@link SourceKey.Wrap.Row} +
     * {@link SourceKey.Reader.ColumnRead} + {@link SourceKey.Cardinality#ONE} with no traversal.
     * Mirrors the polymorphic-Row arm of the deleted {@code SourceKeyResolver
     * .resolveRecordParentForPolymorphic} (the parent IS the source — no separate target
     * table).
     */
    private static no.sikt.graphitron.rewrite.model.SourceKey buildTableBackedPolymorphicParentSourceKey(
            List<ColumnRef> pkCols) {
        return new no.sikt.graphitron.rewrite.model.SourceKey(
            pkCols,
            new SourceKey.Wrap.Row(),
            SourceKey.Cardinality.ONE,
            new SourceKey.Reader.ColumnRead());
    }

    /**
     * Derives the FK-based {@link SourceKey} + {@link LoaderRegistration} for a record-parent
     * batched field ({@link no.sikt.graphitron.rewrite.model.ChildField.RecordTableField},
     * {@link no.sikt.graphitron.rewrite.model.ChildField.RecordLookupTableField}) by reading the
     * FK source columns from the join path's first FK-derived {@link JoinStep.Hop}.
     *
     * <p>Returns {@code null} (→ caller falls through to typed-accessor derivation) when the join
     * path is empty or its first step is not FK-derived.
     *
     * <p>Otherwise returns the projection: {@link SourceKey.Wrap.Row} +
     * {@link SourceKey.Reader.ColumnRead} (the catalog-FK row-keyed shape) with
     * {@code fkJoin.sourceSideColumns()} as the entry columns, and
     * {@link LoaderRegistration.Container#POSITIONAL_LIST} +
     * {@link LoaderRegistration.Dispatch#LOAD_ONE}.
     * {@code GeneratorUtils.buildFkRowKey} forks per parent
     * {@link GraphitronType.ResultType} (jOOQ table record / jOOQ record / Java record / typed
     * POJO) to extract scalar values and build the key via {@code DSL.row(...)}.
     */
    private static RecordParentSource deriveFkRecordParentSource(
            List<JoinStep> joinPath, GraphitronType.ResultType parentResultType,
            ReturnTypeRef.TableBoundReturnType tb) {
        if (joinPath.isEmpty() || !(joinPath.get(0) instanceof JoinStep.Hop hop
                && hop.on() instanceof On.ColumnPairs fkJoin)) {
            return null;
        }
        boolean isList = tb.wrapper().isList();
        SourceKey sourceKey = new SourceKey(
            fkJoin.sourceSideColumns(),
            new SourceKey.Wrap.Row(),
            isList ? SourceKey.Cardinality.MANY : SourceKey.Cardinality.ONE,
            new SourceKey.Reader.ColumnRead());
        LoaderRegistration loaderRegistration = new LoaderRegistration(
            isList,
            LoaderRegistration.Container.POSITIONAL_LIST,
            LoaderRegistration.Dispatch.LOAD_ONE);
        return new RecordParentSource(sourceKey, loaderRegistration);
    }

    /**
     * Builder-internal pair returned by {@link #deriveFkRecordParentSource}: the FK-arm or
     * accessor-arm projection of a class-backed-parent table-bound field's source-side
     * metadata. Pairs the two values so the producer computes both in one place.
     */
    private record RecordParentSource(SourceKey sourceKey, LoaderRegistration loaderRegistration) {}

    /**
     * Outcome of {@link #resolveRecordParentSource} for a class-backed-parent table-bound
     * child field. Two arms; the caller exhausts them with a sealed switch and either projects
     * the resolved {@link SourceKey} + {@link LoaderRegistration} into {@link RecordTableField} /
     * {@link RecordLookupTableField}, or surfaces the rejection as
     * {@link GraphitronField.UnclassifiedField}. Builder-internal sealed result per the
     * {@code development-principles.adoc} rule on {@code Builder-step results are sealed}.
     */
    private sealed interface RecordParentSourceResolution {
        /**
         * {@code joinPath} is the FK-derived original path on the FK arm; on the auto-derived
         * accessor arm it is replaced with {@code [liftedHop]} (mirroring the {@code @sourceRow}
         * leaf-PK call-site convention) so {@link SplitRowsMethodEmitter}'s prelude reads the
         * target-side columns through {@link no.sikt.graphitron.rewrite.model.HasSlots} uniformly.
         */
        record Resolved(SourceKey sourceKey, LoaderRegistration loaderRegistration, List<JoinStep> joinPath,
                        ParentCorrelation.OnLiftedSlots lifted) implements RecordParentSourceResolution {}
        record Rejected(Rejection rejection) implements RecordParentSourceResolution {}
    }

    /**
     * Resolves the {@link SourceKey} + {@link LoaderRegistration} for a class-backed-parent
     * table-bound child field. Tries the FK derivation first (via
     * {@link #deriveFkRecordParentSource}); on null, attempts the typed-accessor derivation; on
     * null again, returns the three-option AUTHOR_ERROR rejection. The helper is shared between
     * the {@link RecordTableField} and {@link RecordLookupTableField} branches;
     * {@code fieldKindLabel} parameterises only the leading clause of the rejection.
     */
    private RecordParentSourceResolution resolveRecordParentSource(
            String fieldName, String accessorBaseName, ReturnTypeRef.TableBoundReturnType tb,
            List<JoinStep> joinPath, GraphitronType.ResultType parentResultType,
            String fieldKindLabel) {
        var fkSource = deriveFkRecordParentSource(joinPath, parentResultType, tb);
        if (fkSource != null) {
            return new RecordParentSourceResolution.Resolved(
                fkSource.sourceKey(), fkSource.loaderRegistration(), joinPath, null);
        }

        var derived = deriveAccessorRecordParentSource(fieldName, accessorBaseName, tb, parentResultType);
        return switch (derived) {
            case AccessorDerivation.Ok ok -> new RecordParentSourceResolution.Resolved(
                ok.sourceKey(), ok.loaderRegistration(), List.of(), ok.lifted());
            case AccessorDerivation.Ambiguous a -> new RecordParentSourceResolution.Rejected(
                Rejection.structural(a.message()));
            case AccessorDerivation.CardinalityMismatch m -> new RecordParentSourceResolution.Rejected(
                Rejection.structural(m.message()));
            case AccessorDerivation.None _ -> new RecordParentSourceResolution.Rejected(
                Rejection.structural(fieldKindLabel + " on a free-form DTO parent requires a typed accessor or @sourceRow to lift the batch key; the catalog has no FK metadata for the parent class. Either expose a typed accessor on the parent returning List<...Record>, Set<...Record>, or ...Record (where ...Record is the element type's jOOQ TableRecord); or add @sourceRow(className: ..., method: ...) optionally composed with @reference; or back the parent with a typed jOOQ TableRecord so the FK can be derived"));
        };
    }

    /**
     * Outcome of {@link #deriveAccessorRecordParentSource}. Four arms, all builder-internal:
     * {@link Ok} when reflection finds exactly one matching accessor with aligned cardinality;
     * {@link None} when no matching accessor exists at all (callers fall through to the existing
     * three-option AUTHOR_ERROR); {@link Ambiguous} when two or more accessors match the
     * name-and-shape rule; {@link CardinalityMismatch} when at least one accessor matches name
     * + element table but the field/accessor cardinalities don't align (and no other accessor
     * matched cleanly). The {@link Ok} arm carries the resolved {@link SourceKey} +
     * {@link LoaderRegistration} pair plus the hop-less {@link ParentCorrelation.OnLiftedSlots}
     * correlation (R431) the orchestrator threads into the surrounding
     * {@link RecordParentSourceResolution.Resolved}.
     */
    private sealed interface AccessorDerivation {
        record Ok(SourceKey sourceKey, LoaderRegistration loaderRegistration,
                  ParentCorrelation.OnLiftedSlots lifted) implements AccessorDerivation {}
        record None() implements AccessorDerivation {}
        record Ambiguous(String message) implements AccessorDerivation {}
        record CardinalityMismatch(String message) implements AccessorDerivation {}
    }

    /**
     * Per-method match outcome inside {@link #deriveAccessorRecordParentSource}'s reflection
     * loop. Cardinality alignment is part of the match rule, not a downstream filter, so the
     * reduction over the collected matches is a single pass over identity rather than a
     * predicate-and-filter chain.
     */
    private sealed interface AccessorMatch {
        record Single(java.lang.reflect.Method method, Class<?> elementClass) implements AccessorMatch {}
        record Many(java.lang.reflect.Method method, Class<?> elementClass) implements AccessorMatch {}
        record CardinalityMismatch(String message) implements AccessorMatch {}
    }

    private AccessorDerivation deriveAccessorRecordParentSource(
            String fieldName, String accessorBaseName, ReturnTypeRef.TableBoundReturnType tb,
            GraphitronType.ResultType parentResultType) {
        // Resolve parent backing class via sealed switch over GraphitronType.ResultType's four
        // permits. JooqRecordType / JooqTableRecordType participate in the FK-derivation path
        // and never reach this helper with the FK derivation having returned non-null; on the
        // null-FK path they have no typed accessor mapping the field's @table return, so they
        // fall through to None.
        String parentFqClassName = switch (parentResultType) {
            case GraphitronType.JavaRecordType jrt -> jrt.fqClassName();
            case GraphitronType.PojoResultType prt -> prt.fqClassName();
            case GraphitronType.JooqRecordType _, GraphitronType.JooqTableRecordType _ -> null;
        };
        if (parentFqClassName == null) return new AccessorDerivation.None();

        Class<?> parentClass;
        try {
            parentClass = Class.forName(parentFqClassName, false, ctx.codegenLoader());
        } catch (ClassNotFoundException e) {
            return new AccessorDerivation.None();
        }

        boolean fieldIsList = tb.wrapper().isList();
        TableRef expectedTable = tb.table();

        List<AccessorMatch> matches = collectAccessorMatches(parentClass, fieldName, accessorBaseName,
            fieldIsList, expectedTable);

        // Reduction over the collected matches.
        List<AccessorMatch> resolvable = matches.stream()
            .filter(am -> am instanceof AccessorMatch.Single || am instanceof AccessorMatch.Many)
            .toList();
        if (resolvable.size() > 1) {
            String candidates = resolvable.stream()
                .map(am -> switch (am) {
                    case AccessorMatch.Single s -> s.method().getName();
                    case AccessorMatch.Many mm -> mm.method().getName();
                    case AccessorMatch.CardinalityMismatch _ -> ""; // filtered above
                })
                .collect(Collectors.joining(", "));
            return new AccessorDerivation.Ambiguous(
                "record-backed parent '" + parentFqClassName + "' exposes more than one typed accessor "
                + "returning '" + expectedTable.tableName() + "' records: [" + candidates + "]. Disambiguate "
                + "by adding @sourceRow(...) on this field.");
        }
        if (resolvable.isEmpty()) {
            List<AccessorMatch.CardinalityMismatch> cmms = matches.stream()
                .filter(am -> am instanceof AccessorMatch.CardinalityMismatch)
                .map(am -> (AccessorMatch.CardinalityMismatch) am)
                .toList();
            if (cmms.isEmpty()) return new AccessorDerivation.None();
            String joined = cmms.stream().map(AccessorMatch.CardinalityMismatch::message)
                .collect(Collectors.joining("; "));
            return new AccessorDerivation.CardinalityMismatch(joined);
        }

        // Exactly one resolvable match; build the corresponding SourceKey + LoaderRegistration
        // pair. The accessor's DataLoader key tuple equals the element table's PK tuple by
        // classifier construction (Invariant Acc-1) — the hop-less OnLiftedSlots correlation
        // (R431). Wrap is Record (the accessor returns a TableRecord, projected
        // as RecordN<...> keys at emit time); the container axis is always POSITIONAL_LIST and
        // the dispatch fork is Single → LOAD_ONE, Many → LOAD_MANY (the loadMany contract that
        // emits one Record per element-PK).
        AccessorMatch only = resolvable.get(0);
        boolean accessorIsMany = only instanceof AccessorMatch.Many;
        java.lang.reflect.Method accessorMethod = switch (only) {
            case AccessorMatch.Single s -> s.method();
            case AccessorMatch.Many mm -> mm.method();
            case AccessorMatch.CardinalityMismatch _ -> throw new IllegalStateException("filtered above");
        };
        Class<?> accessorElementClass = switch (only) {
            case AccessorMatch.Single s -> s.elementClass();
            case AccessorMatch.Many mm -> mm.elementClass();
            case AccessorMatch.CardinalityMismatch _ -> throw new IllegalStateException("filtered above");
        };
        var ref = new AccessorRef(
            ClassName.get(parentClass),
            accessorMethod.getName(),
            ClassName.get(accessorElementClass));
        SourceKey sourceKey = new SourceKey(
            expectedTable.primaryKeyColumns(),
            new SourceKey.Wrap.Record(),
            accessorIsMany ? SourceKey.Cardinality.MANY : SourceKey.Cardinality.ONE,
            new SourceKey.Reader.AccessorCall(ref));
        LoaderRegistration loaderRegistration = new LoaderRegistration(
            false,
            LoaderRegistration.Container.POSITIONAL_LIST,
            accessorIsMany ? LoaderRegistration.Dispatch.LOAD_MANY : LoaderRegistration.Dispatch.LOAD_ONE);
        return new AccessorDerivation.Ok(sourceKey, loaderRegistration,
            new ParentCorrelation.OnLiftedSlots(expectedTable, expectedTable.primaryKeyColumns()));
    }

    /**
     * Iterates {@code parentClass}'s public zero-arg non-bridge non-synthetic instance accessors
     * named after {@code accessorBaseName} (or {@code getX} / {@code isX} with the same base),
     * classifies each return type to a {@link ReturnAxis} of {@code X}, {@code List<X>}, or
     * {@code Set<X>} for some concrete {@code X extends TableRecord}, and reports the cardinality
     * alignment against {@code fieldIsList}. {@code accessorBaseName} is the value of
     * {@code @field(name:)} when the directive is present on the child field, else the GraphQL
     * field name; this restores symmetry with the scalar/result branch on the same parent shape
     * which already threads the directive value through {@link #resolveRecordAccessor}.
     * {@code fieldName} is retained for the cardinality-mismatch text, which quotes the SDL
     * field name rather than the accessor base. When {@code expectedTable} is non-null, only
     * matches whose element table denotes the same table by reified jOOQ class identity
     * ({@link TableRef#denotesSameTableAs}) are kept (table-bound case). Both operands are
     * catalog-constructed and carry a real {@code tableClass}: the accessor side resolves through
     * {@code svc.resolveTableByRecordClass} (record-class identity in the catalog) and the expected
     * side is {@code tb.table()}, so the compare is identity-vs-identity and a schema-qualified
     * {@code @table} echo matches jOOQ's unqualified canonical name across colliding schemas. When
     * {@code null}, every {@code TableRecord} element matches and the caller (polymorphic-hub case)
     * discovers the hub from the unique surviving match.
     *
     * <p>Shared between {@link #deriveAccessorRecordParentSource} (table-bound, expected-table
     * check) and {@link #derivePolymorphicHubSource} (polymorphic, hub discovery). The reduction
     * step differs across callers; only the per-method match logic is shared.
     */
    private List<AccessorMatch> collectAccessorMatches(Class<?> parentClass, String fieldName,
            String accessorBaseName, boolean fieldIsList, TableRef expectedTable) {
        List<AccessorMatch> matches = new ArrayList<>();
        // R461: the per-member name matching, is-gate, and member filter come from the shared
        // candidate enumeration so the name rules cannot drift from the other reductions. The
        // record-source reduction accepts zero-arg methods only (an env-taking / per-argument /
        // public-field candidate is unrepresentable in an AccessorCall), expressed by requesting the
        // PER_ARGUMENT_METHOD kind with a zero-argument expected shape. Candidate order is irrelevant
        // here (all matches are collected, then reduced by identity), so it derives from the class.
        var candidates = ClassAccessorResolver.enumerate(parentClass, accessorBaseName,
            ClassAccessorResolver.forBackingClass(parentClass),
            java.util.EnumSet.of(ClassAccessorResolver.CandidateKind.PER_ARGUMENT_METHOD),
            new ClassAccessorResolver.PerArgument(List.of()));
        for (var candidate : candidates) {
            if (!(candidate instanceof ClassAccessorResolver.Candidate.Accepted accepted)) continue;
            if (!(accepted.member() instanceof java.lang.reflect.Method m)) continue;

            String mName = m.getName();
            ReturnAxis axis = classifyAccessorReturn(m.getGenericReturnType());
            if (axis == null) continue;

            var elementTableRef = svc.resolveTableByRecordClass(axis.elementClass());
            if (elementTableRef.isEmpty()) continue;
            if (expectedTable != null && !elementTableRef.get().denotesSameTableAs(expectedTable)) continue;

            if (fieldIsList) {
                if (axis.container() == ServiceCatalog.ContainerKind.LIST
                        || axis.container() == ServiceCatalog.ContainerKind.SET) {
                    matches.add(new AccessorMatch.Many(m, axis.elementClass()));
                } else {
                    matches.add(new AccessorMatch.CardinalityMismatch(
                        "list field '" + fieldName + "' has accessor '" + mName
                        + "' returning a single record; expected List<" + axis.elementClass().getSimpleName()
                        + "> or Set<" + axis.elementClass().getSimpleName() + ">"));
                }
            } else {
                if (axis.container() == ServiceCatalog.ContainerKind.SINGLE) {
                    matches.add(new AccessorMatch.Single(m, axis.elementClass()));
                } else {
                    String containerLabel = axis.container() == ServiceCatalog.ContainerKind.LIST ? "a list" : "a set";
                    matches.add(new AccessorMatch.CardinalityMismatch(
                        "single field '" + fieldName + "' has accessor '" + mName
                        + "' returning " + containerLabel + "; expected a single record"));
                }
            }
        }
        return matches;
    }

    /**
     * Classifies an interface- or union-typed child field on a class-backed parent. The
     * sole producer of {@link InterfaceField} / {@link UnionField} on the class-backed-parent
     * branch (the table-backed branch produces them in {@link #classifyObjectReturnChildField};
     * those two construction sites are the entirety of the multi-table polymorphic surface).
     *
     * <p>Resolves the parent's {@link SourceKey} and hub
     * {@link TableRef} via {@link #resolvePolymorphicRecordParent}; routes the resolved hub through
     * {@link #resolveChildPolymorphicJoinPaths} for per-participant FK auto-discovery; constructs
     * the appropriate {@code ChildField} variant. Any rejection at either step lands as
     * {@link UnclassifiedField}.
     */
    private GraphitronField classifyRecordParentPolymorphicChild(
            GraphQLFieldDefinition fieldDef, String parentTypeName, String name,
            SourceLocation location, String elementTypeName,
            ReturnTypeRef.PolymorphicReturnType returnType,
            GraphitronType.ResultType parentResultType) {
        // R317 slice 3e — registry-free look-ahead at the polymorphic element (interface / union).
        GraphitronType elementType = typeBuilder.lookAheadVerdict(elementTypeName);
        List<ParticipantRef> participants;
        boolean isInterface;
        if (elementType instanceof InterfaceType interfaceType) {
            participants = interfaceType.participants();
            isInterface = true;
        } else if (elementType instanceof UnionType unionType) {
            participants = unionType.participants();
            isInterface = false;
        } else {
            return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                Rejection.structural("polymorphic return type '" + elementTypeName
                    + "' is neither a multi-table interface nor a union; cannot classify "
                    + "polymorphic child field on record-backed parent"));
        }

        String accessorBaseName = fieldDef.hasAppliedDirective(DIR_FIELD)
            ? argString(fieldDef, DIR_FIELD, ARG_NAME).orElse(name)
            : name;
        var resolution = resolvePolymorphicRecordParent(name, accessorBaseName,
            returnType.wrapper().isList(), parentResultType);
        if (resolution instanceof PolymorphicRecordParentResolution.Rejected rj) {
            return new UnclassifiedField(parentTypeName, name, location, fieldDef, rj.rejection());
        }
        var resolved = (PolymorphicRecordParentResolution.Resolved) resolution;

        var paths = resolveChildPolymorphicJoinPaths(fieldDef, name, parentTypeName,
            location, resolved.hubTable(), participants, returnType.wrapper().isList());
        if (paths.rejection() != null) {
            return new UnclassifiedField(parentTypeName, name, location, fieldDef, paths.rejection());
        }

        if (isInterface) {
            return new InterfaceField(parentTypeName, name, location, returnType,
                participants, paths.paths(), resolved.parentSourceKey(), resolved.hubTable(),
                parentResultType);
        }
        return new UnionField(parentTypeName, name, location, returnType,
            participants, paths.paths(), resolved.parentSourceKey(), resolved.hubTable(),
            parentResultType);
    }

    /**
     * Builder-internal sealed result of {@link #resolvePolymorphicRecordParent}. The classifier
     * arm reads {@code Resolved.parentSourceKey()} / {@code Resolved.hubTable()} when
     * constructing the {@link InterfaceField} / {@link UnionField}: the hub is handed to
     * {@link #resolveChildPolymorphicJoinPaths} and (since R413) also carried onto the field
     * record as {@code parentKeyOwnerTable}, so the batched rows methods can bind the parent-key
     * VALUES cells through the hub columns' registered Converter DataTypes.
     */
    private sealed interface PolymorphicRecordParentResolution {
        record Resolved(SourceKey parentSourceKey, TableRef hubTable)
            implements PolymorphicRecordParentResolution {}
        record Rejected(Rejection rejection) implements PolymorphicRecordParentResolution {}
    }

    private static final String POLYMORPHIC_HUB_AUTHOR_ERROR_TAIL =
        "on a free-form record-backed parent (Pojo / JavaRecord) requires a typed accessor to "
        + "discover the hub table the polymorphic participants share an FK to. Either expose a "
        + "typed accessor on the parent returning '...HubRecord' (single cardinality) or "
        + "'List<...HubRecord>' / 'Set<...HubRecord>' (list cardinality), where '...HubRecord' "
        + "is the concrete jOOQ TableRecord all participants reference; or back the parent with "
        + "a typed jOOQ TableRecord (via a producing @service return type or a @table type) annotated with the hub table so "
        + "RowKeyed can be derived from the parent's PK. Note: @sourceRow is not yet "
        + "supported for polymorphic returns.";

    /**
     * Resolves the parent-side {@link SourceKey} and hub table for a polymorphic child
     * field on a class-backed parent. Three reachable shapes; all four
     * {@link GraphitronType.ResultType} permits handled:
     *
     * <ul>
     *   <li>{@link GraphitronType.JooqTableRecordType} with a non-null table → {@code Resolved(
     *       SourceKey(Wrap.Row, parent.PK, ColumnRead, Cardinality.ONE), parent.table())}.
     *       Empty PK or null table is routed through {@code Rejected(structural)} — same shape
     *       as the existing table-backed arm's {@code UnclassifiedField} path.</li>
     *   <li>{@link GraphitronType.PojoResultType} / {@link GraphitronType.JavaRecordType} →
     *       delegates to {@link #derivePolymorphicHubSource}, which discovers the hub from
     *       the unique matching typed accessor and projects to {@code AccessorCall} + the
     *       Single / Many cardinality.</li>
     *   <li>{@link GraphitronType.JooqRecordType} → {@code Rejected(structural)} (no table
     *       reference, no hub derivation).</li>
     * </ul>
     */
    private PolymorphicRecordParentResolution resolvePolymorphicRecordParent(
            String fieldName, String accessorBaseName, boolean fieldIsList,
            GraphitronType.ResultType parentResultType) {
        return switch (parentResultType) {
            case GraphitronType.JooqTableRecordType jtr -> {
                if (jtr.table() == null) {
                    yield new PolymorphicRecordParentResolution.Rejected(Rejection.structural(
                        "parent backed by jOOQ TableRecord '" + jtr.fqClassName()
                        + "' has no resolvable table at build time; cannot derive hub for "
                        + "polymorphic child field '" + fieldName + "'"));
                }
                var pkCols = jtr.table().primaryKeyColumns();
                if (pkCols.isEmpty()) {
                    yield new PolymorphicRecordParentResolution.Rejected(Rejection.structural(
                        "multi-table interface/union child field '" + fieldName
                        + "' requires a non-empty primary key on the record-backed parent table '"
                        + jtr.table().tableName() + "'"));
                }
                // Polymorphic Row arm: the parent IS the source; cardinality is variant-derived
                // (each parent is one entity, not field-cardinality-derived).
                SourceKey parentSourceKey = new SourceKey(
                    pkCols,
                    new SourceKey.Wrap.Row(),
                    SourceKey.Cardinality.ONE,
                    new SourceKey.Reader.ColumnRead());
                yield new PolymorphicRecordParentResolution.Resolved(parentSourceKey, jtr.table());
            }
            case GraphitronType.PojoResultType _, GraphitronType.JavaRecordType _ ->
                // Both cardinalities route through the hub-deriving accessor classifier. The
                // single-cardinality typed accessor returns the hub TableRecord directly;
                // MultiTablePolymorphicEmitter.buildScalarPerParentFetcher binds parentRecord to
                // that accessor return (mirroring the list arm's parentKey extraction) and reads
                // the hub FK columns off it inline. The list cardinality routes through the
                // DataLoader-batched buildBatchedListFetcher.
                derivePolymorphicHubSource(fieldName, accessorBaseName, fieldIsList, parentResultType);
            case GraphitronType.JooqRecordType jrt ->
                new PolymorphicRecordParentResolution.Rejected(Rejection.structural(
                    "parent backed by jOOQ Record '" + jrt.fqClassName()
                    + "' has no table reference; polymorphic child field '" + fieldName
                    + "' cannot derive a hub. Back the parent with a typed jOOQ TableRecord "
                    + "(via a producing @service return type or a @table type) annotated with the hub table."));
        };
    }

    private PolymorphicRecordParentResolution derivePolymorphicHubSource(
            String fieldName, String accessorBaseName, boolean fieldIsList,
            GraphitronType.ResultType parentResultType) {
        String parentFqClassName = switch (parentResultType) {
            case GraphitronType.JavaRecordType jrt -> jrt.fqClassName();
            case GraphitronType.PojoResultType prt -> prt.fqClassName();
            case GraphitronType.JooqRecordType _, GraphitronType.JooqTableRecordType _ -> null;
        };
        if (parentFqClassName == null) {
            return new PolymorphicRecordParentResolution.Rejected(Rejection.structural(
                "polymorphic child field '" + fieldName + "' " + POLYMORPHIC_HUB_AUTHOR_ERROR_TAIL));
        }

        Class<?> parentClass;
        try {
            parentClass = Class.forName(parentFqClassName, false, ctx.codegenLoader());
        } catch (ClassNotFoundException e) {
            return new PolymorphicRecordParentResolution.Rejected(Rejection.structural(
                "polymorphic child field '" + fieldName + "': record-backed parent backing class '"
                + parentFqClassName + "' could not be loaded; " + POLYMORPHIC_HUB_AUTHOR_ERROR_TAIL));
        }

        List<AccessorMatch> matches = collectAccessorMatches(parentClass, fieldName, accessorBaseName, fieldIsList, null);

        List<AccessorMatch> resolvable = matches.stream()
            .filter(am -> am instanceof AccessorMatch.Single || am instanceof AccessorMatch.Many)
            .toList();
        if (resolvable.size() > 1) {
            String candidates = resolvable.stream()
                .map(am -> switch (am) {
                    case AccessorMatch.Single s -> s.method().getName() + " → "
                        + s.elementClass().getSimpleName();
                    case AccessorMatch.Many mm -> mm.method().getName() + " → "
                        + mm.elementClass().getSimpleName();
                    case AccessorMatch.CardinalityMismatch _ -> "";
                })
                .collect(Collectors.joining(", "));
            return new PolymorphicRecordParentResolution.Rejected(Rejection.structural(
                "polymorphic child field '" + fieldName + "': record-backed parent '" + parentFqClassName
                + "' exposes more than one typed TableRecord-returning accessor whose element "
                + "would be eligible as the polymorphic hub: [" + candidates + "]. Cannot pick a "
                + "unique hub. Disambiguate by removing the unintended accessor or backing the "
                + "parent with a typed jOOQ TableRecord whose table is the hub."));
        }
        if (resolvable.isEmpty()) {
            List<AccessorMatch.CardinalityMismatch> cmms = matches.stream()
                .filter(am -> am instanceof AccessorMatch.CardinalityMismatch)
                .map(am -> (AccessorMatch.CardinalityMismatch) am)
                .toList();
            if (cmms.isEmpty()) {
                return new PolymorphicRecordParentResolution.Rejected(Rejection.structural(
                    "polymorphic child field '" + fieldName + "' " + POLYMORPHIC_HUB_AUTHOR_ERROR_TAIL));
            }
            String joined = cmms.stream().map(AccessorMatch.CardinalityMismatch::message)
                .collect(Collectors.joining("; "));
            return new PolymorphicRecordParentResolution.Rejected(Rejection.structural(joined));
        }

        AccessorMatch only = resolvable.get(0);
        boolean accessorIsMany = only instanceof AccessorMatch.Many;
        java.lang.reflect.Method accessorMethod = switch (only) {
            case AccessorMatch.Single s -> s.method();
            case AccessorMatch.Many mm -> mm.method();
            case AccessorMatch.CardinalityMismatch _ -> throw new IllegalStateException("filtered above");
        };
        Class<?> elementClass = switch (only) {
            case AccessorMatch.Single s -> s.elementClass();
            case AccessorMatch.Many mm -> mm.elementClass();
            case AccessorMatch.CardinalityMismatch _ ->
                throw new IllegalStateException("filtered above");
        };
        TableRef hubTable = svc.resolveTableByRecordClass(elementClass).orElseThrow(() ->
            new IllegalStateException("collectAccessorMatches verified element table presence "
                + "but resolveTableByRecordClass returned empty for " + elementClass.getName()));

        // Polymorphic accessor arm: the hub table (where the accessor's typed return lives) is
        // carried as the leaf's parentKeyOwnerTable; cardinality follows the accessor (Single →
        // ONE, Many → MANY for per-element walk through the parent's typed list-accessor).
        var ref = new AccessorRef(
            ClassName.get(parentClass),
            accessorMethod.getName(),
            ClassName.get(elementClass));
        SourceKey parentSourceKey = new SourceKey(
            hubTable.primaryKeyColumns(),
            new SourceKey.Wrap.Record(),
            accessorIsMany ? SourceKey.Cardinality.MANY : SourceKey.Cardinality.ONE,
            new SourceKey.Reader.AccessorCall(ref));
        return new PolymorphicRecordParentResolution.Resolved(parentSourceKey, hubTable);
    }

    private record ReturnAxis(ServiceCatalog.ContainerKind container, Class<?> elementClass) {}

    /**
     * Classifies an accessor's generic return type into a {@link ReturnAxis} when the shape is
     * one of {@code X}, {@code List<X>}, {@code Set<X>} for some concrete {@code X} extending
     * {@link org.jooq.TableRecord}; returns {@code null} for any other shape (raw types,
     * wildcards, unrelated containers, non-{@code TableRecord} elements). Delegates the
     * container-axis walk to {@link ServiceCatalog#peelContainer}, the shared helper that also
     * powers the SOURCES classifier; the SOURCES classifier targets {@code RowN}/{@code RecordN}
     * elements additionally, which the accessor path does not.
     */
    private static ReturnAxis classifyAccessorReturn(java.lang.reflect.Type returnType) {
        var split = ServiceCatalog.peelContainer(returnType, java.util.EnumSet.allOf(ServiceCatalog.ContainerKind.class));
        if (split.isEmpty()) return null;
        if (split.get().elementType() instanceof Class<?> elementClass
                && org.jooq.TableRecord.class.isAssignableFrom(elementClass)) {
            return new ReturnAxis(split.get().container(), elementClass);
        }
        return null;
    }


    private GraphitronField classifyChildFieldOnTableType(GraphQLFieldDefinition fieldDef, String parentTypeName, TableBackedType tableType, Set<String> expandingTypes) {
        String name = fieldDef.getName();
        SourceLocation location = locationOf(fieldDef);

        if (fieldDef.hasAppliedDirective(DIR_SOURCE_ROW)) {
            return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural("@sourceRow is for record-backed (non-table) parents; use @reference on a @table parent"));
        }

        if (fieldDef.hasAppliedDirective(DIR_SERVICE)) {
            var resolved = serviceResolver.resolve(parentTypeName, fieldDef, tableType.table().primaryKeyColumns());
            if (resolved instanceof ServiceDirectiveResolver.Resolved.Rejected r) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, r.rejection());
            }
            if (resolved instanceof ServiceDirectiveResolver.Resolved.ErrorsLifted e) {
                return e.field();
            }
            // Service reconnect path: starts from the service return type's table (not the parent).
            var servicePath = ctx.parsePath(fieldDef, name, null, null);
            if (servicePath.hasError()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(servicePath.errorMessage()));
            }
            return switch ((ServiceDirectiveResolver.Resolved.Success) resolved) {
                case ServiceDirectiveResolver.Resolved.TableBound tb -> {
                    var sourced = extractSourced(tb.method());
                    var sk = sourced == null ? null : buildServiceTableSourceKey(sourced, tb.returnType());
                    var lr = sourced == null ? null : buildServiceLoaderRegistration(sourced, tb.returnType());
                    yield buildMethodBackedWithChannel(tb.returnType(), tb.method(),
                        parentTypeName, name, location, fieldDef,
                        ch -> new ServiceTableField(parentTypeName, name, location, tb.returnType(),
                            servicePath.elements(), List.of(), new OrderBySpec.None(), null,
                            tb.method(), sk, lr, ch));
                }
                case ServiceDirectiveResolver.Resolved.Result r -> {
                    var sourced = extractSourced(r.method());
                    var sk = sourced == null ? null : buildServiceRecordSourceKey(sourced, r.returnType());
                    var lr = sourced == null ? null : buildServiceLoaderRegistration(sourced, r.returnType());
                    yield buildMethodBackedWithChannel(r.returnType(), r.method(),
                        parentTypeName, name, location, fieldDef,
                        ch -> new ServiceRecordField(parentTypeName, name, location, r.returnType(),
                            servicePath.elements(), r.method(), sk, lr, ch));
                }
                case ServiceDirectiveResolver.Resolved.Scalar s -> {
                    var sourced = extractSourced(s.method());
                    var sk = sourced == null ? null : buildServiceRecordSourceKey(sourced, s.returnType());
                    var lr = sourced == null ? null : buildServiceLoaderRegistration(sourced, s.returnType());
                    yield buildMethodBackedWithChannel(s.returnType(), s.method(),
                        parentTypeName, name, location, fieldDef,
                        ch -> new ServiceRecordField(parentTypeName, name, location, s.returnType(),
                            servicePath.elements(), s.method(), sk, lr, ch));
                }
                // R365 route (a) restores polymorphic returns on ROOT @service fields only.
                // Child @service on a @table parent returning an interface/union stays out of
                // scope (no DataLoader-batched record-class-dispatch path yet); reject at build
                // time rather than emit a stub, per "Validator mirrors classifier invariants".
                case ServiceDirectiveResolver.Resolved.Polymorphic p ->
                    new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.deferred(
                        "child @service returning a polymorphic type (interface/union) is not yet supported"
                        + " — route (a) restores it on root @service fields only",
                        "polymorphic-entity-service-return"));
            };
        }

        if (fieldDef.hasAppliedDirective(DIR_EXTERNAL_FIELD)) {
            var externalPath = ctx.parsePath(fieldDef, name, tableType.table().tableName(), null);
            if (externalPath.hasError()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(externalPath.errorMessage()));
            }
            return switch (externalFieldResolver.resolve(parentTypeName, fieldDef, tableType.table())) {
                case ExternalFieldDirectiveResolver.Resolved.Rejected r ->
                    new UnclassifiedField(parentTypeName, name, location, fieldDef, r.rejection());
                case ExternalFieldDirectiveResolver.Resolved.Success s ->
                    new ComputedField(parentTypeName, name, location, s.returnType(), externalPath.elements(), s.method());
            };
        }

        if (fieldDef.hasAppliedDirective(DIR_TABLE_METHOD)) {
            return switch (tableMethodResolver.resolve(parentTypeName, fieldDef, false)) {
                case TableMethodDirectiveResolver.Resolved.Rejected r ->
                    new UnclassifiedField(parentTypeName, name, location, fieldDef, r.rejection());
                case TableMethodDirectiveResolver.Resolved.TableBound tb -> {
                    String targetTableName = tb.returnType().table().tableName();
                    var tableMethodPath = ctx.parsePath(fieldDef, name, tableType.table().tableName(), targetTableName, tb.returnType().table(), buildWrapper(fieldDef).isList());
                    if (tableMethodPath.hasError()) {
                        yield new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(tableMethodPath.errorMessage()));
                    }
                    var pathElements = tableMethodPath.elements();
                    if (!pathElements.isEmpty() && pathElements.getLast() instanceof JoinStep.Hop lastFk
                        && lastFk.on() instanceof On.ColumnPairs
                        && !lastFk.targetTable().sameTable(targetTableName)) {
                        yield new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(
                            "@tableMethod @reference path: last hop lands on '" + lastFk.targetTable().tableName()
                            + "' but @tableMethod's return type is bound to table '" + targetTableName + "'"));
                    }
                    yield buildMethodBackedWithChannel(tb.returnType(), tb.method(),
                        parentTypeName, name, location, fieldDef,
                        ch -> new TableMethodField(parentTypeName, name, location, tb.returnType(), pathElements, tb.method(), ch));
                }
            };
        }

        if (!isScalarOrEnum(fieldDef)) {
            return classifyObjectReturnChildField(fieldDef, parentTypeName, tableType, expandingTypes);
        }

        if (fieldDef.hasAppliedDirective(DIR_NODE_ID)) {
            Optional<String> typeName = argString(fieldDef, DIR_NODE_ID, ARG_TYPE_NAME);
            if (typeName.isPresent()) {
                ReturnTypeRef targetType = ctx.resolveReturnType(typeName.get(), new FieldWrapper.Single(true));
                // R317 slice 3d — the node fact comes from the pure NodeIndex (a fixed point, not the
                // in-progress registry). Mirror the former two-branch logic precisely: an absent index
                // entry that nonetheless names an existing SDL object is the "does not have @node"
                // rejection; an absent entry naming nothing is the "does not exist in the schema"
                // rejection (candidate list preserved as the registry keyset). The NodeIndex is pure
                // (slice 3d dropped the typeId-uniqueness exclusion), so a typeId-collided node now
                // resolves here and proceeds; the collision still fails the build at
                // validateNodeTypeIdUniqueness before generation, so this is sound.
                var targetNode = ctx.nodes.forName(typeName.get());
                if (targetNode.isEmpty()) {
                    // A target that exists in the SDL (any kind) but is not a node is the
                    // "does not have @node" rejection; a name that resolves to nothing is the
                    // "does not exist in the schema" rejection. SDL presence (getType != null) is
                    // read-free and reproduces the old non-null-but-not-NodeType branch for every
                    // classified target (objects, interfaces, scalars alike).
                    if (ctx.schema.getType(typeName.get()) != null) {
                        return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural("@nodeId(typeName:) type '" + typeName.get() + "' does not have @node"));
                    }
                    // R317 slice 4 — the "did you mean" candidate set is sourced off the schema, not
                    // ctx.types.keySet(): under the single classify-and-emit walk the registry is only
                    // partially populated when a field classifies (its as-yet-unvisited siblings are not
                    // registered), so the partial registry would make this hint walk-order dependent. The
                    // schema's declared type names are a stable, registry-free, order-independent source.
                    // This is the last ctx.types read in FieldBuilder; the read-free invariant now holds.
                    var candidates = ctx.schema.getAllTypesAsList().stream()
                        .map(graphql.schema.GraphQLNamedType::getName)
                        .filter(n -> !n.startsWith("__"))
                        .toList();
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.unknownTypeName(
                        "@nodeId(typeName:) type '" + typeName.get() + "' does not exist in the schema",
                        typeName.get(), candidates));
                }
                NodeType targetNodeType = targetNode.get();
                TableRef parentTable = tableType.table();
                var nodeRefPath = ctx.parsePath(fieldDef, name, tableType.table().tableName(), targetNodeType.table().tableName(), targetNodeType.table());
                if (nodeRefPath.hasError()) {
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(nodeRefPath.errorMessage()));
                }
                return buildNodeIdReferenceCarrier(parentTypeName, name, location, parentTable, targetNodeType, nodeRefPath.elements());
            } else {
                if (!(tableType instanceof NodeType nodeType)) {
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural("@nodeId requires the containing type to be a node type (via @node or KjerneJooqGenerator metadata)"));
                }
                return buildNodeIdOutputCarrier(parentTypeName, name, location, nodeType);
            }
        }

        boolean hasFieldDirective = fieldDef.hasAppliedDirective(DIR_FIELD);
        String columnName = hasFieldDirective
            ? argString(fieldDef, DIR_FIELD, ARG_NAME).orElse(name)
            : name;

        if (fieldDef.hasAppliedDirective(DIR_REFERENCE)) {
            // Cross-table participant field on a TableInterfaceType participant: the interface
            // fetcher (TypeFetcherGenerator) emits a conditional LEFT JOIN per occurrence and
            // projects the column under a unique alias; the per-field DataFetcher reads it back
            // by alias. Lookup uses the prepopulated entry on the participant's
            // ParticipantRef.TableBound — single source of truth lives in TypeBuilder.
            var crossTable = lookupParticipantCrossTableField(parentTypeName, name);
            if (crossTable != null) {
                return new ParticipantColumnReferenceField(
                    parentTypeName, name, location,
                    crossTable.column(), crossTable.hop(), crossTable.aliasName());
            }
            var refPath = ctx.parsePath(fieldDef, name, tableType.table().tableName(), null);
            if (refPath.hasError()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(refPath.errorMessage()));
            }
            Optional<ColumnRef> column = svc.resolveColumnForReference(columnName, refPath.elements(), tableType);
            if (column.isEmpty()) {
                List<String> candidates = svc.terminalTableForReference(refPath.elements(), tableType.table())
                    .map(t -> t.allColumns().stream().map(ColumnRef::javaName).toList())
                    .orElse(List.of());
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.unknownColumn(
                    "column '" + columnName + "' could not be resolved in the jOOQ table",
                    columnName, candidates));
            }
            var crfPcResolution = ctx.buildParentCorrelation(refPath.elements(), tableType.table());
            if (crfPcResolution instanceof BuildContext.ParentCorrelationResolution.AuthorError e) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.structural(e.message()));
            }
            var crfParentCorrelation = ((BuildContext.ParentCorrelationResolution.Resolved) crfPcResolution).correlation();
            return new ColumnReferenceField(parentTypeName, name, location, columnName, column.get(), refPath.elements(),
                new no.sikt.graphitron.rewrite.model.CallSiteCompaction.Direct(),
                crfParentCorrelation);
        }

        Optional<ColumnRef> column = svc.resolveColumn(columnName, tableType);
        if (column.isEmpty()) {
            String tableSqlName = tableType.table().tableName();
            boolean isList = GraphQLTypeUtil.unwrapNonNull(fieldDef.getType()) instanceof GraphQLList;
            String typeName = ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(fieldDef.getType())).getName();
            // Synthesis shim: a scalar ID field on a NodeType without `@nodeId`, `@reference`, or
            // `@field` is treated as an implicit `@nodeId`. Fires a per-site deprecation diagnostic;
            // the canonical form is to declare `@nodeId` explicitly. See plan:
            // roadmap/retire-synthesis-shims.md.
            if (tableType instanceof NodeType nodeType
                    && "ID".equals(typeName)
                    && !isList
                    && !hasFieldDirective) {
                LOG.warn("field '{}.{}' synthesizes an `@nodeId` carrier without the directive;"
                    + " declare `@nodeId` explicitly. The synthesis shim will be removed in a"
                    + " future release. See roadmap/retire-synthesis-shims.md",
                    parentTypeName, name);
                return buildNodeIdOutputCarrier(parentTypeName, name, location, nodeType);
            }
            return new UnclassifiedField(parentTypeName, name, location, fieldDef, Rejection.unknownColumn(
                "column '" + columnName + "' could not be resolved in the jOOQ table",
                columnName, ctx.catalog.columnJavaNamesOf(tableSqlName)));
        }
        return new ColumnField(parentTypeName, name, location, columnName, column.get(),
            new no.sikt.graphitron.rewrite.model.CallSiteCompaction.Direct());
    }

    /**
     * Builds the output carrier for an {@code @nodeId} (no {@code typeName:}) field.
     * Routes by {@code nodeType.nodeKeyColumns().size()}: arity-1 to a single-column
     * {@link ColumnField} carrying {@link no.sikt.graphitron.rewrite.model.CallSiteCompaction.NodeIdEncodeKeys},
     * arity &gt; 1 to a {@link ChildField.CompositeColumnField} narrowed to the same compaction arm.
     * The {@link no.sikt.graphitron.rewrite.model.HelperRef.Encode} reference is read from
     * {@code nodeType.encodeMethod()} so encoder-class and helper-method names cannot drift.
     */
    private ChildField buildNodeIdOutputCarrier(
            String parentTypeName, String name, graphql.language.SourceLocation location, NodeType nodeType) {
        var enc = nodeType.encodeMethod();
        var compaction = new no.sikt.graphitron.rewrite.model.CallSiteCompaction.NodeIdEncodeKeys(enc);
        var keys = nodeType.nodeKeyColumns();
        if (keys.size() == 1) {
            ColumnRef k = keys.get(0);
            return new ColumnField(parentTypeName, name, location, k.javaName(), k, compaction);
        }
        return new ChildField.CompositeColumnField(parentTypeName, name, location, keys, compaction);
    }

    /**
     * Builds the output carrier for an {@code @nodeId(typeName: T)} reference field. Two shapes:
     * <ul>
     *   <li><b>Rooted at child (FK-mirror).</b> The single FK hop's source columns on the parent
     *       table positionally equal the target NodeType's {@code keyColumns}, so the parent
     *       columns ARE the keys; emit them directly through {@link ColumnField} /
     *       {@link ChildField.CompositeColumnField} (no joinPath).</li>
     *   <li><b>Rooted at parent (non-mirror, including multi-hop / condition-join).</b> The FK
     *       columns differ from the target's keyColumns, or the path has more than one step;
     *       emit through {@link ColumnReferenceField} / {@link ChildField.CompositeColumnReferenceField}
     *       carrying the target's keyColumns plus the resolved {@code joinPath}. Multi-hop and
     *       condition-join paths surface as runtime stubs at the emitter; see
     *       roadmap/nodeidreferencefield-join-projection-form.md.</li>
     * </ul>
     */
    private ChildField buildNodeIdReferenceCarrier(
            String parentTypeName, String name, graphql.language.SourceLocation location,
            TableRef parentTable, NodeType targetNodeType, List<JoinStep> joinPath) {
        var enc = targetNodeType.encodeMethod();
        var compaction = new no.sikt.graphitron.rewrite.model.CallSiteCompaction.NodeIdEncodeKeys(enc);
        var keys = targetNodeType.nodeKeyColumns();

        // FK-mirror collapse: single FK hop entered from the parent, FK target columns positionally
        // equal to the target's keyColumns. The parent's FK source columns ARE the keys; emit them
        // directly off the parent without a JOIN. Mirrors the legacy fkMirrorSourceColumns helper.
        List<ColumnRef> fkMirrorColumns = fkMirrorSourceColumns(parentTable, joinPath, keys);
        if (fkMirrorColumns != null) {
            if (fkMirrorColumns.size() == 1) {
                ColumnRef c = fkMirrorColumns.get(0);
                return new ColumnField(parentTypeName, name, location, c.javaName(), c, compaction);
            }
            return new ChildField.CompositeColumnField(parentTypeName, name, location, fkMirrorColumns, compaction);
        }

        // Non-FK-mirror (rooted-at-parent or multi-hop / condition-join). Carry the target's
        // keyColumns plus the joinPath; emitter resolves the parent alias from joinPath when it
        // implements the JOIN-with-projection form.
        if (keys.size() == 1) {
            ColumnRef k = keys.get(0);
            // parentTable is the @nodeId carrier's parent NodeType.table() — always non-null at
            // this site, so the buildParentCorrelation AuthorError arm (gated on parentTable
            // == null when the first hop is a condition join) is unreachable here. The cast is
            // a structural safety net rather than runtime branching.
            var nodeRefPcResolution = ctx.buildParentCorrelation(joinPath, parentTable);
            var nodeRefParentCorrelation = ((BuildContext.ParentCorrelationResolution.Resolved) nodeRefPcResolution).correlation();
            return new ColumnReferenceField(parentTypeName, name, location, k.javaName(), k, joinPath, compaction,
                nodeRefParentCorrelation);
        }
        return new ChildField.CompositeColumnReferenceField(parentTypeName, name, location, keys, joinPath, compaction);
    }

    /**
     * Returns the source-side columns on the parent table when the join path collapses to a
     * single FK hop entered from the parent (parent-holds-FK pattern) <em>and</em> the FK's
     * target-side columns positionally match the target NodeType's {@code keyColumns}.
     * {@code null} otherwise (composite-key with non-mirroring FK, multi-hop, condition-join).
     */
    private static List<ColumnRef> fkMirrorSourceColumns(TableRef parentTable, List<JoinStep> joinPath,
                                                          List<ColumnRef> targetKeyColumns) {
        if (joinPath.size() != 1) return null;
        if (!(joinPath.get(0) instanceof JoinStep.Hop hop
                && hop.on() instanceof On.ColumnPairs fk)) return null;
        if (!hop.originTable().denotesSameTableAs(parentTable)) return null;
        if (fk.slotCount() != targetKeyColumns.size()) return null;
        List<ColumnRef> targetSide = fk.targetSideColumns();
        for (int i = 0; i < targetSide.size(); i++) {
            if (!targetSide.get(i).sqlName().equalsIgnoreCase(targetKeyColumns.get(i).sqlName())) {
                return null;
            }
        }
        return fk.sourceSideColumns();
    }

    private boolean isScalarOrEnum(GraphQLFieldDefinition fieldDef) {
        var baseType = GraphQLTypeUtil.unwrapAll(fieldDef.getType());
        return baseType instanceof GraphQLScalarType || baseType instanceof GraphQLEnumType;
    }

    /**
     * Parses the {@code ExternalCodeReference} input object at argument {@code argName} of the
     * given directive on {@code fieldDef} and returns a builder-private {@code ExternalRef} holding
     * the {@code className} and {@code method} strings. Returns {@code null} when the directive or
     * argument is absent.
     *
     * <p>When the reference uses the deprecated {@code name} form instead of {@code className},
     * the name is looked up in {@link RewriteContext#namedReferences()}. A deprecation warning is
     * logged per field. If the name is not in the map, the returned {@code ExternalRef} carries a
     * non-null {@link ExternalRef#lookupError()} and the {@code className} is {@code null}.
     */
    ExternalRef parseExternalRef(String parentTypeName, GraphQLFieldDefinition fieldDef, String directiveName, String argName) {
        var dir = fieldDef.getAppliedDirective(directiveName);
        if (dir == null) return null;
        var arg = dir.getArgument(argName);
        if (arg == null) return null;
        Map<String, Object> ref = asMap(arg.getValue());
        String className = Optional.ofNullable(ref.get(ARG_CLASS_NAME)).map(Object::toString).orElse(null);
        String methodName = Optional.ofNullable(ref.get(ARG_METHOD)).map(Object::toString).orElse(null);
        if (className == null) {
            String name = Optional.ofNullable(ref.get(ARG_NAME)).map(Object::toString).orElse(null);
            if (name != null) {
                LOG.warn("ExternalCodeReference 'name' is deprecated on field '{}.{}'; use 'className' instead", parentTypeName, fieldDef.getName());
                String resolved = ctx.ctx().namedReferences().get(name);
                if (resolved != null) {
                    className = resolved;
                } else {
                    return new ExternalRef(null, methodName, Map.of(),
                        "named reference '" + name + "' not found in namedReferences config", null);
                }
            }
        }
        String rawArgMapping = Optional.ofNullable(ref.get(ARG_ARG_MAPPING)).map(Object::toString).orElse(null);
        // Structural-inertness check: @externalField reaches a method whose Java parameter set is
        // fixed (the parent table). argMapping has no slot to bind to and is rejected at parse
        // time. (@enum / @record have their own parse sites in TypeBuilder.)
        if (rawArgMapping != null && !rawArgMapping.isBlank() && DIR_EXTERNAL_FIELD.equals(directiveName)) {
            return new ExternalRef(className, methodName, Map.of(), null,
                "argMapping is not supported on @" + directiveName
                + " — this directive does not consume GraphQL-argument-bound parameters");
        }
        var parsed = ArgBindingMap.parseArgMapping(rawArgMapping);
        if (parsed instanceof ArgBindingMap.ParsedArgMapping.ParseError pe) {
            return new ExternalRef(className, methodName, Map.of(), null, pe.message());
        }
        var segmentChains = ((ArgBindingMap.ParsedArgMapping.Ok) parsed).overrides();
        return new ExternalRef(className, methodName, segmentChains, null, null);
    }

    /**
     * Returns the {@code contextArguments} list from the {@code @service} or {@code @tableMethod}
     * directive on {@code fieldDef}, or an empty list when the directive is absent or the argument
     * is not set.
     */
    List<String> parseContextArguments(GraphQLFieldDefinition fieldDef, String directiveName) {
        return argStringList(fieldDef, directiveName, ARG_CONTEXT_ARGUMENTS);
    }

    /**
     * The roadmap slug of the deferred capability R452's rejections point authors at: per-participant
     * explicit join paths on multi-table interface/union child fields (multi-FK disambiguation,
     * condition joins, multi-hop chains, same-table self-FK participants). File basename under
     * {@code roadmap/}, no extension.
     */
    private static final String PER_PARTICIPANT_JOIN_PATHS_SLUG = "per-participant-multitable-child-join-paths";

    /**
     * A one-line {@code @referenceFor} usage sketch, appended to R452's rule 1b / 1c steers so the
     * author sees the sanctioned surface, not just the deferred-capability pointer (R458 slice 1).
     */
    private static final String REFERENCE_FOR_USAGE_SKETCH =
        "State an explicit per-participant path with @referenceFor(type: \"<Participant>\", "
        + "path: [{key: \"<fk>\"}]).";

    /**
     * Carries the result of {@link #resolveChildPolymorphicJoinPaths}: a per-participant
     * {@code Map<String, ParticipantCorrelation>} keyed by typename (each value the resolved
     * parent→participant correlation), or a non-null {@link Rejection} when a participant's FK cannot
     * be uniquely auto-discovered, a {@code @referenceFor} route is malformed or names an unknown
     * participant, or a route resolves to a shape whose emitter has not yet shipped.
     */
    private record ChildPolymorphicJoinPaths(
            java.util.Map<String, no.sikt.graphitron.rewrite.model.ParticipantCorrelation> paths, Rejection rejection) {
        static ChildPolymorphicJoinPaths ok(
                java.util.Map<String, no.sikt.graphitron.rewrite.model.ParticipantCorrelation> paths) {
            return new ChildPolymorphicJoinPaths(paths, null);
        }
        static ChildPolymorphicJoinPaths fail(Rejection rejection) {
            return new ChildPolymorphicJoinPaths(java.util.Map.of(), rejection);
        }
    }

    /**
     * Resolves the per-participant parent→participant correlation from {@code parentTable} to each
     * {@link ParticipantRef.TableBound} participant's table, producing the sealed
     * {@link no.sikt.graphitron.rewrite.model.ParticipantCorrelation} carrier the multi-table
     * polymorphic emitter dispatches on.
     *
     * <p>Single choke point for all four producers (interface / union × table-backed / record-backed
     * parent). It is also the R452 build-time gate generalized by R458: a participant's correlation is
     * either auto-discovered (the single unique FK from the participant's table to the parent's) or
     * stated explicitly with {@code @referenceFor}. Every shape the emitter cannot yet lower is
     * rejected here rather than lowered to an arbitrary-participant-row result on a green build.
     *
     * <ul>
     *   <li><b>Rule 1a</b> — a field-level {@code @reference} is rejected structurally: a single
     *       stated path applies the same hops to every participant, so it is terminal-correct for at
     *       most one. {@code @referenceFor} is the sanctioned per-participant surface; the message
     *       names it.</li>
     *   <li><b>{@code @referenceFor} routes</b> — each application binds one participant's complete
     *       path. Duplicate {@code type:} and unknown / non-table-bound {@code type:} reject
     *       structurally. A route's path is resolved via
     *       {@link BuildContext#parseExplicitPath}; a resolution error or terminal-target mismatch
     *       rejects naming the participant. A single-hop FK route lowers to
     *       {@link no.sikt.graphitron.rewrite.model.ParticipantCorrelation.KeyTupleWhere} (multi-FK
     *       disambiguation and same-table self-FK, both shipped in slice 1). A multi-hop route is a
     *       DEFERRED rejection keyed to slice 2; a condition (predicate) hop to slice 3 — their
     *       emitter arms have not shipped, so the classifier physically cannot construct
     *       {@link no.sikt.graphitron.rewrite.model.ParticipantCorrelation.JoinedCorrelation} yet.</li>
     *   <li><b>Rule 1b</b> — a same-table participant with no {@code @referenceFor} produces an empty
     *       auto-path ({@code parsePath} skips FK discovery when source and target match); now a live
     *       structural steer to {@code @referenceFor} (self-FK is author-correctable in slice 1), not
     *       a deferred capability.</li>
     *   <li><b>Rule 1c</b> — a zero-FK / multi-FK auto-discovery failure carries the generic
     *       {@code fkCountMessage} steer; wrapped here with multi-table-child context and a live
     *       {@code @referenceFor} steer (multi-FK disambiguation is what slice 1 ships).</li>
     * </ul>
     *
     * <p>Per-participant errors are aggregated rather than short-circuited on the first, so the
     * author sees every failing participant in one build. {@link ParticipantRef.Unbound} participants
     * are skipped and produce no map entry.
     *
     * <p>{@code isList} is the child field's cardinality (from its wrapper). It threads into both
     * path resolvers as the {@code selfRefFkOnSource = !isList} orientation hint: for a
     * <em>self-referential</em> FK (participant table equals the parent/hub table) both endpoints are
     * the same generated class, so neither identity nor name can tell the parent side from the child
     * side, and this flag is the sole discriminator (see {@code JooqCatalog.foreignKeyOnSource}). A
     * single-valued field navigates <em>to</em> the parent (FK on the parent/source side); a list or
     * connection field collects the <em>children</em> pointing back (FK on the child/target side). A
     * flipped orientation is silently wrong data, so a same-table self-FK {@code @referenceFor} route
     * must resolve against the real cardinality rather than a hardcoded default. For non-self FKs the
     * hint is ignored (orientation comes from FK class identity), so it is inert on the auto-discovery
     * arm and on cross-table routes.
     */
    private ChildPolymorphicJoinPaths resolveChildPolymorphicJoinPaths(
            GraphQLFieldDefinition fieldDef, String fieldName, String parentTypeName,
            SourceLocation location, TableRef parentTable, List<ParticipantRef> participants,
            boolean isList) {
        String fieldLabel = "Field '" + parentTypeName + "." + fieldName + "'";
        // Rule 1a: a field-level @reference cannot express a distinct join per participant.
        if (fieldDef.hasAppliedDirective(DIR_REFERENCE)) {
            return ChildPolymorphicJoinPaths.fail(Rejection.structural(
                fieldLabel + ": an explicit @reference is not supported on a multi-table "
                + "interface/union child field. Per-participant join paths are auto-discovered (one "
                + "unique single-hop foreign key from each participant table to the parent table '"
                + parentTable.tableName() + "'); a field-level @reference applies one stated path to "
                + "every participant, so it can be terminal-correct for at most one and cannot express "
                + "a distinct join per participant. Remove the @reference directive and, where "
                + "auto-discovery is insufficient, state a per-participant path with @referenceFor. "
                + "See roadmap/" + PER_PARTICIPANT_JOIN_PATHS_SLUG + ".md"));
        }

        // Valid @referenceFor `type:` targets are the field's table-bound participants.
        var tableBoundByName = new java.util.LinkedHashMap<String, ParticipantRef.TableBound>();
        for (var p : participants) {
            if (p instanceof ParticipantRef.TableBound tb) tableBoundByName.put(tb.typeName(), tb);
        }

        // Read @referenceFor applications, keyed by participant type. Repeated applications are
        // independent (one per participant); a duplicate type: is a build error, not a merge.
        var explicitRoutes = new java.util.LinkedHashMap<String, List<?>>();
        for (var app : fieldDef.getAppliedDirectives(DIR_REFERENCE_FOR)) {
            var typeArg = app.getArgument(ARG_TYPE);
            String type = typeArg != null && typeArg.getValue() != null ? typeArg.getValue().toString() : null;
            if (type == null || type.isBlank()) {
                return ChildPolymorphicJoinPaths.fail(Rejection.structural(
                    fieldLabel + ": a @referenceFor application is missing its 'type' argument."));
            }
            if (explicitRoutes.containsKey(type)) {
                return ChildPolymorphicJoinPaths.fail(Rejection.structural(
                    fieldLabel + ": @referenceFor names participant '" + type + "' more than once. "
                    + "Repeated @referenceFor applications are independent, one per participant; each "
                    + "participant may have at most one route (unlike @reference, they do not "
                    + "concatenate)."));
            }
            if (!tableBoundByName.containsKey(type)) {
                return ChildPolymorphicJoinPaths.fail(Rejection.structural(
                    fieldLabel + ": @referenceFor names '" + type + "', which is not a table-bound "
                    + "participant of the field's return type. Valid participant names: "
                    + tableBoundByName.keySet() + "."));
            }
            var pathArg = app.getArgument(ARG_PATH);
            Object pathValue = pathArg != null ? pathArg.getValue() : null;
            List<?> elements = pathValue instanceof List<?> l ? l
                : pathValue != null ? List.of(pathValue) : List.of();
            explicitRoutes.put(type, elements);
        }

        var paths = new java.util.LinkedHashMap<String, no.sikt.graphitron.rewrite.model.ParticipantCorrelation>();
        var errors = new java.util.ArrayList<Rejection>();
        for (var p : participants) {
            if (!(p instanceof ParticipantRef.TableBound tb)) continue;
            String type = tb.typeName();
            boolean explicit = explicitRoutes.containsKey(type);
            var parsed = explicit
                ? ctx.parseExplicitPath(explicitRoutes.get(type), fieldName, parentTable.tableName(),
                    tb.table().tableName(), tb.table(), isList)
                : ctx.parsePath(fieldDef, fieldName, parentTable.tableName(), tb.table().tableName(), tb.table(), isList);

            var outcome = classifyParticipantRoute(explicit, parsed, tb, fieldLabel, parentTable);
            if (outcome.rejection() != null) {
                errors.add(outcome.rejection());
            } else {
                paths.put(type, outcome.correlation());
            }
        }
        if (!errors.isEmpty()) {
            return ChildPolymorphicJoinPaths.fail(aggregateChildPolymorphicErrors(errors));
        }
        return ChildPolymorphicJoinPaths.ok(paths);
    }

    /** One participant's route outcome: a resolved correlation or a rejection (exactly one non-null). */
    private record ParticipantRouteOutcome(
            no.sikt.graphitron.rewrite.model.ParticipantCorrelation correlation, Rejection rejection) {
        static ParticipantRouteOutcome ok(no.sikt.graphitron.rewrite.model.ParticipantCorrelation c) {
            return new ParticipantRouteOutcome(c, null);
        }
        static ParticipantRouteOutcome fail(Rejection r) {
            return new ParticipantRouteOutcome(null, r);
        }
    }

    /**
     * Classifies one participant's resolved path (explicit {@code @referenceFor} route or
     * auto-discovered) into a {@link no.sikt.graphitron.rewrite.model.ParticipantCorrelation} or a
     * per-participant rejection. Slice 1 lowers only single-hop FK routes (to
     * {@link no.sikt.graphitron.rewrite.model.ParticipantCorrelation.KeyTupleWhere}); multi-hop and
     * condition routes are DEFERRED to slices 2 and 3.
     */
    private ParticipantRouteOutcome classifyParticipantRoute(boolean explicit, BuildContext.ParsedPath parsed,
            ParticipantRef.TableBound tb, String fieldLabel, TableRef parentTable) {
        String type = tb.typeName();
        if (explicit) {
            if (parsed.hasError()) {
                return ParticipantRouteOutcome.fail(Rejection.structural(
                    fieldLabel + ": @referenceFor route for participant '" + type + "' could not be "
                    + "resolved: " + parsed.errorMessage() + "."));
            }
            if (parsed.terminalTargetVerdict() instanceof BuildContext.TerminalTargetVerdict.Mismatch mismatch) {
                return ParticipantRouteOutcome.fail(Rejection.structural(
                    fieldLabel + ": @referenceFor route for participant '" + type + "' does not land "
                    + "on that participant's table. " + mismatch.diagnostic()));
            }
            // Condition (predicate) hop → slice 3; else multi-hop → slice 2; else single-hop FK ships.
            if (parsed.elements().stream().anyMatch(
                    e -> e instanceof JoinStep.Hop h && h.on() instanceof On.Predicate)) {
                return ParticipantRouteOutcome.fail(Rejection.deferred(
                    fieldLabel + ": @referenceFor route for participant '" + type + "' correlates by a "
                    + "condition (non-foreign-key predicate). Condition correlation on multi-table "
                    + "child fields is a deferred capability (slice 3)",
                    PER_PARTICIPANT_JOIN_PATHS_SLUG));
            }
            if (parsed.elements().size() > 1) {
                return ParticipantRouteOutcome.fail(Rejection.deferred(
                    fieldLabel + ": @referenceFor route for participant '" + type + "' is a multi-hop "
                    + "key chain (" + parsed.elements().size() + " hops). Multi-hop chains on "
                    + "multi-table child fields are a deferred capability (slice 2)",
                    PER_PARTICIPANT_JOIN_PATHS_SLUG));
            }
            var pairs = singleHopFkColumnPairs(parsed.elements());
            if (pairs.isEmpty()) {
                return ParticipantRouteOutcome.fail(Rejection.structural(
                    fieldLabel + ": @referenceFor route for participant '" + type + "' did not resolve "
                    + "to a single-hop foreign key. See roadmap/" + PER_PARTICIPANT_JOIN_PATHS_SLUG + ".md"));
            }
            return ParticipantRouteOutcome.ok(
                new no.sikt.graphitron.rewrite.model.ParticipantCorrelation.KeyTupleWhere(pairs.get()));
        }

        // Auto-discovery arm (no @referenceFor for this participant).
        if (parsed.hasError()) {
            // Rule 1c: fkCountMessage's generic "add a @reference directive" steer leads straight into
            // rule 1a on these fields; steer to @referenceFor (multi-FK disambiguation ships in slice 1).
            return ParticipantRouteOutcome.fail(Rejection.structural(
                "participant '" + type + "': " + parsed.errorMessage()
                + ". Note: an explicit @reference is not supported on multi-table interface/union child "
                + "fields; auto-discovery could not derive a single FK for this participant. "
                + REFERENCE_FOR_USAGE_SKETCH + " See roadmap/" + PER_PARTICIPANT_JOIN_PATHS_SLUG + ".md"));
        }
        if (parsed.elements().isEmpty()) {
            // Rule 1b: same-table participant. parsePath skips FK auto-discovery when source and target
            // tables match. Now a live structural steer to @referenceFor: a self-FK participant is
            // author-correctable in slice 1 by stating the self-referencing key.
            return ParticipantRouteOutcome.fail(Rejection.structural(
                fieldLabel + ": participant '" + type + "' is backed by the same table as the "
                + "parent/hub ('" + parentTable.tableName() + "'), so no foreign-key correlation from "
                + "parent to participant can be auto-discovered. State the self-referencing key with "
                + "@referenceFor(type: \"" + type + "\", path: [{key: \"<self-fk>\"}]). See roadmap/"
                + PER_PARTICIPANT_JOIN_PATHS_SLUG + ".md"));
        }
        var pairs = singleHopFkColumnPairs(parsed.elements());
        if (pairs.isEmpty()) {
            // Unreachable with rule 1a in place: auto-discovery only produces a single-hop FK hop. Kept
            // so the KeyTupleWhere non-empty-slots invariant is never violated by a future producer
            // change rather than crashing in the emitter.
            return ParticipantRouteOutcome.fail(Rejection.structural(
                fieldLabel + ": participant '" + type + "' resolved to an unsupported multi-table child "
                + "join shape (only a single-hop foreign key is auto-discovered). " + REFERENCE_FOR_USAGE_SKETCH
                + " See roadmap/" + PER_PARTICIPANT_JOIN_PATHS_SLUG + ".md"));
        }
        return ParticipantRouteOutcome.ok(
            new no.sikt.graphitron.rewrite.model.ParticipantCorrelation.KeyTupleWhere(pairs.get()));
    }

    /**
     * Folds per-participant route rejections into one. A single failing participant yields its own
     * rejection verbatim (so the exact structural / deferred kind and message survive); multiple
     * failures join their messages, structural-dominates-deferred (any author-fixable failure makes
     * the aggregate an author error).
     */
    private Rejection aggregateChildPolymorphicErrors(List<Rejection> errors) {
        if (errors.size() == 1) return errors.get(0);
        String joined = errors.stream().map(Rejection::message)
            .collect(java.util.stream.Collectors.joining("; "));
        boolean anyStructural = errors.stream().anyMatch(r -> !(r instanceof Rejection.Deferred));
        return anyStructural
            ? Rejection.structural(joined)
            : Rejection.deferred(joined, PER_PARTICIPANT_JOIN_PATHS_SLUG);
    }

    /** Collects the non-null discriminator values from all table-backed participants
     * ({@link ParticipantRef.TableBound} and {@link ParticipantRef.JoinedTableBound}). */
    private static List<String> knownDiscriminatorValues(GraphitronType.TableInterfaceType tit) {
        return tit.participants().stream()
            .filter(p -> p instanceof ParticipantRef.TableBacked tb && tb.discriminatorValue() != null)
            .map(p -> ((ParticipantRef.TableBacked) p).discriminatorValue())
            .toList();
    }

    /**
     * Returns the {@link ParticipantRef.TableBound.CrossTableField} entry for {@code (parentTypeName,
     * fieldName)} when {@code parentTypeName} is a participant of some {@link GraphitronType.TableInterfaceType}
     * and the field appears in that participant's {@code crossTableFields} list. Returns {@code null}
     * otherwise. Cross-table participant lists are populated in {@code TypeBuilder} from the schema's
     * {@code @reference} directives; this method is the single read path that drives
     * {@link ParticipantColumnReferenceField} classification.
     */
    private ParticipantRef.TableBound.CrossTableField lookupParticipantCrossTableField(
            String parentTypeName, String fieldName) {
        // R317 slice 2 — the participant cross-table-field fixed-point index replaces the nested
        // whole-registry scan over every TableInterfaceType's participants.
        return ctx.crossTableFieldsByParticipant
            .getOrDefault(parentTypeName, Map.of())
            .get(fieldName);
    }

    /**
     * The single-hop-FK shape predicate shared by the single-table {@link ChildField.TableInterfaceField}
     * arm ({@link #validateSingleHopFkJoin}) and the multi-table interface/union child arm
     * ({@link #resolveChildPolymorphicJoinPaths}) (R452 rule 1d). Returns the {@link On.ColumnPairs}
     * when {@code path} is exactly one {@link JoinStep.Hop} keyed by column pairs with at least one
     * slot; empty otherwise. Callers phrase their own per-arm rejection message.
     */
    private static Optional<On.ColumnPairs> singleHopFkColumnPairs(List<JoinStep> path) {
        if (path.size() != 1) return Optional.empty();
        if (!(path.get(0) instanceof JoinStep.Hop hop0 && hop0.on() instanceof On.ColumnPairs pairs)) {
            return Optional.empty();
        }
        if (pairs.slotCount() == 0) return Optional.empty();
        return Optional.of(pairs);
    }

    /**
     * Validates that a join path for a {@link ChildField.TableInterfaceField} is a single
     * FK-derived step. Returns an error message if the path is multi-hop or
     * contains a condition join, or {@code null} if the path is valid. The shape decision goes
     * through {@link #singleHopFkColumnPairs}; the two failure messages are distinguished here.
     */
    private static String validateSingleHopFkJoin(List<JoinStep> path, String fieldName) {
        if (singleHopFkColumnPairs(path).isPresent()) return null;
        if (path.size() != 1) {
            return "Field '" + fieldName + "': TableInterfaceField @reference paths must be a single FK hop "
                + "(multi-hop paths are not yet supported)";
        }
        return "Field '" + fieldName + "': TableInterfaceField @reference paths must use a foreign key "
            + "(condition-join paths are not yet supported)";
    }

    // ===== Inner records =====

    /**
     * Builder-private carrier for an {@code ExternalCodeReference}. {@code argMapping} stores
     * parsed segment chains keyed by Java target; single-segment chains cover the single-name
     * mapping case, multi-segment chains cover dot-path expressions into nested input fields.
     * Schema walking happens later when the consumer calls {@link ArgBindingMap#of}.
     */
    record ExternalRef(String className, String methodName,
                       Map<String, java.util.List<String>> argMapping,
                       String lookupError, String argMappingError) {}

    static Set<String> fieldArgumentNames(GraphQLFieldDefinition fieldDef) {
        return fieldDef.getArguments().stream()
            .map(GraphQLArgument::getName)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * The names of the field-level chain-directive applications ({@code @routine} /
     * {@code @reference}) in authored order — the order the GraphQL parser preserves and the
     * only order-significant read in the classifier (R435). One entry per application, so a
     * repeated directive contributes one entry per repetition.
     */
    private static java.util.List<String> chainDirectiveNames(GraphQLFieldDefinition fieldDef) {
        return fieldDef.getAppliedDirectives().stream()
            .map(graphql.schema.GraphQLAppliedDirective::getName)
            .filter(n -> DIR_ROUTINE.equals(n) || DIR_REFERENCE.equals(n))
            .toList();
    }

    /**
     * Maps each GraphQL argument on {@code fieldDef} to its {@link graphql.schema.GraphQLInputType}.
     * Feeds {@link ArgBindingMap#of} as the slot-type oracle for resolving path expressions
     * against the field's argument types. Insertion order preserved.
     */
    static Map<String, graphql.schema.GraphQLInputType> argSlotTypes(GraphQLFieldDefinition fieldDef) {
        var out = new LinkedHashMap<String, graphql.schema.GraphQLInputType>();
        for (var arg : fieldDef.getArguments()) {
            out.put(arg.getName(), arg.getType());
        }
        return out;
    }
}
