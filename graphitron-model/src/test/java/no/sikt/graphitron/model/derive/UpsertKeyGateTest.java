package no.sikt.graphitron.model.derive;

import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.CatalogInput;
import no.sikt.graphitron.model.test.ClasspathCorpus;
import no.sikt.graphitron.model.test.ScaledSchemaFixture;
import no.sikt.graphitron.model.test.TestRunContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
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
 * the statements a capture actually runs, with H2's query statistics on, so no upsert is missed for
 * being spelled somewhere this gate does not look.
 */
class UpsertKeyGateTest {

    @TempDir
    Path tmp;

    /** Upserts known to match on either key when this gate landed, each with why it stands. */
    private static final Map<String, String> KNOWN = Map.of(
        "GRAPHITRON_FIELD_PARTICIPANT_PATH", "keyed by the participant's element position and unique "
            + "by where the element was written, neither derived from the other; decided per table",
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
        var jooq = CatalogInput.of(TestRunContext.of());
        String sdl = ScaledSchemaFixture.scaledSdl(2);
        Set<String> merges = new TreeSet<>();
        Set<String> either = new TreeSet<>();
        try (var store = CapturedStore.ownStoreOfCatalogWith(tmp, "g1", sdl, jooq,
                ClasspathCorpus.entries())) {
            var dsl = store.dsl();
            dsl.execute("SET QUERY_STATISTICS_MAX_ENTRIES 5000");
            dsl.execute("SET QUERY_STATISTICS TRUE");
            store.andCatalogGraphWith("g2", sdl, jooq, ClasspathCorpus.entries());
            for (var sql : dsl.fetch("SELECT SQL_STATEMENT FROM INFORMATION_SCHEMA.QUERY_STATISTICS")
                    .getValues(0, String.class)) {
                var m = MERGE.matcher(sql.replaceAll("\\s+", " "));
                if (m.find()) {
                    merges.add(m.group(1));
                    if (m.group(2).toLowerCase().contains(" or (")) {
                        either.add(m.group(1));
                    }
                }
            }
            dsl.execute("SET QUERY_STATISTICS FALSE");
        }

        assertThat(merges).as("the capture's upserts, read off what it ran").hasSizeGreaterThan(20);
        assertThat(either)
            .as("an upsert matching on either key scans every graph's rows: name its conflict key "
                + "with onConflict")
            .isSubsetOf(KNOWN.keySet());
        assertThat(KNOWN.keySet())
            .as("a known upsert that now names its key: take it off the list")
            .isSubsetOf(either);
    }
}
