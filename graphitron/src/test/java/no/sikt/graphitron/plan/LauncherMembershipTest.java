package no.sikt.graphitron.plan;

import no.sikt.graphitron.command.LaunchSource;
import no.sikt.graphitron.plan.LauncherCommands.Launch;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.generators.GeneratorCoverageTest;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.OutputField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The launcher relation's membership enforcer, the {@code ProjectionMembershipTest} shape for
 * the launcher family. Membership is the member-and-delivery-derived verdict
 * ({@link LauncherCommands#verdictOf(GraphitronSchema, GraphitronField)}), so the census binds
 * the verdict to observed minting: the relation's coordinate set equals the verdict-derived
 * covered set, every launch family and every {@link LaunchSource} arm is observed over the
 * composite fixture (the per-arm non-vacuity floors), and the pinned coordinate roster keeps
 * the fixture itself honest, so a family member silently ceasing to produce a row, or the
 * fixture silently ceasing to exercise a family, is a census mismatch here, not an
 * unquantified gap.
 */
@PipelineTier
class LauncherMembershipTest {

    // Every launch family in one fixture: the four root catalog shapes (plain table, routine
    // chain, discriminated interface, keyed lookup), the three batched child shapes (plain
    // split, split lookup at list-per-key cardinality, split pivot), both @service child kinds
    // (table-returning and scalar), and all four reentry-carrying DML return arms. The encoded
    // DELETE is the deliberate non-member witness: present in the model, no row.
    private static final String SDL = """
        interface Content @table(name: "content") @discriminate(on: "CONTENT_TYPE") {
          contentId: Int! @field(name: "CONTENT_ID")
        }
        type FilmContent implements Content @table(name: "content") @discriminator(value: "FILM") {
          contentId: Int! @field(name: "CONTENT_ID")
        }
        type ShortContent implements Content @table(name: "content") @discriminator(value: "SHORT") {
          contentId: Int! @field(name: "CONTENT_ID")
        }
        input ContentInput {
          title: String! @field(name: "TITLE")
          contentType: String! @field(name: "CONTENT_TYPE")
        }
        enum Sprak { nn @field(name: "nno") nb @field(name: "nob") }
        type TranslatedTexts { nn: String nb: String }
        type Tilgang @table(name: "tilganger_for_feidebruker_med_fs_fiktivt_fnr") {
          organisasjonskode: Int
        }
        type Language @table(name: "language") {
          name: String
          films: [Film!]! @service(
            service: {className: "no.sikt.graphitron.rewrite.generators.TestFilmService", method: "getFilmsMapped"})
          rank: Int @service(
            service: {className: "no.sikt.graphitron.rewrite.generators.TestFilmService", method: "getRankMapped"})
        }
        type Actor @table(name: "actor") { actorId: Int @field(name: "actor_id") }
        type Film @table(name: "film") {
          title: String
          titleTextsSplit: TranslatedTexts @splitQuery @reference(path: [{table: "film_translation"}])
                                      @pivot(on: "lang_code", value: "title_txt", vocabulary: "Sprak")
          actorsSplit(actor_id: [Int!] @lookupKey): [Actor!]! @splitQuery @reference(path: [
            {key: "film_actor_film_id_fkey"},
            {key: "film_actor_actor_id_fkey"}
          ])
          languages: [Language!]! @splitQuery @reference(path: [{key: "film_language_id_fkey"}])
        }
        input FilmInput { title: String }
        input FilmKeyInput { filmId: Int! @field(name: "film_id") }
        type Query {
          films: [Film!]!
          filmById(film_id: [ID] @lookupKey): [Film!]!
          content: Content
          tilganger(env: String!, serviceId: String!, feideId: String!): [Tilgang!]!
            @routine(name: "tilganger_for_feidebruker_med_fs_fiktivt_fnr", argMapping: "pEnv: env, pServiceId: serviceId, pFeideId: feideId")
        }
        type Mutation {
          createFilm(in: FilmInput!): Film @mutation(typeName: INSERT)
          createFilms(in: [FilmInput!]!): [Film!]! @mutation(typeName: INSERT)
          createContent(in: ContentInput!): Content @mutation(typeName: INSERT, table: "content")
          createContents(in: [ContentInput!]!): [Content!]! @mutation(typeName: INSERT, table: "content")
          deleteFilm(in: FilmKeyInput!): ID @mutation(typeName: DELETE, table: "film")
        }
        """;

    /** The 13 minting coordinates the fixture exercises, pinned so the fixture stays honest. */
    private static final List<String> EXPECTED_COVERED = List.of(
        "Query.films", "Query.filmById", "Query.content", "Query.tilganger",
        "Film.titleTextsSplit", "Film.actorsSplit", "Film.languages",
        "Language.films", "Language.rank",
        "Mutation.createFilm", "Mutation.createFilms",
        "Mutation.createContent", "Mutation.createContents");

    private static LauncherRelation produce(GraphitronSchema schema) {
        var conditions = ConditionCommands.produce(schema, DEFAULT_OUTPUT_PACKAGE);
        return LauncherCommands.produce(schema, conditions, DEFAULT_OUTPUT_PACKAGE);
    }

    /**
     * The census, verdict-derived: the relation's coordinate set equals the covered set the
     * verdict admits, that set is exactly the pinned roster (so the fixture cannot silently
     * stop exercising a family), every non-NONE launch family is observed, and every declared
     * {@link LaunchSource} arm appears on some row (the source-arm grain the retired declared
     * kind set used to pin).
     */
    @Test
    void censusMatchesObservedMintingInBothDirections() {
        var schema = TestSchemaHelper.buildSchema(SDL);
        var relation = produce(schema);

        var covered = schema.fields().values().stream()
            .filter(f -> LauncherCommands.covers(schema, f))
            .map(f -> ((OutputField) f).qualifiedName())
            .toList();
        assertThat(covered)
            .as("the verdict-derived covered set is exactly the pinned fixture roster")
            .containsExactlyInAnyOrderElementsOf(EXPECTED_COVERED);
        assertThat(relation.rows())
            .extracting(r -> r.coordinate().getTypeName() + "." + r.coordinate().getFieldName())
            .containsExactlyInAnyOrderElementsOf(covered);

        var observedFamilies = new LinkedHashSet<Launch>();
        for (var field : schema.fields().values()) {
            observedFamilies.add(LauncherCommands.verdictOf(schema, field));
        }
        assertThat(observedFamilies)
            .as("every launch family is observed over the fixture (NONE included: the"
                + " non-member witnesses)")
            .containsExactlyInAnyOrder(Launch.values());

        var observedSources = new LinkedHashSet<Class<?>>();
        for (var row : relation.rows()) {
            observedSources.add(row.source().getClass());
        }
        assertThat(observedSources)
            .as("every declared LaunchSource arm is produced over the fixture; a new arm must"
                + " extend the fixture (or enumerate itself here with a prose reason)")
            .containsExactlyInAnyOrderElementsOf(
                GeneratorCoverageTest.sealedLeaves(LaunchSource.class));
    }

    /**
     * The accessor-derived covered set equals the relation's key set over the fixture: the
     * positive direction the retired hand-maintained leaf switch used to carry. Also applies
     * the shared invocation-determination pin at this relation.
     */
    @Test
    void relationCoordinateSetEqualsTheAccessorDerivedCoveredSet() {
        var schema = TestSchemaHelper.buildSchema(SDL);
        var relation = produce(schema);

        var covered = schema.fields().values().stream()
            .filter(f -> LauncherCommands.covers(schema, f))
            .map(f -> ((OutputField) f).qualifiedName())
            .toList();
        assertThat(relation.rows())
            .extracting(r -> r.coordinate().getTypeName() + "." + r.coordinate().getFieldName())
            .containsExactlyInAnyOrderElementsOf(covered);

        LauncherAxisPins.assertInvocationMatchesDeclaredDetermination(relation);
    }

    /** The invocation determination is total over {@link LaunchSource}'s concrete arms. */
    @Test
    void invocationDeterminationIsTotalOverTheSourceArms() {
        assertThat(LauncherCommands.INVOCATION_BY_SOURCE.keySet())
            .as("INVOCATION_BY_SOURCE must declare exactly one invocation arm per concrete"
                + " LaunchSource leaf")
            .containsExactlyInAnyOrderElementsOf(
                GeneratorCoverageTest.sealedLeaves(LaunchSource.class).stream()
                    .map(c -> c.asSubclass(LaunchSource.class))
                    .toList());
    }

    /**
     * The schema-free walk's positive direction: fed the fixture's classified fields (so no
     * hand-built records drift from the classifier),
     * {@link LauncherCommands#produceWithoutSchema} serves exactly the covered non-DML
     * coordinates, membership read through the same verdict predicate over the leaf-projected
     * fact sources. The DML companions are deliberately outside the schema-free walk (no
     * write, no captured keys), which is why the expected set filters them out.
     */
    @Test
    void produceWithoutSchemaServesExactlyTheCoveredNonDmlCoordinates() {
        var schema = TestSchemaHelper.buildSchema(SDL);
        List<GraphitronField> fields = List.copyOf(schema.fields().values());
        var relation = LauncherCommands.produceWithoutSchema(fields, DEFAULT_OUTPUT_PACKAGE);

        var expected = fields.stream()
            .filter(f -> LauncherCommands.covers(schema, f))
            .filter(f -> !(f instanceof MutationField.DmlTableField))
            .map(f -> ((OutputField) f).qualifiedName())
            .toList();
        assertThat(expected)
            .as("the fixture must exercise every non-DML launch family member")
            .hasSize(9);
        assertThat(relation.rows())
            .extracting(r -> r.coordinate().getTypeName() + "." + r.coordinate().getFieldName())
            .containsExactlyInAnyOrderElementsOf(expected);
    }
}
