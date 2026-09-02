package no.sikt.graphitron.rewrite;

import graphql.schema.GraphQLFieldDefinition;
import no.sikt.graphitron.render.CatalogRefs;
import no.sikt.graphitron.rewrite.model.CallSiteExtraction;
import no.sikt.graphitron.rewrite.model.ParamSource;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.RoutineRef;
import no.sikt.graphitron.rewrite.model.TableRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static no.sikt.graphitron.rewrite.BuildContext.ARG_ARG_MAPPING;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_COLUMN_MAPPING;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_CONDITION;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_ORDER_BY;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_REFERENCE;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_ROUTINE;
import static no.sikt.graphitron.rewrite.BuildContext.baseTypeName;

/**
 * Resolves {@code @routine} on a field into a sealed {@link Resolved} the caller switches on, the
 * database-routine sibling of {@link ServiceDirectiveResolver}. Day-one resolves the
 * table-valued read function:
 *
 * <ul>
 *   <li>Shape invariant: the return type must be table-bound (a
 *       {@link ReturnTypeRef.TableBoundReturnType}), which for a hop-less read the routine's own
 *       result already makes it ({@link TypeBuilder#routineReturnVerdict}), so {@code @table} on
 *       such a return is redundant rather than demanded. The Connection-wrapper verdicts and the
 *       terminus rule are chain-level facts, evaluated by the caller over the landed chain.</li>
 *   <li>{@link JooqCatalog#resolveTableValuedFunction} resolves the routine name to a catalog
 *       table-valued function and its {@code Routines}-class call surface. The deferred scalar-read
 *       and procedure-write forks reject here (they do not resolve as table-valued functions),
 *       honouring "validator mirrors classifier invariants" — they fail at validate time, not emit.</li>
 *   <li>IN-parameter binding: each routine parameter (in declaration order) binds to a GraphQL field
 *       argument via {@code argMapping} (identity for unmentioned). A parameter binding to an absent
 *       field argument is a typed rejection.</li>
 * </ul>
 */
final class RoutineDirectiveResolver {

    /**
     * Outcome of {@link #resolve}. {@link TableBound} on success (the
     * only success shape, since a read routine is {@code @table}-bound by construction);
     * {@link Rejected} for every error path.
     *
     * <p>{@code resultTable} is the routine's own result table. On the single-node chain it equals
     * {@code returnType.table()}; when hops follow the routine it is that node's position in the
     * chain while {@code returnType.table()} is the terminus the chain must land on. Whether the
     * two agree is the chain-level terminus rule, evaluated by the caller over the <em>landed</em>
     * chain ({@code FieldBuilder.routineChainVerdict}), never here — this resolver knows the
     * routine node, not the node's position.
     */
    sealed interface Resolved {
        record TableBound(ReturnTypeRef.TableBoundReturnType returnType, RoutineRef routine,
                TableRef resultTable) implements Resolved {}
        record Rejected(Rejection rejection) implements Resolved {}
    }

    /**
     * Outcome of {@link #resolveCarrierNode}: the routine node alone — name, argument binding,
     * result table — with the return-shape demand left on the chain path where it belongs. The
     * carrier seat ({@code FieldBuilder.classifyMutationField}'s {@code @routine} fork) resolves
     * the call off a payload-carrier return, which is by definition not {@code @table}-bound, so
     * it consumes this node-only resolution; {@link #resolve} keeps the table-bound demand for
     * the chain path.
     */
    sealed interface NodeResolved {
        record Node(RoutineRef routine, TableRef resultTable) implements NodeResolved {}
        record Rejected(Rejection rejection) implements NodeResolved {}
    }

    private final BuildContext ctx;
    private final FieldBuilder fb;

    RoutineDirectiveResolver(BuildContext ctx, FieldBuilder fb) {
        this.ctx = ctx;
        this.fb = fb;
    }

