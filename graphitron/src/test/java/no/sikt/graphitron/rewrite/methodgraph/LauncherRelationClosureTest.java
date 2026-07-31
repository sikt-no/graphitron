package no.sikt.graphitron.rewrite.methodgraph;

import no.sikt.graphitron.command.Invocation;
import no.sikt.graphitron.command.LaunchSource;
import no.sikt.graphitron.command.LauncherCommand;
import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.plan.LauncherAxisPins;
import no.sikt.graphitron.plan.LauncherCommands;
import no.sikt.graphitron.plan.LauncherRelation;
import no.sikt.graphitron.rewrite.GraphQLRewriteGenerator;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.RewriteContext;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.DmlReturnExpression;
import no.sikt.graphitron.rewrite.model.MutationField;
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
 * The level-2 bidirectional closure oracle over the launcher relation. The level-1 oracle
 * ({@link MethodClosureOracleTest}) proves every callee name resolves to an emitted method;
 * this oracle adds the relation direction: joining the relation the run rendered from (read off
 * the {@code GenerationResult}'s carried plan, {@code plan().launchers()}, never a re-derivation)
 * against the same emit walk and against the classified model, it asserts that
 *
 * <ul>
 *   <li><b>model → row</b>: every schema coordinate the covered families claim (derived
 *       per-family from the model's leaf kinds, never a hand tag) has exactly one relation
 *       row;</li>
 *   <li><b>row → emit</b>: every row's {@code (owner, method)} ref is a method the run
 *       actually declared (a row with no method behind it is production that bypassed the
 *       render);</li>
 *   <li><b>exactly-one</b>: no two rows claim the same emitted method (enforced by the
 *       relation constructor's case-folded census; pinned here at the run level).</li>
 * </ul>
 *
 * <p>The covered families are the producer's minting arms, read off the producer's declared
 * membership data ({@link LauncherCommands#covers}), never a hand-maintained restatement. The
 * batched polymorphic pair is the one decided emitted-and-uncommitted population (its rows
 * methods are named through the same {@code GeneratedUnits} scheme with no row behind them);
 * the fixture instantiates its interface half, so a producer that started minting rows for it
 * fails both the model → row equality and the explicit negative pin here. The root
 * {@code @service} passthrough pin and the Encoded-DML pin keep the other deliberate absences
 * visible: value-level re-fetch without a site-level re-query gets no row, and an encoded
 * return arm carries no reentry, by the fact, not by omission.
 */
@PipelineTier
class LauncherRelationClosureTest {

    private static final String OUTPUT_PACKAGE = TestConfiguration.DEFAULT_OUTPUT_PACKAGE;

    private static final String SCHEMA = """
        type Query {
          film: Film
          city: City
          externalFilm: Film
            @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "getFilm"})
        }

        interface Node { id: ID! }
        type Film implements Node @table(name: "film") @node {
          id: ID! @nodeId
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

        interface Named { name: String }
        type Address implements Named @table(name: "address") { name: String @field(name: "ADDRESS") }
        type City @table(name: "city") {
          namedPlaces: [Named!]!
        }

        type FilmPayload { film: Film }
        input FilmInput { title: String }
        input FilmKeyInput { filmId: Int! @field(name: "film_id") }

        type Mutation {
          createFilm(in: FilmInput!): Film @mutation(typeName: INSERT)
          deleteFilm(in: FilmKeyInput!): ID @mutation(typeName: DELETE, table: "film")
          runFilm: FilmPayload
            @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runFilm"})
        }
        """;

    private static GraphitronSchema model;
    private static EmittedMethodClosure walk;
    private static LauncherRelation launchers;
    private static Map<String, no.sikt.graphitron.javapoet.TypeSpec> emittedUnits;

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
        launchers = result.plan().launchers();
        emittedUnits = result.emittedUnits();
    }

    /**
     * The covered-family boundary, read off the producer's declared minting membership
     * ({@link LauncherCommands#covers}, the one accessor over
     * {@link LauncherCommands#MINTING_KINDS}), so this leg and the producer cannot drift: the
     * membership census test binds the declaration to observed minting, and this test binds the
     * generator run's relation to the same declaration.
     */
    private static Set<String> coveredCoordinates() {
        return model.fields().values().stream()
            .filter(LauncherCommands::covers)
            .map(f -> ((OutputField) f).qualifiedName())
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String coordinateOf(LauncherCommand row) {
        return row.coordinate().getTypeName() + "." + row.coordinate().getFieldName();
    }

    /** Model → row: the relation's coordinate keys are exactly the fact-derived covered families. */
    @Test
    void everyCoveredCoordinateHasExactlyOneRow() {
        assertThat(launchers.rows())
            .extracting(LauncherRelationClosureTest::coordinateOf)
            .as("relation coordinates == the coordinates the covered families' leaf kinds claim"
                + " (a missing entry means a family member bypassed production; an extra one"
                + " means production minted outside the declared families)")
            .containsExactlyInAnyOrderElementsOf(coveredCoordinates());
    }

    /** Row → emit: every row's {@code (owner, method)} ref names a method the run declared. */
    @Test
    void everyRowResolvesToAnEmittedMethod() {
        for (LauncherCommand row : launchers.rows()) {
            String unitFqcn = row.unit().owner().fqcn();
            Map<String, Set<String>> byPath = walk.declaredMethods().get(unitFqcn);
            assertThat(byPath)
                .as("row %s claims unit %s, which the run did not emit", coordinateOf(row), unitFqcn)
                .isNotNull();
            assertThat(byPath.get(""))
                .as("row %s: emitted top-level methods of %s", coordinateOf(row), unitFqcn)
                .isNotNull()
                .contains(row.unit().methodName());
        }
    }

    /**
     * The invocation determination, pinned at the generator-run relation: every produced row's
     * delivery arm equals the arm {@link LauncherCommands#INVOCATION_BY_SOURCE} declares for its
     * source arm.
     */
    @Test
    void everyRowsInvocationMatchesTheDeclaredDetermination() {
        LauncherAxisPins.assertInvocationMatchesDeclaredDetermination(launchers);
    }

    /** Exactly-one: no two rows claim the same emitted method (run-level pin of the relation census). */
    @Test
    void noTwoRowsClaimTheSameEmittedMethod() {
        assertThat(launchers.rows().stream()
                .map(r -> r.unit().owner().fqcn() + "#" + r.unit().methodName())
                .distinct().count())
            .isEqualTo(launchers.rows().size());
    }

    /**
     * The entry-point identity pin, derived from the relation rather than a roster: for every
     * row, the owner fetchers class declares a DataFetcher entry method named exactly the
     * coordinate's field name, taking exactly one {@code DataFetchingEnvironment} parameter.
     * This is the falsifiable form of the formula-derived decision (the entry method's
     * identity IS the coordinate; the schema wiring rebinds the same accessor): signature
     * structure only, no body assertions, so body thinness stays with the render-sites pin
     * (composition can only live in the launcher renderer) and the write entries' deliberate
     * non-thinness (the {@code Reentry}-sourced rows) needs no carve-out here.
     */
    @Test
    void everyRowsEntryPointIsTheCoordinateNamedEnvMethod() {
        for (LauncherCommand row : launchers.rows()) {
            String unitFqcn = row.unit().owner().fqcn();
            var unit = emittedUnits.get(unitFqcn);
            assertThat(unit).as("emitted unit %s", unitFqcn).isNotNull();
            String entryName = row.coordinate().getFieldName();
            var entries = unit.methodSpecs().stream()
                .filter(m -> m.name().equals(entryName))
                .toList();
            assertThat(entries)
                .as("row %s: entry method '%s' on %s", coordinateOf(row), entryName, unitFqcn)
                .hasSize(1);
            assertThat(entries.get(0).parameters())
                .as("row %s: the entry method takes exactly (DataFetchingEnvironment env)",
                    coordinateOf(row))
                .singleElement()
                .satisfies(p -> assertThat(p.type().toString())
                    .isEqualTo("graphql.schema.DataFetchingEnvironment"));
        }
    }

    /**
     * Non-vacuity witnesses plus the migration-boundary pins. The negatives are as load-bearing
     * as the positives: they prove absence follows from the model facts, so the covered set
     * cannot silently drift wide or narrow.
     */
    @Test
    void familyWitnessesAndBoundaryPins() {
        // Positive witnesses: the child service-table lift and the record-sourced carrier.
        assertThat(launchers.rowFor("Language", "filmsViaService"))
            .hasValueSatisfying(row -> {
                assertThat(row.unit().methodName()).isEqualTo("loadFilmsViaService");
                assertThat(row.unit().owner().fqcn())
                    .isEqualTo(OUTPUT_PACKAGE + ".fetchers.LanguageFetchers");
            });
        assertThat(launchers.rowFor("FilmPayload", "film"))
            .hasValueSatisfying(row -> {
                assertThat(row.unit().methodName()).isEqualTo("rowsFilm");
                assertThat(row.unit().owner().fqcn())
                    .isEqualTo(OUTPUT_PACKAGE + ".fetchers.FilmPayloadFetchers");
            });

        // Root @service passthrough: value-level re-fetch true, site-level fact false, no row.
        OutputField externalFilm = (OutputField) model.field("Query", "externalFilm");
        assertThat(externalFilm.requiresReFetch()).isTrue();
        assertThat(externalFilm.emitsKeyedReQuery())
            .as("root service passthrough re-projects downstream, not at its own site")
            .isFalse();
        assertThat(launchers.rowFor("Query", "externalFilm")).isEmpty();

        // The batched polymorphic pair's interface half: emitted-and-uncommitted by decision.
        // Model fact first (the fixture really instantiates the leaf), then the absence.
        assertThat(model.field("City", "namedPlaces"))
            .as("an interface-typed list child with a table-bound participant classifies to the"
                + " batched interface leaf")
            .isInstanceOf(ChildField.BatchedInterfaceField.class);
        assertThat(launchers.rowFor("City", "namedPlaces"))
            .as("the batched polymorphic pair is emitted-and-uncommitted: rows methods are named"
                + " through the GeneratedUnits scheme with no launcher row behind them")
            .isEmpty();

        // Encoded DML: the return arm carries no reentry, so the coordinate mints no row. The
        // model leg is the return-arm fact, deliberately not emitsKeyedReQuery(), which is true
        // for an Encoded DELETE while the relation still mints nothing.
        var deleteFilm = (MutationField.DmlTableField) model.field("Mutation", "deleteFilm");
        assertThat(deleteFilm.returnExpression())
            .as("a DELETE returning ID carries the encoded-single return arm")
            .isInstanceOf(DmlReturnExpression.EncodedSingle.class);
        assertThat(LauncherCommands.covers(deleteFilm))
            .as("the declared membership excludes the encoded return arms")
            .isFalse();
        assertThat(launchers.rowFor("Mutation", "deleteFilm")).isEmpty();

        // Projected DML: the reentry companion's name rides the row's UnitMethodRef (the one
        // minting locus), the source arm carries the correlation, and the delivery is the
        // ReturningKeyed biconditional partner.
        OutputField createFilm = (OutputField) model.field("Mutation", "createFilm");
        assertThat(createFilm.emitsKeyedReQuery()).isTrue();
        assertThat(launchers.rowFor("Mutation", "createFilm"))
            .hasValueSatisfying(row -> {
                assertThat(row.unit().methodName()).isEqualTo("rowsCreateFilm");
                assertThat(row.unit().owner().fqcn())
                    .isEqualTo(OUTPUT_PACKAGE + ".fetchers.MutationFetchers");
                assertThat(row.source()).isInstanceOf(LaunchSource.ProjectedReentry.class);
                assertThat(row.invocation()).isInstanceOf(Invocation.ReturningKeyed.class);
            });
    }
}
