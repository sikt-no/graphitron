package no.sikt.graphitron.model;

import no.sikt.graphitron.model.derive.ViewReferences;
import no.sikt.graphitron.model.test.FactStores;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

/**
 * Closes the schema around the choice the macro expansion created: whether a rule is about what an
 * author wrote or about the schema the generator works against.
 *
 * <p>Those used to be the same relation. The expansion wrote what it minted into {@code graphql_type}
 * and {@code graphql_field} beside the author's own declarations, so a reader naming either got the
 * expanded population whether or not it had decided it wanted it. The transcription now holds only
 * what the author declared and {@code intent_expanded_type} and {@code intent_expanded_field} union
 * it with what the expansion minted, which turns one relation into two and every naming of the old
 * one into a decision.
 *
 * <p>The decision is real in both directions and neither answer is the safe default. A rule
 * reporting a source position, an authored description or a declaration site wants the
 * transcription, and would be wrong to admit a type no author wrote at a line number that does not
 * exist. A rule about what the generator emits, what a field navigates to, or what class a
 * coordinate stands on wants the expanded population, and is silently short by exactly the minted
 * rows if it reads the transcription. Both faults are invisible in a green build: the relation
 * still resolves, the rule still returns rows, and only a consumer schema carrying macro
 * applications shows the difference.
 *
 * <p>So this gate does not decide which relation a view should read. It requires that every reading
 * of the transcription is one somebody put on the roster, and the roster only shrinks. A view that
 * arrives naming {@code graphql_field} is on no frozen roster and fails here, which makes the
 * decision a thing an author takes rather than a thing a reviewer has to notice. Entries leave the
 * roster as each reading is adjudicated and repointed, and the roster is the reviewer's grep query
 * in the meantime.
 *
 * <p>Read off the booted schema through {@link ViewReferences}, which parses the definition the
 * engine stored rather than the source text, so a reading reached through another view is not
 * counted here and a comment naming a relation is not mistaken for a reading of it.
 */
class ExpandedPopulationReaderGateTest {

    /** The two transcription relations the expansion used to write into. */
    private static final Set<String> TRANSCRIPTION = Set.of("graphql_type", "graphql_field");

    /**
     * The union views themselves, whose reading of the transcription is the seam rather than a
     * decision pending about it. They are excluded from the roster instead of sitting on it, so
     * that "the roster only shrinks" keeps meaning "one more reading has been adjudicated": an
     * entry here could only ever be removed by breaking the union, and
     * {@link #theExpandedViewsReadBothArms} is what holds them.
     */
    private static final Set<String> THE_UNIONS =
        Set.of("intent_expanded_type", "intent_expanded_field");

    @Test
    @DisplayName("every reading of the transcription is on the roster, and the roster only shrinks")
    void everyTranscriptionReadingIsDeclared() {
        withStore(dsl ->
            assertThat(observedReadings(dsl))
                .as("views naming graphql_type or graphql_field, against the frozen roster;"
                    + " a missing entry is a view that must declare which population it means, by"
                    + " joining the roster with a reason or by reading intent_expanded_*;"
                    + " an extra entry is a reading already repointed, whose line is now stale")
                .containsExactlyInAnyOrderElementsOf(frozenRoster()));
    }

    /**
     * The union views are the seam this whole gate exists to protect, so their own arms are pinned
     * rather than left to the roster: each must read the transcription relation it unions and the
     * minted relation beside it. A union that lost an arm would empty the roster's justification
     * without failing anything above.
     */
    @Test
    @DisplayName("each expanded view unions the transcription with what the expansion minted")
    void theExpandedViewsReadBothArms() {
        withStore(dsl -> {
            assertThat(ViewReferences.relationsReadBy(dsl, "intent_expanded_type"))
                .contains("graphql_type", "graphitron_minted_type");
            assertThat(ViewReferences.relationsReadBy(dsl, "intent_expanded_field"))
                .contains("graphql_field", "graphitron_minted_field", "graphitron_field_synthesis");
        });
    }

    /** Every view in the schema, paired with each transcription relation its definition names. */
    private static List<String> observedReadings(DSLContext dsl) {
        return views(dsl).stream()
            .filter(view -> !THE_UNIONS.contains(view))
            .flatMap(view -> ViewReferences.relationsReadBy(dsl, view).stream()
                .filter(TRANSCRIPTION::contains)
                .sorted()
                .map(relation -> view + " " + relation))
            .sorted()
            .toList();
    }

    /** The schema's views, lowercased, from the engine's own catalog. */
    private static List<String> views(DSLContext dsl) {
        return dsl.select(field(name("TABLE_NAME"), String.class))
            .from(table(name("INFORMATION_SCHEMA", "VIEWS")))
            .where(field(name("TABLE_SCHEMA"), String.class).eq("PUBLIC"))
            .fetch(r -> r.value1().toLowerCase(Locale.ROOT));
    }

    private static void withStore(Consumer<DSLContext> body) {
        try (var store = FactStores.inMemory()) {
            body.accept(store.dsl());
        }
    }

    /**
     * The frozen roster: one {@code <view> <relation>} pair per line, shrink-only, frozen
     * 2026-08-31. A {@code #} line is a reason, carried beside the entries it covers.
     */
    private static List<String> frozenRoster() {
        try (InputStream in = ExpandedPopulationReaderGateTest.class
            .getResourceAsStream("transcription-readers.txt")) {
            assertThat(in).as("transcription-readers.txt beside this test").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                .map(String::strip)
                .filter(line -> !line.isBlank() && !line.startsWith("#"))
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
