package no.sikt.graphitron.model.derive;

import no.sikt.graphitron.model.test.FactGraphs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every upsert a capture runs finds the row it updates through one key.
 *
 * <p>Left to choose, jOOQ's {@code onDuplicateKeyUpdate} on a table with a unique key beside its
 * primary key renders H2's {@code MERGE} with an {@code ON} that matches either key, and an
 * {@code OR} finds no index: the statement scans the whole table, every graph's rows, on each
 * capture, which is how a stage's time comes to grow with the graphs in a store. Naming the
 * conflict key with {@code onConflict} gives the {@code ON} one key and the plan an index. Read off
 * the statements every capture of the fact documents actually ran
 * ({@link FactGraphs.Reader#statementTexts}), so no upsert is missed for being spelled somewhere
 * this gate does not look, nor for being reached only by a shape one document states.
 */
class UpsertKeyGateTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    /** Upserts known to match on either key when this gate landed, each with why it stands. */
    private static final Map<String, String> KNOWN = Map.of(
        "GRAPHITRON_FEDERATION_DEFINITION", "the unique local name is not derived from the key, so "
            + "naming the key turns a clash on it from an update into a violation; decided per table",
        "GRAPHITRON_AST_REFERENCE_ELEMENT_ENTRY", "keyed by the element's position and unique by the "
            + "application's and the ordinal, neither derived from the other; decided per table",
        "GRAPHITRON_AST_ERROR_HANDLER_ENTRY", "keyed by the element's position and unique by the "
            + "application's and the ordinal, neither derived from the other; decided per table");

    private static final Pattern MERGE = Pattern.compile(
        "(?is)^merge into \"?PUBLIC\"?\\.\"?(\\w+)\"?.*?\\)\\s+\"?t\"?\\s+on\\s+(.*?)\\s+when\\s+(?:not\\s+)?matched");

    @Test
    @DisplayName("no upsert a capture runs matches on either of two keys")
    void everyUpsertNamesItsKey() {
        Set<String> merges = new TreeSet<>();
        Set<String> either = new TreeSet<>();
        for (var sql : FACTS.statementTexts()) {
            var m = MERGE.matcher(sql.replaceAll("\\s+", " "));
            if (m.find()) {
                merges.add(m.group(1));
                if (m.group(2).toLowerCase().contains(" or (")) {
                    either.add(m.group(1));
                }
            }
        }

        assertThat(merges).as("the captures' upserts, read off what they ran").hasSizeGreaterThan(20);
        assertThat(either)
            .as("an upsert matching on either key scans every graph's rows: name its conflict key "
                + "with onConflict")
            .isSubsetOf(KNOWN.keySet());
        assertThat(KNOWN.keySet())
            .as("a known upsert that now names its key: take it off the list")
            .isSubsetOf(either);
    }
}
