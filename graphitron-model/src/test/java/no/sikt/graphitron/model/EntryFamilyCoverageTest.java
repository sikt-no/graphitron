package no.sikt.graphitron.model;

import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.EntryFamilyFixture;
import org.jooq.Field;
import org.jooq.Table;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds {@link EntryFamilyFixture} to the claim its callers rest on: that it populates the whole
 * as-written half of the {@code graphitron_} family, from two sources, so that a gate over that half
 * cannot pass by agreeing about empty relations.
 *
 * <p>The generator module's corpus-isolation differential is the case that wants it. Its scope is
 * {@code graphql_} alone today, and widening it to the as-written {@code graphitron_} relations
 * passes over 57 relations of which only eight hold a row under that gate's own fixture: the other
 * 49 agree by being empty in both arms. A differential over relations nobody wrote to is not a
 * differential, and this fixture is what turns the widening into a check rather than a count.
 *
 * <p>Three cases, and the first is what keeps the other two from rotting. The family is read off the
 * generated model rather than counted here, and the fixture's two lists have to partition it, so a
 * relation added to the family and to neither list fails this rather than slipping quietly into the
 * uncovered set. The classification is the thing being stated: an entry's rows are a function of one
 * document and nothing else, an anchor's are a function of the store, and every relation of the
 * family is one or the other.
 */
class EntryFamilyCoverageTest {

    @TempDir
    static Path directory;

    private static CapturedStore captured;

    /**
     * One capture for both of the cases that need rows, and the one boot this class is counted for
     * in {@code ThreadConfinedStore}'s budget. It is off the funnel for the reason that class states
     * for {@code LentStoreTest}: the subject is a store something else captures into, so a body
     * handed a {@link org.jooq.DSLContext} cannot ask for what it needs. What the funnel would have
     * saved is paid once here rather than per case.
     */
    private static CapturedStore store() {
        if (captured == null) {
            captured = EntryFamilyFixture.capture(directory);
        }
        return captured;
    }

    @AfterAll
    static void closeStore() {
        if (captured != null) {
            captured.close();
            captured = null;
        }
    }

    @Test
    @DisplayName("the two lists partition the graphitron family, so a new relation must be classified")
    void everyRelationOfTheFamilyIsClassifiedAsOneHalfOrTheOther() {
        var entries = Set.copyOf(EntryFamilyFixture.ENTRY_RELATIONS);
        var anchors = Set.copyOf(EntryFamilyFixture.ANCHOR_RELATIONS);

        assertThat(entries)
            .as("a relation listed twice would be covered by accident rather than by classification")
            .hasSize(EntryFamilyFixture.ENTRY_RELATIONS.size());
        assertThat(anchors).hasSize(EntryFamilyFixture.ANCHOR_RELATIONS.size());
        assertThat(entries)
            .as("no relation is both a function of one document and a function of the store")
            .doesNotContainAnyElementsOf(anchors);

        var classified = new TreeSet<>(entries);
        classified.addAll(anchors);
        assertThat(classified)
            .as("classify the new relation: an entry if its rows are a function of one document and "
                + "nothing else, an anchor if a gatherer stage joins, ranks or resolves to write it")
            .isEqualTo(family());
    }

    @Test
    @DisplayName("the fixture writes a row into every entry relation")
    void theFixturePopulatesTheWholeEntryHalf() {
        var empty = new ArrayList<String>();
        for (String relation : EntryFamilyFixture.ENTRY_RELATIONS) {
            if (store().dsl().fetchCount(table(relation)) == 0) {
                empty.add(relation);
            }
        }
        assertThat(empty)
            .as("an entry relation the fixture never populates is one that every gate over this half "
                + "passes vacuously on; apply the directive that writes it")
            .isEmpty();
    }

    @Test
    @DisplayName("entry relations hold rows from both documents, so a per-source delete has a subject")
    void theOverlapBetweenTheTwoDocumentsIsReal() {
        var shared = new ArrayList<String>();
        for (String relation : EntryFamilyFixture.ENTRY_RELATIONS) {
            if (sourcesOf(relation).size() > 1) {
                shared.add(relation);
            }
        }
        assertThat(shared)
            .as("deleting one source's rows can only be observed on a relation holding rows from "
                + "two, so the fixture writes some of them from both documents on purpose")
            .isNotEmpty();
    }

    /** Every relation of the family, read off the generated model so the count cannot drift. */
    private static Set<String> family() {
        var names = new TreeSet<String>();
        for (Table<?> table : Public.PUBLIC.getTables()) {
            String name = table.getName().toLowerCase(Locale.ROOT);
            if (name.startsWith("graphitron_")) {
                names.add(name);
            }
        }
        return names;
    }

    /** The distinct documents a relation's rows were written from, empty where it records none. */
    private static Set<String> sourcesOf(String relation) {
        Table<?> table = table(relation);
        Field<?> sourceName = table.field("SOURCE_NAME");
        if (sourceName == null) {
            return Set.of();
        }
        return Set.copyOf(store().dsl().selectDistinct(sourceName).from(table)
            .where(sourceName.isNotNull()).fetch(sourceName, String.class));
    }

    private static Table<?> table(String relation) {
        for (Table<?> table : Public.PUBLIC.getTables()) {
            if (table.getName().equalsIgnoreCase(relation)) {
                return table;
            }
        }
        throw new IllegalStateException("no such relation in the generated model: " + relation);
    }
}
