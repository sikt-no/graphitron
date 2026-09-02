package no.sikt.graphitron.rewrite.capture;

import no.sikt.graphitron.model.test.FactStores;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.JVM_CLASS;
import static no.sikt.graphitron.model.Tables.STORE_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.model.sink.FactSink;

/**
 * The property the gatherer order rests on: one gatherer's rows are in the store before the next
 * one starts, and nothing outside the load's transaction can see them until it commits.
 *
 * <p>A capture flushes per gatherer rather than once at the end, which is what lets a gatherer read
 * what ran before it through the store instead of through a parameter its caller threaded. That
 * only works if a flush is not a commit, and if a second flush can write a row whose parent the
 * first flush already wrote. Both are asserted here on the sink itself, at the two relations whose
 * foreign key makes the second observable, rather than through a capture, because no gatherer
 * exercises the read yet and a test of the mechanism should fail for the mechanism's own reasons.
 */
@PipelineTier
class GathererHandoffTest {

    private static final String SOURCE = "example.jar";

    @Test
    @DisplayName("a flushed row is readable by the next gatherer, and a later flush resolves against it")
    void aFlushedRowIsReadableByTheNextGatherer() {
        try (var store = FactStores.inMemory()) {
            store.dsl().transaction(tx -> {
                var dsl = tx.dsl();
                var sink = new FactSink(dsl, "graph");

                var source = dsl.newRecord(STORE_SOURCE);
                source.setSourceName(SOURCE);
                source.setSourceKind("JAR");
                source.setLastSeen(LocalDateTime.now());
                sink.add(source);
                sink.flush();

                // What the next gatherer sees: the row, through the store, inside the transaction
                // the load has not committed.
                assertThat(dsl.fetchCount(STORE_SOURCE, STORE_SOURCE.SOURCE_NAME.eq(SOURCE)))
                    .as("an upstream gatherer's row, read by the one that follows it")
                    .isEqualTo(1);

                // And its own write resolves against that row, the foreign key being satisfied by
                // the earlier flush rather than by both rows sharing one.
                var declared = dsl.newRecord(JVM_CLASS);
                declared.setSourceName(SOURCE);
                declared.setClassName("com.example.Thing");
                declared.setClassKind("CLASS");
                sink.add(declared);
                sink.flush();

                assertThat(dsl.fetchCount(JVM_CLASS, JVM_CLASS.SOURCE_NAME.eq(SOURCE))).isEqualTo(1);
            });
        }
    }

    @Test
    @DisplayName("a flush publishes nothing: a load that dies after one leaves the store untouched")
    void aFlushPublishesNothing() {
        try (var store = FactStores.inMemory()) {
            try {
                store.dsl().transaction(tx -> {
                    var dsl = tx.dsl();
                    var sink = new FactSink(dsl, "graph");
                    var source = dsl.newRecord(STORE_SOURCE);
                    source.setSourceName(SOURCE);
                    source.setSourceKind("JAR");
                    source.setLastSeen(LocalDateTime.now());
                    sink.add(source);
                    sink.flush();
                    throw new IllegalStateException("the load dies between two gatherers");
                });
            } catch (IllegalStateException expected) {
                // The load's own failure, thrown to reach the state this test is about.
            }
            assertThat(store.dsl().fetchCount(STORE_SOURCE)).isZero();
        }
    }
}
