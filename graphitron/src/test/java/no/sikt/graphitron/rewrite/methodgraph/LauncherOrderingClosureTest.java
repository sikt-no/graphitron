package no.sikt.graphitron.rewrite.methodgraph;

import no.sikt.graphitron.command.Arity;
import no.sikt.graphitron.command.CallWrap;
import no.sikt.graphitron.command.Contribution;
import no.sikt.graphitron.command.LaunchSource;
import no.sikt.graphitron.command.LauncherCommand;
import no.sikt.graphitron.command.ResultShape;
import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.model.config.RunContext;
import no.sikt.graphitron.model.schema.input.SchemaInput;
import no.sikt.graphitron.plan.EmitPlan;
import no.sikt.graphitron.plan.LauncherCommands;
import no.sikt.graphitron.plan.LauncherRelation;
import no.sikt.graphitron.plan.ProjectionRelation;
import no.sikt.graphitron.rewrite.GraphQLRewriteGenerator;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The ordering closure over the plan a generator run rendered from: an ordering the model resolved
 * reaches a command that can render it. Read off the {@code GenerationResult}'s carried plan, never
 * a re-derivation, on {@link LauncherRelationClosureTest}'s own rule.
 *
 * <p>The production fold this pins lives in {@link LauncherCommands}, and the test is the second
 * artifact rather than the mechanism: an invariant whose value is the schemas nobody has written yet
 * cannot be held by a corpus, so it throws in production and this file states it in both directions
 * over ours.
 *
 * <p>Three legs, and the first two are not the same kind of check.
 *
 * <ul>
 *   <li><b>The multiset comparison</b>: no list-cardinality multiset carries an
 *       {@link OrderBySpec.Argument}, because the projection renderer lowers the
 *       {@link OrderBySpec.Fixed} arm alone. This is the one place in the command tier where the two
 *       ends are derived independently and can therefore disagree, and the case at the shape that
 *       disagrees is beside the corpus leg.</li>
 *   <li><b>The launcher ratchet</b>: every list-shaped launcher row whose source arm is not exempt
 *       carries an ordering. A ratchet over a closed population rather than a comparison, the slot
 *       being computed off the leaf's own spec on every non-exempt arm.</li>
 *   <li><b>The exemption's membership</b>: the arms carrying an absent slot in this corpus are
 *       exactly the arms the production switch exempts, so an arm that quietly stops projecting its
 *       ordering fails here rather than being absorbed by an exemption written for a different
 *       shape. It pins arm membership and not the leaf's spec: a keyed lookup's leaf does resolve a
 *       primary-key ordering, and the row is right to drop it.</li>
 * </ul>
 */
@PipelineTier
class LauncherOrderingClosureTest {

    private static final String OUTPUT_PACKAGE = TestConfiguration.DEFAULT_OUTPUT_PACKAGE;

    /**
     * A corpus carrying one coordinate per launcher family that can return a list, so no leg below
     * passes for want of rows: an ordered root, a keyed-lookup root, a batched child with a
     * client-supplied ordering, a batched child with a fixed one, and a DML insert whose reentry
     * companion re-selects a list of written rows.
     */
    private static final String SCHEMA = """
        type Query {
          films: [Film!]! @defaultOrder(primaryKey: true)
          filmById(film_id: [ID] @lookupKey): [Film]!
          filmsOrdered(order: [FilmOrderBy] @orderBy): [Film!]! @defaultOrder(primaryKey: true)
        }

        type Film @table(name: "film") {
          title: String
          actorsSplit: [Actor!]! @splitQuery @defaultOrder(primaryKey: true)
              @reference(path: [{key: "film_actor_film_id_fkey"}, {key: "film_actor_actor_id_fkey"}])
          actorsOrderedSplit(order: [ActorOrderBy] @orderBy): [Actor!]! @splitQuery
              @defaultOrder(primaryKey: true)
              @reference(path: [{key: "film_actor_film_id_fkey"}, {key: "film_actor_actor_id_fkey"}])
          actorsLookupSplit(actor_id: [Int!] @lookupKey): [Actor!]! @splitQuery
              @defaultOrder(primaryKey: true)
              @reference(path: [{key: "film_actor_film_id_fkey"}, {key: "film_actor_actor_id_fkey"}])
          actorsInline: [Actor!]! @defaultOrder(primaryKey: true)
              @reference(path: [{key: "film_actor_film_id_fkey"}, {key: "film_actor_actor_id_fkey"}])
        }
        type Actor @table(name: "actor") { firstName: String @field(name: "first_name") }

        enum FilmSort { FILM_ID @order(primaryKey: true) }
        input FilmOrderBy { field: FilmSort!  direction: SortDirection }
        enum ActorSort { FIRST_NAME @order(fields: [{name: "first_name"}]) }
        input ActorOrderBy { field: ActorSort!  direction: SortDirection }

        input FilmInput { title: String }
        type Mutation {
          createFilms(in: [FilmInput!]!): [Film!]! @mutation(typeName: INSERT)
        }
        """;

    private static LauncherRelation launchers;
    private static ProjectionRelation projections;

