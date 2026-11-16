package no.sikt.graphitron.model;

import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.model.test.EntryFamilyFixture;
import no.sikt.graphitron.model.test.FactGraphs;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holds the {@code defects/entry-family} document to the claim its readers rest on: that it
 * populates the whole as-written half of the {@code graphitron_} family, from two sources, so that a
 * gate over that half cannot pass by agreeing about empty relations.
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
 * entry holds a row under this document's graph, and some of them hold rows from both of its files.
 */
class EntryFamilyCoverageTest {

    /** The graph whose rows are counted, read through the population rather than captured here. */
    static final String DOCUMENT = "defects/entry-family";

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    @Test
    @DisplayName("the document writes a row into every entry relation")
    void theDocumentPopulatesTheWholeEntryHalf() {
        List<String> empty = FACTS.read(DOCUMENT, graph -> {
            var found = new ArrayList<String>();
            for (String relation : EntryFamilyFixture.entryRelations()) {
                Table<?> table = table(relation);
                if (graph.handle().dsl().fetchCount(table, graphOf(table, graph.handle())) == 0) {
                    found.add(relation);
                }
            }
            return found;
        });
        assertThat(empty)
            .as("an entry relation the document never populates is one that every gate over this half "
                + "passes vacuously on; apply the directive that writes it")
            .isEmpty();
    }

    @Test
    @DisplayName("entry relations hold rows from both files, so a per-source delete has a subject")
    void theOverlapBetweenTheTwoFilesIsReal() {
        List<String> shared = FACTS.read(DOCUMENT, graph -> {
            var found = new ArrayList<String>();
            for (String relation : EntryFamilyFixture.entryRelations()) {
                if (sourcesOf(graph.handle(), relation).size() > 1) {
                    found.add(relation);
                }
            }
            return found;
        });
        assertThat(shared)
            .as("deleting one source's rows can only be observed on a relation holding rows from "
                + "two, so the document writes some of them from both files on purpose")
            .isNotEmpty();
    }

    /** The distinct files a relation's rows in this graph were written from, empty where it records none. */
    private static Set<String> sourcesOf(StoreHandle handle, String relation) {
        Table<?> table = table(relation);
        Field<?> sourceName = table.field("SOURCE_NAME");
        if (sourceName == null) {
            return Set.of();
        }
        return Set.copyOf(handle.dsl().selectDistinct(sourceName).from(table)
            .where(graphOf(table, handle), sourceName.isNotNull())
            .fetch(sourceName, String.class));
    }

    private static Condition graphOf(Table<?> table, StoreHandle handle) {
        return table.field("GRAPH_NAME", String.class).eq(handle.graphName());
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