    /**
 * Resolves the {@code @routine} application contributing a chain's routine node:
     * call-surface resolution, argument binding, and {@code columnMapping} binding against the
     * previous node. Position-agnostic by design — whether the node is the chain's head,
     * mid-chain, or terminus is a fact about the <em>landed</em> chain, so the terminus rule and
     * the Connection composition verdicts are evaluated once by the caller over the finished
     * chain ({@code FieldBuilder.routineChainVerdict}), never here.
     *
     * <p>{@code previousNodeTableSqlName} is the previous node of the chain: {@code null} at root
     * (a root chain's head is the routine itself; {@code columnMapping} is illegal), the implicit
     * head (the parent type's table) for a routine heading a child chain, the preceding hop's
     * target for a routine deeper in ({@code columnMapping} binds against it).
     */
    Resolved resolve(String parentTypeName, GraphQLFieldDefinition fieldDef, boolean isRoot,
            String previousNodeTableSqlName) {
        return switch (resolveNode(parentTypeName, fieldDef, isRoot, previousNodeTableSqlName)) {
            case NodeResolved.Rejected r -> new Resolved.Rejected(r.rejection());
            case NodeResolved.Node n -> bindReturn(fieldDef, n);
        };
    }

    /**
     * The return-shape invariant, applied to a resolved node. Runs after the node so a field whose
     * routine name is wrong hears about the name: the return binding of a hop-less chain is derived
     * from the routine's own result, so an unresolvable name and an unbindable return would
     * otherwise reach the author as the same complaint about the return type.
     *
     * <p>Nothing is derived here. A hop-less {@code @routine} read's return type is already bound to
     * the routine's result by {@code TypeBuilder.routineReturnVerdict}, so it arrives as a
     * {@link ReturnTypeRef.TableBoundReturnType} whether or not the author restated the routine's
     * name in a {@code @table}; deriving at this seat instead would mean binding a return the
     * mutation carrier fork tells apart by its <em>not</em> being table-bound.
     */
    private Resolved bindReturn(GraphQLFieldDefinition fieldDef, NodeResolved.Node node) {
        String rawTypeName = baseTypeName(fieldDef);
        String elementTypeName = ctx.isConnectionType(rawTypeName)
            ? ctx.connectionElementTypeName(rawTypeName)
            : rawTypeName;
        ReturnTypeRef returnType = ctx.resolveReturnType(elementTypeName, fb.buildWrapper(fieldDef));

        if (returnType instanceof ReturnTypeRef.TableBoundReturnType tableBound) {
            return new Resolved.TableBound(tableBound, node.routine(), node.resultTable());
        }
        // Two shapes reach here and they fail for different reasons, so they say different things.
        // A chain's landing is a catalog table the author names on the return type; a hop-less
        // routine's landing is its own result, which binds the return type for them, and what is
        // left to fail is the return not being a type a table binding can attach to.
        return new Resolved.Rejected(Rejection.structural(
            fieldDef.hasAppliedDirective(DIR_REFERENCE)
                ? "@routine with @reference requires a @table-annotated return type — the chain "
                    + "lands on a catalog table and the return type must name it"
                : "@routine could not bind the return type '" + elementTypeName + "' to its result "
                    + "table — the routine's result binds its return type, so no @table is needed, "
                    + "but the return must be a plain object type; a scalar, an interface, a union "
                    + "or a payload-carrier return has no binding to take"));
    }

    /**
     * The carrier seat's resolution: the routine node with no return-shape demand (the caller
     * already established the payload-carrier return). Root position by definition (the carrier
     * fork is a Mutation root shape), so {@code columnMapping} rejects through the shared root
     * check. The write seat's read-surface deferral is the caller's
     * ({@link #writeSeatReadSurfaceDeferral}), seat-gated the way the carrier's own directive
     * conflict is.
     */
    NodeResolved resolveCarrierNode(String parentTypeName, GraphQLFieldDefinition fieldDef) {
        return resolveNode(parentTypeName, fieldDef, /*isRoot=*/true, null);
    }