    @BeforeAll
    static void generate(@TempDir Path workDir) throws Exception {
        Path schemaFile = workDir.resolve("schema.graphqls");
        Files.writeString(schemaFile, SCHEMA);
        var result = new GraphQLRewriteGenerator(new RunContext(
            List.of(SchemaInput.file(schemaFile)),
            workDir, "LauncherOrderingClosureTest",
            workDir.resolve("generated-sources"),
            OUTPUT_PACKAGE,
            TestConfiguration.DEFAULT_JOOQ_PACKAGE)).generate();
        launchers = result.plan().launchers();
        projections = result.plan().projections();
    }

    /**
     * The comparison of two ends over the corpus. A multiset carries the whole resolved spec and the
     * renderer lowers one arm of it, so the argument arm reaching a list multiset is an ordering
     * accepted and dropped.
     */
    @Test
    void noListMultisetCarriesAClientSuppliedOrdering() {
        assertThat(listMultisets())
            .as("a list-cardinality multiset may not carry an OrderBySpec.Argument; the projection"
                + " renderer lowers the fixed arm alone")
            .isNotEmpty()
            .allSatisfy(m -> assertThat(m.orderBy()).isNotInstanceOf(OrderBySpec.Argument.class));
    }

    /**
     * The shape the confirmation recipe turned up, which is what says the invariant above can fail:
     * an inline child list carrying an {@code @orderBy} argument resolves the argument arm into a
     * multiset today, and before the fold landed the emitted projection carried no ORDER BY at all.
     * The remedy is the lowering or a rejection at the coordinate; until one of those lands, the
     * build stops here rather than serving an order the client asked for and did not get.
     */
    @Test
    void anInlineChildListCarryingAnOrderByArgumentIsRefusedAtProduction() {
        var model = TestSchemaHelper.buildSchema("""
            type Query { film: Film }
            type Film @table(name: "film") {
              title: String
              actorsInline(order: [ActorOrderBy] @orderBy): [Actor!]!
                @reference(path: [{key: "film_actor_film_id_fkey"}, {key: "film_actor_actor_id_fkey"}])
            }
            type Actor @table(name: "actor") { firstName: String @field(name: "first_name") }
            enum ActorSort { FIRST_NAME @order(fields: [{name: "first_name"}]) }
            input ActorOrderBy { field: ActorSort!  direction: SortDirection }
            """);

        assertThatThrownBy(() -> EmitPlan.produceWithoutStore(model, false, false, OUTPUT_PACKAGE))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Graphitron generator bug (projection ordering)")
            .hasMessageContaining("the inline child list 'actorsInline'")
            .hasMessageContaining("argument 'order'");
    }

    /** The ratchet: every list-shaped row on a non-exempt source arm carries an ordering. */
    @Test
    void everyNonExemptListRowCarriesAnOrdering() {
        assertThat(listRows())
            .isNotEmpty()
            .allSatisfy(row -> {
                var list = (ResultShape.RecordList) row.result();
                if (!LauncherCommands.orderIsEntailedBySource(row.source())) {
                    assertThat(list.ordering())
                        .as("row %s on non-exempt source %s", coordinateOf(row),
                            row.source().getClass().getSimpleName())
                        .isNotNull();
                }
            });
    }

    /**
     * The exemption's membership, read off the production switch and never restated here: the arms
     * whose list rows carry no ordering are exactly the arms that switch exempts. An arm that stopped
     * projecting the ordering it projects today would show up on the left and not the right.
     */
    @Test
    void theArmsWithAnAbsentSlotAreExactlyTheExemptArms() {
        Set<String> absentSlot = new LinkedHashSet<>();
        Set<String> exempt = new LinkedHashSet<>();
        for (LauncherCommand row : listRows()) {
            String arm = row.source().getClass().getSimpleName();
            if (((ResultShape.RecordList) row.result()).ordering() == null) {
                absentSlot.add(arm);
            }
            if (LauncherCommands.orderIsEntailedBySource(row.source())) {
                exempt.add(arm);
            }
        }
        assertThat(exempt)
            .as("the corpus must exercise the exemption, or this leg says nothing")
            .isNotEmpty();
        assertThat(absentSlot).containsExactlyInAnyOrderElementsOf(exempt);
    }

    // ===== Helpers =====

    private static List<LauncherCommand> listRows() {
        return launchers.rows().stream()
            .filter(row -> row.result() instanceof ResultShape.RecordList)
            .toList();
    }

    private static List<CallWrap.Multiset> listMultisets() {
        return projections.rows().stream()
            .flatMap(unit -> unit.contributions().stream())
            .filter(Contribution.Call.class::isInstance)
            .map(c -> ((Contribution.Call) c).wrap())
            .filter(CallWrap.Multiset.class::isInstance)
            .map(CallWrap.Multiset.class::cast)
            .filter(m -> m.arity() == Arity.LIST)
            .toList();
    }

    private static String coordinateOf(LauncherCommand row) {
        return row.coordinate().getTypeName() + "." + row.coordinate().getFieldName();
    }
}
