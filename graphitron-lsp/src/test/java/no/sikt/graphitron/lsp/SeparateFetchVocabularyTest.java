package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.facts.SeparateFetchRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The seal between {@code intent_field_separate_fetch}'s rule vocabulary and the words an editor
 * shows for it. The view is a union of arms each selecting one quoted rule literal, so its own
 * stored definition is the vocabulary; this reads it back and requires a rendering for every
 * literal in it.
 *
 * <p>Without this, a rule added in SQL reaches a user as a raw {@code SCREAMING_SNAKE} token and
 * nothing fails. That is the failure mode the switch-with-a-default always has, and the reason the
 * inferred-directive arm grew its own coverage test: a vocabulary that lives in one artifact and is
 * rendered in another needs a mechanism holding the two together, not a convention.
 */
class SeparateFetchVocabularyTest {

    @TempDir
    static Path tmp;

    /**
     * The quoted literal each arm selects last. {@code rule} is the view's final column, so the
     * literal standing for it is the select item that immediately precedes its arm's {@code FROM};
     * a literal anywhere else in the body is a predicate's and not a rule. The looser pattern this
     * replaces read every quoted upper-case word, which held only for as long as no arm needed to
     * compare a column against one, and an arm guarding a parent's kind needs exactly that.
     */
    private static final Pattern LITERAL = Pattern.compile("'([A-Z_]+)'\\s*FROM");

    @Test
    void everyRuleTheViewEmitsHasARendering() {
        try (var store = StoreFixture.ofClasspath(tmp, java.util.List.of())) {
            String definition = (String) store.handle().dsl()
                .fetchValue("SELECT view_definition FROM information_schema.views"
                    + " WHERE table_name = 'INTENT_FIELD_SEPARATE_FETCH'");
            assertThat(definition)
                .as("the view must be in the schema for this seal to mean anything")
                .isNotNull();

            var literals = new LinkedHashSet<String>();
            var matcher = LITERAL.matcher(definition);
            while (matcher.find()) literals.add(matcher.group(1));

            assertThat(literals)
                .as("the extraction itself, so a definition this stops matching fails loudly"
                    + " rather than passing on an empty vocabulary")
                .isNotEmpty();
            assertThat(literals)
                .as("every rule the view emits renders as words; a rule added in SQL lands here")
                .containsExactlyInAnyOrderElementsOf(
                    Arrays.stream(SeparateFetchRule.values()).map(Enum::name).toList());
        }
    }

    @Test
    void everyRenderingIsWordsRatherThanTheLiteralItStandsFor() {
        for (var rule : SeparateFetchRule.values()) {
            assertThat(rule.description())
                .as("%s", rule)
                .isNotBlank()
                .isNotEqualTo(rule.name())
                .doesNotContain("_");
        }
    }

    @Test
    void exactlyOneRuleIsUniversal() {
        // The inlay marker's silence rule turns on this being a small, deliberate set: every field
        // of a root type carries ROOT_OPERATION, and a marker repeated down a whole type is noise.
        // A second universal rule is a real possibility and a decision, not a default.
        assertThat(Arrays.stream(SeparateFetchRule.values()).filter(SeparateFetchRule::universal))
            .containsExactly(SeparateFetchRule.ROOT_OPERATION);
    }
}