    /**
     * The write seat's read-surface deferral, or {@code null} when the field declares no filter
     * or order surface. Read fields resolve {@code @condition} and {@code @orderBy} against the
     * chain terminus like any other table read; the Mutation write seats do not, because neither
     * {@link no.sikt.graphitron.rewrite.model.MutationField.MutationRoutineWriteField} nor
     * {@link no.sikt.graphitron.rewrite.model.MutationField.MutationRoutineWriteRecordField}
     * carries a filter or ordering component at all. Without this check the directives would
     * classify clean on a write and silently do nothing.
     *
     * <p>{@code @orderBy} is argument-positioned; {@code @condition} appears on the field or on
     * its arguments.
     */
    static Rejection writeSeatReadSurfaceDeferral(GraphQLFieldDefinition fieldDef) {
        boolean hasOrderOrCondition = fieldDef.hasAppliedDirective(DIR_CONDITION)
            || fieldDef.getArguments().stream().anyMatch(a ->
                a.hasAppliedDirective(DIR_ORDER_BY) || a.hasAppliedDirective(DIR_CONDITION));
        if (!hasOrderOrCondition) {
            return null;
        }
        return Rejection.deferred(
            "@orderBy / @condition on a @routine Mutation field is not yet supported — the "
            + "routine write's result shape carries no filter or order surface; compose the "
            + "read surface on the Query field that reads the written rows");
    }

    /**
     * The shared node resolution behind {@link #resolve} and {@link #resolveCarrierNode}:
     * directive parsing, call-surface resolution against the catalog, and argument binding.
     * Knows nothing about the field's return shape.
     */
    private NodeResolved resolveNode(String parentTypeName, GraphQLFieldDefinition fieldDef,
            boolean isRoot, String previousNodeTableSqlName) {
        var dir = fieldDef.getAppliedDirective(DIR_ROUTINE);
        if (dir == null) {
            // Caller pre-checked hasAppliedDirective; reaching here is a classifier bug.
            throw new IllegalStateException(
                "RoutineDirectiveResolver invoked on field without @routine: "
                + parentTypeName + "." + fieldDef.getName());
        }
        String routineName = Optional.ofNullable(dir.getArgument(ARG_NAME))
            .map(a -> a.getValue()).map(Object::toString).orElse(null);
        if (routineName == null || routineName.isBlank()) {
            return new NodeResolved.Rejected(Rejection.structural("@routine requires a non-empty `name`"));
        }
        String rawArgMapping = Optional.ofNullable(dir.getArgument(ARG_ARG_MAPPING))
            .map(a -> a.getValue()).map(Object::toString).orElse(null);
        var parsedMapping = ArgBindingMap.parseArgMapping(rawArgMapping, ArgMappingSigil.Site.ROUTINE);
        if (parsedMapping instanceof ArgBindingMap.ParsedArgMapping.ParseError pe) {
            return new NodeResolved.Rejected(Rejection.structural("@routine " + pe.message()));
        }
        Map<String, List<String>> overrides = ((ArgBindingMap.ParsedArgMapping.Ok) parsedMapping).overrides();

        String rawColumnMapping = Optional.ofNullable(dir.getArgument(ARG_COLUMN_MAPPING))
            .map(a -> a.getValue()).map(Object::toString).orElse(null);
        var parsedColumnMapping = ArgBindingMap.parseArgMapping(rawColumnMapping);
        if (parsedColumnMapping instanceof ArgBindingMap.ParsedArgMapping.ParseError pe) {
            return new NodeResolved.Rejected(Rejection.structural("@routine columnMapping " + pe.message()));
        }
        Map<String, List<String>> columnOverrides =
            ((ArgBindingMap.ParsedArgMapping.Ok) parsedColumnMapping).overrides();
        if (!columnOverrides.isEmpty() && isRoot) {
            return new NodeResolved.Rejected(Rejection.structural(
                "@routine columnMapping requires a previous table node in the chain, and a root "
                + "chain's head has none — bind routine parameters from GraphQL arguments via argMapping"));
        }
        if (!columnOverrides.isEmpty() && previousNodeTableSqlName == null) {
            // Non-root position whose implicit head is not a resolvable catalog table (a
            // record-backed parent). Correlation against a record head lands with the emit slice.
            return new NodeResolved.Rejected(Rejection.deferred(
                "@routine columnMapping under a parent without a catalog table is not yet supported"));
        }

        return switch (ctx.catalog.resolveTableValuedFunction(routineName)) {
            case JooqCatalog.RoutineResolution.NotInCatalog ignored -> new NodeResolved.Rejected(Rejection.unknownTable(
                "@routine could not be resolved — no table-valued function named '" + routineName
                + "' in the jOOQ catalog",
                routineName, ctx.catalog.allTableSqlNames()));
            // The name exists as a database routine but is not table-valued (a procedure or
            // scalar / void function): a capability gap, not a typo, so it signposts the
            // non-table-valued call surface's follow-up item rather than the unknown-name rejection.
            case JooqCatalog.RoutineResolution.NonTableValuedRoutine ntv -> new NodeResolved.Rejected(Rejection.deferred(
                "@routine " + ntv.detail()
                + "; the non-table-valued call surface (procedures, scalar and void routines) does not emit yet"));
            case JooqCatalog.RoutineResolution.NotATableValuedFunction ignored -> new NodeResolved.Rejected(Rejection.structural(
                "@routine could not be resolved — '" + routineName
                + "' resolves to a table or view, not a table-valued function"));
            case JooqCatalog.RoutineResolution.NoConvenienceMethod nc -> new NodeResolved.Rejected(Rejection.structural(
                "@routine could not be resolved — " + nc.detail()));
            case JooqCatalog.RoutineResolution.Resolved fn ->
                bindArgs(fieldDef, fn, overrides, columnOverrides, previousNodeTableSqlName);
        };
    }

