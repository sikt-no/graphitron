package no.sikt.graphitron.plan;

import no.sikt.graphitron.command.CallWrap;
import no.sikt.graphitron.command.Contribution;
import no.sikt.graphitron.command.ProjectionCommand;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.model.BatchKeyField;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.GraphitronType;
import no.sikt.graphitron.rewrite.model.ParentRowDemand;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The projection relation's membership enforcer: over a composite fixture exercising every
 * unit-minting shape, the relation's unit set must equal an independently derived covered set
 * (anchors from the type filter, nested units from a nesting-reach walk, pivot units from the
 * pivot-bearing coordinates), each exactly once. "Exactly one per key" is what replaces a
 * bidirectional oracle under a keyed relation; the census half binds the producer's declared
 * minting membership ({@link ProjectionCommands#CONTRIBUTION_MINTING_LEAVES}) to observed
 * minting in both directions, so the declaration cannot drift from the dispatch it sits beside.
 */
@PipelineTier
class ProjectionMembershipTest {

    // Every unit-minting shape in one fixture: two anchors sharing a nesting type (per-anchor
    // nested units), a second-level nesting type, an inline and a batched pivot coordinate, and
    // the correlation-key leaves reachable without new types (a split child, a split lookup
    // child, both @service child shapes).
    private static final String SDL = """
        type Language @table(name: "language") {
            name: String
            films: [Film!]! @service(
                service: {className: "no.sikt.graphitron.rewrite.generators.TestFilmService", method: "getFilmsMapped"})
            rank: Int @service(
                service: {className: "no.sikt.graphitron.rewrite.generators.TestFilmService", method: "getRankMapped"})
        }
        type TranslatedTexts { nn: String nb: String }
        type FilmDetails {
            note: String @field(name: "title")
            more: FilmMore
        }
        type FilmMore { extra: String @field(name: "description") }
        type Actor @table(name: "actor") { actorId: Int @field(name: "actor_id") }
        type Film @table(name: "film") {
            title: String
            details: FilmDetails
            titleTexts: TranslatedTexts @reference(path: [{table: "film_translation"}])
                                        @pivot(on: "lang_code", value: "title_txt")
            titleTextsSplit: TranslatedTexts @splitQuery @reference(path: [{table: "film_translation"}])
                                        @pivot(on: "lang_code", value: "title_txt")
            language: Language @reference(path: [{key: "film_language_id_fkey"}])
            languageName: String @field(name: "name") @reference(path: [{key: "film_language_id_fkey"}])
            actors(actor_id: [Int!] @lookupKey): [Actor!]! @reference(path: [
                {key: "film_actor_film_id_fkey"},
                {key: "film_actor_actor_id_fkey"}
            ])
            actorsSplit(actor_id: [Int!] @lookupKey): [Actor!]! @splitQuery @reference(path: [
                {key: "film_actor_film_id_fkey"},
                {key: "film_actor_actor_id_fkey"}
            ])
            languages: [Language!]! @splitQuery @reference(path: [{key: "film_language_id_fkey"}])
        }
        type FilmList @table(name: "film_list") {
            details: FilmDetails
        }
        type Query { films: [Film!]! filmLists: [FilmList!]! }
        """;

    private static ProjectionRelation produce(GraphitronSchema schema) {
        var conditions = ConditionCommands.produce(schema, DEFAULT_OUTPUT_PACKAGE);
        return ProjectionCommands.produce(schema, conditions, DEFAULT_OUTPUT_PACKAGE);
    }

    @Test
    void relationUnitSetEqualsTheIndependentlyDerivedCoveredSet() {
        var schema = TestSchemaHelper.buildSchema(SDL);
        var relation = produce(schema);

        var covered = new LinkedHashSet<String>();
        for (var e : schema.types().entrySet()) {
            if (e.getValue() instanceof GraphitronType.TableType
                    || e.getValue() instanceof GraphitronType.NodeType) {
                covered.add(e.getKey());
                walkCovered(schema.fieldsOf(e.getKey()), e.getKey(), covered);
            }
        }

        assertThat(relation.units())
            .extracting(u -> u.simpleName())
            .containsExactlyInAnyOrderElementsOf(covered);
        // The composite fixture's pinned key set, so a silent membership regression cannot hide
        // behind a fixture that stopped exercising a shape.
        assertThat(covered).containsExactlyInAnyOrder(
            "Film", "FilmList", "Language", "Actor",
            "FilmFilmDetails", "FilmFilmMore",
            "FilmListFilmDetails", "FilmListFilmMore",
            "FilmTitleTexts", "FilmTitleTextsSplit");
    }

    private static void walkCovered(List<? extends GraphitronField> fields, String anchorTypeName,
            Set<String> covered) {
        for (var f : fields) {
            switch (f) {
                case ChildField.NestingField nf -> {
                    covered.add(anchorTypeName + nf.returnType().returnTypeName());
                    walkCovered(nf.nestedFields(), anchorTypeName, covered);
                }
                case ChildField.PivotField pf ->
                    covered.add(pf.parentTypeName() + upperCamel(pf.name()));
                case ChildField.BatchedPivotField bpf ->
                    covered.add(bpf.parentTypeName() + upperCamel(bpf.name()));
                default -> { }
            }
        }
    }

    private static String upperCamel(String name) {
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    @Test
    void censusMatchesObservedMintingInBothDirections() {
        var schema = TestSchemaHelper.buildSchema(SDL);
        var relation = produce(schema);

        // Observed: which leaf kinds landed projection output. Contributions map back to the
        // classified field at the coordinate; pivot units observe their two minters and their
        // ridden slots.
        var observed = new LinkedHashSet<Class<? extends GraphitronField>>();
        for (var row : relation.rows()) {
            switch (row) {
                case ProjectionCommand.AnchorUnit a ->
                    observeContributions(schema, a.unit().simpleName(), a.contributions(), observed);
                case ProjectionCommand.NestedUnit n -> {
                    // Nested contributions observe through the nesting walk below; the unit's
                    // existence itself observes NestingField.
                    observed.add(ChildField.NestingField.class);
                }
                case ProjectionCommand.PivotUnit ignored ->
                    observed.add(ChildField.PivotSlotField.class);
            }
        }
        // The inline pivot minter observes from the fixture's coordinate (its own contribution
        // is a Call to the pivot unit, observed above; the leaf itself is recorded here). The
        // batched twin observes through its correlation-key arm in the contribution walk.
        observed.add(ChildField.PivotField.class);

        assertThat(observed)
            .as("every observed minting kind is declared in CONTRIBUTION_MINTING_LEAVES")
            .isSubsetOf(ProjectionCommands.CONTRIBUTION_MINTING_LEAVES);

        var unexercised = new ArrayList<>(ProjectionCommands.CONTRIBUTION_MINTING_LEAVES);
        unexercised.removeAll(observed);
        assertThat(unexercised)
            .as("declared minting kinds this fixture does not observe directly: the computed"
                + " field (needs an authored @externalField method; its minting is covered by"
                + " ServiceProjectionPipelineTest) and the three polymorphic child shapes, whose"
                + " gated correlation-key arms are pinned per-shape by"
                + " CorrelationKeyArmPipelineTest")
            .containsExactlyInAnyOrder(
                ChildField.ComputedField.class,
                ChildField.TableInterfaceField.class,
                ChildField.InterfaceField.class,
                ChildField.UnionField.class);
    }

    /**
     * The residual enforcer of the retired parent-projection containment check. Single-sourcing
     * (the correlation-key arm and the extraction emitter read the same model accessors) makes
     * <em>value</em> divergence impossible, but it cannot see <em>membership</em> divergence: a
     * leaf whose fetcher reads parent-row columns while its contribution arm mints nothing
     * compiles green and fails at request time with an absent column. The demand population is
     * derivable from the seal, so derive it: every {@link ChildField} leaf implementing
     * {@link BatchKeyField} or {@link ParentRowDemand} must be declared minting.
     */
    @Test
    void everyParentRowReadingLeafIsDeclaredMinting() {
        var demanding = new LinkedHashSet<Class<?>>();
        collectSealedLeaves(ChildField.class, demanding);
        var parentRowReaders = demanding.stream()
            .filter(c -> BatchKeyField.class.isAssignableFrom(c)
                || ParentRowDemand.class.isAssignableFrom(c))
            .toList();
        assertThat(parentRowReaders)
            .as("every ChildField leaf with a parent-row-reading capability mints a gated"
                + " correlation-key arm; a capability-bearing leaf outside the minting census"
                + " would read a column no arm projects")
            .isNotEmpty()
            .allSatisfy(leaf -> assertThat(ProjectionCommands.CONTRIBUTION_MINTING_LEAVES)
                .contains(leaf.asSubclass(GraphitronField.class)));
    }

    private static void collectSealedLeaves(Class<?> node, Set<Class<?>> leaves) {
        var permitted = node.getPermittedSubclasses();
        if (permitted == null || permitted.length == 0) {
            leaves.add(node);
            return;
        }
        for (var p : permitted) {
            collectSealedLeaves(p, leaves);
        }
    }

    private void observeContributions(GraphitronSchema schema, String typeName,
            List<Contribution> contributions, Set<Class<? extends GraphitronField>> observed) {
        for (var c : contributions) {
            var field = schema.field(typeName, c.field());
            if (field != null) {
                observed.add(((ChildField) field).getClass());
            }
            if (c instanceof Contribution.Call call && call.wrap() instanceof CallWrap.Splice) {
                observed.add(ChildField.NestingField.class);
            }
        }
    }
}
