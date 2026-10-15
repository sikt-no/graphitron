package no.sikt.graphitron.render;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.command.TenantAcquisition;
import no.sikt.graphitron.command.TenantRouting;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.TypeName;

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
     * where its {@code DSLContext dsl = ...} line goes; {@code handDown} carries the divined key
     * exactly when the declaration divined one the subtree's inherited acquisitions need, and is
     * {@link TenantHandDown#none()} otherwise.
     */
    public record Declaration(CodeBlock statement, TenantHandDown handDown) {}

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
                yield routed(coordinate, acquisition, ClassName.bestGuess(routed.tenantKeyTypeName()),
                    className(routed.connections()), contextRead);
            }
        };
    }

    /** The single-tenant declaration: the one connection the request context holds. */
    private static Declaration requestContext(RequestContextRead contextRead) {
        return new Declaration(
            CodeBlock.builder()
                .addStatement("$T dsl = $L.getDslContext(env)", DSL_CONTEXT, contextRead.call())
                .build(),
            TenantHandDown.none());
    }

    private static Declaration routed(FieldCoordinates coordinate, TenantAcquisition acquisition,
            TypeName keyType, ClassName connections, RequestContextRead contextRead) {
        return switch (acquisition) {
            case TenantAcquisition.DefaultSource ignored -> new Declaration(
                CodeBlock.builder()
                    .addStatement("$T dsl = $T.dslDefault(env)", DSL_CONTEXT, connections)
                    .build(),
                TenantHandDown.none());
            case TenantAcquisition.Inherited ignored -> new Declaration(
                CodeBlock.builder()
                    .addStatement("$T dsl = $T.dslFor(env, $T.divinedTenant(env.<Object>getLocalContext()))",
                        DSL_CONTEXT, connections, connections)
                    .build(),
                TenantHandDown.none());
            case TenantAcquisition.ArgumentBound bound -> new Declaration(
                CodeBlock.builder()
                    .add(divinedKey(coordinate, bound, keyType, connections, contextRead))
                    .addStatement("$T dsl = $T.dslFor(env, $L)",
                        DSL_CONTEXT, connections, TENANT_KEY_LOCAL)
                    .build(),
                TenantHandDown.divined(TENANT_KEY_LOCAL));
        };
    }

    /**
     * The divined-key declaration: every bound slot's read folded through the carrier's agreement
     * guard. Declared with the run's {@link TenantRouting.Routed#tenantKeyTypeName()}, because
     * generated sources never use {@code var}. Forks on the acquisition's
     * {@link TenantAcquisition.WhenNoTenant} arm: an unreachable empty request folds through
     * {@code divinedTenant}, a refused one through {@code namedTenant} and the client error
     * {@link #noTenantMessage} words; an entry point of this kind has no early return, so an
     * empty answer reaching it is a production failure.
     */
    private static CodeBlock divinedKey(FieldCoordinates coordinate, TenantAcquisition.ArgumentBound bound,
            TypeName keyType, ClassName connections, RequestContextRead contextRead) {
        String fold = switch (bound.whenNoTenant()) {
            case TenantAcquisition.WhenNoTenant.Unreachable ignored -> "divinedTenant";
            case TenantAcquisition.WhenNoTenant.Refuse ignored -> "namedTenant";
            case TenantAcquisition.WhenNoTenant.AnswerEmpty ignored -> throw new IllegalStateException(
                "Graphitron generator bug (tenancy acquisition): coordinate '" + coordinate
                + "' answers an empty request with an empty value, but its entry point declares"
                + " its connection through a plan row, which has no early return to render it");
        };
        var b = CodeBlock.builder()
            .add("$T $L = $T.$L(", keyType, TENANT_KEY_LOCAL, connections, fold);
        var slots = bound.slots();
        for (int i = 0; i < slots.size(); i++) {
            if (i > 0) {
                b.add(", ");
            }
            b.add(slotRead(slots.get(i), connections, contextRead));
        }
        b.add(");\n");
        if (bound.whenNoTenant() instanceof TenantAcquisition.WhenNoTenant.Refuse refuse) {
            b.beginControlFlow("if ($L == null)", TENANT_KEY_LOCAL)
                .addStatement("throw new $T($S)",
                    ClassName.get(connections.packageName(), CLIENT_EXCEPTION_CLASS),
                    noTenantMessage(coordinate.getTypeName() + "." + coordinate.getFieldName(),
                        refuse.arguments()))
                .endControlFlow();
        }
        return b.build();
    }

    /** The generated client-error type's simple name, beside the connections carrier. */
    private static final String CLIENT_EXCEPTION_CLASS = "GraphitronClientException";

    /**
     * The client-facing refusal for a request whose lists name no tenant:
     * {@code Mutation.rateFilms names no tenant: its tenant is read from the elements of 'in',
     * which is empty.} One home for the wording, which the field-carrier emission reads too.
     */
    public static String noTenantMessage(String coordinate, java.util.List<String> arguments) {
        var quoted = arguments.stream().map(a -> "'" + a + "'").toList();
        String names = quoted.size() == 1
            ? quoted.get(0)
            : String.join(", ", quoted.subList(0, quoted.size() - 1)) + " and " + quoted.getLast();
        return coordinate + " names no tenant: its tenant is read from the elements of " + names
            + (quoted.size() == 1 ? ", which is empty." : ", which are empty.");
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

    /**
     * The per-element hand-down a per-tenant dispatch group puts in each resolved slot:
     * {@code row}, wrapped as its own field value carrying the tenant held in
     * {@code tenantKeyLocal}, the key the group was routed on. graphql-java unwraps each element
     * before completing it, so the row's subtree inherits exactly the tenant it was read from.
     */
    public static CodeBlock handDownRow(ClassName connections, CodeBlock row, String tenantKeyLocal) {
        return CodeBlock.of("$T.$L($L, $L)", connections,
            no.sikt.graphitron.rewrite.generators.util.ConnectionRuntimeClassGenerator.HAND_DOWN_METHOD,
            row, tenantKeyLocal);
    }

    private static ClassName className(no.sikt.graphitron.command.UnitRef unit) {
        return ClassName.get(unit.packageName(), unit.simpleName());
    }
}
