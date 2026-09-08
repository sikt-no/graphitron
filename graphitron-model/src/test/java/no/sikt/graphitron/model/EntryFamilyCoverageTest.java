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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds {@link EntryFamilyFixture} to the claim its callers rest on: that it populates the whole
 * as-written half of the {@code graphitron_} family, from two sources, so that a gate over that half
 * cannot pass by agreeing about empty relations.
 *
 * <p>The generator module's corpus-isolation differential is the case that wanted it. Its scope was
 * {@code graphql_} alone, and widening it to the as-written {@code graphitron_} relations passed
 * over 56 relations of which only eight held a row under that gate's own fixture: the other 48
 * agreed by being empty in both arms. A differential over relations nobody wrote to is not a
 * differential, and this fixture is what turned the widening into a check rather than a count.
 *
 * <p>Two cases, and there used to be a third. It held the fixture's two enumerated lists to
 * partitioning the family, so a relation added to neither had to be classified rather than slipping
 * out of every gate that read them. The suffix retired it: the halves are read off the generated
 * model by name now, so the partition is true by construction and asserting it says nothing. What
 * the suffix cannot say by itself is that a new decode relation gets named right, and that is
 * {@code EntryNamingGuardTest}'s, which holds the decode to naming only suffixed relations.
 *
 * <p>What is left here is the claim that could always only be measured: every relation named an
 * entry holds a row under this fixture, and some of them hold rows from both of its documents.
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
    @DisplayName("the fixture writes a row into every entry relation")
    void theFixturePopulatesTheWholeEntryHalf() {
        var empty = new ArrayList<String>();
        for (String relation : EntryFamilyFixture.entryRelations()) {
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
        for (String relation : EntryFamilyFixture.entryRelations()) {
            if (sourcesOf(relation).size() > 1) {
                shared.add(relation);
            }
        }
        assertThat(shared)
            .as("deleting one source's rows can only be observed on a relation holding rows from "
                + "two, so the fixture writes some of them from both documents on purpose")
            .isNotEmpty();
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
