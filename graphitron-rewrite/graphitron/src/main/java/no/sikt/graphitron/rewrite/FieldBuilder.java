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
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.ChildField.ColumnField;
import no.sikt.graphitron.rewrite.model.ChildField.ConstructorField;
import no.sikt.graphitron.rewrite.model.ChildField.ColumnReferenceField;
import no.sikt.graphitron.rewrite.model.ChildField.ParticipantColumnReferenceField;
import no.sikt.graphitron.rewrite.model.ChildField.ComputedField;
import no.sikt.graphitron.rewrite.model.ChildField.ErrorsField;
import no.sikt.graphitron.rewrite.model.ChildField.InterfaceField;
import no.sikt.graphitron.rewrite.model.ChildField.MultitableReferenceField;
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
import no.sikt.graphitron.rewrite.model.BatchKey;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.ErrorChannel;
import no.sikt.graphitron.rewrite.model.PayloadAssembly;
import no.sikt.graphitron.rewrite.model.InputColumnBinding;
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
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.LookupMapping;
import no.sikt.graphitron.rewrite.model.LookupMapping.ColumnMapping;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.PaginationSpec;
import no.sikt.graphitron.rewrite.model.ParticipantRef;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.TableRef;

import no.sikt.graphitron.rewrite.model.BodyParam;
import no.sikt.graphitron.rewrite.model.CallParam;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ConditionFilter;
import no.sikt.graphitron.rewrite.model.GeneratedConditionFilter;
import no.sikt.graphitron.rewrite.model.WhereFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static no.sikt.graphitron.rewrite.BuildContext.ARG_CLASS_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_CONTEXT_ARGUMENTS;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_ARG_MAPPING;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_METHOD;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_TYPE_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_AS_CONNECTION;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_BATCH_KEY_LIFTER;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_EXTERNAL_FIELD;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_FIELD;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_LOOKUP_KEY;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_MULTITABLE_REFERENCE;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_MUTATION;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_NODE_ID;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_NOT_GENERATED;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_ORDER_BY;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_REFERENCE;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_SERVICE;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_SPLIT_QUERY;
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

    private final BuildContext ctx;
    private final ServiceCatalog svc;
    private final ServiceDirectiveResolver serviceResolver;
    private final TableMethodDirectiveResolver tableMethodResolver;
    private final ExternalFieldDirectiveResolver externalFieldResolver;
    private final LookupKeyDirectiveResolver lookupKeyResolver;
    private final OrderByResolver orderByResolver;
    private final LookupMappingResolver lookupMappingResolver;
    private final PaginationResolver paginationResolver;
    private final ConditionResolver conditionResolver;
    private final InputFieldResolver inputFieldResolver;
    private final MutationInputResolver mutationInputResolver;
    private final EnumMappingResolver enumMappingResolver;
    private final BatchKeyLifterDirectiveResolver batchKeyLifterResolver;
    FieldBuilder(BuildContext ctx, ServiceCatalog svc) {
        this.ctx = ctx;
        this.svc = svc;
        this.enumMappingResolver = new EnumMappingResolver(ctx);
        this.serviceResolver = new ServiceDirectiveResolver(ctx, svc, this, enumMappingResolver);
        this.tableMethodResolver = new TableMethodDirectiveResolver(ctx, svc, this, enumMappingResolver);
        this.externalFieldResolver = new ExternalFieldDirectiveResolver(ctx, svc, this);
        this.lookupKeyResolver = new LookupKeyDirectiveResolver();
        this.orderByResolver = new OrderByResolver(ctx);
        this.lookupMappingResolver = new LookupMappingResolver();
        this.paginationResolver = new PaginationResolver();
        this.conditionResolver = new ConditionResolver(ctx, svc);
        this.inputFieldResolver = new InputFieldResolver(ctx);
        this.mutationInputResolver = new MutationInputResolver(ctx, conditionResolver, enumMappingResolver);
        this.batchKeyLifterResolver = new BatchKeyLifterDirectiveResolver(ctx, this);
    }

    // ===== Shared resolution helpers =====

    /**
     * Extracts the {@link BatchKey} from the first {@link MethodRef.Param.Sourced} parameter of the
     * given method, or {@code null} when the method has no such parameter.
     *
     * <p>A {@code null} result means the service method lacks the required {@code Sources}
     * parameter — the validator will surface this as an error before code generation runs.
     */
    private static BatchKey.ParentKeyed extractBatchKey(MethodRef method) {
        return method.params().stream()
            .filter(p -> p instanceof MethodRef.Param.Sourced)
            .map(p -> ((MethodRef.Param.Sourced) p).batchKey())
            .findFirst()
            .orElse(null);
    }

    /**
     * Outcome of {@link #resolveTableFieldComponents}. Two terminal arms the caller exhausts
     * with a switch or {@code instanceof}; mirrors the per-resolver {@code Resolved} shapes the
     * orchestrator threads together.
     */
    private sealed interface TableFieldComponents {
        record Ok(List<WhereFilter> filters, OrderBySpec orderBy, PaginationSpec pagination,
                  LookupMapping lookupMapping) implements TableFieldComponents {}
        record Rejected(String message) implements TableFieldComponents {}
    }

    /**
     * Resolves the filter, order-by, and pagination components for a table-bound list field.
     * Returns a non-null {@code error} when any component fails to resolve.
     *
     * @param returnTypeName the GraphQL return type name (e.g. {@code "Film"}), used to derive
     *                       the {@code *Conditions} class name for any generated filter method
     */
    private TableFieldComponents resolveTableFieldComponents(GraphQLFieldDefinition fieldDef, TableRef table, String returnTypeName) {
        // R40: @asConnection + same-table @nodeId leaf is incoherent at runtime — the result
        // cardinality is bounded by the input id list (lookup semantics), not paginatable.
        // Reject before classification so the structural conflict surfaces with a pointed hint
        // instead of building a degenerate connection. Symmetric across argument-level and
        // input-field leaves: closes the latent R50 gap where the input-field same-table case
        // was already a silent runtime mismatch.
        if (fieldDef.hasAppliedDirective(DIR_AS_CONNECTION)) {
            String rejection = findSameTableNodeIdUnderAsConnection(fieldDef, table);
            if (rejection != null) {
                return new TableFieldComponents.Rejected(rejection);
            }
        }
        var errors = new ArrayList<String>();
        var refs = classifyArguments(fieldDef, table, errors);
        return projectForFilter(refs, fieldDef, table, returnTypeName, errors);
    }

    /**
     * Walks {@code fieldDef}'s argument set looking for any {@code @nodeId}-decorated leaf —
     * top-level arguments and nested input-field leaves under {@code @table}-input or plain
     * input arguments — that resolves to {@link NodeIdLeafResolver.Resolved.SameTable} against
     * {@code containingTable}. Returns a fully formatted rejection message when one is found,
     * or {@code null} when the field's argument set is consistent with {@code @asConnection}.
     *
     * <p>The walk reuses {@link NodeIdLeafResolver#resolve} so the shape decision (same-table
     * vs FK-target) lives in one place. FK-target leaves do not trigger the rejection — they
     * are legitimate filter args that compose with seek pagination.
     */
    private String findSameTableNodeIdUnderAsConnection(GraphQLFieldDefinition fieldDef, TableRef containingTable) {
        var resolver = ctx.nodeIdLeafResolver();
        for (var arg : fieldDef.getArguments()) {
            String hit = checkLeafForSameTableNodeId(resolver, arg, arg.getName(), containingTable, fieldDef.getName());
            if (hit != null) return hit;
            var argType = GraphQLTypeUtil.unwrapAll(arg.getType());
            if (argType instanceof GraphQLInputObjectType iot) {
                String nestedHit = walkInputTypeForSameTableNodeId(resolver, iot, containingTable, fieldDef.getName(), new java.util.LinkedHashSet<>());
                if (nestedHit != null) return nestedHit;
            }
        }
        return null;
    }

    private String walkInputTypeForSameTableNodeId(NodeIdLeafResolver resolver, GraphQLInputObjectType iot,
                                                   TableRef containingTable, String fieldName,
                                                   java.util.LinkedHashSet<String> visited) {
        if (!visited.add(iot.getName())) return null;
        for (var inputField : iot.getFieldDefinitions()) {
            String hit = checkLeafForSameTableNodeId(resolver, inputField, inputField.getName(), containingTable, fieldName);
            if (hit != null) return hit;
            var nestedType = GraphQLTypeUtil.unwrapAll(inputField.getType());
            if (nestedType instanceof GraphQLInputObjectType nestedIot) {
                String nestedHit = walkInputTypeForSameTableNodeId(resolver, nestedIot, containingTable, fieldName, visited);
                if (nestedHit != null) return nestedHit;
            }
        }
        return null;
    }

    private String checkLeafForSameTableNodeId(NodeIdLeafResolver resolver, graphql.schema.GraphQLDirectiveContainer leaf,
                                               String leafName, TableRef containingTable, String fieldName) {
        if (!leaf.hasAppliedDirective(DIR_NODE_ID)) return null;
        var resolved = resolver.resolve(leaf, leafName, containingTable);
        if (resolved instanceof NodeIdLeafResolver.Resolved.SameTable st) {
            return "@nodeId(typeName: '" + st.refTypeName() + "') on '" + leafName + "' resolves to '"
                + containingTable.tableName() + "', the field's own backing table, which makes this"
                + " argument a lookup key. Lookups don't compose with @asConnection (the result"
                + " cardinality is bounded by the input list, not paginatable). Drop @asConnection"
                + " from '" + fieldName + "', or use a filter argument that resolves to a different"
                + " table via FK.";
        }
        return null;
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
        GraphitronType elementType = ctx.types.get(elementTypeName);

        if (elementType instanceof TableBackedType tbt && !(elementType instanceof TableInterfaceType)) {
            var wrapper = buildWrapper(fieldDef);
            var returnType = (ReturnTypeRef.TableBoundReturnType) ctx.resolveReturnType(elementTypeName, wrapper);
            var referencePath = ctx.parsePath(fieldDef, name, parentTableType.table().tableName(), returnType.table().tableName());
            if (referencePath.hasError()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR, referencePath.errorMessage());
            }
            var components = resolveTableFieldComponents(fieldDef, returnType.table(), elementTypeName);
            if (components instanceof TableFieldComponents.Rejected rj) return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR, rj.message());
            var tfc = (TableFieldComponents.Ok) components;
            boolean hasSplitQuery = fieldDef.hasAppliedDirective(DIR_SPLIT_QUERY);
            boolean hasLookupKey  = hasLookupKeyAnywhere(fieldDef);
            boolean isList = returnType.wrapper().isList();
            var parentBatchKey = deriveSplitQueryBatchKey(parentTableType.table(), referencePath.elements(), isList);
            if (hasSplitQuery && hasLookupKey) {
                var lookupResolved = lookupKeyResolver.resolveAtChild(returnType, true);
                if (lookupResolved instanceof LookupKeyDirectiveResolver.Resolved.Rejected r) {
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef, r.kind(), r.message());
                }
                return new no.sikt.graphitron.rewrite.model.ChildField.SplitLookupTableField(
                    parentTypeName, name, location, returnType, referencePath.elements(), tfc.filters(), tfc.orderBy(), tfc.pagination(), parentBatchKey,
                    tfc.lookupMapping());
            }
            if (!hasSplitQuery && hasLookupKey) {
                var lookupResolved = lookupKeyResolver.resolveAtChild(returnType, false);
                if (lookupResolved instanceof LookupKeyDirectiveResolver.Resolved.Rejected r) {
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef, r.kind(), r.message());
                }
                return new no.sikt.graphitron.rewrite.model.ChildField.LookupTableField(
                    parentTypeName, name, location, returnType, referencePath.elements(), tfc.filters(), tfc.orderBy(), tfc.pagination(),
                    tfc.lookupMapping());
            }
            if (hasSplitQuery) {
                if (returnType.wrapper() instanceof FieldWrapper.Single
                        && referencePath.elements().size() != 1) {
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.DEFERRED,
                        "Single-cardinality @splitQuery requires a single-hop parent-holds-FK reference path; "
                        + "multi-hop paths are not yet supported on single cardinality");
                }
                return new no.sikt.graphitron.rewrite.model.ChildField.SplitTableField(
                    parentTypeName, name, location, returnType, referencePath.elements(), tfc.filters(), tfc.orderBy(), tfc.pagination(), parentBatchKey);
            }
            if (returnType.wrapper() instanceof FieldWrapper.Connection) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.INVALID_SCHEMA,
                    "@asConnection on inline (non-@splitQuery) TableField is not supported; add @splitQuery for batched connection semantics");
            }
            return new TableField(parentTypeName, name, location,
                returnType, referencePath.elements(), tfc.filters(), tfc.orderBy(), tfc.pagination());
        }

        if (elementType instanceof TableInterfaceType tableInterfaceType) {
            var wrapper = buildWrapper(fieldDef);
            var referencePath = ctx.parsePath(fieldDef, name, parentTableType.table().tableName(), tableInterfaceType.table().tableName());
            if (referencePath.hasError()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR, referencePath.errorMessage());
            }
            var components = resolveTableFieldComponents(fieldDef, tableInterfaceType.table(), elementTypeName);
            if (components instanceof TableFieldComponents.Rejected rj) return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR, rj.message());
            var tfc = (TableFieldComponents.Ok) components;
            var joinPathError = validateSingleHopFkJoin(referencePath.elements(), name);
            if (joinPathError != null) return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR, joinPathError);
            var knownValues = knownDiscriminatorValues(tableInterfaceType);
            return new TableInterfaceField(parentTypeName, name, location,
                new ReturnTypeRef.TableBoundReturnType(elementTypeName, tableInterfaceType.table(), wrapper),
                tableInterfaceType.discriminatorColumn(), knownValues, tableInterfaceType.participants(),
                referencePath.elements(), tfc.filters(), tfc.orderBy(), tfc.pagination());
        }

        if (elementType instanceof InterfaceType interfaceType) {
            // R36 Track B3: per-participant FK auto-discovery from parent table to each
            // participant's table. parsePath looks for a unique FK between the two and falls
            // back to a directive-stated path when the @reference path: array is non-empty.
            // For B3 v1 we expect the auto-discovery branch; an explicit shared @reference path
            // would apply ambiguously across heterogeneous participants, so callers should not
            // declare one on multi-table interface child fields.
            var resolved = resolveChildPolymorphicJoinPaths(fieldDef, name, parentTypeName,
                location, parentTableType.table(), interfaceType.participants());
            if (resolved.error() != null) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                    RejectionKind.AUTHOR_ERROR, resolved.error());
            }
            return new InterfaceField(parentTypeName, name, location,
                new ReturnTypeRef.PolymorphicReturnType(elementTypeName, buildWrapper(fieldDef)),
                interfaceType.participants(), resolved.paths());
        }

        if (elementType instanceof UnionType unionType) {
            var resolved = resolveChildPolymorphicJoinPaths(fieldDef, name, parentTypeName,
                location, parentTableType.table(), unionType.participants());
            if (resolved.error() != null) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                    RejectionKind.AUTHOR_ERROR, resolved.error());
            }
            return new UnionField(parentTypeName, name, location,
                new ReturnTypeRef.PolymorphicReturnType(elementTypeName, buildWrapper(fieldDef)),
                unionType.participants(), resolved.paths());
        }

        // NestingField: a plain object type in the schema with no Graphitron domain classification.
        // Its fields are resolved from the same table context as the parent — classified
        // recursively so nested scalars reach the model as ColumnField (and future arms as
        // their respective leaves). @record parents cannot reach here; this path is gated
        // on TableBackedType by classifyChildFieldOnTableType's caller at line 1217.
        //
        // "No domain classification" includes both the pre-classifier-records-plain-types state
        // (elementType == null) and the post-Phase-4 state (classified as PlainObjectType).
        boolean isPlainObjectElement = elementType == null
            || elementType instanceof GraphitronType.PlainObjectType;
        if (ctx.schema.getType(elementTypeName) instanceof GraphQLObjectType graphQLObjectType
                && isPlainObjectElement) {
            var wrapper = buildWrapper(fieldDef);
            if (expandingTypes.contains(elementTypeName)) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.INVALID_SCHEMA,
                    "circular type reference detected while expanding '" + elementTypeName + "'");
            }
            var newExpanding = new LinkedHashSet<>(expandingTypes);
            newExpanding.add(elementTypeName);
            var nestedFields = new ArrayList<ChildField>();
            for (var nestedDef : graphQLObjectType.getFieldDefinitions()) {
                var nested = classifyChildFieldOnTableType(nestedDef, elementTypeName, parentTableType, newExpanding);
                if (nested instanceof UnclassifiedField unc) {
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef, unc.kind(),
                        "nested type '" + elementTypeName + "' field '" + nestedDef.getName() + "': " + unc.reason());
                }
                if (nested instanceof ChildField cf) {
                    nestedFields.add(cf);
                } else {
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.INTERNAL_INVARIANT,
                        "nested type '" + elementTypeName + "' field '" + nestedDef.getName()
                        + "' classified as unexpected variant " + nested.getClass().getSimpleName());
                }
            }
            return new NestingField(parentTypeName, name, location,
                new ReturnTypeRef.TableBoundReturnType(elementTypeName, parentTableType.table(), wrapper),
                List.copyOf(nestedFields));
        }

        // ConstructorField: @table parent with a @record child — pass the parent's Record through as
        // the child's source. The child's own Fetchers class handles property/table-child resolution.
        if (elementType instanceof ResultType rt) {
            var wrapper = buildWrapper(fieldDef);
            var returnType = (ReturnTypeRef.ResultReturnType) ctx.resolveReturnType(elementTypeName, wrapper);
            return new ConstructorField(parentTypeName, name, location, returnType);
        }

        return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR,
            "return type '" + elementTypeName + "' is not a @table, @record, interface, or union Graphitron type");
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
        GraphQLType fieldType = fieldDef.getType();
        boolean outerNullable = !(fieldType instanceof GraphQLNonNull);
        GraphQLType unwrappedOnce = GraphQLTypeUtil.unwrapNonNull(fieldType);

        // @asConnection on a list field → Connection wrapper.
        // Per-type metadata (name, element, item nullability) lives on ConnectionType in
        // schema.types(); this wrapper only carries per-carrier-site pagination metadata.
        if (fieldDef.hasAppliedDirective(DIR_AS_CONNECTION) && unwrappedOnce instanceof GraphQLList) {
            int defaultPageSize = paginationResolver.resolveDefaultFirstValue(fieldDef);
            return new FieldWrapper.Connection(outerNullable, defaultPageSize);
        }

        if (unwrappedOnce instanceof GraphQLList listType) {
            boolean itemNullable = !(listType.getWrappedType() instanceof GraphQLNonNull);
            return new FieldWrapper.List(outerNullable, itemNullable);
        }

        // Structural detection: pre-expanded Connection type with edges.node pattern.
        String typeName = baseTypeName(fieldDef);
        if (ctx.isConnectionType(typeName)) {
            return new FieldWrapper.Connection(outerNullable, FieldWrapper.DEFAULT_PAGE_SIZE);
        }

        return new FieldWrapper.Single(outerNullable);
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
    List<ArgumentRef> classifyArguments(GraphQLFieldDefinition fieldDef, TableRef rt, List<String> errors) {
        var fieldCondition = ctx.readConditionDirective(fieldDef);
        boolean fieldOverride = fieldCondition != null && fieldCondition.override();
        var refs = new ArrayList<ArgumentRef>();
        for (var arg : fieldDef.getArguments()) {
            refs.add(classifyArgument(fieldDef, arg, rt, fieldOverride, errors));
        }
        return List.copyOf(refs);
    }

    @no.sikt.graphitron.rewrite.model.LoadBearingClassifierCheck(
        key = "r40.nodeid-fk-target.direct-fk",
        description = "FK-target @nodeId arg arm rejects classification when the FK's "
            + "targetColumns do not positionally match the resolved NodeType's keyColumns "
            + "(sameColumnsBySqlName check). The accepted shape always has FK target = "
            + "NodeType key, so projectFilters can bind BodyParam.In/Eq/RowIn/RowEq directly "
            + "against fkJoin.sourceColumns() without a JOIN. JOIN-with-translation emission "
            + "for the rejected pathological case is deferred to R57.")
    private ArgumentRef classifyArgument(GraphQLFieldDefinition fieldDef, GraphQLArgument arg,
                                         TableRef rt, boolean fieldOverride, List<String> errors) {
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
        var resolvedType = ctx.types.get(typeName);
        if (resolvedType instanceof GraphitronType.TableInputType tit) {
            List<InputColumnBinding.MapBinding> bindings = enumMappingResolver.buildLookupBindings(tit, arg, fieldDef, name, errors);
            return new ArgumentRef.InputTypeArg.TableInputArg(
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
                        "input field '" + rejected.get().getName()
                        + "': @notGenerated is no longer supported. Remove the directive; fields must be fully described by the schema.");
                }
            }
            List<InputField> plainFields = inputFieldResolver.resolve(typeName, rt, errors);
            return new ArgumentRef.InputTypeArg.PlainInputArg(
                name, typeName, nonNull, list, argCondition, plainFields);
        }

        // R40: @nodeId-decorated ID arg routes through NodeIdLeafResolver to pick same-table
        // (lookup) vs FK-target (filter) shape. Sits before the legacy implicit scalar-ID arm
        // below, which keeps owning synthesised paths (no @nodeId declared, parent table has
        // nodeId metadata) per scope.
        if ("ID".equals(typeName) && arg.hasAppliedDirective(DIR_NODE_ID)) {
            // Composition rejections: @nodeId is incompatible with @lookupKey (redundant for
            // same-table since @nodeId implies it; meaningless for FK-target since FK is a
            // filter, not a lookup) and with @field(name:) (the two target different binding
            // axes — key columns come from the resolved NodeType, not the directive).
            if (arg.hasAppliedDirective(DIR_LOOKUP_KEY)) {
                return new ArgumentRef.UnclassifiedArg(name, typeName, nonNull, list,
                    "@nodeId already implies @lookupKey for same-table; the explicit directive is"
                    + " redundant (and FK-target @nodeId is a filter, not a lookup, where"
                    + " @lookupKey is meaningless)");
            }
            if (arg.hasAppliedDirective(DIR_FIELD)) {
                return new ArgumentRef.UnclassifiedArg(name, typeName, nonNull, list,
                    "@nodeId arg cannot also carry @field(name:); the directives target different"
                    + " binding axes (key columns come from the resolved NodeType, not the"
                    + " @field directive)");
            }
            var resolved = ctx.nodeIdLeafResolver().resolve(arg, name, rt);
            switch (resolved) {
                case NodeIdLeafResolver.Resolved.Rejected r ->
                    { return new ArgumentRef.UnclassifiedArg(name, typeName, nonNull, list, r.message()); }
                case NodeIdLeafResolver.Resolved.SameTable st -> {
                    // Same-table @nodeId arg = lookup-by-id semantics. The existing @lookupKey
                    // dispatch path (LookupValuesJoinEmitter) requires ThrowOnMismatch — the
                    // per-row decode in addRowBuildingCore throws on null. Reusing that path
                    // gives R40 lookup semantics for free; failure-mode parity (Skip vs Throw)
                    // for the arg side is a deferred shape question, not blocking R40.
                    var extraction = new CallSiteExtraction.ThrowOnMismatch(st.decodeMethod());
                    var keys = st.keyColumns();
                    if (keys.size() == 1) {
                        return new ArgumentRef.ScalarArg.ColumnArg(
                            name, typeName, nonNull, list, keys.get(0), extraction,
                            argCondition, fieldOverride, /* isLookupKey= */ true);
                    }
                    return new ArgumentRef.ScalarArg.CompositeColumnArg(
                        name, typeName, nonNull, list, keys, extraction,
                        argCondition, fieldOverride, /* isLookupKey= */ true);
                }
                case NodeIdLeafResolver.Resolved.FkTarget ft -> {
                    // FK-target @nodeId arg = filter semantics. Skip extraction (malformed ids
                    // drop silently to "no match"). projectFilters emits BodyParam.In/Eq/RowIn
                    // /RowEq using FK source columns from joinPath — direct, no JOIN — but
                    // only when the FK's targetColumns positionally match the NodeType key
                    // columns (the simple direct-FK case where FK target = NodeType key).
                    // Pathological cases where they differ (e.g. R50 parent_node + child_ref
                    // fixture: FK targets parent.alt_key, NodeType key is parent.pk_id) need
                    // JOIN-with-translation emission and are filed as a sibling follow-on
                    // parallel to R24's output-side JOIN-with-projection arm.
                    var fkJoin = (JoinStep.FkJoin) ft.joinPath().get(0);
                    if (!sameColumnsBySqlName(fkJoin.targetColumns(), ft.keyColumns())) {
                        return new ArgumentRef.UnclassifiedArg(name, typeName, nonNull, list,
                            "@nodeId(typeName: '" + ft.refTypeName() + "') FK-target arg on table '"
                            + rt.tableName() + "': the FK's target columns do not positionally"
                            + " match NodeType '" + ft.refTypeName() + "''s key columns,"
                            + " so emission requires JOIN-with-translation."
                            + " This pathological case is deferred to a sibling follow-on parallel"
                            + " to R24's output-side JOIN-with-projection emission.");
                    }
                    var extraction = new CallSiteExtraction.SkipMismatchedElement(ft.decodeMethod());
                    var keys = ft.keyColumns();
                    if (keys.size() == 1) {
                        return new ArgumentRef.ScalarArg.ColumnReferenceArg(
                            name, typeName, nonNull, list, keys.get(0), fkJoin,
                            extraction, argCondition, fieldOverride);
                    }
                    return new ArgumentRef.ScalarArg.CompositeColumnReferenceArg(
                        name, typeName, nonNull, list, keys, fkJoin,
                        extraction, argCondition, fieldOverride);
                }
            }
        }

        // R50 phase (f-D1)/(g-A): scalar ID arg on a node-type table folds onto a column-shaped
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
                // Composite-PK + non-list non-@lookupKey: today's classifier only wires the
                // composite-PK path with @lookupKey (mutation-key and top-level filter paths land
                // in a later R50 slice). Non-list arity-1 falls through to ColumnArg below.
                if (keyColumns.size() > 1 && !isLookupKey) {
                    return new ArgumentRef.UnclassifiedArg(name, typeName, nonNull, list,
                        "scalar @nodeId arg targeting a composite-PK NodeType is only wired for "
                        + "@lookupKey today; mutation-key and top-level filter paths land in a "
                        + "later R50 slice");
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
                            "@nodeId arg: unable to resolve decode helper for table '" + rt.tableName() + "'");
                    }
                    var extraction = new CallSiteExtraction.ThrowOnMismatch(decodeMethod);
                    if (keyColumns.size() == 1) {
                        return new ArgumentRef.ScalarArg.ColumnArg(
                            name, typeName, nonNull, list, keyColumns.get(0), extraction,
                            argCondition, fieldOverride, isLookupKey);
                    }
                    return new ArgumentRef.ScalarArg.CompositeColumnArg(
                        name, typeName, nonNull, list, keyColumns, extraction,
                        argCondition, fieldOverride, isLookupKey);
                }
            }
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
        if (!columnName.equals(col.get().javaName())) {
            LOG.warn("@field(name: '{}') on arg '{}' resolved via SQL name; prefer Java field name '{}'",
                columnName, name, col.get().javaName());
        }
        var columnRef = new ColumnRef(col.get().sqlName(), col.get().javaName(), col.get().columnClass());
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
                    "enum filter validation failed for column '" + columnRef.sqlName() + "'");
            }
        }
        CallSiteExtraction extraction = enumMappingResolver.deriveExtraction(typeName, columnRef, enumClassName,
            fieldDef.getName().toUpperCase() + "_" + name.toUpperCase() + "_MAP");
        boolean isLookupKey = arg.hasAppliedDirective(DIR_LOOKUP_KEY);
        return new ArgumentRef.ScalarArg.ColumnArg(
            name, typeName, nonNull, list, columnRef, extraction, argCondition, fieldOverride, isLookupKey);
    }

    private ArgumentRef classifyOrderByArg(GraphQLArgument arg, String name, String typeName,
                                           boolean nonNull, boolean list, List<String> errors) {
        var rawType = ctx.schema.getType(typeName);
        if (!(rawType instanceof GraphQLInputObjectType inputType)) {
            return new ArgumentRef.UnclassifiedArg(name, typeName, nonNull, list,
                "@orderBy argument type '" + typeName + "' is not an input type");
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
                        "@orderBy input type '" + typeName + "' must have exactly one sort enum field, but found multiple");
                }
                sortFieldName = field.getName();
            } else {
                if (directionFieldName != null) {
                    return new ArgumentRef.UnclassifiedArg(name, typeName, nonNull, list,
                        "@orderBy input type '" + typeName + "' must have exactly one direction field, but found multiple");
                }
                directionFieldName = field.getName();
            }
        }
        if (sortFieldName == null) {
            return new ArgumentRef.UnclassifiedArg(name, typeName, nonNull, list,
                "@orderBy input type '" + typeName + "' has no sort enum field (no enum values with @order)");
        }
        if (directionFieldName == null) {
            return new ArgumentRef.UnclassifiedArg(name, typeName, nonNull, list,
                "@orderBy input type '" + typeName + "' has no direction field");
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
                                                  TableRef rt, String returnTypeName, List<String> errors) {
        var filters = projectFilters(refs, fieldDef, rt, returnTypeName, errors);
        if (filters == null) return new TableFieldComponents.Rejected(String.join("; ", errors));
        ConditionFilter fieldCondition;
        switch (conditionResolver.resolveField(fieldDef)) {
            case ConditionResolver.FieldConditionResult.None n -> fieldCondition = null;
            case ConditionResolver.FieldConditionResult.Ok ok -> fieldCondition = ok.filter();
            case ConditionResolver.FieldConditionResult.Rejected r -> {
                errors.add(r.message());
                return new TableFieldComponents.Rejected(String.join("; ", errors));
            }
        }
        if (fieldCondition != null) {
            var withField = new ArrayList<>(filters);
            withField.add(fieldCondition);
            filters = List.copyOf(withField);
        }
        var orderByResolved = orderByResolver.resolve(refs, fieldDef, rt.tableName());
        if (orderByResolved instanceof OrderByResolver.Resolved.Rejected r) {
            errors.add(r.message());
            return new TableFieldComponents.Rejected(String.join("; ", errors));
        }
        OrderBySpec orderBy = ((OrderByResolver.Resolved.Ok) orderByResolved).spec();
        var lookupMapping = lookupMappingResolver.resolve(refs, rt);
        // LookupField invariant: if any @lookupKey is present, the mapping must be non-empty.
        // ColumnMapping must have at least one arg. (Pre-R50 phase (f-D), a NodeIdMapping arm
        // covered scalar NodeId @lookupKey args; that's now folded onto ColumnMapping carrying
        // ScalarLookupArg with NodeIdDecodeKeys.ThrowOnMismatch.)
        boolean emptyMapping = switch (lookupMapping) {
            case ColumnMapping cm -> cm.args().isEmpty();
        };
        if (hasLookupKeyAnywhere(fieldDef) && emptyMapping) {
            // Prefer the specific binding-failure reason (e.g. @lookupKey on a @reference field)
            // when buildLookupBindings recorded one; fall back to the generic empty-mapping error.
            String msg = errors.isEmpty()
                ? "@lookupKey is declared but no argument resolved to a lookup column"
                : String.join("; ", errors);
            return new TableFieldComponents.Rejected(msg);
        }
        return new TableFieldComponents.Ok(filters, orderBy, paginationResolver.resolve(refs, fieldDef), lookupMapping);
    }

    /**
     * Resolves the Java type that a {@link BodyParam} must carry given its extraction strategy
     * and target column. Extracted from the old {@code buildFilters} switch so projection can
     * derive {@link BodyParam#javaType} without re-classifying the argument.
     */
    private static String javaTypeFor(CallSiteExtraction extraction, ColumnRef column) {
        return switch (extraction) {
            case CallSiteExtraction.EnumValueOf ev -> ev.enumClassName();
            case CallSiteExtraction.TextMapLookup ignored -> String.class.getName();
            case CallSiteExtraction.JooqConvert ignored -> column.columnClass();
            case CallSiteExtraction.Direct ignored -> column.columnClass();
            case CallSiteExtraction.ContextArg ignored -> column.columnClass();
            case CallSiteExtraction.NestedInputField ignored -> column.columnClass();
            case CallSiteExtraction.NodeIdDecodeKeys ignored -> column.columnClass();
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
    @no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck(
        key = "r40.nodeid-fk-target.direct-fk",
        reliesOn = "ColumnReferenceArg / CompositeColumnReferenceArg arms bind BodyParam.In/Eq"
            + "/RowIn/RowEq directly against fkJoin.sourceColumns() on the field's containing"
            + " table, with no JOIN — the classifier guarantees FK targetColumns positionally"
            + " match NodeType keyColumns, so the decoded keys feed the predicate against the"
            + " FK source columns without translation. Relaxing the classifier check would"
            + " break this binding silently at SQL-shape level.")
    private List<WhereFilter> projectFilters(List<ArgumentRef> refs, GraphQLFieldDefinition fieldDef,
                                             TableRef rt, String returnTypeName, List<String> errors) {
        var bodyParams = new ArrayList<BodyParam>();
        var argConditions = new ArrayList<ConditionFilter>();
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
                    var lookupBoundNames = tia.fieldBindings().stream()
                        .map(InputColumnBinding.MapBinding::fieldName)
                        .collect(Collectors.toUnmodifiableSet());
                    var implicitParams = new ArrayList<BodyParam>();
                    walkInputFieldConditions(tia.fields(), tia.name(), List.of(),
                        enclosingOverride, lookupBoundNames, implicitParams, argConditions);
                    bodyParams.addAll(implicitParams);
                }
                case ArgumentRef.InputTypeArg.PlainInputArg pia -> {
                    // Plain input types are silently skipped unless paired with @condition;
                    // see the out-of-scope note in docs/argument-resolution.md.
                    pia.argCondition().ifPresent(ac -> argConditions.add(ac.filter()));
                    walkInputFieldConditions(pia.fields(), pia.name(), List.of(),
                        false, Set.of(), null, argConditions);
                }
                case ArgumentRef.UnclassifiedArg u -> {
                    errors.add("argument '" + u.name() + "': " + u.reason());
                    hadError = true;
                }
                case ArgumentRef.ScalarArg.UnboundArg u -> {
                    errors.add("argument '" + u.name() + "': " + u.reason());
                    hadError = true;
                }
                case ArgumentRef.ScalarArg.ColumnArg ca -> {
                    boolean autoSuppressed = ca.suppressedByFieldOverride()
                        || (ca.argCondition().isPresent() && ca.argCondition().get().override());
                    // Lookup-key args are consumed by LookupMappingResolver → LookupMapping and
                    // emitted via VALUES+JOIN by LookupValuesJoinEmitter. They must not appear
                    // as GeneratedConditionFilter bodyParams (per docs/argument-resolution.md Phase 1).
                    if (!autoSuppressed && !ca.isLookupKey()) {
                        String javaType = javaTypeFor(ca.extraction(), ca.column());
                        bodyParams.add(ca.list()
                            ? new BodyParam.In(ca.name(), ca.column(), javaType, ca.nonNull(), ca.extraction())
                            : new BodyParam.Eq(ca.name(), ca.column(), javaType, ca.nonNull(), ca.extraction()));
                    }
                    ca.argCondition().ifPresent(ac -> argConditions.add(ac.filter()));
                }
                case ArgumentRef.ScalarArg.CompositeColumnArg cca -> {
                    boolean autoSuppressed = cca.suppressedByFieldOverride()
                        || (cca.argCondition().isPresent() && cca.argCondition().get().override());
                    // R40: composite-PK NodeId scalar args now reach this branch with
                    // isLookupKey == false when @nodeId(typeName: T) targets the field's own
                    // table without an explicit @lookupKey (the same-table arm synthesises
                    // isLookupKey: true via classifyArgument; non-lookup-key composite-PK args
                    // are top-level filter args under @condition / @field paths). Project to
                    // BodyParam.RowEq (scalar) / RowIn (list) using the carrier's column tuple
                    // and NodeIdDecodeKeys extraction; LookupMappingResolver consumes the
                    // isLookupKey branch separately.
                    if (!autoSuppressed && !cca.isLookupKey()) {
                        String javaType = "org.jooq.RowN";
                        bodyParams.add(cca.list()
                            ? new BodyParam.RowIn(cca.name(), cca.columns(), javaType, cca.nonNull(), cca.extraction())
                            : new BodyParam.RowEq(cca.name(), cca.columns(), javaType, cca.nonNull(), cca.extraction()));
                    }
                    cca.argCondition().ifPresent(ac -> argConditions.add(ac.filter()));
                }
                case ArgumentRef.ScalarArg.ColumnReferenceArg cra -> {
                    // R40 FK-target arm. The carrier's column is the target NodeType's key
                    // column; cra.fkJoin() is the single-hop FK whose sourceColumns sit on
                    // the field's own containing table. The classifier already enforced the
                    // direct-FK precondition (FK targetColumns positionally match NodeType
                    // keyColumns); see the @LoadBearingClassifierCheck producer at
                    // classifyArgument, which rejects the JOIN-with-translation case as
                    // UnclassifiedArg. We can therefore bind the predicate against the FK
                    // source columns directly — no JOIN needed.
                    boolean autoSuppressed = cra.suppressedByFieldOverride()
                        || (cra.argCondition().isPresent() && cra.argCondition().get().override());
                    if (!autoSuppressed) {
                        ColumnRef fkSourceColumn = cra.fkJoin().sourceColumns().get(0);
                        String javaType = javaTypeFor(cra.extraction(), fkSourceColumn);
                        bodyParams.add(cra.list()
                            ? new BodyParam.In(cra.name(), fkSourceColumn, javaType, cra.nonNull(), cra.extraction())
                            : new BodyParam.Eq(cra.name(), fkSourceColumn, javaType, cra.nonNull(), cra.extraction()));
                    }
                    cra.argCondition().ifPresent(ac -> argConditions.add(ac.filter()));
                }
                case ArgumentRef.ScalarArg.CompositeColumnReferenceArg ccra -> {
                    // R40 FK-target composite arm. Analogous to ColumnReferenceArg but with a
                    // RowEq / RowIn predicate against the FK source-column tuple. Same
                    // direct-FK precondition enforced at classifyArgument.
                    boolean autoSuppressed = ccra.suppressedByFieldOverride()
                        || (ccra.argCondition().isPresent() && ccra.argCondition().get().override());
                    if (!autoSuppressed) {
                        List<ColumnRef> fkSourceColumns = ccra.fkJoin().sourceColumns();
                        String javaType = "org.jooq.RowN";
                        bodyParams.add(ccra.list()
                            ? new BodyParam.RowIn(ccra.name(), fkSourceColumns, javaType, ccra.nonNull(), ccra.extraction())
                            : new BodyParam.RowEq(ccra.name(), fkSourceColumns, javaType, ccra.nonNull(), ccra.extraction()));
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
                .map(bp -> new CallParam(bp.name(), bp.extraction(), bp.list(), bp.javaType()))
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
     * <p><b>Implicit conditions</b> — when {@code implicitBodyParams} is non-null (i.e. the
     * input is a {@code @table}-annotated {@link ArgumentRef.InputTypeArg.TableInputArg}), every
     * un-annotated {@link InputField.ColumnField} and {@link InputField.ColumnReferenceField}
     * that carries no {@code @condition} annotation, is not already consumed by a {@code @lookupKey}
     * binding, and is not suppressed by an enclosing {@code override: true}, gets an implicit
     * column-equality predicate — a {@link BodyParam} with a
     * {@link CallSiteExtraction.NestedInputField} extraction — added to {@code implicitBodyParams}.
     * Fields that carry an explicit {@code @condition} (any override value) never also emit an
     * implicit predicate.
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
     */
    private void walkInputFieldConditions(
            List<InputField> fields, String outerArgName, List<String> pathPrefix,
            boolean enclosingOverride, Set<String> lookupBoundNames,
            List<BodyParam> implicitBodyParams,
            List<ConditionFilter> out) {
        for (var f : fields) {
            var leafPath = new ArrayList<>(pathPrefix);
            leafPath.add(f.name());
            switch (f) {
                case InputField.ColumnField cf -> {
                    cf.condition().ifPresent(c -> out.add(conditionResolver.rewrapForNested(c.filter(), outerArgName, leafPath)));
                    if (implicitBodyParams != null && !enclosingOverride
                            && cf.condition().isEmpty()
                            && !lookupBoundNames.contains(cf.name())) {
                        // The leaf extraction (Direct or NodeIdDecodeKeys.*) flows through to the
                        // BodyParam via NestedInputField(outer, path, leaf), so the call-site
                        // emitter applies the per-element decode chain on the Map traversal result.
                        implicitBodyParams.add(implicitBodyParam(
                            cf.column(), cf.name(), cf.typeName(), cf.nonNull(), cf.list(),
                            cf.extraction(), outerArgName, leafPath));
                    }
                }
                case InputField.ColumnReferenceField rf -> {
                    rf.condition().ifPresent(c -> out.add(conditionResolver.rewrapForNested(c.filter(), outerArgName, leafPath)));
                    if (implicitBodyParams != null && !enclosingOverride
                            && rf.condition().isEmpty()
                            && !lookupBoundNames.contains(rf.name())) {
                        implicitBodyParams.add(implicitBodyParam(
                            rf.column(), rf.name(), rf.typeName(), rf.nonNull(), rf.list(),
                            rf.extraction(), outerArgName, leafPath));
                    }
                }
                case InputField.NestingField nf -> {
                    nf.condition().ifPresent(c -> out.add(conditionResolver.rewrapForNested(c.filter(), outerArgName, leafPath)));
                    boolean nestOverride = enclosingOverride
                        || nf.condition().map(ArgConditionRef::override).orElse(false);
                    walkInputFieldConditions(nf.fields(), outerArgName, leafPath,
                        nestOverride, lookupBoundNames, implicitBodyParams, out);
                }
                case InputField.CompositeColumnField ccf -> {
                    ccf.condition().ifPresent(c -> out.add(conditionResolver.rewrapForNested(c.filter(), outerArgName, leafPath)));
                    if (implicitBodyParams != null && !enclosingOverride
                            && ccf.condition().isEmpty()
                            && !lookupBoundNames.contains(ccf.name())) {
                        implicitBodyParams.add(compositeImplicitBodyParam(
                            ccf.columns(), ccf.name(), ccf.nonNull(), ccf.list(),
                            ccf.extraction(), outerArgName, leafPath));
                    }
                }
                case InputField.CompositeColumnReferenceField ccrf -> {
                    ccrf.condition().ifPresent(c -> out.add(conditionResolver.rewrapForNested(c.filter(), outerArgName, leafPath)));
                    if (implicitBodyParams != null && !enclosingOverride
                            && ccrf.condition().isEmpty()
                            && !lookupBoundNames.contains(ccrf.name())) {
                        implicitBodyParams.add(compositeImplicitBodyParam(
                            ccrf.columns(), ccrf.name(), ccrf.nonNull(), ccrf.list(),
                            ccrf.extraction(), outerArgName, leafPath));
                    }
                }
            }
        }
    }

    /**
     * Returns {@code true} when {@code a} and {@code b} have the same length and each pair of
     * positionally aligned columns has the same SQL name (case-insensitive). Used by R40's
     * FK-target arm to gate the simple direct-FK emission (FK target columns ≡ NodeType key
     * columns); pathological cases where they differ require JOIN-with-translation emission
     * and are deferred to a sibling follow-on parallel to R24.
     */
    private static boolean sameColumnsBySqlName(List<ColumnRef> a, List<ColumnRef> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).sqlName().equalsIgnoreCase(b.get(i).sqlName())) return false;
        }
        return true;
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
        // For NodeId-decoded leaves the post-decode value is the column's typed Java class
        // (single scalar or List of it). For Direct, ID scalars stay String; everything else
        // takes the column's Java type.
        boolean nodeIdLeaf = leaf instanceof CallSiteExtraction.NodeIdDecodeKeys;
        String javaType = nodeIdLeaf
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
     * {@link BodyParam.RowIn} (list) with {@code javaType = org.jooq.RowN}; the call-site
     * extraction projects each decoded record to its {@code valuesRow()} so the params arrive
     * shaped as untyped {@link org.jooq.RowN} (or {@code List<RowN>}).
     */
    private static BodyParam compositeImplicitBodyParam(List<ColumnRef> columns, String fieldName,
                                                        boolean nonNull, boolean list,
                                                        CallSiteExtraction.NodeIdDecodeKeys leaf,
                                                        String outerArgName, List<String> leafPath) {
        String javaType = "org.jooq.RowN";
        var nested = new CallSiteExtraction.NestedInputField(outerArgName, leafPath, leaf);
        return list
            ? new BodyParam.RowIn(fieldName, columns, javaType, nonNull, nested)
            : new BodyParam.RowEq(fieldName, columns, javaType, nonNull, nested);
    }

    // ===== Field classification =====

    GraphitronField classifyField(GraphQLFieldDefinition fieldDef, String parentTypeName, GraphitronType parentType) {
        String name = fieldDef.getName();
        SourceLocation location = locationOf(fieldDef);

        // @notGenerated is no longer supported. Reject any application before conflict detection
        // so the user sees the no-longer-supported reason rather than a misleading "conflict with
        // @service" message when both directives are present.
        if (fieldDef.hasAppliedDirective(DIR_NOT_GENERATED)) {
            return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.INVALID_SCHEMA,
                "@notGenerated is no longer supported. Remove the directive; fields must be fully described by the schema.");
        }

        // Detect conflicts among the child-field exclusive directives before the
        // @multitableReference early-return; that return would otherwise silently mask a
        // conflicting directive on the same field.
        if (!(parentType instanceof RootType)) {
            String conflict = detectChildFieldConflict(fieldDef);
            if (conflict != null) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.INVALID_SCHEMA, conflict);
            }
        }

        if (fieldDef.hasAppliedDirective(DIR_MULTITABLE_REFERENCE)) {
            return new MultitableReferenceField(parentTypeName, name, location);
        }

        if (parentType instanceof RootType rootType) {
            return classifyRootField(fieldDef, parentTypeName);
        }
        if (parentType instanceof TableBackedType tbt && !(parentType instanceof TableInterfaceType)) {
            return classifyChildFieldOnTableType(fieldDef, parentTypeName, tbt, Set.of());
        }
        if (parentType instanceof ResultType resultType) {
            return classifyChildFieldOnResultType(fieldDef, parentTypeName, resultType);
        }
        if (parentType instanceof ErrorType) {
            return classifyChildFieldOnErrorType(fieldDef, parentTypeName);
        }

        return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR,
            "parent type is unclassified");
    }

    private GraphitronField classifyChildFieldOnErrorType(GraphQLFieldDefinition fieldDef, String parentTypeName) {
        String name = fieldDef.getName();
        SourceLocation location = locationOf(fieldDef);
        if (isScalarOrEnum(fieldDef)) {
            return new PropertyField(parentTypeName, name, location, name, null);
        }
        return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.INVALID_SCHEMA,
            "fields on @error types must be scalar or enum");
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
            if (ctx.types.get(memberName) instanceof ErrorType et) {
                errorTypes.add(et);
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
            return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                RejectionKind.AUTHOR_ERROR,
                "errors-shaped field on polymorphic '" + returnType.returnTypeName()
                    + "' must have every member declared @error; non-@error member(s): "
                    + String.join(", ", nonErrorMembers));
        }

        // Field-level nullability (§2b): the errors field must be a nullable list. A non-list
        // shape (single) or a non-null list (`[X]!` / `[X!]!`) is rejected.
        if (!(returnType.wrapper() instanceof FieldWrapper.List list)) {
            return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                RejectionKind.AUTHOR_ERROR,
                "errors-shaped field of @error type(s) must be a list; declared as a single value. "
                    + "Use [" + returnType.returnTypeName() + "] or [" + returnType.returnTypeName() + "!]");
        }
        if (!list.listNullable()) {
            return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                RejectionKind.AUTHOR_ERROR,
                "errors-shaped list field must be nullable; declared as non-null. "
                    + "Use [" + returnType.returnTypeName() + "] or [" + returnType.returnTypeName() + "!]");
        }

        return new ErrorsField(parentTypeName, name, location, errorTypes);
    }

    // ===== Carrier classifier: ErrorChannel resolution (R12 §2c) =====

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
     * Resolves the carrier-side {@link ErrorChannel} for a
     * fetcher-emitting field per R12 §2c. Walks the payload type's GraphQL field defs to find an
     * {@code errors}-shaped field (structural match by polymorphic-of-all-{@code @error} list
     * shape), then reflects on the developer-supplied payload class to identify the
     * canonical-constructor errors slot and capture each parameter's default literal.
     *
     * <p>Channel detection is structural (not name-based) and reuses the {@link #liftToErrorsField}
     * predicate so the carrier classifier and the child classifier agree on what counts as an
     * {@code ErrorsField}. The first matching field on the payload populates {@code mappedErrorTypes};
     * a second matching field is rejected at child classification time, not here.
     *
     * <p>The errors slot is identified by channel-typed structural match: the unique
     * constructor parameter whose type is {@link java.util.List} / {@link java.util.Collection}
     * / {@link Iterable} (or a subtype) and whose element-type upper bound is a supertype of
     * every channel {@code @error} class. {@code @error} types whose backing class did not
     * resolve at classify time contribute no constraint to the match (the dispatch arm
     * silently skips factory-less mappings); the channel-typed match parallels
     * {@link #resolveDmlPayloadAssembly}'s row-slot match against the jOOQ table record class.
     */
    @no.sikt.graphitron.rewrite.model.LoadBearingClassifierCheck(
        key = "error-channel.mappings-constant",
        description = "Every classified ErrorChannel carries a non-null mappingsConstantName "
            + "derived from the payload class's simple name (toScreamingSnake). The "
            + "ErrorMappingsClassGenerator groups channels by this name so identical channels "
            + "share one Mapping[] constant; collisions on the same constant with different "
            + "handler shapes are rejected at emission time. The §3 hash-suffix dedup is a "
            + "follow-up addition.")
    private ErrorChannelResult resolveErrorChannel(ReturnTypeRef returnType) {
        // Channel detection runs against @record payloads; @table payloads can in principle
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

        List<ErrorType> mappedErrorTypes = null;
        for (var childFieldDef : payloadObj.getFieldDefinitions()) {
            var detected = detectErrorsFieldShape(childFieldDef, result.returnTypeName());
            if (detected != null) {
                mappedErrorTypes = detected;
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

        Class<?> payloadCls;
        try {
            payloadCls = Class.forName(result.fqClassName());
        } catch (ClassNotFoundException e) {
            return new ErrorChannelResult.Reject(
                "payload class '" + result.fqClassName() + "' could not be loaded");
        }

        var ctors = payloadCls.getDeclaredConstructors();
        if (ctors.length == 0) {
            return new ErrorChannelResult.Reject(
                "payload class '" + result.fqClassName() + "' has no declared constructors");
        }
        // Records always declare a single canonical constructor; hand-rolled @record POJOs are
        // expected to expose one matching the SDL field order. Multiple constructors → reject;
        // the implementer collapses to one or annotates the canonical one in a future extension.
        if (ctors.length > 1) {
            return new ErrorChannelResult.Reject(
                "payload class '" + result.fqClassName() + "' has " + ctors.length
                    + " declared constructors; the carrier requires a single canonical "
                    + "(all-fields) constructor");
        }
        var ctor = ctors[0];
        var parameters = ctor.getParameters();
        var genericParameterTypes = ctor.getGenericParameterTypes();

        // Resolve every channel @error type's backing class for the channel-typed slot match.
        // ErrorTypes whose classFqn is empty contribute no constraint; the dispatch arm
        // separately skips factory-less mappings at emission time.
        var mappedErrorClasses = new java.util.ArrayList<Class<?>>();
        for (var et : mappedErrorTypes) {
            var fqnOpt = et.classFqn();
            if (fqnOpt.isEmpty()) continue;
            try {
                mappedErrorClasses.add(Class.forName(fqnOpt.get()));
            } catch (ClassNotFoundException e) {
                // The @error type's classFqn was already validated by TypeBuilder's
                // (List<String>, String) constructor check, which loaded the class. A
                // ClassNotFound here would indicate a classpath drift between classify and
                // channel-resolution phases; treat as no-constraint and let the channel
                // resolution proceed.
            }
        }

        int errorsSlotIndex = -1;
        for (int i = 0; i < parameters.length; i++) {
            if (isErrorsListSlot(parameters[i].getType(), genericParameterTypes[i], mappedErrorClasses)) {
                if (errorsSlotIndex >= 0) {
                    return new ErrorChannelResult.Reject(
                        "payload class '" + result.fqClassName()
                            + "' has multiple errors-slot parameters on its constructor; "
                            + "exactly one parameter typed to receive the channel's @error "
                            + "classes (a List/Iterable/Collection whose element type is a "
                            + "supertype of every @error class) is required");
                }
                errorsSlotIndex = i;
            }
        }
        if (errorsSlotIndex < 0) {
            return new ErrorChannelResult.Reject(
                "payload class '" + result.fqClassName()
                    + "' has no errors-slot parameter on its constructor; one parameter "
                    + "typed to receive the channel's @error classes (a List/Iterable/Collection "
                    + "whose element type is a supertype of every @error class) is required");
        }

        var defaultedSlots = collectDefaultedSlots(parameters, genericParameterTypes, errorsSlotIndex);

        var payloadClassName = ClassName.bestGuess(result.fqClassName());
        String mappingsConstantName = toScreamingSnake(payloadCls.getSimpleName());
        return new ErrorChannelResult.Channel(new ErrorChannel(
            mappedErrorTypes, payloadClassName, errorsSlotIndex, defaultedSlots, mappingsConstantName));
    }

    // ===== Carrier classifier: DML PayloadAssembly resolution (R12 DML chunk) =====

    /**
     * Outcome of the carrier-side {@code PayloadAssembly} resolution. Three terminal states,
     * symmetric with {@link ErrorChannelResult}:
     *
     * <ul>
     *   <li>{@link NoAssembly} — the return type isn't a {@code @record} payload; the DML
     *       fetcher takes the raw-row emission path (existing behaviour for {@code @table}
     *       and {@code ID} returns).</li>
     *   <li>{@link Assembly} — the payload class's canonical constructor exposes one
     *       parameter typed as the DML's table record; the emitter constructs the payload by
     *       binding that slot to the SQL row record.</li>
     *   <li>{@link Reject} — the payload is a {@code @record} type but the constructor doesn't
     *       expose a single matching row slot; the field surfaces as {@code UnclassifiedField}
     *       on the carrier with the carried reason.</li>
     * </ul>
     */
    private sealed interface DmlPayloadAssemblyResult {
        record NoAssembly() implements DmlPayloadAssemblyResult {}
        record Assembly(PayloadAssembly assembly) implements DmlPayloadAssemblyResult {}
        record Reject(String reason) implements DmlPayloadAssemblyResult {}
    }

    private static final DmlPayloadAssemblyResult NO_ASSEMBLY = new DmlPayloadAssemblyResult.NoAssembly();

    /**
     * Resolves a DML mutation field's success-arm payload-construction recipe per R12. The
     * caller passes the field's return type and the SQL name of the table its single
     * {@code @table} input argument drives ({@code tia.inputTable().tableName()}); the resolver
     * looks up the table's record class on the jOOQ catalog, then walks the developer-supplied
     * payload class's canonical constructor to find the unique parameter typed as that record
     * class. That parameter is the row slot; the emitter binds the SQL row record there at the
     * success arm.
     *
     * <p>Symmetric with {@link #resolveErrorChannel}: the two reflect on the same payload class
     * for the same kind of return type, but resolve different concerns. Both run from
     * {@link #buildDmlField} so a misconfigured payload surfaces on its own terms regardless of
     * which side caught it first. They share the {@link #collectDefaultedSlots} helper for
     * capturing per-non-bound-slot defaults; each owns its bound-slot index resolution.
     */
    private DmlPayloadAssemblyResult resolveDmlPayloadAssembly(
            ReturnTypeRef returnType, String dmlTableSqlName, List<ErrorType> mappedErrorTypes) {
        if (!(returnType instanceof ReturnTypeRef.ResultReturnType result)) {
            return NO_ASSEMBLY;
        }
        if (result.fqClassName() == null) {
            return NO_ASSEMBLY;
        }

        Class<?> tableRecordClass = ctx.catalog.findRecordClass(dmlTableSqlName).orElse(null);
        if (tableRecordClass == null) {
            return new DmlPayloadAssemblyResult.Reject(
                "DML target table '" + dmlTableSqlName + "' is not in the jOOQ catalog; "
                    + "cannot resolve a row-slot type for payload class '" + result.fqClassName() + "'");
        }

        Class<?> payloadCls;
        try {
            payloadCls = Class.forName(result.fqClassName());
        } catch (ClassNotFoundException e) {
            return new DmlPayloadAssemblyResult.Reject(
                "payload class '" + result.fqClassName() + "' could not be loaded");
        }

        var ctors = payloadCls.getDeclaredConstructors();
        if (ctors.length == 0) {
            return new DmlPayloadAssemblyResult.Reject(
                "payload class '" + result.fqClassName() + "' has no declared constructors");
        }
        if (ctors.length > 1) {
            return new DmlPayloadAssemblyResult.Reject(
                "payload class '" + result.fqClassName() + "' has " + ctors.length
                    + " declared constructors; the carrier requires a single canonical "
                    + "(all-fields) constructor");
        }
        var ctor = ctors[0];
        var parameters = ctor.getParameters();
        var genericParameterTypes = ctor.getGenericParameterTypes();

        // The errors slot in the DML payload (if any) is identified the same way as in
        // resolveErrorChannel: channel-typed structural match against the channel's @error
        // classes. mappedErrorTypes is the (possibly empty) list inherited from the channel
        // resolver; an empty list means no @error types in scope and isErrorsListSlot returns
        // true for every parameterised List/Iterable parameter (the row slot's class-equality
        // check below disambiguates the row slot from any such parameter).
        var mappedErrorClasses = new java.util.ArrayList<Class<?>>();
        for (var et : mappedErrorTypes) {
            var fqnOpt = et.classFqn();
            if (fqnOpt.isEmpty()) continue;
            try {
                mappedErrorClasses.add(Class.forName(fqnOpt.get()));
            } catch (ClassNotFoundException e) {
                // Already validated upstream; treat classpath drift as no-constraint.
            }
        }

        int rowSlotIndex = -1;
        for (int i = 0; i < parameters.length; i++) {
            var p = parameters[i];
            boolean isErrorsSlot = isErrorsListSlot(p.getType(), genericParameterTypes[i], mappedErrorClasses);
            if (!isErrorsSlot && p.getType().equals(tableRecordClass)) {
                if (rowSlotIndex >= 0) {
                    return new DmlPayloadAssemblyResult.Reject(
                        "payload class '" + result.fqClassName()
                            + "' has multiple parameters typed as " + tableRecordClass.getName()
                            + " on its constructor; the DML's table '" + dmlTableSqlName
                            + "' requires exactly one row-slot parameter");
                }
                rowSlotIndex = i;
            }
        }
        if (rowSlotIndex < 0) {
            return new DmlPayloadAssemblyResult.Reject(
                "payload class '" + result.fqClassName()
                    + "' has no parameter typed as " + tableRecordClass.getName()
                    + " on its constructor; the DML's table '" + dmlTableSqlName
                    + "' requires exactly one row-slot parameter assignable from this record class");
        }

        var defaultedSlots = collectDefaultedSlots(parameters, genericParameterTypes, rowSlotIndex);
        var payloadClassName = ClassName.bestGuess(result.fqClassName());
        var rowSlotType = no.sikt.graphitron.javapoet.TypeName.get(genericParameterTypes[rowSlotIndex]);
        return new DmlPayloadAssemblyResult.Assembly(new PayloadAssembly(
            payloadClassName, rowSlotIndex, rowSlotType, defaultedSlots));
    }

    /**
     * Lightweight predicate for "this GraphQL field is an {@code errors}-shaped field" used by
     * the carrier classifier to walk a payload's fields without materialising a full
     * {@link ErrorsField} or {@link UnclassifiedField}. Returns the resolved
     * {@code List<ErrorType>} when the field's return type is a polymorphic-of-all-{@code @error}
     * list with the nullability shape §2b allows; returns {@code null} otherwise.
     *
     * <p>Mirrors the lift rules in {@link #liftToErrorsField}: every member of the polymorphic
     * type must be {@code @error}, and the field itself must be a nullable list. A future
     * widening to single-{@code @error} list shapes ({@code [SomeError]}) is part of §2b's
     * remaining work; this helper picks up the same shapes the lift currently accepts.
     */
    private List<ErrorType> detectErrorsFieldShape(GraphQLFieldDefinition fieldDef, String parentTypeName) {
        var returnType = ctx.resolveReturnType(baseTypeName(fieldDef), buildWrapper(fieldDef));
        if (!(returnType instanceof ReturnTypeRef.PolymorphicReturnType poly)) {
            return null;
        }
        var schemaType = ctx.schema.getType(poly.returnTypeName());
        java.util.List<String> memberNames = switch (schemaType) {
            case GraphQLUnionType union -> union.getTypes().stream().map(GraphQLNamedType::getName).toList();
            case GraphQLInterfaceType iface ->
                ctx.schema.getImplementations(iface).stream().map(GraphQLObjectType::getName).toList();
            case null, default -> java.util.List.of();
        };
        if (memberNames.isEmpty()) return null;
        var errorTypes = new java.util.ArrayList<ErrorType>();
        for (String memberName : memberNames) {
            if (!(ctx.types.get(memberName) instanceof ErrorType et)) {
                return null;
            }
            errorTypes.add(et);
        }
        if (!(poly.wrapper() instanceof FieldWrapper.List list) || !list.listNullable()) {
            return null;
        }
        return errorTypes;
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
     *   <li>Rule 9: a {@code ValidationHandler} coexists with an {@code ExceptionHandler} whose
     *       exception class is {@code ValidationViolationGraphQLException} or any of its
     *       supertypes ({@code AbortExecutionException}, {@code RuntimeException},
     *       {@code Exception}, {@code Throwable}). The runtime runs validation fan-out ahead
     *       of {@code MAPPINGS} iteration (§5), so the broader handler would shadow the
     *       fan-out for {@code ValidationViolationGraphQLException} cases.</li>
     *   <li>Rule 8 (§3): two handlers of the same variant in the channel's flattened handler
     *       list with identical match-criteria. The runtime's source-order {@code findFirst}
     *       on {@code MAPPINGS} would make the second mapping unreachable, so the duplicate
     *       is an author mistake. Intra-variant only: an {@code ExceptionHandler(SQLException)}
     *       and a {@code SqlStateHandler("23503")} discriminate on different fields and are
     *       intentionally allowed to overlap (§3 source-order resolves which {@code @error}
     *       type wins).</li>
     * </ul>
     */
    private static String checkChannelLevelHandlerRules(List<ErrorType> mappedErrorTypes) {
        // Rule 7: multiple VALIDATION handlers in the same channel.
        var validationCarriers = new java.util.ArrayList<String>();
        for (var et : mappedErrorTypes) {
            for (var h : et.handlers()) {
                if (h instanceof ErrorType.ValidationHandler) {
                    validationCarriers.add(et.name());
                    break;
                }
            }
        }
        if (validationCarriers.size() > 1) {
            return "@error channel has more than one {handler: VALIDATION} entry across "
                + "@error types " + String.join(", ", validationCarriers)
                + "; VALIDATION is a single fan-out target per payload — split into separate "
                + "fields with distinct payloads, or collapse to one VALIDATION-carrying type";
        }

        // Rule 9: VALIDATION coexists with an ExceptionHandler whose class shadows
        // ValidationViolationGraphQLException.
        if (validationCarriers.isEmpty()) return null;
        var shadowers = new java.util.ArrayList<String>();
        for (var et : mappedErrorTypes) {
            for (var h : et.handlers()) {
                if (h instanceof ErrorType.ExceptionHandler eh
                        && shadowsValidationViolation(eh.exceptionClassName())) {
                    shadowers.add(et.name() + " ({handler: GENERIC, className: \""
                        + eh.exceptionClassName() + "\"})");
                }
            }
        }
        if (!shadowers.isEmpty()) {
            return "@error channel mixes {handler: VALIDATION} (on "
                + String.join(", ", validationCarriers)
                + ") with an ExceptionHandler whose class would shadow "
                + "ValidationViolationGraphQLException at dispatch: " + String.join(", ", shadowers)
                + "; VALIDATION runs ahead of MAPPINGS iteration so the broader handler is "
                + "unreachable for validation-violation causes — split into two separate fields "
                + "with distinct payloads";
        }
        return null;
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
    private static String checkDuplicateMatchCriteria(List<ErrorType> mappedErrorTypes) {
        record CriteriaKey(String variant, String discriminator, java.util.Optional<String> matches) {}
        var seen = new java.util.LinkedHashMap<CriteriaKey, String>();
        for (var et : mappedErrorTypes) {
            for (var h : et.handlers()) {
                CriteriaKey key;
                String fingerprint;
                if (h instanceof ErrorType.ExceptionHandler eh) {
                    key = new CriteriaKey("ExceptionHandler", eh.exceptionClassName(), eh.matches());
                    fingerprint = "ExceptionHandler(className=\"" + eh.exceptionClassName() + "\""
                        + matchesSuffix(eh.matches()) + ")";
                } else if (h instanceof ErrorType.SqlStateHandler sh) {
                    key = new CriteriaKey("SqlStateHandler", sh.sqlState(), sh.matches());
                    fingerprint = "SqlStateHandler(sqlState=\"" + sh.sqlState() + "\""
                        + matchesSuffix(sh.matches()) + ")";
                } else if (h instanceof ErrorType.VendorCodeHandler vh) {
                    key = new CriteriaKey("VendorCodeHandler", vh.vendorCode(), vh.matches());
                    fingerprint = "VendorCodeHandler(vendorCode=\"" + vh.vendorCode() + "\""
                        + matchesSuffix(vh.matches()) + ")";
                } else {
                    continue;
                }
                String trace = et.name() + " " + fingerprint;
                String prior = seen.putIfAbsent(key, trace);
                if (prior != null) {
                    return "@error channel has two handlers with identical match-criteria: "
                        + prior + " and " + trace
                        + "; the runtime's source-order findFirst on MAPPINGS would make the "
                        + "second mapping unreachable — collapse the duplicate or differentiate "
                        + "the criteria";
                }
            }
        }
        return null;
    }

    private static String matchesSuffix(java.util.Optional<String> matches) {
        return matches.isPresent() ? ", matches=\"" + matches.get() + "\"" : "";
    }

    /**
     * Set of binary class names that, used as an {@code ExceptionHandler.exceptionClassName},
     * would shadow {@code ValidationViolationGraphQLException} at dispatch time. The check
     * also matches by simple name {@code "ValidationViolationGraphQLException"} so a
     * developer-visible reference to the generated marker is recognised regardless of the
     * output package's qualifier.
     */
    private static final java.util.Set<String> KNOWN_VALIDATION_SHADOWERS = java.util.Set.of(
        "java.lang.Throwable",
        "java.lang.Exception",
        "java.lang.RuntimeException",
        "graphql.execution.AbortExecutionException"
    );

    private static boolean shadowsValidationViolation(String exceptionClassName) {
        if (exceptionClassName == null) return false;
        if (KNOWN_VALIDATION_SHADOWERS.contains(exceptionClassName)) return true;
        int lastDot = exceptionClassName.lastIndexOf('.');
        String simple = lastDot < 0 ? exceptionClassName : exceptionClassName.substring(lastDot + 1);
        return "ValidationViolationGraphQLException".equals(simple);
    }

    /**
     * Detects whether a constructor parameter can receive the channel's errors list per the
     * §2c contract: parameter type is {@link java.util.List}, {@link java.util.Collection},
     * or {@link Iterable} (or any of their subtypes), and the actual type argument's upper
     * bound is a {@link Class} that every channel {@code @error} class is assignable to.
     *
     * <p>{@code mappedErrorClasses} is the set of resolved Java classes for the channel's
     * {@code @error} types: {@link no.sikt.graphitron.rewrite.model.GraphitronType.ErrorType}
     * entries whose {@code classFqn} resolved at classify time. {@code @error} types without
     * a backing class contribute no constraint to the match (their absence is handled by the
     * dispatch arm, which silently skips factory-less mappings); the parameter still has to
     * be a parameterised list/iterable for the match to succeed.
     *
     * <p>The match parallels {@code resolveDmlPayloadAssembly}'s row-slot match against the
     * jOOQ table record class (same producer/consumer pattern, different element type).
     * Element types parameterised as wildcards ({@code List<?>}, {@code List<? extends X>})
     * unwrap to their upper bound. A raw collection type or a non-resolvable element type
     * returns {@code false}.
     */
    private static boolean isErrorsListSlot(
            Class<?> rawType,
            java.lang.reflect.Type genericType,
            List<Class<?>> mappedErrorClasses) {
        if (!Iterable.class.isAssignableFrom(rawType)) return false;
        if (!(genericType instanceof java.lang.reflect.ParameterizedType pt)) return false;
        var args = pt.getActualTypeArguments();
        if (args.length != 1) return false;
        var elementType = args[0];
        Class<?> elementBound = resolveElementClass(elementType);
        if (elementBound == null) return false;
        for (var errorClass : mappedErrorClasses) {
            if (!elementBound.isAssignableFrom(errorClass)) return false;
        }
        return true;
    }

    /**
     * Walks a generic type to its concrete {@link Class} bound: a {@link Class} returns itself,
     * a {@link java.lang.reflect.WildcardType} returns its upper bound's class, a
     * {@link java.lang.reflect.ParameterizedType} returns its raw type. Returns {@code null}
     * for type-variable bounds and other shapes that don't reduce to a class.
     */
    private static Class<?> resolveElementClass(java.lang.reflect.Type t) {
        if (t instanceof Class<?> cls) return cls;
        if (t instanceof java.lang.reflect.WildcardType wt) {
            var upper = wt.getUpperBounds();
            return upper.length == 1 ? resolveElementClass(upper[0]) : null;
        }
        if (t instanceof java.lang.reflect.ParameterizedType pt) {
            return pt.getRawType() instanceof Class<?> raw ? raw : null;
        }
        return null;
    }

    /**
     * Builds the {@link no.sikt.graphitron.rewrite.model.DefaultedSlot} list for every
     * constructor parameter except the bound slot at {@code boundSlotIndex}. Used by both
     * {@link #resolveErrorChannel} (errors slot) and {@link #resolveDmlPayloadAssembly} (row
     * slot) to capture per-non-bound-slot default literals from one reflection pass.
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
            case ErrorChannelResult.Reject r -> new UnclassifiedField(parentTypeName, fieldName, location, fieldDef,
                RejectionKind.AUTHOR_ERROR, r.reason());
        };
    }

    /**
     * Variant of {@link #buildWithChannel} used by {@code DmlTableField} construction sites:
     * resolves the {@link PayloadAssembly} (DML success-arm payload-construction recipe) and
     * the {@link ErrorChannel} (catch-arm dispatch recipe) and forwards both to {@code builder}.
     * A rejection on either side surfaces as {@code UnclassifiedField}; a {@code NoAssembly}
     * (the return type isn't a {@code @record} payload) or {@code NoChannel} (the payload has
     * no errors-shaped field) yields {@link Optional#empty()} on the corresponding slot.
     *
     * <p>The two resolutions are independent: a DML field can carry an assembly without a
     * channel (payload constructs on success, {@code redact} on catch) or a channel without an
     * assembly (impossible today since channel resolution requires {@code ResultReturnType}
     * and so does assembly resolution, but kept symmetric for the model's clarity).
     */
    private GraphitronField buildDmlField(
            ReturnTypeRef returnType, String parentTypeName, String fieldName,
            SourceLocation location, GraphQLFieldDefinition fieldDef, String dmlTableSqlName,
            java.util.function.BiFunction<Optional<PayloadAssembly>, Optional<ErrorChannel>, GraphitronField> builder) {
        // Resolve the channel first so the DML resolver can borrow its mappedErrorTypes
        // for the channel-typed errors-slot match. A NoChannel result yields an empty list,
        // which makes the DML resolver's slot match trivially permissive (any parameterised
        // List/Iterable parameter qualifies); the row-slot's class-equality check still picks
        // out the row slot deterministically.
        Optional<ErrorChannel> channel;
        List<ErrorType> mappedForDml;
        switch (resolveErrorChannel(returnType)) {
            case ErrorChannelResult.NoChannel ignored -> {
                channel = Optional.empty();
                mappedForDml = List.of();
            }
            case ErrorChannelResult.Channel c -> {
                channel = Optional.of(c.channel());
                mappedForDml = c.channel().mappedErrorTypes();
            }
            case ErrorChannelResult.Reject r -> {
                return new UnclassifiedField(parentTypeName, fieldName, location, fieldDef,
                    RejectionKind.AUTHOR_ERROR, r.reason());
            }
        }
        Optional<PayloadAssembly> assembly;
        switch (resolveDmlPayloadAssembly(returnType, dmlTableSqlName, mappedForDml)) {
            case DmlPayloadAssemblyResult.NoAssembly ignored -> assembly = Optional.empty();
            case DmlPayloadAssemblyResult.Assembly a -> assembly = Optional.of(a.assembly());
            case DmlPayloadAssemblyResult.Reject r -> {
                return new UnclassifiedField(parentTypeName, fieldName, location, fieldDef,
                    RejectionKind.AUTHOR_ERROR, r.reason());
            }
        }
        return builder.apply(assembly, channel);
    }

    // ===== Root field classification (P5) =====

    private GraphitronField classifyRootField(GraphQLFieldDefinition fieldDef, String parentTypeName) {
        if (parentTypeName.equals("Mutation")) {
            return classifyMutationField(fieldDef, parentTypeName);
        }
        if (parentTypeName.equals("Query")) {
            return classifyQueryField(fieldDef, parentTypeName);
        }
        return new UnclassifiedField(parentTypeName, fieldDef.getName(), locationOf(fieldDef), fieldDef, RejectionKind.DEFERRED,
            "fields on '" + parentTypeName + "' (Subscription is not supported)");
    }

    private GraphitronField classifyQueryField(GraphQLFieldDefinition fieldDef, String parentTypeName) {
        String name = fieldDef.getName();
        SourceLocation location = locationOf(fieldDef);

        String conflict = detectQueryFieldConflict(fieldDef);
        if (conflict != null) {
            return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.INVALID_SCHEMA, conflict);
        }

        if (fieldDef.hasAppliedDirective(DIR_SERVICE)) {
            return switch (serviceResolver.resolve(parentTypeName, fieldDef, List.of())) {
                case ServiceDirectiveResolver.Resolved.Rejected r ->
                    new UnclassifiedField(parentTypeName, name, location, fieldDef, r.kind(), r.message());
                case ServiceDirectiveResolver.Resolved.ErrorsLifted e -> e.field();
                case ServiceDirectiveResolver.Resolved.TableBound tb ->
                    buildWithChannel(tb.returnType(), parentTypeName, name, location, fieldDef, ch ->
                        new QueryField.QueryServiceTableField(parentTypeName, name, location, tb.returnType(), tb.method(), ch));
                case ServiceDirectiveResolver.Resolved.Result r ->
                    buildWithChannel(r.returnType(), parentTypeName, name, location, fieldDef, ch ->
                        new QueryField.QueryServiceRecordField(parentTypeName, name, location, r.returnType(), r.method(), ch));
                case ServiceDirectiveResolver.Resolved.Scalar s ->
                    buildWithChannel(s.returnType(), parentTypeName, name, location, fieldDef, ch ->
                        new QueryField.QueryServiceRecordField(parentTypeName, name, location, s.returnType(), s.method(), ch));
            };
        }

        // Relay-style node fetchers are recognised by signature, not by name. Any Query
        // field whose element type is the `Node` interface is a node fetcher: single
        // cardinality routes to QueryNodeField, list cardinality to QueryNodesField.
        // Federation subgraphs commonly expose extra node-by-id entry points under
        // distinct names (e.g. `internalOpptakNode(id: ID): Node @inaccessible`); name-
        // based dispatch alone would misclassify those as QueryInterfaceField.
        if (baseTypeName(fieldDef).equals("Node") && ctx.types.get("Node") instanceof InterfaceType) {
            var wrapper = buildWrapper(fieldDef);
            var returnType = new ReturnTypeRef.PolymorphicReturnType("Node", wrapper);
            if (wrapper instanceof FieldWrapper.List) {
                return new QueryField.QueryNodesField(parentTypeName, name, location, returnType);
            }
            return new QueryField.QueryNodeField(parentTypeName, name, location, returnType);
        }

        // Resolve the field's backing table name early so the @nodeId-as-lookup gate can ask
        // the resolver whether any @nodeId-decorated arg resolves to same-table (which implies
        // @lookupKey). The bare hasLookupKeyAnywhere check covers explicit @lookupKey only.
        String lookupTypeName = baseTypeName(fieldDef);
        var lookupReturnType = ctx.resolveReturnType(lookupTypeName, buildWrapper(fieldDef));
        if (hasLookupKeyAnywhere(fieldDef)
                || (lookupReturnType instanceof ReturnTypeRef.TableBoundReturnType tableBound
                    && hasSameTableNodeIdAnywhere(fieldDef, tableBound.table()))) {
            return switch (lookupKeyResolver.resolveAtRoot(lookupReturnType)) {
                case LookupKeyDirectiveResolver.Resolved.Rejected r ->
                    new UnclassifiedField(parentTypeName, name, location, fieldDef, r.kind(), r.message());
                case LookupKeyDirectiveResolver.Resolved.Ok ok -> {
                    var tb = ok.returnType();
                    var components = resolveTableFieldComponents(fieldDef, tb.table(), lookupTypeName);
                    yield switch (components) {
                        case TableFieldComponents.Rejected rj ->
                            new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR, rj.message());
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
                    new UnclassifiedField(parentTypeName, name, location, fieldDef, r.kind(), r.message());
                case TableMethodDirectiveResolver.Resolved.TableBound tb ->
                    new QueryField.QueryTableMethodTableField(parentTypeName, name, location, tb.returnType(), tb.method());
                // NonTableBound is rejected inside the resolver when isRoot=true; the arm exists
                // to satisfy switch exhaustiveness over the shared sealed Resolved type.
                case TableMethodDirectiveResolver.Resolved.NonTableBound nb ->
                    throw new IllegalStateException("@tableMethod root resolver returned NonTableBound; should have rejected upstream");
            };
        }

        String rawTypeName = baseTypeName(fieldDef);
        String elementTypeName = ctx.isConnectionType(rawTypeName) ? ctx.connectionElementTypeName(rawTypeName) : rawTypeName;
        GraphitronType elementType = ctx.types.get(elementTypeName);

        if (elementType instanceof TableBackedType tbt && !(elementType instanceof TableInterfaceType)) {
            var wrapper = buildWrapper(fieldDef);
            var returnType = (ReturnTypeRef.TableBoundReturnType) ctx.resolveReturnType(elementTypeName, wrapper);
            var components = resolveTableFieldComponents(fieldDef, returnType.table(), elementTypeName);
            if (components instanceof TableFieldComponents.Rejected rj) return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR, rj.message());
            var tfc = (TableFieldComponents.Ok) components;
            return new QueryField.QueryTableField(parentTypeName, name, location, returnType, tfc.filters(), tfc.orderBy(), tfc.pagination());
        }
        if (elementType instanceof TableInterfaceType tableInterfaceType) {
            var wrapper = buildWrapper(fieldDef);
            var components = resolveTableFieldComponents(fieldDef, tableInterfaceType.table(), elementTypeName);
            if (components instanceof TableFieldComponents.Rejected rj) return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR, rj.message());
            var tfc = (TableFieldComponents.Ok) components;
            var knownValues = knownDiscriminatorValues(tableInterfaceType);
            return new QueryField.QueryTableInterfaceField(parentTypeName, name, location,
                new ReturnTypeRef.TableBoundReturnType(elementTypeName, tableInterfaceType.table(), wrapper),
                tableInterfaceType.discriminatorColumn(), knownValues, tableInterfaceType.participants(),
                tfc.filters(), tfc.orderBy(), tfc.pagination());
        }
        if (elementType instanceof InterfaceType interfaceType) {
            return new QueryField.QueryInterfaceField(parentTypeName, name, location,
                new ReturnTypeRef.PolymorphicReturnType(elementTypeName, buildWrapper(fieldDef)),
                interfaceType.participants());
        }
        if (elementType instanceof UnionType unionType) {
            return new QueryField.QueryUnionField(parentTypeName, name, location,
                new ReturnTypeRef.PolymorphicReturnType(elementTypeName, buildWrapper(fieldDef)),
                unionType.participants());
        }

        return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR,
            "return type '" + elementTypeName + "' is not a @table, interface, or union Graphitron type; " +
            "@service, @lookupKey, and @tableMethod are all absent");
    }

    private GraphitronField classifyMutationField(GraphQLFieldDefinition fieldDef, String parentTypeName) {
        String name = fieldDef.getName();
        SourceLocation location = locationOf(fieldDef);

        if (fieldDef.hasAppliedDirective(DIR_SERVICE) && fieldDef.hasAppliedDirective(DIR_MUTATION)) {
            return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.INVALID_SCHEMA,
                "@" + DIR_SERVICE + ", @" + DIR_MUTATION + " are mutually exclusive");
        }

        if (fieldDef.hasAppliedDirective(DIR_SERVICE)) {
            return switch (serviceResolver.resolve(parentTypeName, fieldDef, List.of())) {
                case ServiceDirectiveResolver.Resolved.Rejected r ->
                    new UnclassifiedField(parentTypeName, name, location, fieldDef, r.kind(), r.message());
                case ServiceDirectiveResolver.Resolved.ErrorsLifted e -> e.field();
                case ServiceDirectiveResolver.Resolved.TableBound tb ->
                    buildWithChannel(tb.returnType(), parentTypeName, name, location, fieldDef, ch ->
                        new MutationField.MutationServiceTableField(parentTypeName, name, location, tb.returnType(), tb.method(), ch));
                case ServiceDirectiveResolver.Resolved.Result r ->
                    buildWithChannel(r.returnType(), parentTypeName, name, location, fieldDef, ch ->
                        new MutationField.MutationServiceRecordField(parentTypeName, name, location, r.returnType(), r.method(), ch));
                case ServiceDirectiveResolver.Resolved.Scalar s ->
                    buildWithChannel(s.returnType(), parentTypeName, name, location, fieldDef, ch ->
                        new MutationField.MutationServiceRecordField(parentTypeName, name, location, s.returnType(), s.method(), ch));
            };
        }

        if (fieldDef.hasAppliedDirective(DIR_MUTATION)) {
            MutationInputResolver.DmlKind kind;
            switch (mutationInputResolver.parseDmlKind(fieldDef)) {
                case MutationInputResolver.DmlKindResult.Absent a -> kind = null;
                case MutationInputResolver.DmlKindResult.Kind k -> kind = k.kind();
                case MutationInputResolver.DmlKindResult.Unknown u -> {
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR,
                        "unknown @mutation(typeName:) value '" + u.raw() + "'");
                }
            }
            if (kind != null) {
                ArgumentRef.InputTypeArg.TableInputArg tia;
                switch (mutationInputResolver.resolveInput(fieldDef, kind)) {
                    case MutationInputResolver.Resolved.Ok ok -> tia = ok.tia();
                    case MutationInputResolver.Resolved.Rejected r -> {
                        return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                            RejectionKind.AUTHOR_ERROR, r.message());
                    }
                }

                String rawReturn = baseTypeName(fieldDef);
                ReturnTypeRef returnType = ctx.resolveReturnType(rawReturn, buildWrapper(fieldDef));

                String returnTypeError = MutationInputResolver.validateReturnType(returnType, kind);
                if (returnTypeError != null) {
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                        RejectionKind.AUTHOR_ERROR, returnTypeError);
                }

                Optional<HelperRef.Encode> encodeReturn = Optional.empty();
                if (returnType instanceof ReturnTypeRef.ScalarReturnType s && "ID".equals(s.returnTypeName())) {
                    String tableSqlName = tia.inputTable().tableName();
                    encodeReturn = ctx.types.values().stream()
                        .filter(t -> t instanceof NodeType nt && nt.table().tableName().equals(tableSqlName))
                        .map(t -> ((NodeType) t).encodeMethod())
                        .findFirst();
                    if (encodeReturn.isEmpty()) {
                        return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                            RejectionKind.AUTHOR_ERROR,
                            "@mutation field '" + name + "' returns ID but no @node type is declared for table '"
                                + tableSqlName + "'; annotate the type with @node or use a @table return type");
                    }
                }

                Optional<HelperRef.Encode> enc = encodeReturn;
                String dmlTableSqlName = tia.inputTable().tableName();
                return switch (kind) {
                    case INSERT -> buildDmlField(returnType, parentTypeName, name, location, fieldDef, dmlTableSqlName,
                        (pa, ch) -> new MutationField.MutationInsertTableField(parentTypeName, name, location, returnType, tia, enc, pa, ch));
                    case UPDATE -> buildDmlField(returnType, parentTypeName, name, location, fieldDef, dmlTableSqlName,
                        (pa, ch) -> new MutationField.MutationUpdateTableField(parentTypeName, name, location, returnType, tia, enc, pa, ch));
                    case DELETE -> buildDmlField(returnType, parentTypeName, name, location, fieldDef, dmlTableSqlName,
                        (pa, ch) -> new MutationField.MutationDeleteTableField(parentTypeName, name, location, returnType, tia, enc, pa, ch));
                    case UPSERT -> buildDmlField(returnType, parentTypeName, name, location, fieldDef, dmlTableSqlName,
                        (pa, ch) -> new MutationField.MutationUpsertTableField(parentTypeName, name, location, returnType, tia, enc, pa, ch));
                };
            }
        }

        return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR,
            "@" + DIR_SERVICE + " and @" + DIR_MUTATION + " are both absent on this mutation field");
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

    /**
     * R40: returns {@code true} when any direct argument of {@code fieldDef} carries
     * {@code @nodeId} and the resolver classifies it as
     * {@link NodeIdLeafResolver.Resolved.SameTable} against {@code containingTable}. Such an
     * argument is a lookup-by-id (cardinality bounded by the input list, ordering reflects
     * input membership) and must promote the field to {@code QueryLookupTableField} just like
     * an explicit {@code @lookupKey} would.
     *
     * <p>FK-target {@code @nodeId} args do not trigger promotion: they are filters, not
     * lookups, and stay on the regular {@code QueryTableField} path. Nested input-field
     * leaves are not walked: input-field {@code @nodeId} same-table classifies into a
     * {@code @table}-input filter which the lookup-promotion gate already handles via
     * {@link #hasLookupKeyAnywhere} when {@code @lookupKey} accompanies it; bare
     * input-field {@code @nodeId} stays a regular filter.
     */
    private boolean hasSameTableNodeIdAnywhere(GraphQLFieldDefinition fieldDef, TableRef containingTable) {
        var resolver = ctx.nodeIdLeafResolver();
        for (var arg : fieldDef.getArguments()) {
            if (!arg.hasAppliedDirective(DIR_NODE_ID)) continue;
            var unwrapped = GraphQLTypeUtil.unwrapAll(arg.getType());
            if (!(unwrapped instanceof GraphQLNamedType named) || !"ID".equals(named.getName())) continue;
            if (resolver.resolve(arg, arg.getName(), containingTable) instanceof NodeIdLeafResolver.Resolved.SameTable) {
                return true;
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
     * Returns a reason string when mutually exclusive child-field classification directives appear
     * together, or {@code null} when at most one exclusive slot is occupied.
     *
     * <p>Note: {@code @reference} is a path-annotation directive, not a classification directive —
     * it may be combined with {@code @service}, {@code @externalField}, {@code @tableMethod}, and
     * {@code @tableField} (as a FK reference path) or with {@code @nodeId} (producing
     * {@link NodeIdReferenceField}). It is therefore not included in this check.
     */
    private String detectChildFieldConflict(GraphQLFieldDefinition fieldDef) {
        boolean hasMultitable    = fieldDef.hasAppliedDirective(DIR_MULTITABLE_REFERENCE);
        boolean hasService       = fieldDef.hasAppliedDirective(DIR_SERVICE);
        boolean hasExternalField = fieldDef.hasAppliedDirective(DIR_EXTERNAL_FIELD);
        boolean hasTableMethod   = fieldDef.hasAppliedDirective(DIR_TABLE_METHOD);
        boolean hasNodeId        = fieldDef.hasAppliedDirective(DIR_NODE_ID);

        int slots = (hasMultitable    ? 1 : 0)
                  + (hasService       ? 1 : 0)
                  + (hasExternalField ? 1 : 0)
                  + (hasTableMethod   ? 1 : 0)
                  + (hasNodeId        ? 1 : 0);

        if (slots <= 1) return null;

        var names = new ArrayList<String>();
        if (hasMultitable)    names.add("@" + DIR_MULTITABLE_REFERENCE);
        if (hasService)       names.add("@" + DIR_SERVICE);
        if (hasExternalField) names.add("@" + DIR_EXTERNAL_FIELD);
        if (hasTableMethod)   names.add("@" + DIR_TABLE_METHOD);
        if (hasNodeId)        names.add("@" + DIR_NODE_ID);
        return String.join(", ", names) + " are mutually exclusive";
    }

    /**
     * Returns a reason string when mutually exclusive query-field directives appear together
     * ({@code @service}, {@code @lookupKey} on arguments, {@code @tableMethod}), or {@code null}.
     */
    private String detectQueryFieldConflict(GraphQLFieldDefinition fieldDef) {
        boolean hasService     = fieldDef.hasAppliedDirective(DIR_SERVICE);
        boolean hasLookupKey   = hasLookupKeyAnywhere(fieldDef);
        boolean hasTableMethod = fieldDef.hasAppliedDirective(DIR_TABLE_METHOD);

        int slots = (hasService     ? 1 : 0)
                  + (hasLookupKey   ? 1 : 0)
                  + (hasTableMethod ? 1 : 0);

        if (slots <= 1) return null;

        var names = new ArrayList<String>();
        if (hasService)     names.add("@" + DIR_SERVICE);
        if (hasLookupKey)   names.add("@" + DIR_LOOKUP_KEY);
        if (hasTableMethod) names.add("@" + DIR_TABLE_METHOD);
        return String.join(", ", names) + " are mutually exclusive";
    }

    private GraphitronField classifyChildFieldOnResultType(GraphQLFieldDefinition fieldDef, String parentTypeName,
            ResultType parentResultType) {
        String name = fieldDef.getName();
        SourceLocation location = locationOf(fieldDef);

        // @batchKeyLifter is owned by its dedicated resolver from this point onward: the resolver
        // validates the parent shape, the directive payload, and the lifter's signature; non-table
        // returns surface a directive-specific rejection here rather than being silently dropped
        // by the PropertyField / RecordField branches below.
        if (fieldDef.hasAppliedDirective(DIR_BATCH_KEY_LIFTER)) {
            String rawTypeName = baseTypeName(fieldDef);
            String elementTypeName = ctx.isConnectionType(rawTypeName) ? ctx.connectionElementTypeName(rawTypeName) : rawTypeName;
            var lifterResult = batchKeyLifterResolver.resolve(parentTypeName, fieldDef, parentResultType, elementTypeName);
            if (lifterResult instanceof BatchKeyLifterDirectiveResolver.Resolved.Rejected rj) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, rj.kind(), rj.message());
            }
            var ok = (BatchKeyLifterDirectiveResolver.Resolved.Ok) lifterResult;
            var components = resolveTableFieldComponents(fieldDef, ok.targetTable(), elementTypeName);
            if (components instanceof TableFieldComponents.Rejected rj) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR, rj.message());
            }
            var tfc = (TableFieldComponents.Ok) components;
            // joinPath publishes the same hop instance held by the BatchKey; the List wrap is the
            // rows-method emitter's existing API surface.
            List<JoinStep> joinPath = List.of(ok.liftedHop());
            if (hasLookupKeyAnywhere(fieldDef)) {
                return new RecordLookupTableField(parentTypeName, name, location, ok.tbReturnType(), joinPath,
                    tfc.filters(), tfc.orderBy(), tfc.pagination(), ok.batchKey(), tfc.lookupMapping());
            }
            return new RecordTableField(parentTypeName, name, location, ok.tbReturnType(), joinPath,
                tfc.filters(), tfc.orderBy(), tfc.pagination(), ok.batchKey());
        }

        if (fieldDef.hasAppliedDirective(DIR_SERVICE)) {
            var resolved = serviceResolver.resolve(parentTypeName, fieldDef, List.of());
            if (resolved instanceof ServiceDirectiveResolver.Resolved.Rejected r) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, r.kind(), r.message());
            }
            if (resolved instanceof ServiceDirectiveResolver.Resolved.ErrorsLifted e) {
                return e.field();
            }
            var servicePath = ctx.parsePath(fieldDef, name, null, null);
            if (servicePath.hasError()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR, servicePath.errorMessage());
            }
            return switch ((ServiceDirectiveResolver.Resolved.Success) resolved) {
                case ServiceDirectiveResolver.Resolved.TableBound tb ->
                    new ServiceTableField(parentTypeName, name, location, tb.returnType(),
                        servicePath.elements(), List.of(), new OrderBySpec.None(), null,
                        tb.method(), extractBatchKey(tb.method()));
                // @service on a @record-typed parent returning scalar/record is DEFERRED:
                // deriving the batch key would require lifting through the parent chain to the
                // rooted @table whose PK provides the key columns, which is its own design
                // problem (parallel to interface-union dispatch).
                case ServiceDirectiveResolver.Resolved.Result r ->
                    new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.DEFERRED,
                        "@service on a @record-typed parent is not yet supported; the batch key "
                            + "must be lifted through the parent chain to the rooted @table — see "
                            + "graphitron-rewrite/roadmap/service-record-field.md");
                case ServiceDirectiveResolver.Resolved.Scalar s ->
                    new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.DEFERRED,
                        "@service on a @record-typed parent is not yet supported; the batch key "
                            + "must be lifted through the parent chain to the rooted @table — see "
                            + "graphitron-rewrite/roadmap/service-record-field.md");
            };
        }

        if (isScalarOrEnum(fieldDef)) {
            String columnName = fieldDef.hasAppliedDirective(DIR_FIELD)
                ? argString(fieldDef, DIR_FIELD, ARG_NAME).orElse(name)
                : name;
            return new PropertyField(parentTypeName, name, location, columnName,
                resolveColumnOnJooqTableRecord(columnName, parentResultType));
        }

        // Object return type on a result-mapped parent.
        String rawTypeName = baseTypeName(fieldDef);
        String elementTypeName = ctx.isConnectionType(rawTypeName) ? ctx.connectionElementTypeName(rawTypeName) : rawTypeName;
        String columnName = fieldDef.hasAppliedDirective(DIR_FIELD)
            ? argString(fieldDef, DIR_FIELD, ARG_NAME).orElse(name)
            : name;
        // Typed @record parents backed by a jOOQ TableRecord anchor the path's starting table,
        // which is how parsePath validates FK direction on each hop. Without it, multi-hop paths
        // through junction tables (e.g. film → film_actor → actor) flip the first hop's traversal
        // direction and spuriously fail to resolve.
        String parentSqlTableName = parentResultType instanceof GraphitronType.JooqTableRecordType jtr && jtr.table() != null
            ? jtr.table().tableName() : null;
        var resolvedReturnType = ctx.resolveReturnType(elementTypeName, buildWrapper(fieldDef));
        String targetSqlTableName = resolvedReturnType instanceof ReturnTypeRef.TableBoundReturnType tbt
            ? tbt.table().tableName() : null;
        var objectPath = ctx.parsePath(fieldDef, name, parentSqlTableName, targetSqlTableName);
        if (objectPath.hasError()) {
            return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR, objectPath.errorMessage());
        }
        return switch (resolvedReturnType) {
            case ReturnTypeRef.TableBoundReturnType tb -> {
                var components = resolveTableFieldComponents(fieldDef, tb.table(), elementTypeName);
                if (components instanceof TableFieldComponents.Rejected rj) yield new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR, rj.message());
                var tfc = (TableFieldComponents.Ok) components;
                var batchKey = deriveBatchKeyForResultType(objectPath.elements(), parentResultType);
                if (hasLookupKeyAnywhere(fieldDef)) {
                    if (batchKey == null) {
                        yield new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.DEFERRED,
                            "RecordLookupTableField requires a FK join path and a typed backing class for batch key extraction; for free-form DTO parents, supply @batchKeyLifter on the field");
                    }
                    yield new RecordLookupTableField(parentTypeName, name, location, tb, objectPath.elements(), tfc.filters(), tfc.orderBy(), tfc.pagination(),
                        batchKey, tfc.lookupMapping());
                }
                if (batchKey == null) {
                    yield new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.DEFERRED,
                        "RecordTableField requires a FK join path and a typed backing class for batch key extraction; for free-form DTO parents, supply @batchKeyLifter on the field");
                }
                yield new RecordTableField(parentTypeName, name, location, tb, objectPath.elements(), tfc.filters(), tfc.orderBy(), tfc.pagination(), batchKey);
            }
            case ReturnTypeRef.ResultReturnType r ->
                new RecordField(parentTypeName, name, location, r, columnName,
                    resolveColumnOnJooqTableRecord(columnName, parentResultType));
            case ReturnTypeRef.ScalarReturnType s ->
                new RecordField(parentTypeName, name, location, s, columnName,
                    resolveColumnOnJooqTableRecord(columnName, parentResultType));
            case ReturnTypeRef.PolymorphicReturnType p -> {
                var lift = liftToErrorsField(fieldDef, parentTypeName, p);
                yield lift != null ? lift
                    : new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.DEFERRED,
                        "@record type returning a polymorphic type is not yet supported");
            }
        };
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
     * Derives the {@link BatchKey} for a {@code @table}-parent {@code @splitQuery} field. Single
     * cardinality keys by the parent's FK columns (parent-holds-FK); list cardinality keys by the
     * parent's PK. The direction signal is cardinality alone — the {@code @splitQuery} schema
     * contract ties Single ⇒ parent-holds-FK and List ⇒ child-holds-FK, so no table-identity
     * comparison is needed. The caller enforces the single-hop precondition; this helper only
     * picks the keying strategy and is safe to call with any path shape (multi-hop single
     * cardinality falls through to parent-PK, but the classifier rejects it upstream).
     *
     * <p>Sibling of {@link #deriveBatchKeyForResultType} — that helper is for record parents and
     * unconditionally uses {@code fk.sourceColumns()} because record parents never batch by
     * parent PK.
     */
    private static BatchKey.ParentKeyed deriveSplitQueryBatchKey(TableRef parentTable, List<JoinStep> path, boolean isList) {
        if (!isList && !path.isEmpty() && path.get(0) instanceof JoinStep.FkJoin fk) {
            return new BatchKey.RowKeyed(fk.sourceColumns());
        }
        return new BatchKey.RowKeyed(parentTable.primaryKeyColumns());
    }

    /**
     * Derives the {@link BatchKey} for a record-parent batched field
     * ({@link no.sikt.graphitron.rewrite.model.ChildField.RecordTableField},
     * {@link no.sikt.graphitron.rewrite.model.ChildField.RecordLookupTableField}) by reading the FK
     * source columns from the join path's first {@link JoinStep.FkJoin} step.
     *
     * <p>Returns {@code null} (→ {@link GraphitronField.UnclassifiedField}) when:
     * <ul>
     *   <li>the join path is empty or its first step is not an {@link JoinStep.FkJoin}</li>
     *   <li>the parent is an untyped {@link GraphitronType.PojoResultType} with a {@code null} class
     *       (cannot generate a typed cast for key extraction)</li>
     * </ul>
     */
    private static BatchKey.RecordParentBatchKey deriveBatchKeyForResultType(
            List<JoinStep> joinPath, GraphitronType.ResultType parentResultType) {
        if (joinPath.isEmpty() || !(joinPath.get(0) instanceof JoinStep.FkJoin fkJoin)) {
            return null;
        }
        if (parentResultType instanceof GraphitronType.PojoResultType prt && prt.fqClassName() == null) {
            return null;
        }
        return new BatchKey.RowKeyed(fkJoin.sourceColumns());
    }

    @no.sikt.graphitron.rewrite.model.LoadBearingClassifierCheck(
        key = "column-field-requires-table-backed-parent",
        description = "ChildField.ColumnField is constructed only inside this method (sole "
            + "construction site at the tail return), and this method is only entered for "
            + "parents that are table-backed types. Lets the TypeFetcherGenerator switch arm "
            + "treat parentTable == null as a classifier-invariant violation rather than an "
            + "expected runtime branch.")
    private GraphitronField classifyChildFieldOnTableType(GraphQLFieldDefinition fieldDef, String parentTypeName, TableBackedType tableType, Set<String> expandingTypes) {
        String name = fieldDef.getName();
        SourceLocation location = locationOf(fieldDef);

        if (fieldDef.hasAppliedDirective(DIR_BATCH_KEY_LIFTER)) {
            return new UnclassifiedField(parentTypeName, name, location, fieldDef,
                RejectionKind.AUTHOR_ERROR,
                "@batchKeyLifter is for @record (non-table) parents; use @reference on a @table parent");
        }

        if (fieldDef.hasAppliedDirective(DIR_SERVICE)) {
            var resolved = serviceResolver.resolve(parentTypeName, fieldDef, tableType.table().primaryKeyColumns());
            if (resolved instanceof ServiceDirectiveResolver.Resolved.Rejected r) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, r.kind(), r.message());
            }
            if (resolved instanceof ServiceDirectiveResolver.Resolved.ErrorsLifted e) {
                return e.field();
            }
            // Service reconnect path: starts from the service return type's table (not the parent).
            var servicePath = ctx.parsePath(fieldDef, name, null, null);
            if (servicePath.hasError()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR, servicePath.errorMessage());
            }
            return switch ((ServiceDirectiveResolver.Resolved.Success) resolved) {
                case ServiceDirectiveResolver.Resolved.TableBound tb ->
                    new ServiceTableField(parentTypeName, name, location, tb.returnType(),
                        servicePath.elements(), List.of(), new OrderBySpec.None(), null,
                        tb.method(), extractBatchKey(tb.method()));
                case ServiceDirectiveResolver.Resolved.Result r ->
                    new ServiceRecordField(parentTypeName, name, location, r.returnType(), servicePath.elements(), r.method(), extractBatchKey(r.method()));
                case ServiceDirectiveResolver.Resolved.Scalar s ->
                    new ServiceRecordField(parentTypeName, name, location, s.returnType(), servicePath.elements(), s.method(), extractBatchKey(s.method()));
            };
        }

        if (fieldDef.hasAppliedDirective(DIR_EXTERNAL_FIELD)) {
            var externalPath = ctx.parsePath(fieldDef, name, tableType.table().tableName(), null);
            if (externalPath.hasError()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR, externalPath.errorMessage());
            }
            return switch (externalFieldResolver.resolve(parentTypeName, fieldDef, tableType.table())) {
                case ExternalFieldDirectiveResolver.Resolved.Rejected r ->
                    new UnclassifiedField(parentTypeName, name, location, fieldDef, r.kind(), r.message());
                case ExternalFieldDirectiveResolver.Resolved.Success s ->
                    new ComputedField(parentTypeName, name, location, s.returnType(), externalPath.elements(), s.method());
            };
        }

        if (fieldDef.hasAppliedDirective(DIR_TABLE_METHOD)) {
            var tableMethodPath = ctx.parsePath(fieldDef, name, tableType.table().tableName(), null);
            if (tableMethodPath.hasError()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR, tableMethodPath.errorMessage());
            }
            return switch (tableMethodResolver.resolve(parentTypeName, fieldDef, false)) {
                case TableMethodDirectiveResolver.Resolved.Rejected r ->
                    new UnclassifiedField(parentTypeName, name, location, fieldDef, r.kind(), r.message());
                case TableMethodDirectiveResolver.Resolved.TableBound tb ->
                    new TableMethodField(parentTypeName, name, location, tb.returnType(), tableMethodPath.elements(), tb.method());
                case TableMethodDirectiveResolver.Resolved.NonTableBound nb ->
                    new TableMethodField(parentTypeName, name, location, nb.returnType(), tableMethodPath.elements(), nb.method());
            };
        }

        if (!isScalarOrEnum(fieldDef)) {
            return classifyObjectReturnChildField(fieldDef, parentTypeName, tableType, expandingTypes);
        }

        if (fieldDef.hasAppliedDirective(DIR_NODE_ID)) {
            Optional<String> typeName = argString(fieldDef, DIR_NODE_ID, ARG_TYPE_NAME);
            if (typeName.isPresent()) {
                ReturnTypeRef targetType = ctx.resolveReturnType(typeName.get(), new FieldWrapper.Single(true));
                var targetGType = ctx.types.get(typeName.get());
                if (targetGType == null) {
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR,
                        "@nodeId(typeName:) type '" + typeName.get() + "' does not exist in the schema"
                        + candidateHint(typeName.get(), new ArrayList<>(ctx.types.keySet())));
                }
                if (!(targetGType instanceof NodeType targetNodeType)) {
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR,
                        "@nodeId(typeName:) type '" + typeName.get() + "' does not have @node");
                }
                TableRef parentTable = tableType.table();
                var nodeRefPath = ctx.parsePath(fieldDef, name, tableType.table().tableName(), targetNodeType.table().tableName());
                if (nodeRefPath.hasError()) {
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR, nodeRefPath.errorMessage());
                }
                return buildNodeIdReferenceCarrier(parentTypeName, name, location, parentTable, targetNodeType, nodeRefPath.elements());
            } else {
                if (!(tableType instanceof NodeType nodeType)) {
                    return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR,
                        "@nodeId requires the containing type to be a node type (via @node or KjerneJooqGenerator metadata)");
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
                    crossTable.column(), crossTable.fkJoin(), crossTable.aliasName());
            }
            var refPath = ctx.parsePath(fieldDef, name, tableType.table().tableName(), null);
            if (refPath.hasError()) {
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR, refPath.errorMessage());
            }
            Optional<ColumnRef> column = svc.resolveColumnForReference(columnName, refPath.elements(), tableType);
            if (column.isEmpty()) {
                String terminalTable = svc.terminalTableSqlNameForReference(refPath.elements(), tableType);
                return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR,
                    "column '" + columnName + "' could not be resolved in the jOOQ table"
                    + (terminalTable != null ? candidateHint(columnName, ctx.catalog.columnJavaNamesOf(terminalTable)) : ""));
            }
            if (!columnName.equals(column.get().javaName())) {
                LOG.warn("@field(name: '{}') on field '{}.{}' resolved via SQL name; prefer Java field name '{}'",
                    columnName, parentTypeName, name, column.get().javaName());
            }
            return new ColumnReferenceField(parentTypeName, name, location, columnName, column.get(), refPath.elements(),
                new no.sikt.graphitron.rewrite.model.CallSiteCompaction.Direct());
        }

        Optional<ColumnRef> column = svc.resolveColumn(columnName, tableType);
        if (column.isEmpty()) {
            String tableSqlName = tableType.table().tableName();
            boolean isList = GraphQLTypeUtil.unwrapNonNull(fieldDef.getType()) instanceof GraphQLList;
            String typeName = ((GraphQLNamedType) GraphQLTypeUtil.unwrapAll(fieldDef.getType())).getName();
            // Path 2 — migration-shim NodeIdField. `@nodeId`, `@reference`, and `@field` are
            // already excluded above (`@nodeId` by the directive check, `@reference` by its own
            // block, and `@field` via the exclusion here). Fires a per-site deprecation diagnostic
            // — the canonical form is to declare `@nodeId` explicitly. Retired at R7. See plan:
            // graphitron-rewrite/roadmap/retire-nodeid-synthesis-shim.md.
            if (tableType instanceof NodeType nodeType
                    && "ID".equals(typeName)
                    && !isList
                    && !hasFieldDirective) {
                LOG.warn("field '{}.{}' synthesizes NodeIdField without '@nodeId' — declare the"
                    + " directive explicitly; synthesis shim will be removed in a future release."
                    + " See graphitron-rewrite/roadmap/retire-nodeid-synthesis-shim.md",
                    parentTypeName, name);
                return buildNodeIdOutputCarrier(parentTypeName, name, location, nodeType);
            }
            return new UnclassifiedField(parentTypeName, name, location, fieldDef, RejectionKind.AUTHOR_ERROR,
                "column '" + columnName + "' could not be resolved in the jOOQ table"
                + candidateHint(columnName, ctx.catalog.columnJavaNamesOf(tableSqlName)));
        }
        if (!columnName.equals(column.get().javaName())) {
            LOG.warn("@field(name: '{}') on field '{}.{}' resolved via SQL name; prefer Java field name '{}'",
                columnName, parentTypeName, name, column.get().javaName());
        }
        return new ColumnField(parentTypeName, name, location, columnName, column.get(),
            new no.sikt.graphitron.rewrite.model.CallSiteCompaction.Direct());
    }

    /**
     * Builds the post-R50 output carrier for an {@code @nodeId} (no {@code typeName:}) field.
     * Routes by {@code nodeType.nodeKeyColumns().size()}: arity-1 to a single-column
     * {@link ColumnField} carrying {@link no.sikt.graphitron.rewrite.model.CallSiteCompaction.NodeIdEncodeKeys},
     * arity > 1 to a {@link ChildField.CompositeColumnField} narrowed to the same compaction arm.
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
     * Builds the post-R50 output carrier for an {@code @nodeId(typeName: T)} reference field.
     * Two shapes per "Variant-by-variant collapse → Single-hop emission, two shapes":
     * <ul>
     *   <li><b>Rooted at child (FK-mirror).</b> The single FK hop's source columns on the parent
     *       table positionally equal the target NodeType's {@code keyColumns}, so the parent
     *       columns ARE the keys; emit them directly through {@link ColumnField} /
     *       {@link ChildField.CompositeColumnField} (no joinPath).</li>
     *   <li><b>Rooted at parent (non-mirror, including multi-hop / condition-join).</b> The FK
     *       columns differ from the target's keyColumns, or the path has more than one step;
     *       emit through {@link ColumnReferenceField} / {@link ChildField.CompositeColumnReferenceField}
     *       carrying the target's keyColumns plus the resolved {@code joinPath}. Single-hop
     *       rooted-at-parent emission is the JOIN-with-projection capability R50 absorbs from
     *       R24; multi-hop and condition-join paths surface as runtime stubs at the emitter
     *       until the broader emission lift lands.</li>
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
            return new ColumnReferenceField(parentTypeName, name, location, k.javaName(), k, joinPath, compaction);
        }
        return new ChildField.CompositeColumnReferenceField(parentTypeName, name, location, keys, joinPath, compaction);
    }

    /**
     * Returns the FK source columns on the parent table when the join path collapses to a single
     * FK hop entered from the parent (parent-holds-FK pattern) <em>and</em> the FK's target
     * columns positionally match the target NodeType's {@code keyColumns}. {@code null} otherwise
     * (composite-key with non-mirroring FK, multi-hop, condition-join).
     */
    private static List<ColumnRef> fkMirrorSourceColumns(TableRef parentTable, List<JoinStep> joinPath,
                                                          List<ColumnRef> targetKeyColumns) {
        if (joinPath.size() != 1) return null;
        if (!(joinPath.get(0) instanceof JoinStep.FkJoin fk)) return null;
        if (!fk.originTable().tableName().equalsIgnoreCase(parentTable.tableName())) return null;
        if (fk.targetColumns().size() != targetKeyColumns.size()) return null;
        for (int i = 0; i < fk.targetColumns().size(); i++) {
            if (!fk.targetColumns().get(i).sqlName().equalsIgnoreCase(targetKeyColumns.get(i).sqlName())) {
                return null;
            }
        }
        return fk.sourceColumns();
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
        Map<String, String> argMapping = ((ArgBindingMap.ParsedArgMapping.Ok) parsed).overrides();
        return new ExternalRef(className, methodName, argMapping, null, null);
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
     * Carries the result of {@link #resolveChildPolymorphicJoinPaths}: a per-participant
     * {@code Map<String, List<JoinStep>>} keyed by typename, or a non-null error message when
     * any participant's FK cannot be uniquely auto-discovered.
     */
    private record ChildPolymorphicJoinPaths(java.util.Map<String, List<JoinStep>> paths, String error) {
        static ChildPolymorphicJoinPaths ok(java.util.Map<String, List<JoinStep>> paths) {
            return new ChildPolymorphicJoinPaths(paths, null);
        }
        static ChildPolymorphicJoinPaths fail(String error) {
            return new ChildPolymorphicJoinPaths(java.util.Map.of(), error);
        }
    }

    /**
     * R36 Track B3: resolves the per-participant FK chain from {@code parentTable} to each
     * {@link ParticipantRef.TableBound} participant's table. Each call to
     * {@link BuildContext#parsePath} for a different target table picks up a different
     * auto-discovered FK, so heterogeneous participants (each on its own table with its own
     * FK back to the parent) yield a distinct path per branch.
     *
     * <p>Returns an error result when any participant fails (zero or multiple FKs between the
     * pair); the field is then classified as {@link UnclassifiedField}. {@link ParticipantRef.Unbound}
     * participants are skipped and produce no map entry.
     */
    private ChildPolymorphicJoinPaths resolveChildPolymorphicJoinPaths(
            GraphQLFieldDefinition fieldDef, String fieldName, String parentTypeName,
            SourceLocation location, TableRef parentTable, List<ParticipantRef> participants) {
        var paths = new java.util.LinkedHashMap<String, List<JoinStep>>();
        for (var p : participants) {
            if (!(p instanceof ParticipantRef.TableBound tb)) continue;
            var parsed = ctx.parsePath(fieldDef, fieldName, parentTable.tableName(), tb.table().tableName());
            if (parsed.hasError()) {
                return ChildPolymorphicJoinPaths.fail(
                    "participant '" + tb.typeName() + "': " + parsed.errorMessage());
            }
            paths.put(tb.typeName(), parsed.elements());
        }
        return ChildPolymorphicJoinPaths.ok(paths);
    }

    /** Collects the non-null discriminator values from all {@link ParticipantRef.TableBound} participants. */
    private static List<String> knownDiscriminatorValues(GraphitronType.TableInterfaceType tit) {
        return tit.participants().stream()
            .filter(p -> p instanceof ParticipantRef.TableBound tb && tb.discriminatorValue() != null)
            .map(p -> ((ParticipantRef.TableBound) p).discriminatorValue())
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
        for (var t : ctx.types.values()) {
            if (!(t instanceof GraphitronType.TableInterfaceType tit)) continue;
            for (var p : tit.participants()) {
                if (!(p instanceof ParticipantRef.TableBound tb)) continue;
                if (!tb.typeName().equals(parentTypeName)) continue;
                for (var ctf : tb.crossTableFields()) {
                    if (ctf.fieldName().equals(fieldName)) return ctf;
                }
            }
        }
        return null;
    }

    /**
     * Validates that a join path for a {@link ChildField.TableInterfaceField} is a single
     * {@link JoinStep.FkJoin} step. Returns an error message if the path is multi-hop or
     * contains a {@link JoinStep.ConditionJoin}, or {@code null} if the path is valid.
     */
    private static String validateSingleHopFkJoin(List<JoinStep> path, String fieldName) {
        if (path.size() != 1) {
            return "Field '" + fieldName + "': TableInterfaceField @reference paths must be a single FK hop "
                + "(multi-hop paths are not yet supported — see stub-interface-union-fetchers.md)";
        }
        if (!(path.get(0) instanceof JoinStep.FkJoin)) {
            return "Field '" + fieldName + "': TableInterfaceField @reference paths must use a foreign key "
                + "(ConditionJoin paths are not yet supported — see stub-interface-union-fetchers.md)";
        }
        return null;
    }

    // ===== Inner records =====

    record ExternalRef(String className, String methodName, Map<String, String> argMapping,
                       String lookupError, String argMappingError) {}

    static Set<String> fieldArgumentNames(GraphQLFieldDefinition fieldDef) {
        return fieldDef.getArguments().stream()
            .map(GraphQLArgument::getName)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
