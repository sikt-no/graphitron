package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.rewrite.ScalarTypeResolver;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the engine scalars {@code intent_scalar_java_type} spells are the ones the generator
 * recognises, name for name and type for type.
 *
 * <p>The relation states a closed list twice, once in SQL and once in Java, and it has to: the
 * relation is evaluated inside the store, where {@link ScalarTypeResolver}'s tables are not
 * reachable, and the resolver runs where the store is not. Two spellings of a closed vocabulary
 * drift, so this reads the list back out of the live view definition and holds it against the
 * resolver's.
 */
@UnitTier
class ScalarEngineVocabularyGateTest {

    /** {@code ('Int', 'java.lang.Integer')}, as the view body writes each engine pair. */
    private static final Pattern PAIR = Pattern.compile("\\('([A-Za-z_][A-Za-z0-9_]*)',\\s*'([\\w.]+)'\\)");

    @Test
    void theViewsEngineListIsTheResolversRecognisedScalarSpace() {
        Map<String, String> declared = engineListFromTheView();

        Map<String, String> recognised = new LinkedHashMap<>();
        for (String name : ScalarTypeResolver.specBuiltInNames()) {
            recognised.put(name, ScalarTypeResolver.coercionOutputType(name, List.of()).toString());
        }
        for (String name : ScalarTypeResolver.federationNamespaceScalarNames()) {
            recognised.put(name, ScalarTypeResolver.coercionOutputType(name, List.of()).toString());
        }

        assertThat(declared)
            .as("the view's engine arm and the resolver's closed tables name the same scalars")
            .containsExactlyInAnyOrderEntriesOf(recognised);
    }

    /** The pairs of the view's own definition, read back from the store that holds it. */
    private static Map<String, String> engineListFromTheView() {
        var pairs = new LinkedHashMap<String, String>();
        withSeededStore("g", dsl -> {
            String body = dsl.fetchValue("SELECT view_definition FROM information_schema.views"
                + " WHERE table_name = 'INTENT_SCALAR_JAVA_TYPE'").toString();
            Matcher m = PAIR.matcher(body);
            while (m.find()) {
                pairs.put(m.group(1), m.group(2));
            }
        });
        return pairs;
    }
}
