package no.sikt.graphitron.rewrite;

import graphql.schema.GraphQLFieldDefinition;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.rewrite.BuildContext.ARG_TABLE_METHOD_REF;
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
 *       expected-return-class invariant (Invariants §3) — always present at root, only
 *       present at child sites when the resolved return type is table-bound.</li>
 *   <li>Root-only invariants: non-{@code TableBound} return rejection and Connection
 *       wrapper rejection (Invariants §1).</li>
 * </ul>
 *
 * <p>Each classify arm projects {@link Resolved.TableBound} / {@link Resolved.NonTableBound}
 * into its specific variant ({@code QueryTableMethodTableField} at root,
 * {@code TableMethodField} at child sites carrying the resolved join path) and handles
 * parent-context-only concerns (the child-site join-path parse runs before this resolver, since
 * a path-parse error must surface ahead of any reflection failure).
 *
 * <p>Root vs child is signalled by {@code isRoot}: root sites pass {@code true} (Connection /
 * non-table-bound rejections fire, expected-return-class is always non-null); child sites pass
 * {@code false} (those rejections skip; expected-return-class is non-null only on table-bound
 * returns, matching the today-deferred shape of scalar/enum {@code TableMethodField}).
 *
 * <p>Implementation note: like {@link ServiceDirectiveResolver}, the helpers this resolver
 * calls back into ({@code parseExternalRef}, {@code fieldArgumentNames},
 * {@code parseContextArguments}, {@code buildWrapper}, {@code enrichArgExtractions}) are
 * package-private members of {@link FieldBuilder}; subsequent R6 phases will share them with
 * the remaining directive resolvers.
 */
final class TableMethodDirectiveResolver {

    /**
     * Outcome of {@link #resolve}. Three terminal arms; the caller exhausts them with a switch.
     *
     * <ul>
     *   <li>{@link TableBound} — successful resolution to a {@code @table}-annotated return type.
     *       Carries the typed {@link ReturnTypeRef.TableBoundReturnType} and the resolved
     *       {@link MethodRef}. Reachable from both root and child sites.</li>
     *   <li>{@link NonTableBound} — successful resolution to a non-table-bound return type
     *       (scalar / enum / record / polymorphic). Carries the resolved {@link ReturnTypeRef}
     *       and {@link MethodRef}. Reachable only from child sites; root rejects this shape
     *       inside the resolver.</li>
     *   <li>{@link Rejected} — every error path: directive-parse failure, method-reflection
     *       failure, root-only return-type / Connection rejections.</li>
     * </ul>
     */
    sealed interface Resolved {
        record TableBound(ReturnTypeRef.TableBoundReturnType returnType, MethodRef method) implements Resolved {}
        record NonTableBound(ReturnTypeRef returnType, MethodRef method) implements Resolved {}
        record Rejected(RejectionKind kind, String message) implements Resolved {}
    }

    private final BuildContext ctx;
    private final ServiceCatalog svc;
    private final FieldBuilder fb;
    private final EnumMappingResolver enumMapping;

    TableMethodDirectiveResolver(BuildContext ctx, ServiceCatalog svc, FieldBuilder fb, EnumMappingResolver enumMapping) {
        this.ctx = ctx;
        this.svc = svc;
        this.fb = fb;
        this.enumMapping = enumMapping;
    }

    /**
     * Resolves {@code @tableMethod} on {@code fieldDef}. Pass {@code isRoot=true} at root sites
     * (Query) so the non-table-bound and Connection invariants fire; pass {@code false} at child
     * sites on a {@code @table}-typed parent.
     */
    Resolved resolve(String parentTypeName, GraphQLFieldDefinition fieldDef, boolean isRoot) {
        String rawTypeName = baseTypeName(fieldDef);
        String elementTypeName = ctx.isConnectionType(rawTypeName)
            ? ctx.connectionElementTypeName(rawTypeName)
            : rawTypeName;
        ReturnTypeRef returnType = ctx.resolveReturnType(elementTypeName, fb.buildWrapper(fieldDef));

        if (isRoot) {
            if (!(returnType instanceof ReturnTypeRef.TableBoundReturnType)) {
                return new Resolved.Rejected(RejectionKind.AUTHOR_ERROR,
                    "@tableMethod requires a @table-annotated return type");
            }
            // Invariants §1: Connection wrapper not supported on @tableMethod at root.
            if (returnType.wrapper() instanceof FieldWrapper.Connection) {
                return new Resolved.Rejected(RejectionKind.INVALID_SCHEMA,
                    "@tableMethod at the root does not support Connection return types — use [T] or T instead");
            }
        }

        FieldBuilder.ExternalRef ref = fb.parseExternalRef(parentTypeName, fieldDef, DIR_TABLE_METHOD, ARG_TABLE_METHOD_REF);
        if (ref != null && ref.lookupError() != null) {
            return new Resolved.Rejected(RejectionKind.AUTHOR_ERROR,
                "table method could not be resolved — " + ref.lookupError());
        }
        if (ref != null && ref.argMappingError() != null) {
            return new Resolved.Rejected(RejectionKind.AUTHOR_ERROR,
                "table method could not be resolved — @tableMethod " + ref.argMappingError());
        }
        var argMapping = ref != null ? ref.argMapping() : Map.<String, String>of();
        var argBindingsResult = ArgBindingMap.of(FieldBuilder.fieldArgumentNames(fieldDef), argMapping);
        if (argBindingsResult instanceof ArgBindingMap.Result.UnknownArgRef u) {
            return new Resolved.Rejected(RejectionKind.AUTHOR_ERROR,
                "table method could not be resolved — @tableMethod " + u.message());
        }
        var argBindings = ((ArgBindingMap.Result.Ok) argBindingsResult).map();
        List<String> contextArgs = fb.parseContextArguments(fieldDef, DIR_TABLE_METHOD);

        // Invariants §3 (return-type strictness): the developer's @tableMethod must return the
        // generated jOOQ table class exactly, not a wider Table<R>. Always present at root (the
        // earlier instanceof check guarantees TableBoundReturnType); at child sites only when the
        // resolved return type is table-bound. Today's child arm with a non-table-bound return is
        // a deferred stub (TableMethodField with scalar/enum return) — see roadmap R43.
        ClassName expectedReturnClass = returnType instanceof ReturnTypeRef.TableBoundReturnType tbr
            ? ClassName.get(ctx.ctx().jooqPackage() + ".tables", tbr.table().javaClassName())
            : null;

        var result = svc.reflectTableMethod(
            ref != null ? ref.className() : null,
            ref != null ? ref.methodName() : null,
            argBindings, new HashSet<>(contextArgs),
            expectedReturnClass);
        if (result.failed()) {
            return new Resolved.Rejected(RejectionKind.AUTHOR_ERROR,
                "table method could not be resolved — " + result.failureReason());
        }
        MethodRef method = enumMapping.enrichArgExtractions(result.ref(), fieldDef);
        return returnType instanceof ReturnTypeRef.TableBoundReturnType tb
            ? new Resolved.TableBound(tb, method)
            : new Resolved.NonTableBound(returnType, method);
    }
}
