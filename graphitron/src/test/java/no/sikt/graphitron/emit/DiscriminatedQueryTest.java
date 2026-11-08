package no.sikt.graphitron.emit;

import no.sikt.graphitron.model.test.FactGraphs;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DiscriminatedQuery}'s rows over the discriminated-interface fact document: the column that
 * tells the shared table's rows apart, each participant with its value, and a participant's field on
 * another table with the one step it is read through and the column read there.
 */
@PipelineTier
class DiscriminatedQueryTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private static DiscriminatedQuery.Row content() {
        return FACTS.read("scenarios/discriminated-interface", graph ->
            DiscriminatedQuery.read(graph.handle(), Map.of("Query.content", "Content")).get("Query.content"));
    }

    @Test
    void eachParticipantIsToldApartByItsValueOfTheSharedColumn() {
        var row = content();
        assertThat(row.refusal()).isEqualTo(Optional.empty());
        assertThat(row.discriminatorColumn()).isEqualTo("content_type");
        assertThat(row.participants()).extracting(DiscriminatedQuery.Participant::typeName,
                DiscriminatedQuery.Participant::value)
            .containsExactly(org.assertj.core.groups.Tuple.tuple("FilmContent", "FILM"),
                org.assertj.core.groups.Tuple.tuple("ShortContent", "SHORT"));
    }

    @Test
    void aParticipantsFieldOnAnotherTableIsReadThroughItsOneStep() {
        var film = content().participants().getFirst();
        assertThat(film.crossFields()).singleElement().satisfies(cross -> {
            assertThat(cross.fieldName()).isEqualTo("rating");
            assertThat(cross.table().sqlName()).isEqualTo("film");
            assertThat(cross.on()).containsExactly(new DiscriminatedQuery.StepColumn("FILM_ID", "FILM_ID"));
            assertThat(cross.columnJavaName()).isEqualTo("RATING");
        });
        assertThat(content().participants().getLast().crossFields()).isEqualTo(List.of());
    }
}
