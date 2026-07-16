package no.sikt.graphitron.rewrite.methodgraph;

import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.rewrite.GraphQLRewriteGenerator;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.RewriteContext;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.model.BatchKeyField;
import no.sikt.graphitron.rewrite.model.OutputField;
import no.sikt.graphitron.rewrite.schema.input.SchemaInput;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R333 thread I, level 2 — the bidirectional closure oracle over the reentry family (R314 goal
 * 4). The level-1 oracle ({@link MethodClosureOracleTest}) proves every callee name resolves to
 * an emitted method; this oracle adds the command direction: joining the run's committed
 * {@link MethodCommand} relation (surfaced on
 * {@code GraphQLRewriteGenerator.GenerationResult#methodCommands()}) against the same emit walk
 * and against the classified model, it asserts that
 *
 * <ul>
 *   <li><b>model → command</b>: every schema coordinate the covered family claims — derived from
 *       the site-level fact, {@code emitsKeyedReQuery() && field instanceof BatchKeyField},
 *       never a hand tag — has exactly one committed command;</li>
 *   <li><b>command → emit</b>: every committed command's {@code (unit, typePath, method)} is a
 *       method the run actually declared (a command with no method behind it is a mint that
 *       bypassed the declaration, i.e. census drift);</li>
 *   <li><b>exactly-one</b>: no two commands claim the same emitted method (enforced at commit
 *       time by {@link MethodCommandRegistry}; pinned here at the run level).</li>
 * </ul>
 *
 * <p>The covered family spans the whole reentry family: the DataLoader-backed leaves
 * (rows/load methods, slices 1-3) and the projected / discriminated DML arms (the named rows
 * companion holding the follow-up SELECT, slice 4). The root {@code @service} passthrough pin
 * keeps the one deliberate absence visible: value-level re-fetch without a site-level re-query
 * commits nothing, by the fact, not by omission.
 */
@PipelineTier
class ReentryCommandClosureTest {

    private static final String OUTPUT_PACKAGE = TestConfiguration.DEFAULT_OUTPUT_PACKAGE;

    private static final String SCHEMA = """
        type Query {
          film: Film
          externalFilm: Film
            @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilm"})
        }

        type Film @table(name: "film") {
          title: String
          language: Language @reference(path: [{key: "film_language_id_fkey"}])
          actors: [Actor!]! @splitQuery
              @reference(path: [{key: "film_actor_film_id_fkey"}, {key: "film_actor_actor_id_fkey"}])
        }
        type Actor @table(name: "actor") { firstName: String @field(name: "first_name") }

        type Language @table(name: "language") {
          name: String
          filmsViaService: [Film!]! @service(
            service: {className: "no.sikt.graphitron.rewrite.generators.TestFilmService", method: "getFilmsMapped"}
          )
        }

        type FilmPayload { film: Film }
        input FilmInput @table(name: "film") { title: String }

        type Mutation {
          createFilm(in: FilmInput!): Film @mutation(typeName: INSERT)
          runFilm: FilmPayload
            @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runFilm"})
        }
        """;

    private static GraphitronSchema model;
    private static EmittedMethodClosure walk;
    private static List<MethodCommand> commands;

    @BeforeAll
    static void generateAndWalk(@TempDir Path workDir) throws Exception {
        model = TestSchemaHelper.buildSchema(SCHEMA);
        Path schemaFile = workDir.resolve("schema.graphqls");
        Files.writeString(schemaFile, SCHEMA);
        RewriteContext ctx = new RewriteContext(
            List.of(SchemaInput.plain(schemaFile.toString())),
            workDir,
            workDir.resolve("generated-sources"),
            OUTPUT_PACKAGE,
            TestConfiguration.DEFAULT_JOOQ_PACKAGE,
            Map.of());
        var result = new GraphQLRewriteGenerator(ctx).generate();
        walk = EmittedMethodClosure.walk(result.emittedUnits());
        commands = result.methodCommands();
    }

    /**
     * The covered-family boundary, derived from the model's site-level fact — never a tag. The
     * structural conjunct names the two shapes whose reentry unit is a named method today: the
     * DataLoader-backed leaves (rows/load methods) and, since slice 4, the projected /
     * discriminated DML arms (the rows companion holding the follow-up SELECT).
     */
    private static Set<String> coveredCoordinates() {
        return model.fields().values().stream()
            .filter(f -> f instanceof OutputField of && of.emitsKeyedReQuery())
            .filter(f -> f instanceof BatchKeyField
                || f instanceof no.sikt.graphitron.rewrite.model.MutationField.DmlTableField)
            .map(f -> ((OutputField) f).qualifiedName())
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** Model → command: the committed coordinates are exactly the fact-derived covered family. */
    @Test
    void everyCoveredCoordinateHasExactlyOneCommittedCommand() {
        assertThat(commands)
            .extracting(MethodCommand::coordinate)
            .as("committed command coordinates == the coordinates whose site-level fact claims a "
                + "keyed re-query at a DataLoader-backed site (a missing entry means a reentry "
                + "declaration bypassed the command-mint seam)")
            .containsExactlyInAnyOrderElementsOf(coveredCoordinates());
    }

    /** Command → emit: every committed command names a method the run actually declared. */
    @Test
    void everyCommittedCommandResolvesToAnEmittedMethod() {
        for (MethodCommand cmd : commands) {
            Map<String, Set<String>> byPath = walk.declaredMethods().get(cmd.unitFqcn());
            assertThat(byPath)
                .as("command %s claims unit %s, which the run did not emit", cmd, cmd.unitFqcn())
                .isNotNull();
            assertThat(byPath.get(cmd.typePath()))
                .as("command %s: emitted methods at type path '%s'", cmd, cmd.typePath())
                .isNotNull()
                .contains(cmd.methodName());
        }
    }

    /** Exactly-one: no two commands claim the same emitted method (run-level pin of the commit guard). */
    @Test
    void noTwoCommandsClaimTheSameEmittedMethod() {
        assertThat(commands.stream().map(MethodCommand::methodKey).distinct().count())
            .isEqualTo(commands.size());
    }

    /**
     * Non-vacuity witnesses plus the two migration-boundary pins. The negatives are as
     * load-bearing as the positives: they prove absence follows from the model facts, so the
     * covered set cannot silently drift wide or narrow.
     */
    @Test
    void familyWitnessesAndBoundaryPins() {
        // Positive witnesses: the child service-table lift and the record-sourced carrier.
        assertThat(commands)
            .anySatisfy(c -> {
                assertThat(c.coordinate()).isEqualTo("Language.filmsViaService");
                assertThat(c.methodName()).isEqualTo("loadFilmsViaService");
                assertThat(c.unitFqcn()).isEqualTo(OUTPUT_PACKAGE + ".fetchers.LanguageFetchers");
            })
            .anySatisfy(c -> {
                assertThat(c.coordinate()).isEqualTo("FilmPayload.film");
                assertThat(c.methodName()).isEqualTo("rowsFilm");
                assertThat(c.unitFqcn()).isEqualTo(OUTPUT_PACKAGE + ".fetchers.FilmPayloadFetchers");
            });

        // Table-sourced @splitQuery flows through the same declaration seam and commits nothing:
        // its rows-method is emitted (level-1 node) but it is not reentry.
        assertThat(commands).noneMatch(c -> c.coordinate().equals("Film.actors"));
        assertThat(declaredIn(OUTPUT_PACKAGE + ".fetchers.FilmFetchers")).contains("rowsActors");

        // Root @service passthrough: value-level re-fetch true, site-level fact false -> no command.
        OutputField externalFilm = (OutputField) model.field("Query", "externalFilm");
        assertThat(externalFilm.requiresReFetch()).isTrue();
        assertThat(externalFilm.emitsKeyedReQuery())
            .as("root service passthrough re-projects downstream, not at its own site")
            .isFalse();
        assertThat(commands).noneMatch(c -> c.coordinate().equals("Query.externalFilm"));

        // Projected DML (slice 4): the follow-up SELECT lives in the named rows companion and
        // its command is committed through the same registry — the reentry family's registry
        // coverage is whole.
        OutputField createFilm = (OutputField) model.field("Mutation", "createFilm");
        assertThat(createFilm.emitsKeyedReQuery()).isTrue();
        assertThat(commands)
            .anySatisfy(c -> {
                assertThat(c.coordinate()).isEqualTo("Mutation.createFilm");
                assertThat(c.methodName()).isEqualTo("rowsCreateFilm");
                assertThat(c.unitFqcn()).isEqualTo(OUTPUT_PACKAGE + ".fetchers.MutationFetchers");
            });
    }

    private Set<String> declaredIn(String unit) {
        Map<String, Set<String>> byPath = walk.declaredMethods().get(unit);
        assertThat(byPath).as("emitted unit %s", unit).isNotNull();
        return byPath.get("");
    }
}
