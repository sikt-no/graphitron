package no.sikt.graphitron.rewrite;

import graphql.schema.GraphQLFieldDefinition;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.RoutineRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static no.sikt.graphitron.rewrite.BuildContext.ARG_ARG_MAPPING;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_ROUTINE;
import static no.sikt.graphitron.rewrite.BuildContext.baseTypeName;

/**
 * Resolves {@code @routine} on a field into a sealed {@link Resolved} the caller switches on, the
 * database-routine sibling of {@link TableMethodDirectiveResolver}. Day-one resolves the
 * table-valued read function (R300):
 *
 * <ul>
 *   <li>Shape invariant: the return type must be {@code @table}-annotated (a
 *       {@link ReturnTypeRef.TableBoundReturnType}); at root the Connection-wrapper rejection fires.</li>
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
     * Outcome of {@link #resolve}. {@link TableBound} on success (the only success shape, since a
     * read routine is {@code @table}-bound by construction); {@link Rejected} for every error path.
     */
    sealed interface Resolved {
        record TableBound(ReturnTypeRef.TableBoundReturnType returnType, RoutineRef routine) implements Resolved {}
        record Rejected(Rejection rejection) implements Resolved {}
    }

    private final BuildContext ctx;
    private final FieldBuilder fb;

    RoutineDirectiveResolver(BuildContext ctx, FieldBuilder fb) {
        this.ctx = ctx;
        this.fb = fb;
    }

    /**
     * Resolves {@code @routine} on {@code fieldDef}. Pass {@code isRoot=true} at root sites so the
     * Connection invariant fires.
     */
    Resolved resolve(String parentTypeName, GraphQLFieldDefinition fieldDef, boolean isRoot) {
        String rawTypeName = baseTypeName(fieldDef);
        String elementTypeName = ctx.isConnectionType(rawTypeName)
            ? ctx.connectionElementTypeName(rawTypeName)
            : rawTypeName;
        ReturnTypeRef returnType = ctx.resolveReturnType(elementTypeName, fb.buildWrapper(fieldDef));

        if (!(returnType instanceof ReturnTypeRef.TableBoundReturnType tableBound)) {
            return new Resolved.Rejected(Rejection.structural("@routine requires a @table-annotated return type"));
        }
        if (isRoot && returnType.wrapper() instanceof FieldWrapper.Connection) {
            return new Resolved.Rejected(Rejection.invalidSchema(
                "@routine at the root does not support Connection return types — use [T] or T instead"));
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
            case JooqCatalog.RoutineResolution.Resolved fn -> bindArgs(fieldDef, tableBound, fn, overrides);
        };
    }

    private Resolved bindArgs(GraphQLFieldDefinition fieldDef, ReturnTypeRef.TableBoundReturnType returnType,
            JooqCatalog.RoutineResolution.Resolved fn, Map<String, List<String>> overrides) {
        // The @table-bound element type must be the routine-result table, so projection ($fields)
        // and the routine call agree on columns.
        if (!returnType.table().tableClass().equals(fn.resultTable().tableClass())) {
            return new Resolved.Rejected(Rejection.structural(
                "@routine could not be resolved — the field's @table type ('" + returnType.table().tableName()
                + "') does not match the routine's result table ('" + fn.resultTable().tableName() + "')"));
        }

        Set<String> fieldArgs = FieldBuilder.fieldArgumentNames(fieldDef);
        var bindings = new ArrayList<RoutineRef.ArgBinding>();
        for (var param : fn.params()) {
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
            bindings.add(new RoutineRef.ArgBinding(param.name(), param.type(), graphqlArg));
        }
        return new Resolved.TableBound(returnType, new RoutineRef(fn.routinesClass(), fn.methodName(), bindings));
    }
}
