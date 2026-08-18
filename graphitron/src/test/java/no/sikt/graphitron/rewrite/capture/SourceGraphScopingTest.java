package no.sikt.graphitron.rewrite.capture;

import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.model.read.SourceGraph;
import no.sikt.graphitron.rewrite.schema.input.SchemaSource;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.jooq.DSLContext;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Turning a source file into the graph whose facts answer for it, which is the read a consumer
 * holding a buffer has to make before it can query anything graph-keyed.
 *
 * <p>The interesting case is the one a projection could not represent at all: a schema file two
 * modules both read is a member of both graphs, and both memberships are true. The store says so
 * because {@code store_graph_source} puts no uniqueness on {@code source_name}, and the resolution
 * hands the ambiguity back rather than resolving it by row order, where the answer would depend on
 * which module's build happened to run first.
 */
@UnitTier
class SourceGraphScopingTest {

    private static final String SDL = """
        type Query { films: [Film!]! }
        type Film { title: String }
        """;

    @Test
    void aSourceOneGraphReadResolvesToThatGraphsHandle(@TempDir Path tmp) {
        var registry = CapturedStore.registryOf(tmp, SDL);
        try (var store = GraphitronModelStore.open()) {
            captureAs(store, "only-reader", tmp, registry);

            assertThat(SourceGraph.of(store.dsl(), fixtureSourceName(tmp)))
                .isInstanceOfSatisfying(SourceGraph.Scoped.class, scoped -> {
                    assertThat(scoped.handle().graphName()).isEqualTo("only-reader");
                    assertThat(scoped.handle().dsl())
                        .as("the handle reads through the surface it resolved on, not a new one")
                        .isSameAs(store.dsl());
                });
        }
    }

    @Test
    void aSourceTwoGraphsReadHandsBackBothRatherThanPickingOne(@TempDir Path tmp) {
        var registry = CapturedStore.registryOf(tmp, SDL);
        try (var store = GraphitronModelStore.open()) {
            captureAs(store, "downstream", tmp, registry);
            captureAs(store, "api", tmp, registry);

            assertThat(SourceGraph.of(store.dsl(), fixtureSourceName(tmp)))
                .isInstanceOfSatisfying(SourceGraph.Shared.class, shared -> {
                    assertThat(shared.sourceName()).isEqualTo(fixtureSourceName(tmp));
                    assertThat(shared.graphNames())
                        .as("both memberships, ordered by name so a render is stable")
                        .containsExactly("api", "downstream");
                });
        }
    }

    @Test
    void aSourceNoGraphHasReadIsUncaptured(@TempDir Path tmp) {
        var registry = CapturedStore.registryOf(tmp, SDL);
        try (var store = GraphitronModelStore.open()) {
            captureAs(store, "only-reader", tmp, registry);
            String unread = tmp.resolve("written-since-the-last-capture.graphqls").toString();

            assertThat(SourceGraph.of(store.dsl(), unread))
                .isEqualTo(new SourceGraph.Uncaptured(unread));
        }
    }

    @Test
    void manySourcesResolveInOneQueryAndEveryOneIsAnswered(@TempDir Path tmp) {
        // The read a whole recalculation makes. Resolving each document separately cost a query per
        // document before a single fact had been read, which for a drain of open files was half its
        // statements; a source no graph has read is answered rather than left out, so a caller reads
        // every source it asked about out of the result.
        var registry = CapturedStore.registryOf(tmp, SDL);
        try (var store = GraphitronModelStore.open()) {
            captureAs(store, "only-reader", tmp, registry);
            String read = fixtureSourceName(tmp);
            String unread = tmp.resolve("written-since-the-last-capture.graphqls").toString();

            var counted = new AtomicInteger();
            var dsl = counting(store, counted);
            var resolved = SourceGraph.ofAll(dsl, List.of(read, unread));

            assertThat(counted.get())
                .as("one query for the whole set, not one per source")
                .isEqualTo(1);
            assertThat(resolved).containsOnlyKeys(read, unread);
            assertThat(resolved.get(read)).isInstanceOf(SourceGraph.Scoped.class);
            assertThat(resolved.get(unread)).isEqualTo(new SourceGraph.Uncaptured(unread));
        }
    }

    @Test
    void resolvingNoSourceCostsNoQuery(@TempDir Path tmp) {
        var registry = CapturedStore.registryOf(tmp, SDL);
        try (var store = GraphitronModelStore.open()) {
            captureAs(store, "only-reader", tmp, registry);

            var counted = new AtomicInteger();
            assertThat(SourceGraph.ofAll(counting(store, counted), List.of())).isEmpty();
            assertThat(counted.get()).isZero();
        }
    }

    /** The store's own surface, seen through a context that counts the statements it executes. */
    private static DSLContext counting(GraphitronModelStore store, AtomicInteger counted) {
        return DSL.using(store.dsl().configuration()
            .derive(new DefaultExecuteListenerProvider(new ExecuteListener() {
                @Override
                public void executeStart(ExecuteContext ctx) {
                    counted.incrementAndGet();
                }
            })));
    }

    /** The fixture file's canonical name, spelled the way capture's membership row spells it. */
    private static String fixtureSourceName(Path directory) {
        return SchemaSource.file(CapturedStore.fixtureFile(directory)).sourceName();
    }

    /**
     * Captures the one fixture file under {@code graphName}. Two calls with two names is the
     * shared-file case: the same file, read by two modules, into the one store a workspace shares.
     */
    private static void captureAs(GraphitronModelStore store, String graphName, Path directory,
                                  TypeDefinitionRegistry registry) {
        FactCapture.capture(store.dsl(), new FactCapture.GraphIdentity(graphName, directory),
            FactCapture.SubjectConfig.none(), registry, CapturedStore.attributionOf(directory));
    }
}
