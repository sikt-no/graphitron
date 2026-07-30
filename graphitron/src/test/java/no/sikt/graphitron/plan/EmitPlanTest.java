package no.sikt.graphitron.plan;

import no.sikt.graphitron.command.GlobalCommand;
import no.sikt.graphitron.command.GlobalUnitKind;
import no.sikt.graphitron.command.UnitRef;
import no.sikt.graphitron.rewrite.GraphitronSchemaBuilder;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.session.SessionStateConfig;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the produced global command relation as data, without javapoet: which kinds appear over a
 * given fixture (non-vacuity), which kinds the membership predicates exclude (boundary), that the
 * relation is keyed (each kind at most once, enforced structurally by {@link EmitPlan}'s
 * constructor and re-asserted here over real fixtures), and that every committed unit is named by
 * the plan's own naming vocabulary.
 */
@PipelineTier
class EmitPlanTest {

    private static final String PLAIN_SDL = "type Query { x: String }";

    /** The kinds present for every schema, regardless of shape or configuration. */
    private static final EnumSet<GlobalUnitKind> UNCONDITIONAL = EnumSet.complementOf(EnumSet.of(
        GlobalUnitKind.ONE_OF_DIRECTIVE_SDL,
        GlobalUnitKind.ENTITY_FETCHER_DISPATCH,
        GlobalUnitKind.QUERY_NODE_FETCHER,
        GlobalUnitKind.DEV_EXECUTOR));

    @Test
    void plainSchema_producesTheUnconditionalKindsPlusDevExecutor() {
        var plan = producePlain(SessionStateConfig.none());

        var expected = EnumSet.copyOf(UNCONDITIONAL);
        expected.add(GlobalUnitKind.DEV_EXECUTOR);
        assertThat(kinds(plan))
            .as("every unconditional kind exactly once; the gated kinds' excluded shapes appear zero times")
            .isEqualTo(expected);
    }

    @Test
    void everyCommittedUnitIsAnchoredAtTheOutputPackage() {
        var plan = producePlain(SessionStateConfig.none());
        assertThat(plan.globals())
            .allSatisfy(command -> assertThat(command.units())
                .allSatisfy(unit -> assertThat(unit.packageName())
                    .as("unit %s of %s", unit.simpleName(), command.kind())
                    .startsWith(DEFAULT_OUTPUT_PACKAGE)));
    }

    @Test
    void connectionRuntime_commitsTheFixedFourWithoutSessionState() {
        var plan = producePlain(SessionStateConfig.none());
        assertThat(unitNames(plan, GlobalUnitKind.CONNECTION_RUNTIME))
            .containsExactly("SessionHook", "PinnedConnection", "GraphitronRuntime", "TenantConnections");
    }

    @Test
    void connectionRuntime_commitsTheHookImplementationWhenSessionStateIsConfigured() {
        var variables = new SessionStateConfig.Variables(
            List.of(new SessionStateConfig.Variable("app.user", "sub")));
        var plan = producePlain(variables);
        assertThat(unitNames(plan, GlobalUnitKind.CONNECTION_RUNTIME))
            .containsExactly("SessionHook", "PinnedConnection", "GraphitronRuntime", "TenantConnections",
                "GraphitronSessionHook");
    }

    @Test
    void oneOfRow_requiresBothFederationAndOneOfUse() {
        var model = TestSchemaHelper.buildBundle(PLAIN_SDL).model();

        var both = EmitPlan.produce(model, true, true, SessionStateConfig.none(), DEFAULT_OUTPUT_PACKAGE);
        assertThat(kinds(both)).contains(GlobalUnitKind.ONE_OF_DIRECTIVE_SDL);
        assertThat(unitNames(both, GlobalUnitKind.ONE_OF_DIRECTIVE_SDL)).containsExactly("OneOfDirectiveSdl");

        var federationOnly = EmitPlan.produce(model, true, false, SessionStateConfig.none(), DEFAULT_OUTPUT_PACKAGE);
        assertThat(kinds(federationOnly)).doesNotContain(GlobalUnitKind.ONE_OF_DIRECTIVE_SDL);

        // The non-federation printer already prints the definition, so @oneOf alone commits nothing.
        var oneOfOnly = EmitPlan.produce(model, false, true, SessionStateConfig.none(), DEFAULT_OUTPUT_PACKAGE);
        assertThat(kinds(oneOfOnly)).doesNotContain(GlobalUnitKind.ONE_OF_DIRECTIVE_SDL);
    }

