package no.sikt.graphitron.render;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.command.TenantAcquisition;
import no.sikt.graphitron.command.TenantRouting;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;

/**
 * Renders one entry point's {@code DSLContext dsl = ...;} declaration, and the routed-tenant
 * rider its success return carries, off the run's {@link TenantRouting} axis. Total over the
 * axis's two arms and the acquisition's three, so a coordinate whose tenancy the plan stated has
 * exactly one declaration here and the emitters hold no tenancy fork of their own.
 *
 * <p>Every fragment is a render over a decided arm. Nothing here consults a schema, resolves a
 * binding, or searches an argument map: which slots a divined key is folded from, and how each
 * one is read, was decided by the classifier's single traversal and restated by the producer, so
 * classification and emission cannot disagree about a tenant.
 */
public final class TenantAcquisitionFragments {

    private TenantAcquisitionFragments() {}

    /** The local holding the divined tenant key, for the arm that divines one. */
    public static final String TENANT_KEY_LOCAL = "_divinedTenant";

    private static final ClassName DSL_CONTEXT = ClassName.get("org.jooq", "DSLContext");

    /**
     * One entry point's resolved declaration. {@code statement} is the full block the site pastes
     * where its {@code DSLContext dsl = ...} line goes; {@code localContextTail} is
     * {@code .localContext(...)} exactly when the declaration divined a key the subtree's
     * inherited acquisitions need, and empty otherwise.
     */
    public record Declaration(CodeBlock statement, CodeBlock localContextTail) {}

    /**
     * The declaration for {@code coordinate} under this run's axis. A single-tenant run declares
     * the request context's one connection; a multi-tenant run renders the coordinate's own
     * acquisition arm.
     *
     * <p>A coordinate the routed axis does not cover is a production failure rather than a
     * fallback: emitting the request-context read for it would route a tenant-scoped query to the
     * default connection, which is the leak the axis exists to prevent, and it would do so
     * silently.
     */
    public static Declaration declare(TenantRouting tenancy, FieldCoordinates coordinate,
            RequestContextRead contextRead) {
        return switch (tenancy) {
            case TenantRouting.Unrouted ignored -> requestContext(contextRead);
            case TenantRouting.Routed routed -> {
                var acquisition = routed.byCoordinate().get(coordinate);
                if (acquisition == null) {
                    throw new IllegalStateException(
                        "Graphitron generator bug (tenancy acquisition): coordinate '" + coordinate
                        + "' is emitted in a multi-tenant run whose routing axis does not cover it;"
                        + " the producer's coverage and this emission have drifted, and falling"
                        + " back to the request context's connection would route a tenant-scoped"
                        + " read to the default source");
                }
                yield routed(acquisition, className(routed.connections()), contextRead);
            }
        };
    }

    /** The single-tenant declaration: the one connection the request context holds. */
    private static Declaration requestContext(RequestContextRead contextRead) {
        return new Declaration(
            CodeBlock.builder()
                .addStatement("$T dsl = $L.getDslContext(env)", DSL_CONTEXT, contextRead.call())
                .build(),
            CodeBlock.of(""));
    }

    private static Declaration routed(TenantAcquisition acquisition, ClassName connections,
            RequestContextRead contextRead) {
        return switch (acquisition) {
            case TenantAcquisition.Untenanted ignored -> new Declaration(
                CodeBlock.builder()
                    .addStatement("$T dsl = $T.dslDefault(env)", DSL_CONTEXT, connections)
                    .build(),
                CodeBlock.of(""));
            case TenantAcquisition.Inherited ignored -> new Declaration(
                CodeBlock.builder()
                    .addStatement("$T dsl = $T.dslFor(env, $T.divinedTenant(env.<Object>getLocalContext()))",
                        DSL_CONTEXT, connections, connections)
                    .build(),
                CodeBlock.of(""));
            case TenantAcquisition.ArgumentBound bound -> new Declaration(
                CodeBlock.builder()
                    .add(divinedKey(bound, connections, contextRead))
                    .addStatement("$T dsl = $T.dslFor(env, $L)",
                        DSL_CONTEXT, connections, TENANT_KEY_LOCAL)
                    .build(),
                CodeBlock.of(".localContext($L)", TENANT_KEY_LOCAL));
        };
    }

    /**
     * The divined-key declaration: every bound slot's read folded through the carrier's agreement
     * guard. Declared with the bound column's own Java type, boxed where the catalog reports a
     * primitive, because generated sources never use {@code var}.
     */
    private static CodeBlock divinedKey(TenantAcquisition.ArgumentBound bound, ClassName connections,
            RequestContextRead contextRead) {
        var columnType = CatalogRefs.columnType(bound.keyColumn());
        var keyType = columnType.isPrimitive() ? columnType.box() : columnType;
        var b = CodeBlock.builder()
            .add("$T $L = $T.divinedTenant(", keyType, TENANT_KEY_LOCAL, connections);
        var slots = bound.slots();
        for (int i = 0; i < slots.size(); i++) {
            if (i > 0) {
                b.add(", ");
            }
            b.add(slotRead(slots.get(i), connections, contextRead));
        }
        return b.add(");\n").build();
    }

    /** One bound slot's runtime read, rendered from the arm the classifier resolved for it. */
    private static CodeBlock slotRead(TenantAcquisition.SlotRead read, ClassName connections,
            RequestContextRead contextRead) {
        return switch (read) {
            case TenantAcquisition.SlotRead.TopLevelArg arg ->
                CodeBlock.of("env.<Object>getArgument($S)", arg.argName());
            case TenantAcquisition.SlotRead.NestedInput nested -> {
                var walk = CodeBlock.builder()
                    .add("$T.tenantSlot(env.getArgument($S)", connections, nested.outerArgName());
                for (String key : nested.path()) {
                    walk.add(", $S", key);
                }
                yield walk.add(")").build();
            }
            case TenantAcquisition.SlotRead.ContextArg arg ->
                CodeBlock.of("$L.getContextArgument(env, $S)", contextRead.call(), arg.argName());
        };
    }

    private static ClassName className(no.sikt.graphitron.command.UnitRef unit) {
        return ClassName.get(unit.packageName(), unit.simpleName());
    }
}