    /**
     * Binds every routine IN parameter to its value source, routing the {@code argMapping} half
     * through the shared {@link ArgBindingMap#of} seam so a {@code @routine} path expression
     * resolves against the field's argument types by exactly the rules {@code @service} and
     * {@code @condition} use. Both left-hand-side checks live here, mirroring what
     * {@code columnMapping} and {@code ServiceCatalog.checkOverrideTargets} already do: an entry
     * naming a non-parameter, and a parameter left with no binding.
     */
    private NodeResolved bindArgs(GraphQLFieldDefinition fieldDef,
            JooqCatalog.RoutineResolution.Resolved fn, Map<String, List<String>> overrides,
            Map<String, List<String>> columnOverrides, String previousNodeTableSqlName) {
        for (var claimed : columnOverrides.keySet()) {
            if (fn.params().stream().noneMatch(p -> p.name().equals(claimed))) {
                return new NodeResolved.Rejected(Rejection.structural(
                    "@routine columnMapping names parameter '" + claimed
                    + "', which is not an IN parameter of routine '" + fn.methodName() + "'"));
            }
            if (overrides.containsKey(claimed)) {
                return new NodeResolved.Rejected(Rejection.structural(
                    "@routine parameter '" + claimed + "' appears in both argMapping and columnMapping — "
                    + "a routine parameter has exactly one source"));
            }
        }
        // The left-hand side is per-directive and does not unify: a routine names catalog IN
        // parameters, a @service method names reflected Java parameters. Without this check a
        // misspelled target is silently dropped and the parameter it meant to claim falls through
        // to the unbound rejection below, describing an entry the author did not write.
        for (var claimed : overrides.keySet()) {
            if (fn.params().stream().noneMatch(p -> p.name().equals(claimed))) {
                return new NodeResolved.Rejected(Rejection.structural(
                    "@routine argMapping names parameter '" + claimed
                    + "', which is not an IN parameter of routine '" + fn.methodName() + "'"
                    + BuildContext.candidateHint(claimed,
                        fn.params().stream().map(JooqCatalog.RoutineParam::name).toList())));
            }
        }

        var slotTypes = FieldBuilder.argSlotTypes(fieldDef);
        var bindingResult = ArgBindingMap.of(slotTypes, overrides);
        if (bindingResult instanceof ArgBindingMap.Result.Failure f) {
            return new NodeResolved.Rejected(Rejection.structural("@routine " + f.message()));
        }
        // of() keys its identity entries by unclaimed *slot*, which is the opposite direction from
        // a routine's parameter list: the resolver knows its parameters up front and asks which
        // slot each one reads. So only the explicitly-mapped parameters are read out of the map;
        // the identity case resolves per parameter against the slot map below.
        var resolvedOverrides = ((ArgBindingMap.Result.Ok) bindingResult).map().byJavaName();
        var bindings = new ArrayList<RoutineRef.ArgBinding>();
        for (var param : fn.params()) {
            var columnOverride = columnOverrides.get(param.name());
            if (columnOverride != null) {
                if (columnOverride.size() != 1) {
                    // Permanent, not a capability gap: the right-hand side of a columnMapping entry
                    // names a column of the previous node, and a column has no sub-path to walk.
                    return new NodeResolved.Rejected(Rejection.structural(
                        "@routine columnMapping for parameter '" + param.name()
                        + "' must bind a single column of the previous node; a column has no nested "
                        + "fields, so dot-path bindings are meaningless here"));
                }
                String columnName = columnOverride.get(0);
                var column = ctx.catalog.resolveColumn(previousNodeTableSqlName, columnName);
                if (column.isEmpty()) {
                    return new NodeResolved.Rejected(Rejection.unknownColumn(
                        "@routine columnMapping binds parameter '" + param.name() + "' to column '"
                        + columnName + "', which is not a column of the previous node ('"
                        + previousNodeTableSqlName + "')",
                        columnName, ctx.catalog.columnSqlNamesOf(previousNodeTableSqlName)));
                }
                // Type compatibility: the emitted call passes the column's Field directly to the
                // routine's Field overload, so the column's boxed Java type must be the parameter's
                // boxed Java type — a mismatch here would be a javac error in the generated source.
                if (!column.get().columnClass().equals(param.typeName())) {
                    return new NodeResolved.Rejected(Rejection.structural(
                        "@routine columnMapping binds parameter '" + param.name() + "' ("
                        + param.typeName() + ") to column '" + columnName + "' of '"
                        + previousNodeTableSqlName + "' (" + column.get().columnClass()
                        + ") — the column's Java type must match the routine parameter's"));
                }
                bindings.add(new RoutineRef.ArgBinding(param.name(), CatalogRefs.typeName(param.typeName()),
                    new ParamSource.SourceColumn(column.get())));
                continue;
            }
            PathExpr path;
            if (overrides.containsKey(param.name())) {
                path = resolvedOverrides.get(param.name());
            } else if (slotTypes.containsKey(param.name())) {
                path = PathExpr.head(param.name()); // identity-bind
            } else {
                return new NodeResolved.Rejected(Rejection.structural(
                    "@routine parameter '" + param.name() + "' has no binding: it is not a GraphQL "
                    + "argument of this field and no argMapping entry names it; available arguments are "
                    + ArgBindingMap.formatNameSet(slotTypes.keySet())
                    + BuildContext.candidateHint(param.name(), List.copyOf(slotTypes.keySet()))));
            }
            var leafGate = leafTypeGate(param, path, fieldDef, slotTypes);
            if (leafGate != null) {
                return new NodeResolved.Rejected(leafGate);
            }
            bindings.add(new RoutineRef.ArgBinding(param.name(), CatalogRefs.typeName(param.typeName()),
                new ParamSource.Arg(new CallSiteExtraction.Direct(), path)));
        }
        return new NodeResolved.Node(
            new RoutineRef(CatalogRefs.className(fn.routinesClassName()), fn.methodName(), bindings), fn.resultTable());
    }

