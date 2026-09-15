package no.sikt.graphitron.model;

import no.sikt.graphitron.model.capture.document.EntryKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static no.sikt.graphitron.model.test.FactStores.schemaText;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The entry-kind vocabulary as the schema states it against the enum the writer names it by.
 *
 * <p>Binding the column to an enum buys a compiler where there were nineteen string literals, and
 * costs one thing: the set is now written twice, once as a {@code CHECK} and once as constants.
 * This is what keeps the two from drifting, and the drift it catches is asymmetric. An enum
 * constant the constraint does not admit fails at the first write, loudly. A constraint value no
 * constant names fails nowhere at all: nothing can produce it through the generated column, so the
 * arm simply becomes unreachable and the schema goes on claiming it is possible.
 *
 * <p>Read out of the DDL rather than out of the running catalog, because what is being checked is
 * the text a reviewer reads. A constraint the file states and the engine silently declined to
 * enforce would satisfy a catalog query and is exactly the thing worth failing on.
 */
class EntryKindVocabularyTest {

    /** The IN-list of the entry-kind check, wherever in the DDL it is written. */
    private static final Pattern CHECK = Pattern.compile(
        "CHECK\\s*\\(entry_kind\\s+IN\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);

    @Test
    @DisplayName("the check constraint and the enum name the same nineteen kinds")
    void theSchemaAndTheEnumAgree() {
        var stated = statedKinds();
        var declared = Arrays.stream(EntryKind.values())
            .map(Enum::name)
            .collect(Collectors.toCollection(LinkedHashSet::new));

        assertThat(stated)
            .as("the DDL states an entry-kind vocabulary at all")
            .isNotEmpty();
        assertThat(stated)
            .as("kinds the constraint admits and no constant names, which nothing could ever"
                + " write, or constants the constraint would refuse, which fail at the first write")
            .containsExactlyInAnyOrderElementsOf(declared);
    }

    /** The values the constraint admits, as the file spells them. */
    private static Set<String> statedKinds() {
        var matcher = CHECK.matcher(schemaText());
        var kinds = new LinkedHashSet<String>();
        while (matcher.find()) {
            for (String value : matcher.group(1).split(",")) {
                String trimmed = value.trim().replace("'", "");
                if (!trimmed.isEmpty()) {
                    kinds.add(trimmed);
                }
            }
        }
        return kinds;
    }
}
