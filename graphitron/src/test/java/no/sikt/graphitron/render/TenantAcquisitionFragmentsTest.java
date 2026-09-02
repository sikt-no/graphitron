package no.sikt.graphitron.render;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.command.TenantAcquisition;
import no.sikt.graphitron.command.TenantRouting;
import no.sikt.graphitron.command.UnitRef;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.model.jooq.ColumnRef;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Per-arm unit tests for {@link TenantAcquisitionFragments}: a total function over the run's
 * tenancy axis and one coordinate, needing no schema, catalog or fixture. The declarations are
 * pinned as exact text rather than by fingerprint, unusually for a renderer test, because what
 * they say is which database connection a generated query runs against: a fragment that reads
 * plausibly and acquires the wrong source is the failure this axis exists to prevent, and it
 * cannot be caught by a shape assertion.
 *
 * <p>The hand-down rider is pinned beside each declaration for the same reason. It is the only
 * thing that puts a divined tenant where a descendant's inherited acquisition can read it, so a
 * declaration that divines a key and drops the rider silently sends the subtree to whatever the
 * request context resolved.
 */
@UnitTier
class TenantAcquisitionFragmentsTest {

    private static final FieldCoordinates RENT_FILM =
        FieldCoordinates.coordinates("Mutation", "rentFilm");
    private static final UnitRef CONNECTIONS =
        new UnitRef("com.example.generated.schema", "TenantConnections");

    /** The host's helper seam, standing in for the fetcher class's: records nothing, emits the call. */
    private static final RequestContextRead CONTEXT_READ = () -> CodeBlock.of("graphitronContext(env)");

    private static final ColumnRef TENANT_COLUMN =
        new ColumnRef("customer_id", "CUSTOMER_ID", "java.lang.Integer");

    private static TenantAcquisitionFragments.Declaration declare(TenantAcquisition acquisition) {
        return TenantAcquisitionFragments.declare(
            new TenantRouting.Routed(CONNECTIONS, Map.of(RENT_FILM, acquisition)),
            RENT_FILM, CONTEXT_READ);
    }

    @Test
    void anUnroutedRunDeclaresTheRequestContextsOwnConnection() {
        var declaration = TenantAcquisitionFragments.declare(
            new TenantRouting.Unrouted(), RENT_FILM, CONTEXT_READ);

        assertThat(declaration.statement().toString())
            .isEqualTo("org.jooq.DSLContext dsl = graphitronContext(env).getDslContext(env);\n");
        assertThat(declaration.localContextTail().toString())
            .as("a single-tenant run divines no key, so there is nothing to hand down")
            .isEmpty();
    }

    @Test
    void anUntenantedAcquisitionTakesTheDefaultSource() {
        var declaration = declare(new TenantAcquisition.Untenanted());

        assertThat(declaration.statement().toString())
            .isEqualTo("org.jooq.DSLContext dsl = "
                + "com.example.generated.schema.TenantConnections.dslDefault(env);\n");
        assertThat(declaration.localContextTail().toString())
            .as("global reference data divines nothing and hands nothing down")
            .isEmpty();
    }

    @Test
    void anInheritedAcquisitionReadsTheHandedDownKey() {
        var declaration = declare(new TenantAcquisition.Inherited());

        assertThat(declaration.statement().toString())
            .isEqualTo("org.jooq.DSLContext dsl = com.example.generated.schema.TenantConnections"
                + ".dslFor(env, com.example.generated.schema.TenantConnections"
                + ".divinedTenant(env.<Object>getLocalContext()));\n");
        assertThat(declaration.localContextTail().toString())
            .as("the key was divined by an ancestor and is already on the local context")
            .isEmpty();
    }

    @Test
    void anArgumentBoundAcquisitionDivinesFromItsSlotsAndHandsTheKeyDown() {
        var declaration = declare(new TenantAcquisition.ArgumentBound(
            List.of(new TenantAcquisition.SlotRead.TopLevelArg("customerId")), TENANT_COLUMN));

        assertThat(declaration.statement().toString())
            .isEqualTo("java.lang.Integer _divinedTenant = com.example.generated.schema"
                + ".TenantConnections.divinedTenant(env.<Object>getArgument(\"customerId\"));\n"
                + "org.jooq.DSLContext dsl = com.example.generated.schema.TenantConnections"
                + ".dslFor(env, _divinedTenant);\n");
        assertThat(declaration.localContextTail().toString())
            .as("the divining site is the one that hands the key to its subtree")
            .isEqualTo(".localContext(_divinedTenant)");
    }

    /**
     * The three slot reads in one declaration, which is also the co-binding shape: every slot's
     * value is read and the carrier's guard folds them, so a request naming two disagreeing
     * tenants fails at the fold rather than picking one.
     */
    @Test
    void everySlotReadArmRendersItsOwnRuntimeRead() {
        var declaration = declare(new TenantAcquisition.ArgumentBound(
            List.of(
                new TenantAcquisition.SlotRead.TopLevelArg("customerId"),
                new TenantAcquisition.SlotRead.NestedInput("input", List.of("owner", "customerId")),
                new TenantAcquisition.SlotRead.ContextArg("tenant")),
            TENANT_COLUMN));

        assertThat(declaration.statement().toString())
            .isEqualTo("java.lang.Integer _divinedTenant = com.example.generated.schema"
                + ".TenantConnections.divinedTenant(env.<Object>getArgument(\"customerId\"), "
                + "com.example.generated.schema.TenantConnections.tenantSlot("
                + "env.getArgument(\"input\"), \"owner\", \"customerId\"), "
                + "graphitronContext(env).getContextArgument(env, \"tenant\"));\n"
                + "org.jooq.DSLContext dsl = com.example.generated.schema.TenantConnections"
                + ".dslFor(env, _divinedTenant);\n");
    }

    /** Generated sources never use {@code var}, so a primitive tenant column declares boxed. */
    @Test
    void aPrimitiveTenantColumnDeclaresTheKeyLocalBoxed() {
        var declaration = declare(new TenantAcquisition.ArgumentBound(
            List.of(new TenantAcquisition.SlotRead.TopLevelArg("customerId")),
            new ColumnRef("customer_id", "CUSTOMER_ID", "int")));

        assertThat(declaration.statement().toString()).startsWith("java.lang.Integer _divinedTenant");
    }

    /**
     * The refusal that matters most: a coordinate the routed axis does not cover must not fall
     * back to the request context's connection. That fallback compiles, runs, and reads another
     * tenant's rows.
     */
    @Test
    void aCoordinateTheRoutedAxisDoesNotCoverIsRefusedRatherThanDefaulted() {
        var routing = new TenantRouting.Routed(CONNECTIONS, Map.of());

        assertThatThrownBy(() ->
                TenantAcquisitionFragments.declare(routing, RENT_FILM, CONTEXT_READ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Mutation.rentFilm")
            .hasMessageContaining("default source");
    }
}
