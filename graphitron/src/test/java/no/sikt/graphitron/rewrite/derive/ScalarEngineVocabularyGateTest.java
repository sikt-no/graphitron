package no.sikt.graphitron.rewrite.derive;

import no.sikt.graphitron.rewrite.ScalarTypeResolver;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the engine scalars {@code intent_scalar_java_type} spells are the ones the generator
 * recognises, name for name and type for type.
 *
 * <p>The relation states a closed list twice, once in SQL and once in Java, and it has to: the
 * relation is evaluated inside the store, where {@link ScalarTypeResolver}'s tables are not
 * reachable, and the resolver runs where the store is not. Two spellings of a closed vocabulary
 * drift, so this reads the list back out of the view body and holds it against the resolver's.
 *
 * <p>Read off the schema resource rather than out of a booted store, which is the cheaper of two
 * correct readings and the one that needs no fixture: the view body is the text the store is built
 * from, so a list this finds is the list H2 will evaluate.
 */
@UnitTier
class ScalarEngineVocabularyGateTest {

    private static final String SCHEMA = "/no/sikt/graphitron/model/graphitron-model.sql";
    private static final String VIEW = "CREATE VIEW intent_scalar_java_type";

    /** {@code ('Int', 'java.lang.Integer')}, as the view body writes each engine pair. */
    private static final Pattern PAIR =
        Pattern.compile("\\('([A-Za-z_][A-Za-z0-9_]*)',\\s*'([\\w.]+)'\\)");

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

    /** The pairs of the view's own body, taken from the statement that declares it. */
    private static Map<String, String> engineListFromTheView() {
        String schema = schemaText();
        int start = schema.indexOf(VIEW);
        assertThat(start).as("the schema declares %s", VIEW).isNotNegative();
        String body = schema.substring(start, schema.indexOf(';', start));

        var pairs = new LinkedHashMap<String, String>();
        Matcher m = PAIR.matcher(body);
        while (m.find()) {
            pairs.put(m.group(1), m.group(2));
        }
        return pairs;
    }

    private static String schemaText() {
        try (InputStream in = ScalarEngineVocabularyGateTest.class.getResourceAsStream(SCHEMA)) {
            assertThat(in).as("the fact schema is on the classpath at %s", SCHEMA).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read " + SCHEMA, e);
        }
    }
}
