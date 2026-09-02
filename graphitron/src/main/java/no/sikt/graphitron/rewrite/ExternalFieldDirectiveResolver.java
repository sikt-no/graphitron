package no.sikt.graphitron.rewrite;

import graphql.schema.GraphQLFieldDefinition;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.model.diagnostics.Rejection;
import no.sikt.graphitron.rewrite.model.ReturnTypeRef;
import no.sikt.graphitron.model.jooq.TableRef;

import static no.sikt.graphitron.rewrite.BuildContext.ARG_EXTERNAL_FIELD_REF;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_EXTERNAL_FIELD;
import static no.sikt.graphitron.rewrite.BuildContext.baseTypeName;
import no.sikt.graphitron.model.diagnostics.RejectionKind;

/**
 * Resolves {@code @externalField} on a child field of a {@code @table}-typed parent into a sealed
 * {@link Resolved} that the classify site ({@code FieldBuilder.classifyChildFieldOnTableType})
 * switches on, projecting {@link Resolved.Success} into
 * {@link no.sikt.graphitron.rewrite.model.ChildField.ComputedField}. Reflection goes through
 * {@link ServiceCatalog#reflectExternalField}.
 *
 * <p>Join-path parsing stays at the classify site, ahead of this resolver: the path uses the
 * parent table's name as the join start, and a path error must surface ahead of any reflection
 * failure.
 */
final class ExternalFieldDirectiveResolver {

    /**
     * Outcome of {@link #resolve}; the caller exhausts the two arms with a switch.
     * {@link Rejected} carries every error path.
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
     * table and record class names gate the {@link ServiceCatalog#reflectExternalField}
     * parent-table-class invariant. The whole ref is passed rather than a projection of it, so
     * that check stays a value comparison and no live jOOQ handle crosses into this resolver.
     */
    Resolved resolve(GraphQLFieldDefinition fieldDef, TableRef parentTable) {
        String name = fieldDef.getName();

        // Alias-collision check: the wiring side looks up the field by name via
        // DSL.field("<name>") against the result Record. If the GraphQL field name collides
        // with a real SQL column on the parent @table, the alias shadows it and the aliased
        // read resolves to the wrong value.
        if (ctx.catalog.findColumn(parentTable.tableName(), name).isPresent()) {
            return new Resolved.Rejected(Rejection.structural("@externalField name '" + name + "' collides with column '" + name
                    + "' on table '" + parentTable.tableName()
                    + "'; rename the GraphQL field or use @field(name: ...) to disambiguate"));
        }

        FieldBuilder.ExternalRef extRef = fb.parseExternalRef(fieldDef, DIR_EXTERNAL_FIELD, ARG_EXTERNAL_FIELD_REF);
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
        var extResult = svc.reflectExternalField(extClassName, resolvedMethodName, parentTable);
        if (extResult.failed()) {
            return new Resolved.Rejected(extResult.rejection().prefixedWith("external field reference could not be resolved — "));
        }

        ReturnTypeRef returnType = ctx.resolveReturnType(baseTypeName(fieldDef), fb.buildWrapper(fieldDef));
        return new Resolved.Success(returnType, extResult.ref());
    }
}