    @Test
    void devExecutorRow_isAbsentUnderFederation() {
        var model = TestSchemaHelper.buildBundle(PLAIN_SDL).model();
        var federated = EmitPlan.produce(model, true, false, SessionStateConfig.none(), DEFAULT_OUTPUT_PACKAGE);
        assertThat(kinds(federated)).doesNotContain(GlobalUnitKind.DEV_EXECUTOR);
    }

    @Test
    void queryNodeFetcherRow_appearsExactlyWhenANodeTypeIsClassified() {
        var withNode = TestSchemaHelper.buildBundle("""
            type Film implements Node @table(name: "film") @node(keyColumns: ["film_id"]) {
                id: ID!
            }
            type Query { film: Film }
            """);
        var plan = EmitPlan.produce(withNode.model(), withNode.federationLink(), withNode.usesOneOf(),
            SessionStateConfig.none(), DEFAULT_OUTPUT_PACKAGE);
        assertThat(kinds(plan)).contains(GlobalUnitKind.QUERY_NODE_FETCHER);
        assertThat(unitNames(plan, GlobalUnitKind.QUERY_NODE_FETCHER)).containsExactly("QueryNodeFetcher");

        assertThat(kinds(producePlain(SessionStateConfig.none())))
            .doesNotContain(GlobalUnitKind.QUERY_NODE_FETCHER);
    }

    /**
     * The dispatch row is the sealed relation's one data-carrying arm: its schema-dependent
     * outbound refs (the per-type projection classes the emitted dispatch references) ride the
     * row, node types included through the {@code @node}-to-{@code @key} synthesis. A plain
     * schema gets no row at all (pinned by {@link #plainSchema_producesTheUnconditionalKindsPlusDevExecutor}),
     * so the arm's non-empty guard never meets a live empty set.
     */
    @Test
    void entityDispatchRow_carriesItsDispatchTargets() {
        var withNode = TestSchemaHelper.buildBundle("""
            type Film implements Node @table(name: "film") @node(keyColumns: ["film_id"]) {
                id: ID!
            }
            type Query { film: Film }
            """);
        var plan = EmitPlan.produce(withNode.model(), withNode.federationLink(), withNode.usesOneOf(),
            SessionStateConfig.none(), DEFAULT_OUTPUT_PACKAGE);

        var dispatch = plan.globals().stream()
            .filter(command -> command.kind() == GlobalUnitKind.ENTITY_FETCHER_DISPATCH)
            .findFirst().orElseThrow();
        assertThat(dispatch).isInstanceOf(GlobalCommand.EntityDispatch.class);
        assertThat(((GlobalCommand.EntityDispatch) dispatch).dispatchTargets())
            .extracting(UnitRef::fqcn)
            .containsExactly(DEFAULT_OUTPUT_PACKAGE + ".types.Film");
    }

    @Test
    void bundleLandsTheOneOfFactOnce() {
        assertThat(TestSchemaHelper.buildBundle(PLAIN_SDL).usesOneOf()).isFalse();
        assertThat(TestSchemaHelper.buildBundle("""
            type Query { x(filter: Filter): String }
            input Filter @oneOf { byId: ID byName: String }
            """).usesOneOf()).isTrue();
    }

    private static EmitPlan producePlain(SessionStateConfig sessionState) {
        GraphitronSchemaBuilder.Bundle bundle = TestSchemaHelper.buildBundle(PLAIN_SDL);
        return EmitPlan.produce(bundle.model(), bundle.federationLink(), bundle.usesOneOf(),
            sessionState, DEFAULT_OUTPUT_PACKAGE);
    }

    private static Set<GlobalUnitKind> kinds(EmitPlan plan) {
        Map<GlobalUnitKind, GlobalCommand> byKind = plan.globals().stream()
            .collect(Collectors.toMap(GlobalCommand::kind, Function.identity()));
        return byKind.keySet();
    }

    private static List<String> unitNames(EmitPlan plan, GlobalUnitKind kind) {
        return plan.globals().stream()
            .filter(command -> command.kind() == kind)
            .flatMap(command -> command.units().stream())
            .map(UnitRef::simpleName)
            .toList();
    }
}
