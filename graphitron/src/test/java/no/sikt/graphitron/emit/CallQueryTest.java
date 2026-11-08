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
 * {@link CallQuery}'s rows over a captured schema: a static method called on its class, an instance
 * method on an object made with the constructor taking the jOOQ context, and under an object the
 * parameter the batch's keys go into, with the type the method answers. Read over the
 * {@code scenarios/occurrence-producer} fact document.
 */
@PipelineTier
class CallQueryTest {

    @RegisterExtension
    static final FactGraphs.Reader FACTS = FactGraphs.reader();

    private static Map<String, CallQuery.Row> rows;

    @BeforeAll
    static void read() {
        rows = FACTS.read("scenarios/occurrence-producer", graph -> CallQuery.read(graph.handle()).stream()
            .collect(Collectors.toMap(CallQuery.Row::coordinate, Function.identity())));
    }

    @Test
    void aStaticMethodIsCalledOnItsClass() {
        var row = rows.get("Query.greetingByUser");
        assertThat(row.isStatic()).isTrue();
        assertThat(row.className()).isEqualTo("no.sikt.graphitron.rewrite.test.services.UserGreetingService");
        assertThat(row.resultType()).isEqualTo("java.lang.String");
        assertThat(row.source()).isEmpty();
    }

    @Test
    void anInstanceMethodRunsOnAnObjectMadeWithTheContext() {
        var row = rows.get("Query.titleByFilmIdViaInstance");
        assertThat(row.isStatic()).isFalse();
        assertThat(row.holderTakesDsl()).isTrue();
    }

    @Test
    void aBatchedCallNamesTheParameterItsKeysGoInto() {
        var row = rows.get("Film.titleLowercase");
        assertThat(row.source()).hasValueSatisfying(source -> {
            assertThat(source.position()).isZero();
            assertThat(source.parameterType()).isEqualTo("java.util.Set<org.jooq.Row1<java.lang.Integer>>");
        });
        assertThat(row.resultType()).startsWith("java.util.Map<org.jooq.Row1<java.lang.Integer>");
    }
}
