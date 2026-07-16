package no.sikt.graphitron.rewrite;

import graphql.schema.GraphQLFieldDefinition;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static no.sikt.graphitron.rewrite.BuildContext.ARG_ARG_MAPPING;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_CLASS_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.ARG_METHOD;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_TABLE_METHOD;
import static no.sikt.graphitron.rewrite.BuildContext.baseTypeName;

/**
 * Resolves {@code @tableMethod} on a field into a sealed {@link Resolved} the caller switches on,
 * absorbing the duplication previously inlined at the two classify sites
 * ({@code classifyQueryField}, {@code classifyChildFieldOnTableType}):
 *
 * <ul>
 *   <li>External-reference parse, argMapping parse, arg-bindings validation.</li>
 *   <li>{@link ServiceCatalog#reflectTableMethod} reflection with the strict
 *       expected-return-class invariant (Invariants §3).</li>
 *   <li>Shape invariants: the return type must be {@code @table}-annotated (a
 *       {@link ReturnTypeRef.TableBoundReturnType}); at root the additional Connection-wrapper
 *       rejection fires (Invariants §1).</li>
 * </ul>
 *
 * <p>Each classify arm projects {@link Resolved.TableBound} into its specific variant
 * ({@code QueryTableMethodTableField} at root, {@code TableMethodField} at child sites carrying
 * the resolved join path) and handles parent-context-only concerns (the child-site join-path
 * parse runs before this resolver, since a path-parse error must surface ahead of any reflection
 * failure).
 *
 * <p>Root vs child is signalled by {@code isRoot}: root sites pass {@code true} so the
 * Connection-wrapper rejection fires. The non-table-bound return rejection fires at both sites
 * since {@code @tableMethod} returning a scalar / enum is never a valid shape: the
 * directive exists precisely to bind a developer-authored jOOQ table method, which by
 * construction returns a generated jOOQ table class.
 *
 * <p>Implementation note: like {@link ServiceDirectiveResolver}, the helpers this resolver
 * calls back into ({@code parseExternalRef}, {@code fieldArgumentNames},
 * {@code parseContextArguments}, {@code buildWrapper}) are package-private members of
 * {@link FieldBuilder}, shared with the other directive resolvers.
 */
final class TableMethodDirectiveResolver {

    /**
     * Outcome of {@link #resolve}. Two terminal arms; the caller exhausts them with a switch.
     *
     * <ul>
     *   <li>{@link TableBound} — successful resolution to a {@code @table}-annotated return type.
     *       Carries the typed {@link ReturnTypeRef.TableBoundReturnType} and the resolved
     *       {@link MethodRef}. The only success shape: {@code @tableMethod} returning a
 * non-table type is rejected here.</li>
     *   <li>{@link Rejected} — every error path: directive-parse failure, method-reflection
     *       failure, return-type rejection (non-table-bound or Connection at root).</li>
     * </ul>
     */
    sealed interface Resolved {
        record TableBound(ReturnTypeRef.TableBoundReturnType returnType, MethodRef method) implements Resolved {}
        record Rejected(Rejection rejection) implements Resolved {
            public String message() { return rejection.message(); }
            public RejectionKind kind() { return RejectionKind.of(rejection); }
        }
    }

    private final BuildContext ctx;
    private final ServiceCatalog svc;
    private final FieldBuilder fb;

    TableMethodDirectiveResolver(BuildContext ctx, ServiceCatalog svc, FieldBuilder fb) {
        this.ctx = ctx;
        this.svc = svc;
        this.fb = fb;
    }

