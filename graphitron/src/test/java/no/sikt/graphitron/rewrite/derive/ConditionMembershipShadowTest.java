package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.plan.ConditionCommands;
import no.sikt.graphitron.rewrite.CapturedStore;
import no.sikt.graphitron.rewrite.JooqCatalog;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.catalog.ClasspathScanner;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.classifieddsl.CorpusDocuments;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.INTENT_CONDITION_MEMBERSHIP;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shadow reader of the condition fold: whether {@code intent_condition_membership} admits the
 * same {@code (coordinate, table)} keys {@code ConditionCommands.produce} emits glue for, over real
 * captures rather than seeded rows.
 *
 * <p>It exists because that comparison used to be run by hand. Two populations were found missing
 * from the fold that way and each was filed, which is a good outcome for a diff and a bad one for a
 * method: nothing in the build re-ran it, so the relation's own comment carried a coordinate count
 * that no step recomputed and that went stale the moment either population moved. This test is that
 * diff as a gate, and it retires with the walk: the producer conversion this diff was blocking will
 * read the fold instead of the commands, at which point the two sides are one and there is nothing
 * left to compare.
 *
 * <p>What the relations return given rows is pinned where the SQL is declared, in
 * {@code no.sikt.graphitron.model.intent.ConditionMembershipTest}, and what the covered set is by an
 * independent walk over the classified fields in {@code no.sikt.graphitron.plan.ConditionMembershipTest}.
 * Neither of those can catch this one's subject, which is the fold and the producer disagreeing about
 * a coordinate both of them reach.
 *
 * <p>Comparison is by key and never by payload. Membership is presence, so what a glue-minting
 * consumer needs from the fold is the key set; which predicates a row carries and which arguments
 * carry them are the resolution relations' answers, and a comparison of those would be comparing
 * this producer against itself.
 */
@PipelineTier
class ConditionMembershipShadowTest {

    @TempDir
    Path tmp;

    /**
     * The sweep: every corpus example captured as its own graph in one store, its key set against
     * the producer's. Asserted by equality in both directions, so a coordinate the producer emits
     * glue for and the fold does not admit fails here, and so does the reverse.
     *
     * <p>The reverse direction is the one worth naming, because it is a real shape rather than a
     * symmetry for its own sake: the fold has no read-side refusal, so a coordinate whose argument
     * classification fails is admitted here with no glue behind it. On a schema that builds, that
     * population is empty, and the corpus is such a schema, which is why this direction can be
     * asserted rather than masked.
     *
     * <p>The non-vacuity floor is three, which is what the corpus reaches, and the number is worth
     * knowing before trusting this arm: the corpus documents the classification vocabulary rather
     * than the filter surface, so the sweep is a drift guard over a thin population. The case below
     * is what carries the population this test was landed for, and the floor is a ratchet to raise
     * when the corpus grows a filter example rather than a figure to leave discovered.
     */
    @Test
    void membershipAgreesWithTheProducerOverTheCorpus() {
        var ctx = testContext();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        var examples = CorpusDocuments.documents();
        int comparedKeys = 0;
        try (var captured = CapturedStore.ofCatalog(tmp, examples.getFirst().id(),
                fullSdl(examples.getFirst()), jooq, census())) {
            for (CorpusDocuments.Document example : examples.subList(1, examples.size())) {
                captured.andCatalogGraph(example.id(), fullSdl(example), jooq, census());
            }
            for (CorpusDocuments.Document example : examples) {
                var produced = produced(fullSdl(example));
                assertThat(admitted(captured.dsl(), example.id()))
                    .as("the fold's keys against the producer's (%s)", example.id())
                    .containsExactlyInAnyOrderElementsOf(produced);
                comparedKeys += produced.size();
            }
        }
        assertThat(comparedKeys)
            .as("the corpus reaches the condition producer, so the sweep pinned something")
            .isGreaterThan(2);
    }

    /**
     * The population this test was landed for: an argument whose {@code @reference} path ends in a
     * bare condition hop. The generator emits a filter predicate at exactly this coordinate, and
     * before the hop relations grew a condition arm the fold admitted nothing here, so every store
     * reader asking whether the argument contributed a predicate got a false no.
     *
     * <p>Stated as its own case beside the sweep because the corpus does not write this shape, and a
     * gate that only sweeps a corpus is only as strong as the corpus. The condition method is
     * concrete-typed on both parameters, which is what a filter site requires: it carries no
     * return-type binding for a wildcard signature to fall back on.
     */
    @Test
    void anArgumentPathEndingInAConditionHopIsInBothSides() {
        var sdl = """
            type Film @table(name: "film") { title: String }
            type FilmActor @table(name: "film_actor") { filmId: Int @field(name: "film_id") }
            type Query {
              filmsByJunction(actorId: Int @field(name: "actor_id") @reference(path: [{condition: {
                  className: "no.sikt.graphitron.rewrite.TestConditionRoutes",
                  method: "filmToFilmActor"
              }}])): [Film!]!
            }
            """;
        withCatalogStore(sdl, dsl -> assertThat(admitted(dsl, CapturedStore.GRAPH))
            .as("the filter binds on the condition's target table, and the fold says so")
            .containsExactlyInAnyOrderElementsOf(produced(sdl))
            .contains("Query.filmsByJunction|film"));
    }

    // ===== Helpers =====

    /** The fold's own keys for one graph, lower-cased so the two sides compare as values. */
    private static Set<String> admitted(DSLContext dsl, String graphName) {
        var m = INTENT_CONDITION_MEMBERSHIP;
        return new LinkedHashSet<>(dsl.select(m.fields())
            .from(m)
            .where(m.GRAPH_NAME.eq(graphName))
            .fetch(row -> key(row.get(m.TYPE_NAME), row.get(m.FIELD_NAME), row.get(m.TABLE_NAME))));
    }

    /** The producer's keys for the same SDL, read off the relation it hands its consumers. */
    private static Set<String> produced(String sdl) {
        var schema = TestSchemaHelper.buildSchema(sdl);
        return ConditionCommands.produce(schema, DEFAULT_OUTPUT_PACKAGE).rows().stream()
            .map(row -> key(row.coordinate().getTypeName(), row.coordinate().getFieldName(),
                row.table().tableName()))
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private static String key(String typeName, String fieldName, String tableName) {
        return typeName + "." + fieldName + "|" + tableName.toLowerCase(Locale.ROOT);
    }

    private static String fullSdl(CorpusDocuments.Document example) {
        return CorpusDocuments.prelude() + "\n" + example.sdl();
    }

    private void withCatalogStore(String sdl, Consumer<DSLContext> body) {
        var ctx = testContext();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        try (var captured = CapturedStore.ofCatalog(tmp, CapturedStore.GRAPH, sdl, jooq, census())) {
            body.accept(captured.dsl());
        }
    }

    /**
     * The real scan over the test classes: a condition method's route is read off its captured
     * signature, so a capture with no census resolves no condition hop at all and this test's own
     * subject would go missing rather than disagree.
     */
    private static List<CompletionData.ExternalReference> census() {
        return ClasspathScanner.scan(testClassRoot(), testContext().jooqPackage());
    }

    private static Path testClassRoot() {
        try {
            return Path.of(ConditionMembershipShadowTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("the test classes are not on a file path", e);
        }
    }
}