    /**
     * The type gate on one argument-sourced routine parameter, or {@code null} when the binding
     * is sound. Two checks, in the order an author meets them:
     *
     * <ol>
     *   <li>A leaf that is neither scalar nor enum. Routine-specific: a jOOQ IN parameter has no
     *       bean concept to instantiate the way {@code InputBeanResolver} does for
     *       {@code @service}, so an input-object leaf can only ever emit a cast that fails at
     *       request time. This is the rejection that closes {@code "pParam: input"}, which the
     *       shared gate below passes through (the wire-coercion check has no opinion on a
     *       non-scalar leaf).</li>
     *   <li>The shared coercion-aware gate, {@link ServiceCatalog#argExtraction}. Three outcomes,
     *       not two: a rejection is an authoring error, a {@link CallSiteExtraction.Direct}
     *       extraction proceeds, and any other extraction is a deferral: the routine call
     *       emitter renders a direct read only, so an enum or converted leaf would need the
     *       coercing arms that do not emit yet.</li>
     * </ol>
     *
     * <p>Both checks stand aside on a leaf carrying {@code @nodeId}, because neither is about the
     * value the parameter receives. Such a leaf is decoded first, so what arrives is a key column's
     * own value rather than the {@code ID}'s coercion output, and a gate comparing the coercion
     * output against the declared type rejects the binding the decode exists to make work. The
     * checks below are also structurally unable to judge it: the key list and the column's binding
     * type are captured facts, and this runs before capture. So the whole judgment is the store's,
     * which has an arm for each way it fails,
     * {@link no.sikt.graphitron.rewrite.derive.ArgmappingProjectionDefects} decoding them.
     * Standing aside here rather than there is not a second copy of that rule: it is this gate
     * declining to answer a question about a value it is not looking at.
     */
    private Rejection leafTypeGate(JooqCatalog.RoutineParam param, PathExpr path,
            GraphQLFieldDefinition fieldDef,
            Map<String, graphql.schema.GraphQLInputType> slotTypes) {
        var segments = path.segments();
        for (int i = 0; i < segments.size() - 1; i++) {
            if (segments.get(i).liftsList()) {
                return Rejection.deferred(
                    "@routine parameter '" + param.name() + "' binds to '" + path.asString()
                    + "', which walks through the list-shaped field '" + segments.get(i).name()
                    + "'; element-wise traversal does not emit for routine bindings yet");
            }
        }
        if (ServiceCatalog.pathLeafDeclaresNodeId(path, fieldDef, slotTypes)) {
            return null; // a decoded value, not this leaf's own: the store judges it
        }
        var leafType = ServiceCatalog.resolvePathLeafType(path, slotTypes);
        if (leafType == null) {
            return null; // unresolvable leaf: pass through rather than over-reject
        }
        var named = graphql.schema.GraphQLTypeUtil.unwrapAll(leafType);
        if (!(named instanceof graphql.schema.GraphQLScalarType)
                && !(named instanceof graphql.schema.GraphQLEnumType)) {
            return Rejection.structural(
                "@routine parameter '" + param.name() + "' binds to '" + path.asString()
                + "', whose GraphQL type '" + graphql.schema.GraphQLTypeUtil.simplePrint(leafType)
                + "' is not a scalar or enum; a routine IN parameter takes a single value, so bind "
                + "a scalar field inside it (for example '" + param.name() + ": "
                + path.asString() + ".<field>')");
        }
        if (ctx.svc == null) {
            return null; // schema-free contexts carry no catalog to reflect against
        }
        var extraction = ctx.svc.argExtraction(param.typeName(), leafType,
            "@routine parameter '" + param.name() + "'");
        if (extraction instanceof ServiceCatalog.ArgExtraction.Rejected rejected) {
            return rejected.rejection();
        }
        var resolved = ((ServiceCatalog.ArgExtraction.Resolved) extraction).extraction();
        if (!(resolved instanceof CallSiteExtraction.Direct)) {
            return Rejection.deferred(
                "@routine parameter '" + param.name() + "' binds to '" + path.asString()
                + "', which needs a " + resolved.getClass().getSimpleName()
                + " extraction; the routine call emitter reads argument values directly, and the "
                + "coercing read arms do not emit yet");
        }
        return null;
    }
}