    /**
     * Resolves {@code @tableMethod} on {@code fieldDef}. Pass {@code isRoot=true} at root sites
     * (Query) so the Connection invariant fires; pass {@code false} at child sites on a
     * {@code @table}-typed parent. The non-table-bound return rejection fires at both sites:
     * {@code @tableMethod} is, by construction, a binding to a developer-authored jOOQ table
     * method, and those return generated jOOQ table classes. A schema declaring a scalar / enum
 * return on {@code @tableMethod} is malformed.
     */
    Resolved resolve(String parentTypeName, GraphQLFieldDefinition fieldDef, boolean isRoot) {
        String rawTypeName = baseTypeName(fieldDef);
        String elementTypeName = ctx.isConnectionType(rawTypeName)
            ? ctx.connectionElementTypeName(rawTypeName)
            : rawTypeName;
        ReturnTypeRef returnType = ctx.resolveReturnType(elementTypeName, fb.buildWrapper(fieldDef));

        if (!(returnType instanceof ReturnTypeRef.TableBoundReturnType tableBoundReturnType)) {
            return new Resolved.Rejected(Rejection.structural("@tableMethod requires a @table-annotated return type"));
        }
        if (isRoot && returnType.wrapper() instanceof FieldWrapper.Connection) {
            // Invariants §1: Connection wrapper not supported on @tableMethod at root.
            return new Resolved.Rejected(Rejection.invalidSchema("@tableMethod at the root does not support Connection return types — use [T] or T instead"));
        }

        var dir = fieldDef.getAppliedDirective(DIR_TABLE_METHOD);
        if (dir == null) {
            // Caller pre-checked hasAppliedDirective; reaching here is a classifier bug.
            throw new IllegalStateException(
                "TableMethodDirectiveResolver invoked on field without @tableMethod: "
                + parentTypeName + "." + fieldDef.getName());
        }
        String className = Optional.ofNullable(dir.getArgument(ARG_CLASS_NAME))
            .map(a -> a.getValue()).map(Object::toString).orElse(null);
        String methodName = Optional.ofNullable(dir.getArgument(ARG_METHOD))
            .map(a -> a.getValue()).map(Object::toString).orElse(null);
        String rawArgMapping = Optional.ofNullable(dir.getArgument(ARG_ARG_MAPPING))
            .map(a -> a.getValue()).map(Object::toString).orElse(null);

        var parsedMapping = ArgBindingMap.parseArgMapping(rawArgMapping);
        if (parsedMapping instanceof ArgBindingMap.ParsedArgMapping.ParseError pe) {
            return new Resolved.Rejected(Rejection.structural("table method could not be resolved — @tableMethod " + pe.message()));
        }
        Map<String, List<String>> argMapping = ((ArgBindingMap.ParsedArgMapping.Ok) parsedMapping).overrides();
        var argBindingsResult = ArgBindingMap.of(FieldBuilder.argSlotTypes(fieldDef), argMapping);
        if (argBindingsResult instanceof ArgBindingMap.Result.UnknownArgRef u) {
            return new Resolved.Rejected(Rejection.structural("table method could not be resolved — @tableMethod " + u.message()));
        }
        if (argBindingsResult instanceof ArgBindingMap.Result.PathRejected p) {
            return new Resolved.Rejected(Rejection.structural("table method could not be resolved — @tableMethod " + p.message()));
        }
        var argBindings = ((ArgBindingMap.Result.Ok) argBindingsResult).map();
        List<String> contextArgs = fb.parseContextArguments(fieldDef, DIR_TABLE_METHOD);

        // Invariants §3 (return-type strictness): the developer's @tableMethod must return the
        // generated jOOQ table class exactly, not a wider Table<R>. The instanceof gate above
        // guarantees a TableBoundReturnType at both root and child sites.
        ClassName expectedReturnClass = tableBoundReturnType.table().tableClass();

        var result = svc.reflectTableMethod(
            className, methodName,
            argBindings, new HashSet<>(contextArgs),
            expectedReturnClass,
            ServiceCatalog.TableSlotPolicy.FORBIDDEN,
            FieldBuilder.argSlotTypes(fieldDef));
        if (result.failed()) {
            return new Resolved.Rejected(result.rejection().prefixedWith("table method could not be resolved — "));
        }
        return new Resolved.TableBound(tableBoundReturnType, (MethodRef.StaticOnly) result.ref());
    }
}
