package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LiftQuery}'s rows over a captured schema: a service whose target binds the table its
 * records belong to lifts them, one record or a list, at a root or under an object, keyed by the
 * table's primary key; a service whose records go down to a payload's data field, or which returns
 * a scalar, lifts nothing. Read over the {@code scenarios/service-lift} fact document.
 */
@PipelineTier
class LiftQueryTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private static Map<String, LiftQuery.Row> rows;

    @BeforeAll
    static void read() {
        rows = FACTS.read("scenarios/service-lift", graph -> LiftQuery.read(graph.handle()).stream()
            .collect(Collectors.toMap(LiftQuery.Row::coordinate, Function.identity())));
    }

    @Test
    void aListOfRecordsOfTheBoundTableIsLiftedAtTheRoot() {
        var row = rows.get("Query.filmsByIds");
        assertThat(row).isNotNull();
        assertThat(row.list()).isTrue();
        assertThat(row.root()).isTrue();
        assertThat(row.targetType()).isEqualTo("Film");
        assertThat(row.table().sqlName()).isEqualTo("film");
        assertThat(row.table().javaFieldName()).isEqualTo("FILM");
        assertThat(row.key()).extracting(LiftQuery.KeyColumn::sqlName).containsExactly("film_id");
    }

    @Test
    void oneRecordIsLiftedAsOne() {
        var row = rows.get("Query.filmById");
        assertThat(row).isNotNull();
        assertThat(row.list()).isFalse();
    }

    @Test
    void aCompositeKeyIsReadInKeyOrder() {
        assertThat(rows.get("Query.filmActorsByIds").key())
            .extracting(LiftQuery.KeyColumn::sqlName)
            .containsExactly("actor_id", "film_id");
    }

    @Test
    void aServiceUnderAnObjectLiftsToo() {
        var row = rows.get("Film.languageByService");
        assertThat(row).isNotNull();
        assertThat(row.root()).isFalse();
        assertThat(row.table().sqlName()).isEqualTo("language");
    }

    @Test
    void recordsForAPayloadsDataFieldOrAScalarAreNoLift() {
        assertThat(rows).doesNotContainKeys("Query.filmsAsPayload", "Query.filmCount");
        assertThat(List.copyOf(rows.keySet())).containsExactlyInAnyOrder(
            "Query.filmsByIds", "Query.filmById", "Query.filmActorsByIds", "Film.languageByService",
            "Film.castMembers");
    }
}
