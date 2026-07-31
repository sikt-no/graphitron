package no.sikt.graphitron.plan;

import no.sikt.graphitron.command.LaunchSource;
import no.sikt.graphitron.plan.LauncherCommands.MintingKind;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.generators.GeneratorCoverageTest;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.MutationField;
import no.sikt.graphitron.rewrite.model.OutputField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The launcher relation's membership enforcer, the {@code ProjectionMembershipTest} shape for
 * the launcher family: over a composite fixture exercising every declared minting kind, the
 * producer's declared membership ({@link LauncherCommands#MINTING_KINDS}) is bound to observed
 * minting in both directions (observed a subset of declared; declared minus observed empty),
 * and the relation's coordinate set equals the accessor-derived covered set, so neither the
 * declaration nor {@link LauncherCommands#covers} can drift from the dispatch they sit beside.
 * A family member silently ceasing to produce a row is a census mismatch here, not an
 * unquantified gap.
 */
@PipelineTier
class LauncherMembershipTest {

    // Every declared minting kind in one fixture: the four migrated root kinds (plain table,
    // routine chain, discriminated interface, keyed lookup), the three batched child kinds
    // (plain split, split lookup at list-per-key cardinality, split pivot), both @service child
    // kinds (table-returning and scalar), and all four reentry-carrying DML return arms. The
    // encoded DELETE is the deliberate non-member witness: present in the model, no row.
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

    private static LauncherRelation produce(GraphitronSchema schema) {
        var conditions = ConditionCommands.produce(schema, DEFAULT_OUTPUT_PACKAGE);
        return LauncherCommands.produce(schema, conditions, DEFAULT_OUTPUT_PACKAGE);
    }

    /** The declared key of one classified field: leaf class, or DML leaf plus return arm. */
    private static MintingKind mintingKindOf(GraphitronField field) {
        if (field instanceof MutationField.DmlTableField dml) {
            return new MintingKind.DmlReturn(dml.returnExpression().getClass());
        }
        return new MintingKind.Leaf(field.getClass());
    }

    @Test
    void censusMatchesObservedMintingInBothDirections() {
        var schema = TestSchemaHelper.buildSchema(SDL);
        var relation = produce(schema);

        // Observed: map each produced row's coordinate back to the classified field and take
        // its minting kind.
        var observed = new LinkedHashSet<MintingKind>();
        for (var row : relation.rows()) {
            var field = schema.field(row.coordinate().getTypeName(), row.coordinate().getFieldName());
            assertThat(field)
                .as("row %s must map back to a classified model field", row.coordinate())
                .isNotNull();
            observed.add(mintingKindOf(field));
        }

        assertThat(observed)
            .as("every observed minting kind is declared in MINTING_KINDS")
            .isSubsetOf(LauncherCommands.MINTING_KINDS);

        var unexercised = new ArrayList<>(LauncherCommands.MINTING_KINDS);
        unexercised.removeAll(observed);
        assertThat(unexercised)
            .as("the composite fixture exercises every declared minting kind, so the declared"
                + " set has no unobserved residue; a new declared kind must extend the fixture"
                + " (or enumerate itself here with a prose reason)")
            .isEmpty();
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
            .filter(LauncherCommands::covers)
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
     * The schema-free walk's positive direction: fed one instance of every declared non-DML
     * member (the fixture's classified fields, so no hand-built records drift from the
     * classifier), {@link LauncherCommands#produceWithoutSchema} serves exactly the coordinates
     * the declared membership's non-DML view admits. The DML companions are deliberately outside
     * the schema-free walk (no write, no captured keys), which is why the expected set filters
     * them out.
     */
    @Test
    void produceWithoutSchemaServesExactlyTheDeclaredNonDmlMembers() {
        var schema = TestSchemaHelper.buildSchema(SDL);
        List<GraphitronField> fields = List.copyOf(schema.fields().values());
        var relation = LauncherCommands.produceWithoutSchema(fields, DEFAULT_OUTPUT_PACKAGE);

        var expected = fields.stream()
            .filter(LauncherCommands::covers)
            .filter(f -> !(f instanceof MutationField.DmlTableField))
            .map(f -> ((OutputField) f).qualifiedName())
            .toList();
        assertThat(expected)
            .as("the fixture must exercise every non-DML declared member (%d leaves)",
                LauncherCommands.MINTING_KINDS.size() - 4)
            .hasSize(9);
        assertThat(relation.rows())
            .extracting(r -> r.coordinate().getTypeName() + "." + r.coordinate().getFieldName())
            .containsExactlyInAnyOrderElementsOf(expected);
    }
}
