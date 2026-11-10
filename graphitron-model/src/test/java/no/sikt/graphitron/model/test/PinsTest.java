package no.sikt.graphitron.model.test;

import no.sikt.graphitron.model.test.CorpusExpectations.Block;
import no.sikt.graphitron.model.test.CorpusExpectations.Mode;
import no.sikt.graphitron.model.test.Pins.Family;
import no.sikt.graphitron.model.test.Pins.Pin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a claim pins: the behaviour the rows it matched are, in its family's kinds, and nothing for a
 * claim that matched nothing or said rows are not there.
 */
class PinsTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private static final String SERVICES = "scenarios/service-lift";
    private static final String UNRESOLVED = "defects/routine-step-unresolved";

    private static Set<Pin> pins(String path, String relation, List<String> columns, List<String> row, Mode mode) {
        return FACTS.read(path, graph -> Pins.all(Pins.of(graph.handle().dsl(),
            List.of(new Block(path, relation, columns, List.of(row), List.of(), mode)))));
    }

    @Test
    @DisplayName("a claim on the claims pins the claim's kind and its element's")
    void claimFamily() {
        assertThat(pins(SERVICES, "graphitron_claim", List.of("coordinate", "kind"),
                List.of("Query.filmById", "SERVICE"), Mode.CONTAINS))
            .containsExactly(new Pin(Family.CLAIM, "graphitron_claim", "SERVICE|FIELD"));
        assertThat(pins(SERVICES, "graphitron_producer_claim", List.of("coordinate", "kind"),
                List.of("Query.filmById", "SERVICE"), Mode.EQUALS))
            .as("a projection of the claims pins as the claims")
            .containsExactly(new Pin(Family.CLAIM, "graphitron_claim", "SERVICE|FIELD"));
        assertThat(pins(SERVICES, "graphitron_claim", List.of("coordinate", "kind"),
                List.of("Query.filmById", "LOOKUP"), Mode.ABSENT))
            .as("saying a claim is not there pins nothing").isEmpty();
    }

    @Test
    @DisplayName("a claim on the defects pins the code, the entry kind and the directive there")
    void defectFamily() {
        assertThat(pins(UNRESOLVED, "graphitron_entry_defect_site", List.of("code", "coordinate"),
                List.of("ELEMENT_UNRESOLVED", "Query.missingFilms"), Mode.CONTAINS))
            .containsExactly(new Pin(Family.DEFECT, "graphitron_entry_defect",
                "ELEMENT_UNRESOLVED|FIELD_DIRECTIVE|routine"));
        assertThat(pins(UNRESOLVED, "graphitron_entry_defect_site", List.of("code", "coordinate"),
                List.of("ELEMENT_UNRESOLVED", "Query.recentFilmsForActor"), Mode.CONTAINS))
            .as("a claim the store does not bear out pins nothing").isEmpty();
    }

    @Test
    @DisplayName("a claim on an occurrence's detail pins the occurrence through that detail")
    void occurrenceFamily() {
        assertThat(pins(SERVICES, "graphitron_occurrence_producer_result", List.of("coordinate", "shape"),
                List.of("Query.filmById", "SINGLE_RECORD"), Mode.CONTAINS))
            .containsExactly(new Pin(Family.OCCURRENCE, "graphitron_occurrence_producer_result",
                "FIELD|SERVICE|ROOT"));
        assertThat(pins(SERVICES, "graphitron_field", List.of("coordinate"),
                List.of("Query.filmById"), Mode.CONTAINS))
            .as("a relation of no family pins nothing").isEmpty();
    }

    @Test
    @DisplayName("a family is the relations declared at its anchor's grain")
    void membersShareTheGrain() {
        var members = FACTS.read(SERVICES, graph -> Pins.membersOf(graph.handle().dsl()));
        assertThat(members.get(Family.DEFECT)).contains("GRAPHITRON_ENTRY_DEFECT_SITE")
            .as("a detail keying one method of a defect is a grain of its own")
            .doesNotContain("GRAPHITRON_ENTRY_DEFECT_METHOD");
        assertThat(members.get(Family.OCCURRENCE)).contains("GRAPHITRON_OCCURRENCE_PRODUCER_RESULT")
            .as("a step of an occurrence's path keys something finer than the occurrence")
            .doesNotContain("GRAPHITRON_JOIN_PATH_STEP", "GRAPHITRON_FIELD");
        assertThat(members.get(Family.CLAIM)).contains("GRAPHITRON_PRODUCER_CLAIM");
    }

    @Test
    @DisplayName("a behaviour two documents pin is a duplicate in every family, one pinned once is not")
    void duplicates() {
        for (var family : Family.values()) {
            var shared = new Pin(family, "relation", "A");
            var own = new Pin(family, "relation", "B");
            var duplicates = Pins.duplicates(Map.of(
                "scenarios/one", Set.of(shared, own),
                "scenarios/two", Set.of(shared),
                "scenarios/three", Set.of(shared)));
            assertThat(duplicates).as(family.name()).containsOnlyKeys(shared);
            assertThat(Pins.surplus(duplicates)).as(family.name()).isEqualTo(2);
        }
    }
}
