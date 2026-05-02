package no.sikt.graphitron.rewrite;

import graphql.schema.GraphQLFieldDefinition;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.Rejection;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.rewrite.model.TableRef;

import static no.sikt.graphitron.rewrite.BuildContext.ARG_EXTERNAL_FIELD_REF;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_EXTERNAL_FIELD;
import static no.sikt.graphitron.rewrite.BuildContext.baseTypeName;

/**
 * Resolves {@code @externalField} on a child field of a {@code @table}-typed parent into a sealed
 * {@link Resolved} the caller switches on, absorbing the cross-cutting checks previously inlined
 * at the sole classify site ({@code classifyChildFieldOnTableType}):
 *
 * <ul>
 *   <li>Alias-collision rejection: the GraphQL field name must not collide with a real SQL column
 *       on the parent table (the wiring side looks the field up by name via {@code DSL.field("…")}
 *       against the result Record, so a collision shadows the real column).</li>
 *   <li>External-reference parse, missing-className rejection, argMapping parse.</li>
 *   <li>{@link ServiceCatalog#reflectExternalField} reflection against the parent table's Java
 *       class, with method-name defaulting to the GraphQL field name when {@code method:} is
 *       omitted from the directive reference.</li>
 * </ul>
 *
 * <p>The classify arm projects {@link Resolved.Success} into {@code ComputedField}, carrying the
 * parsed join path it parses ahead of this resolver (path-parse is a parent-context concern: it
 * uses the parent table's name as the join start, and a path error must surface ahead of any
 * reflection failure).
 *
 * <p>Implementation note: like {@link ServiceDirectiveResolver} and
 * {@link TableMethodDirectiveResolver}, the helpers this resolver calls back into
 * ({@code parseExternalRef}, {@code buildWrapper}) are package-private members of
 * {@link FieldBuilder}. With three consumers now sharing them, the still-shared helpers
 * ({@code parseExternalRef}, {@code fieldArgumentNames}) become candidates to migrate to a common
 * location in a later R6 mop-up phase.
 */
final class ExternalFieldDirectiveResolver {

    /**
     * Outcome of {@link #resolve}. Two terminal arms; the caller exhausts them with a switch.
     *
     * <ul>
     *   <li>{@link Success} — successful resolution, carrying the resolved {@link ReturnTypeRef}
     *       (computed from the field's GraphQL type + wrapper) and the reflected
     *       {@link MethodRef}.</li>
     *   <li>{@link Rejected} — every error path: alias-collision, directive-parse failure,
     *       missing-className, method-reflection failure.</li>
     * </ul>
     */
    sealed interface Resolved {
        record Success(ReturnTypeRef returnType, MethodRef method) implements Resolved {}
        record Rejected(Rejection rejection) implements Resolved {
            public String message() { return rejection.message(); }
            public RejectionKind kind() { return RejectionKind.of(rejection); }
        }
    }

    private final BuildContext ctx;
    private final ServiceCatalog svc;
    private final FieldBuilder fb;

    ExternalFieldDirectiveResolver(BuildContext ctx, ServiceCatalog svc, FieldBuilder fb) {
        this.ctx = ctx;
        this.svc = svc;
        this.fb = fb;
    }

    /**
     * Resolves {@code @externalField} on {@code fieldDef}. {@code parentTable} is the parent
     * type's resolved {@link TableRef}: its SQL name gates the alias-collision check, and its
     * Java class name gates the {@code reflectExternalField} parent-table-class invariant.
     */
    Resolved resolve(String parentTypeName, GraphQLFieldDefinition fieldDef, TableRef parentTable) {
        String name = fieldDef.getName();

        // Alias-collision check: the wiring side looks up the field by name via
        // DSL.field("<name>") against the result Record. If the GraphQL field name collides
        // with a real SQL column on the parent @table, the alias shadows it and ColumnFetcher
        // resolves to the wrong value.
        if (ctx.catalog.findColumn(parentTable.tableName(), name).isPresent()) {
            return new Resolved.Rejected(Rejection.structural("@externalField name '" + name + "' collides with column '" + name
                    + "' on table '" + parentTable.tableName()
                    + "'; rename the GraphQL field or use @field(name: ...) to disambiguate"));
        }

        FieldBuilder.ExternalRef extRef = fb.parseExternalRef(parentTypeName, fieldDef, DIR_EXTERNAL_FIELD, ARG_EXTERNAL_FIELD_REF);
        if (extRef != null && extRef.lookupError() != null) {
            return new Resolved.Rejected(Rejection.structural("external field reference could not be resolved — " + extRef.lookupError()));
        }
        if (extRef != null && extRef.argMappingError() != null) {
            return new Resolved.Rejected(Rejection.structural(extRef.argMappingError()));
        }
        // `className` is the only required schema-level input; surface a targeted error here
        // so reflectExternalField below can require non-null className/methodName.
        String extClassName = extRef != null ? extRef.className() : null;
        if (extClassName == null) {
            return new Resolved.Rejected(Rejection.structural("external field reference could not be resolved — missing className"));
        }
        // When `method` is omitted from the @externalField reference, default to the GraphQL
        // field name. The static-method-name-equals-field-name convention is the common case;
        // requiring `method:` only when it diverges removes ceremony from the schema.
        String resolvedMethodName = extRef.methodName() != null ? extRef.methodName() : name;
        ClassName parentTableClass = ClassName.get(
            ctx.ctx().jooqPackage() + ".tables", parentTable.javaClassName());
        var extResult = svc.reflectExternalField(extClassName, resolvedMethodName, parentTableClass);
        if (extResult.failed()) {
            return new Resolved.Rejected(Rejection.structural("external field reference could not be resolved — " + extResult.failureReason()));
        }

        ReturnTypeRef returnType = ctx.resolveReturnType(baseTypeName(fieldDef), fb.buildWrapper(fieldDef));
        return new Resolved.Success(returnType, extResult.ref());
    }
}
