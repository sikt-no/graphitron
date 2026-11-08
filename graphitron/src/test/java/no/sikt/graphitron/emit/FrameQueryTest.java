package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FrameQuery}'s rows over a captured schema: a service at a root has a frame with no parent;
 * one under an object takes its key off its parent's table, the table's primary key in key order;
 * one under a plain object takes the key of the table its source parameter collects, read off what
 * the parent's one member returning such a record hands back; one whose result is an outcome names
 * the outcome and its errors field. Read over the {@code scenarios/field-key-row} fact document.
 */
@PipelineTier
class FrameQueryTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private static Map<String, FrameQuery.Row> rows;

    @BeforeAll
    static void read() {
        rows = FACTS.read("scenarios/field-key-row", graph -> FrameQuery.read(graph.handle()).stream()
            .collect(Collectors.toMap(FrameQuery.Row::coordinate, Function.identity())));
    }

    @Test
    void aServiceAtARootHasNoParent() {
        var row = rows.get("Query.filmKeySummaries");
        assertThat(row).isNotNull();
        assertThat(row.root()).isTrue();
        assertThat(row.declaringType()).isEqualTo("Query");
        assertThat(row.outcome()).isEmpty();
    }

    @Test
    void aServiceUnderAnObjectTakesItsParentsKey() {
        var row = rows.get("Film.titleUppercase");
        assertThat(row).isNotNull();
        assertThat(row.root()).isFalse();
        assertThat(row.declaringType()).isEqualTo("Film");
        var parent = row.parent().orElseThrow();
        assertThat(parent.sqlName()).isEqualTo("film");
        assertThat(parent.javaFieldName()).isEqualTo("FILM");
        assertThat(parent.key()).extracting(FrameQuery.KeyColumn::sqlName).containsExactly("film_id");
    }

    @Test
    void aServiceUnderAPlainObjectTakesItsKeyThroughTheMemberReturningTheRecord() {
        // FilmKeySummary binds no table and is reached through Query.filmKeySummaries, so its field's
        // occurrence is spelled under that one.
        var row = rows.get("Query.filmKeySummaries.keyCensus");
        assertThat(row).as("frames: " + rows.keySet()).isNotNull();
        assertThat(row.root()).isFalse();
        var parent = row.parent().orElseThrow();
        assertThat(parent.sqlName()).isEqualTo("film");
        assertThat(parent.key()).extracting(FrameQuery.KeyColumn::sqlName).containsExactly("film_id");
        // The member itself is the document's accessor claim; what the frame adds is the record it reads.
        assertThat(row.accessor().orElseThrow().recordClassName()).endsWith(".FilmRecord");
    }

    @Test
    void aServiceUnderATableRowReadsNoMember() {
        assertThat(rows.get("Film.titleUppercase").accessor()).isEmpty();
    }

    @Test
    void onlyServicesHaveAFrameHere() {
        // Its films take keys off the payload too, but through no service.
        assertThat(rows).doesNotContainKey("Query.recentlyCreatedFilmsBatched.films");
    }
}
