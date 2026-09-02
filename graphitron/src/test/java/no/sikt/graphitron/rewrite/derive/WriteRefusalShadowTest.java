package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.rewrite.CapturedStore;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.classifieddsl.CorpusDocuments;
import no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField;
import no.sikt.graphitron.model.diagnostics.UpdateRowsError;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static no.sikt.graphitron.common.configuration.TestConfiguration.testContext;
import static no.sikt.graphitron.model.Tables.INTENT_MUTATION_WRITE_REFUSAL;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shadow reader of {@code intent_mutation_write_refusal}: whether the relation refuses the
 * coordinates {@code UpdateRowsWalker} refuses, and for the same reason, over a real capture of the
 * spec-by-example corpus. The walk is the other side of every assertion here, which is what keeps
 * this half in the module the walk lives in; it retires when the walk does.
 *
 * <p>What the relation returns given rows is a question about the model's own SQL and is pinned
 * where that SQL is declared, in {@code no.sikt.graphitron.model.intent.MutationWriteRefusalTest},
 * against a store seeded row by row. What this class owes instead is that the two statements of one
 * rule do not drift apart, which is a thing hand-written seeds cannot notice: the walker's rule is
 * Java and the relation's is SQL, and each was authored against its own idea of the same predicate.
 *
 * <p>The sweep is only worth its cost while the corpus carries the shapes that discriminate. The
 * one that does here is the nullable straddling reference, whose admission turns on whether some
 * <em>other</em> carrier supplies each key column it lifts: a narrower reading of that (whole
 * carriers supply, straddlers do not) and a wider one (a non-null straddler's winning claim supplies
 * it too) agree on every payload whose identity is one whole carrier, and disagree exactly where a
 * non-null straddler is a key column's only source. {@code dml-update-straddle} carries that shape
 * and the surviving refusal beside it, so a rule spelled one way in Java and the other in SQL fails
 * here rather than at a consumer.
 *
 * <p><b>What the comparison can see, stated so the binding is not read as stronger than it is.</b>
 * Coordinates are compared both ways: every coordinate the walk refuses through an
 * {@link UpdateRowsError} arm this relation carries has refusal rows, and every coordinate with
 * refusal rows is one the walk refuses. Causes are compared one way, because the two sides do not
 * carry the same multiplicity: the classifier keeps the walk's first error and drops the rest, while
 * the relation keeps every cause of the first stage that has any. So the walk's reported cause must
 * be among the relation's causes for that coordinate, and a cause the relation reports alone is not
 * a failure here. Two arms are deliberately outside the compared set, and neither is a gap: the
 * coverage failure is the matched key's {@code UNCOVERED} verdict rather than a refusal row, and the
 * per-field admissibility arms are {@code intent_mutation_payload_refusal}'s, both of which have
 * their own relation to answer for them.
 */
@PipelineTier
class WriteRefusalShadowTest {

    @TempDir
    Path tmp;

    /**
     * The four causes this relation carries, keyed by the walker arm that produces each. An arm
     * outside this map is one another relation answers for, and is skipped rather than expected
     * absent, because the walk returning it says nothing about what this relation should hold.
     */
    private static String causeOf(UpdateRowsError error) {
        return switch (error) {
            case UpdateRowsError.MixedCarrierKeyMembership _ -> "MIXED_CARRIER_KEY_MEMBERSHIP";
            case UpdateRowsError.NullableStraddlingReference _ -> "NULLABLE_STRADDLING_REFERENCE";
            case UpdateRowsError.PlainColumnCollision _ -> "PLAIN_COLUMN_COLLISION";
            case UpdateRowsError.NoSetFields _ -> "NO_SET_FIELDS";
            case UpdateRowsError.NoUniqueKeyCoverage _ -> null;
            case UpdateRowsError.UnsupportedInputFieldShape _ -> null;
            case UpdateRowsError.OverrideConditionNotSupported _ -> null;
        };
    }

    @Test
    void writeRefusalsAgreeWithTheUpdateWalkerOverTheCorpus() {
        var ctx = testContext();
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());
        var examples = CorpusDocuments.documents();
        int comparedCoordinates = 0;
        try (var captured = CapturedStore.ofCatalog(tmp, examples.getFirst().id(),
                fullSdl(examples.getFirst()), jooq)) {
            for (CorpusDocuments.Document example : examples.subList(1, examples.size())) {
                captured.andCatalogGraph(example.id(), fullSdl(example), jooq);
            }
            var refusedByGraph = refusalsByGraph(captured.dsl());
            for (CorpusDocuments.Document example : examples) {
                var schema = TestSchemaHelper.buildSchema(fullSdl(example));

                // The walk's side: every coordinate whose rejection is one of this relation's four
                // causes, with the cause the classifier kept.
                var walked = new LinkedHashMap<String, String>();
                schema.fields().forEach((coordinate, field) -> {
                    if (!(field instanceof UnclassifiedField u)
                            || !(u.rejection() instanceof UpdateRowsError error)) {
                        return;
                    }
                    String cause = causeOf(error);
                    if (cause != null) {
                        walked.put(coordinate.getTypeName() + "." + coordinate.getFieldName(), cause);
                    }
                });

                var derived = refusedByGraph.getOrDefault(example.id(), Map.of());
                assertThat(derived.keySet())
                    .as("write-refusal coordinates vs the UPDATE walker's (%s)", example.id())
                    .containsExactlyInAnyOrderElementsOf(walked.keySet());
                walked.forEach((coordinate, cause) -> assertThat(derived.get(coordinate))
                    .as("the walker's reported cause at %s is among the relation's (%s)",
                        coordinate, example.id())
                    .contains(cause));
                comparedCoordinates += walked.size();
            }
        }
        assertThat(comparedCoordinates)
            .as("the corpus reaches this refusal at all, so the sweep pinned something rather than "
                + "agreeing on two empty sets for every document")
            .isPositive();
    }

    /** Every graph's refusal rows in one read, as coordinate to the causes reported for it. */
    private static Map<String, Map<String, Set<String>>> refusalsByGraph(DSLContext dsl) {
        var out = new LinkedHashMap<String, Map<String, Set<String>>>();
        for (var row : dsl.select(INTENT_MUTATION_WRITE_REFUSAL.GRAPH_NAME,
                                  INTENT_MUTATION_WRITE_REFUSAL.TYPE_NAME,
                                  INTENT_MUTATION_WRITE_REFUSAL.FIELD_NAME,
                                  INTENT_MUTATION_WRITE_REFUSAL.CAUSE)
                .from(INTENT_MUTATION_WRITE_REFUSAL)
                .fetch()) {
            out.computeIfAbsent(row.value1(), k -> new LinkedHashMap<>())
                .computeIfAbsent(row.value2() + "." + row.value3(), k -> new LinkedHashSet<>())
                .add(row.value4());
        }
        return out;
    }

    private static String fullSdl(CorpusDocuments.Document example) {
        return CorpusDocuments.prelude() + "\n" + example.sdl();
    }
}
