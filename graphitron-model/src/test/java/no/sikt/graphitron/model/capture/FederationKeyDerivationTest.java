package no.sikt.graphitron.model.capture;

import no.sikt.graphitron.model.config.RunContext;
import no.sikt.graphitron.model.run.GraphitronStore;
import no.sikt.graphitron.model.schema.input.SchemaInput;
import no.sikt.graphitron.model.schema.input.SchemaSource;
import no.sikt.graphitron.model.test.TestRunContext;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_DIRECTIVE_APPLICATION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FEDERATION_LINK;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FEDERATION_LINK_IMPORT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_SCHEMA_PROBLEM;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Federation's node-entity rule under a configured tag, the opt-in's second arm: a run configured
 * with a tag and no authored link federates through a link of graphitron's own. Each case is a run
 * under a configuration, captured into a store of its own.
 *
 * <p>The rule over a graph's own schema is stated by fact documents: {@code defects/federation-key}
 * (keyless nodes, the stand-downs and alternatives, the minted key's arguments and ordinals),
 * {@code scenarios/federation-key-opt-out} (an authored {@code resolvable: false} reaching the
 * anchor unaltered), {@code scenarios/federation-key-inferred} and
 * {@code scenarios/federation-key-inferred-bare} (a node by inference, with a catalog and without),
 * {@code defects/federation-key-unlinked} and {@code defects/federation-key-not-the-opt-in}.
 */
class FederationKeyDerivationTest {

    /** The graph a run of {@link #context} captures, into a store of its own. */
    private static final String RUN_GRAPH = "FederationKeyDerivationTest";

    private static final String LINK =
        "extend schema @link(url: \"https://specs.apollo.dev/federation/v2.10\", import: [\"@key\"])";

    private static final String FEDERATED = LINK + """


        type Query { film: Film }

        interface Node { id: ID! }

        type Film implements Node @table(name: "film") @node {
          id: ID!
          title: String
        }
        """;

    /**
     * The opt-in's second arm: a configured tag with no {@code @link} in any document federates the
     * graph through a link of graphitron's own, which imports what graphitron applies, the key it
     * mints and the tag it configures. Driven both ways, with an authored link importing both, and
     * with one importing the key alone, which graphitron completes rather than refuses.
     */
    @Test
    @DisplayName("a configured tag with no authored link federates through a link importing what is applied")
    void aConfiguredTagIsTheOptInsSecondArm(@TempDir Path tmp) throws IOException {
        String unlinked = FEDERATED.replace(LINK, "");
        String linkedWithTag = FEDERATED.replace(LINK,
            "extend schema @link(url: \"https://specs.apollo.dev/federation/v2.10\","
                + " import: [\"@key\", \"@tag\"])");

        try (var store = GraphitronStore.captured(
                context(tmp.resolve("tagged"), unlinked, "stable")).store()) {
            assertThat(originOf(store.dsl(), RUN_GRAPH)).containsExactly("CONFIGURED");
            assertThat(mintedKeysOf(store.dsl(), RUN_GRAPH)).containsExactly("Film");
            assertThat(importsOf(store.dsl(), RUN_GRAPH)).containsExactly("@key", "@tag");
            assertThat(problemsOf(store.dsl(), RUN_GRAPH)).isEmpty();
        }
        try (var store = GraphitronStore.captured(
                context(tmp.resolve("untagged"), unlinked, null)).store()) {
            assertThat(originOf(store.dsl(), RUN_GRAPH)).isEmpty();
            assertThat(mintedKeysOf(store.dsl(), RUN_GRAPH)).isEmpty();
        }
        try (var store = GraphitronStore.captured(
                context(tmp.resolve("linked"), linkedWithTag, "stable")).store()) {
            assertThat(originOf(store.dsl(), RUN_GRAPH))
                .as("the author wrote the link, so the configuration adds none")
                .containsExactly("AUTHORED");
            assertThat(mintedKeysOf(store.dsl(), RUN_GRAPH)).containsExactly("Film");
            assertThat(importsOf(store.dsl(), RUN_GRAPH)).containsExactly("@key", "@tag");
        }
        try (var store = GraphitronStore.captured(
                context(tmp.resolve("linked-key-only"), FEDERATED, "stable")).store()) {
            assertThat(importsOf(store.dsl(), RUN_GRAPH))
                .as("the author imported the key; graphitron adds the tag it configures")
                .containsExactly("@key", "@tag");
            assertThat(problemsOf(store.dsl(), RUN_GRAPH)).isEmpty();
        }
    }

    /** What the graph's link imports, in order. */
    private static List<String> importsOf(DSLContext dsl, String graph) {
        var i = GRAPHITRON_FEDERATION_LINK_IMPORT;
        return dsl.select(i.NAME).from(i).where(i.GRAPH_NAME.eq(graph)).orderBy(i.ORDINAL)
            .fetch(i.NAME);
    }

    /** What the toolchain refused, by error class. */
    private static List<String> problemsOf(DSLContext dsl, String graph) {
        var p = GRAPHQL_SCHEMA_PROBLEM;
        return dsl.select(p.ERROR_CLASS).from(p).where(p.GRAPH_NAME.eq(graph)).fetch(p.ERROR_CLASS);
    }

    /** How the graph opts in to federation, if it does. */
    private static List<String> originOf(DSLContext dsl, String graph) {
        return dsl.select(GRAPHITRON_FEDERATION_LINK.ORIGIN).from(GRAPHITRON_FEDERATION_LINK)
            .where(GRAPHITRON_FEDERATION_LINK.GRAPH_NAME.eq(graph))
            .fetch(GRAPHITRON_FEDERATION_LINK.ORIGIN);
    }

    /** A run over one schema file, carrying {@code tag} where it is not null. */
    private static RunContext context(Path dir, String sdl, String tag) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve("schema.graphqls");
        Files.writeString(file, sdl);
        return new RunContext(
            List.of(new SchemaInput(SchemaSource.file(file), Optional.ofNullable(tag), Optional.empty())),
            dir, RUN_GRAPH, dir.resolve("out"),
            TestRunContext.OUTPUT_PACKAGE, TestRunContext.JOOQ_PACKAGE);
    }

    /** The types the rule minted a key for, by name. */
    private static List<String> mintedKeysOf(DSLContext dsl, String graph) {
        var d = GRAPHITRON_DIRECTIVE_APPLICATION;
        return dsl.select(d.COORDINATE)
            .from(d)
            .where(d.GRAPH_NAME.eq(graph), d.DIRECTIVE_NAME.eq("key"))
            .and(d.ORIGIN.eq("MINTED"))
            .orderBy(d.COORDINATE)
            .fetch(d.COORDINATE);
    }
}
