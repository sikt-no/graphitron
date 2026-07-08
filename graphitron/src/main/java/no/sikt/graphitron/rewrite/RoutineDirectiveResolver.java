package no.sikt.graphitron.rewrite;

import graphql.schema.GraphQLFieldDefinition;
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
import static no.sikt.graphitron.rewrite.BuildContext.DIR_ROUTINE;
import static no.sikt.graphitron.rewrite.BuildContext.baseTypeName;

/**
 * Resolves {@code @routine} on a field into a sealed {@link Resolved} the caller switches on, the
 * database-routine sibling of {@link TableMethodDirectiveResolver}. Day-one resolves the
 * table-valued read function (R300):
 *
 * <ul>
 *   <li>Shape invariant: the return type must be {@code @table}-annotated (a
 *       {@link ReturnTypeRef.TableBoundReturnType}). The Connection-wrapper verdicts and the
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

    private final BuildContext ctx;
    private final FieldBuilder fb;

    RoutineDirectiveResolver(BuildContext ctx, FieldBuilder fb) {
        this.ctx = ctx;
        this.fb = fb;
    }

    /**
     * Resolves the {@code @routine} application contributing a chain's routine node (R435):
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
        // Composition verdict (R435): @orderBy / @condition key on the resolved table and are
        // meaningful for catalog-terminus chains, but no filter/order surface ships for
        // routine-backed fields yet — a capability gap, not a schema contradiction. @orderBy is
        // argument-positioned; @condition appears on the field or its arguments.
        boolean hasOrderOrCondition = fieldDef.hasAppliedDirective(DIR_CONDITION)
            || fieldDef.getArguments().stream().anyMatch(a ->
                a.hasAppliedDirective(DIR_ORDER_BY) || a.hasAppliedDirective(DIR_CONDITION));
        if (hasOrderOrCondition) {
            return new Resolved.Rejected(Rejection.deferred(
                "@orderBy / @condition on a routine-backed field is not yet supported — "
                + "no filter or order surface ships for routine-backed fields", ""));
        }

        String rawTypeName = baseTypeName(fieldDef);
        String elementTypeName = ctx.isConnectionType(rawTypeName)
            ? ctx.connectionElementTypeName(rawTypeName)
            : rawTypeName;
        ReturnTypeRef returnType = ctx.resolveReturnType(elementTypeName, fb.buildWrapper(fieldDef));

        if (!(returnType instanceof ReturnTypeRef.TableBoundReturnType tableBound)) {
            return new Resolved.Rejected(Rejection.structural("@routine requires a @table-annotated return type"));
        }

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
            return new Resolved.Rejected(Rejection.structural("@routine requires a non-empty `name`"));
        }
        String rawArgMapping = Optional.ofNullable(dir.getArgument(ARG_ARG_MAPPING))
            .map(a -> a.getValue()).map(Object::toString).orElse(null);
        var parsedMapping = ArgBindingMap.parseArgMapping(rawArgMapping);
        if (parsedMapping instanceof ArgBindingMap.ParsedArgMapping.ParseError pe) {
            return new Resolved.Rejected(Rejection.structural("@routine " + pe.message()));
        }
        Map<String, List<String>> overrides = ((ArgBindingMap.ParsedArgMapping.Ok) parsedMapping).overrides();

        String rawColumnMapping = Optional.ofNullable(dir.getArgument(ARG_COLUMN_MAPPING))
            .map(a -> a.getValue()).map(Object::toString).orElse(null);
        var parsedColumnMapping = ArgBindingMap.parseArgMapping(rawColumnMapping);
        if (parsedColumnMapping instanceof ArgBindingMap.ParsedArgMapping.ParseError pe) {
            return new Resolved.Rejected(Rejection.structural("@routine columnMapping " + pe.message()));
        }
        Map<String, List<String>> columnOverrides =
            ((ArgBindingMap.ParsedArgMapping.Ok) parsedColumnMapping).overrides();
        if (!columnOverrides.isEmpty() && isRoot) {
            return new Resolved.Rejected(Rejection.structural(
                "@routine columnMapping requires a previous table node in the chain, and a root "
                + "chain's head has none — bind routine parameters from GraphQL arguments via argMapping"));
        }
        if (!columnOverrides.isEmpty() && previousNodeTableSqlName == null) {
            // Non-root position whose implicit head is not a resolvable catalog table (a
            // record-backed parent). Correlation against a record head lands with the emit slice.
            return new Resolved.Rejected(Rejection.deferred(
                "@routine columnMapping under a parent without a catalog table is not yet supported",
                "routine-table-node-composition"));
        }

        return switch (ctx.catalog.resolveTableValuedFunction(routineName)) {
            case JooqCatalog.RoutineResolution.NotInCatalog ignored -> new Resolved.Rejected(Rejection.unknownTable(
                "@routine could not be resolved — no table-valued function named '" + routineName
                + "' in the jOOQ catalog (scalar-read and procedure-write routines are not yet supported)",
                routineName, ctx.catalog.allTableSqlNames()));
            case JooqCatalog.RoutineResolution.NotATableValuedFunction ignored -> new Resolved.Rejected(Rejection.structural(
                "@routine could not be resolved — '" + routineName
                + "' resolves to a table or view, not a table-valued function"));
            case JooqCatalog.RoutineResolution.NoConvenienceMethod nc -> new Resolved.Rejected(Rejection.structural(
                "@routine could not be resolved — " + nc.detail()));
            case JooqCatalog.RoutineResolution.Resolved fn ->
                bindArgs(fieldDef, tableBound, fn, overrides, columnOverrides, previousNodeTableSqlName);
        };
    }

    private Resolved bindArgs(GraphQLFieldDefinition fieldDef, ReturnTypeRef.TableBoundReturnType returnType,
            JooqCatalog.RoutineResolution.Resolved fn, Map<String, List<String>> overrides,
            Map<String, List<String>> columnOverrides, String previousNodeTableSqlName) {
        for (var claimed : columnOverrides.keySet()) {
            if (fn.params().stream().noneMatch(p -> p.name().equals(claimed))) {
                return new Resolved.Rejected(Rejection.structural(
                    "@routine columnMapping names parameter '" + claimed
                    + "', which is not an IN parameter of routine '" + fn.methodName() + "'"));
            }
            if (overrides.containsKey(claimed)) {
                return new Resolved.Rejected(Rejection.structural(
                    "@routine parameter '" + claimed + "' appears in both argMapping and columnMapping — "
                    + "a routine parameter has exactly one source"));
            }
        }

        Set<String> fieldArgs = FieldBuilder.fieldArgumentNames(fieldDef);
        var bindings = new ArrayList<RoutineRef.ArgBinding>();
        for (var param : fn.params()) {
            var columnOverride = columnOverrides.get(param.name());
            if (columnOverride != null) {
                if (columnOverride.size() != 1) {
                    return new Resolved.Rejected(Rejection.structural(
                        "@routine columnMapping for parameter '" + param.name()
                        + "' must bind a single column of the previous node; dot-path bindings are not supported"));
                }
                String columnName = columnOverride.get(0);
                var column = ctx.catalog.resolveColumn(previousNodeTableSqlName, columnName);
                if (column.isEmpty()) {
                    return new Resolved.Rejected(Rejection.unknownColumn(
                        "@routine columnMapping binds parameter '" + param.name() + "' to column '"
                        + columnName + "', which is not a column of the previous node ('"
                        + previousNodeTableSqlName + "')",
                        columnName, ctx.catalog.columnSqlNamesOf(previousNodeTableSqlName)));
                }
                bindings.add(new RoutineRef.ArgBinding(param.name(), param.type(),
                    new ParamSource.SourceColumn(column.get())));
                continue;
            }
            var override = overrides.get(param.name());
            String graphqlArg;
            if (override != null) {
                if (override.size() != 1) {
                    return new Resolved.Rejected(Rejection.structural(
                        "@routine argMapping for parameter '" + param.name()
                        + "' must bind a single GraphQL argument; dot-path bindings are not supported"));
                }
                graphqlArg = override.get(0);
            } else {
                graphqlArg = param.name(); // identity-bind
            }
            if (!fieldArgs.contains(graphqlArg)) {
                return new Resolved.Rejected(Rejection.structural(
                    "@routine parameter '" + param.name() + "' binds to GraphQL argument '" + graphqlArg
                    + "', which is not an argument of this field"));
            }
            bindings.add(new RoutineRef.ArgBinding(param.name(), param.type(),
                new ParamSource.Arg(new CallSiteExtraction.Direct(), new PathExpr.Head(graphqlArg))));
        }
        return new Resolved.TableBound(returnType,
            new RoutineRef(fn.routinesClass(), fn.methodName(), bindings), fn.resultTable());
    }
}
