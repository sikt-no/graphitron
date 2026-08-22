package no.sikt.graphitron.model.catalog;

import no.sikt.graphitron.model.test.FactStores;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static no.sikt.graphitron.model.Tables.META_RELATION_FAMILY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

/**
 * Pins {@link GrainSentence}'s acceptance line in both directions and sweeps it over the corpus
 * it exists for, the way the renderability gate pins its own accepted subset.
 *
 * <p>The hazards below are the corpus's real ones rather than invented ones: the store's comments
 * carry dotted coordinates, package-qualified class names, a leading {@code .java} and version
 * numbers, and every one of them is a dot a naive split on period-and-anything would break at. A
 * blank-result floor alone would not catch any of them, because the blank case cannot happen and
 * the mis-split case will: what a mis-split produces is a plausible short string, which is
 * exactly what nobody notices in a rendered page.
 */
class GrainSentenceTest {

    @Test
    @DisplayName("the extractor pins its acceptance line in both directions")
    void theExtractorPinsItsAcceptanceLine() {
        // Accepted: the first sentence, terminator included, of a multi-sentence comment.
        assertThat(GrainSentence.of("A table exists. The rest follows."))
            .isEqualTo("A table exists.");
        assertThat(GrainSentence.of("What resolves here? The rest follows."))
            .isEqualTo("What resolves here?");
        assertThat(GrainSentence.of("A grain (with an aside). The rest follows."))
            .isEqualTo("A grain (with an aside).");
        assertThat(GrainSentence.of("It says \"no.\" The rest follows."))
            .isEqualTo("It says \"no.\"");
        // Accepted: a whole comment that is one sentence comes back whole.
        assertThat(GrainSentence.of("A union lists a member type."))
            .isEqualTo("A union lists a member type.");
        // Handled: the corpus's dots that end no sentence.
        assertThat(GrainSentence.of("A dotted coordinate like Type.field stays put. Rest."))
            .isEqualTo("A dotted coordinate like Type.field stays put.");
        assertThat(GrainSentence.of(
            "Derived by no.sikt.graphitron.model.derive.MaterializeDependencies at boot. Rest."))
            .isEqualTo("Derived by no.sikt.graphitron.model.derive.MaterializeDependencies"
                + " at boot.");
        assertThat(GrainSentence.of("A .java file whose declarations this store holds. Rest."))
            .isEqualTo("A .java file whose declarations this store holds.");
        assertThat(GrainSentence.of("Read through jOOQ 3.20.11 as one unit. Rest."))
            .isEqualTo("Read through jOOQ 3.20.11 as one unit.");
        // Floors: nothing to extract from.
        assertThat(GrainSentence.of(null)).isEmpty();
        assertThat(GrainSentence.of("   ")).isEmpty();
        assertThat(GrainSentence.of("no terminator at all")).isEqualTo("no terminator at all");
    }

    /**
     * The sweep the acceptance line is for: every relation the census carries yields a sentence a
     * family page could render. A prefix of the comment rather than merely shorter than it, since
     * a mis-split that dropped or reordered text would still be shorter.
     */
    @Test
    @DisplayName("every censused relation yields a plausible grain sentence")
    void everyRelationYieldsAGrainSentence() {
        try (var store = FactStores.inMemory()) {
            var comments = store.dsl()
                .select(field(name("TABLE_NAME"), String.class),
                    field(name("REMARKS"), String.class))
                .from(table(name("INFORMATION_SCHEMA", "TABLES")))
                .where(field(name("TABLE_SCHEMA"), String.class).eq("PUBLIC"))
                .fetch();
            assertThat(comments).as("relation comments swept")
                .hasSize(store.dsl().fetchCount(META_RELATION_FAMILY));

            var findings = new ArrayList<String>();
            for (var row : comments) {
                String comment = row.value2().strip();
                String grain = GrainSentence.of(comment);
                if (grain.isBlank()) {
                    findings.add(row.value1() + ": no grain sentence at all");
                    continue;
                }
                if (!comment.startsWith(grain)) {
                    findings.add(row.value1() + ": the grain sentence is not a prefix of the"
                        + " comment, so the split dropped or reordered text: " + grain);
                }
                if (grain.length() > comment.length()) {
                    findings.add(row.value1() + ": the grain sentence outruns its comment");
                }
                if (!endsASentence(grain)) {
                    findings.add(row.value1() + ": the grain sentence is unterminated, so the"
                        + " comment states no sentence the page can lift: " + grain);
                }
            }
            assertThat(findings).as("relations whose comment yields no renderable grain sentence")
                .isEmpty();
        }
    }

    private static boolean endsASentence(String sentence) {
        String tail = sentence.endsWith("\"") || sentence.endsWith("'") || sentence.endsWith(")")
            || sentence.endsWith("]") || sentence.endsWith("`")
            ? sentence.substring(0, sentence.length() - 1) : sentence;
        return tail.endsWith(".") || tail.endsWith("?") || tail.endsWith("!");
    }
}
