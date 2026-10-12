package no.sikt.graphitron.model.run;

import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.model.config.ClasspathEntry;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.model.schema.SchemaLoader;
import no.sikt.graphitron.model.test.AgreementCorpus;
import no.sikt.graphitron.model.test.CapturedStore;
import no.sikt.graphitron.model.test.ClasspathCorpus;
import no.sikt.graphitron.model.test.TestRunContext;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which sources a graph read, as {@code store_graph_source} records it: the run's read-set reduced
 * two ways, once by capture's own notes and once by re-enumerating the run's inputs here (the SDL
 * registry's source census, the catalog's schema packages, the classpath's declared entries).
 *
 * <p>Two graphs in one store, one catalog-bearing and one SDL-only, so "this graph's sources" is
 * distinguishable from "sources anyone read": the SDL-only graph must not inherit the sibling's
 * catalog membership, and capturing it must leave the sibling's rows standing.
 */
class ModelCaptureMembershipTest {

    @Test
    @DisplayName("graph-to-source membership equals the run's own read-set, per graph")
    void membershipEqualsTheRunsReadSet(@TempDir Path tmp) {
        var jooq = new JooqCatalog(TestRunContext.JOOQ_PACKAGE);
        var classpath = ClasspathCorpus.entries();
        String bSdl = "type Query { ping: String }";
        try (var store = CapturedStore.ofCatalogWith(tmp, "a", AgreementCorpus.SDL, jooq, classpath)) {
            var expectedA = new LinkedHashSet<>(sdlSourceNames(store.registry()));
            for (var entry : jooq.allTableEntries()) {
                expectedA.add(entry.table().getSchema().getClass().getPackageName());
            }
            expectedA.addAll(declaredEntries(classpath));
            assertThat(membership(store.dsl(), "a")).isEqualTo(expectedA);

            // The second graph is SDL alone: no catalog and no classpath, so a source in its
            // membership that is not its own file is one it inherited.
            store.andGraph("b", bSdl);
            assertThat(membership(store.dsl(), "b"))
                .as("an SDL-only capture's membership is its own file census, no inherited catalog")
                .isEqualTo(new LinkedHashSet<>(sdlSourceNames(CapturedStore.registryOf(tmp, "b", bSdl))));
            assertThat(membership(store.dsl(), "a"))
                .as("a sibling's capture leaves this graph's membership standing")
                .isEqualTo(expectedA);
        }
    }

    /** The entries a capture of {@code classpath} reads, which is every one it did not inherit. */
    private static List<String> declaredEntries(List<ClasspathEntry> classpath) {
        return classpath.stream()
            .filter(entry -> entry.origin() != ClasspathEntry.Origin.TRANSITIVE)
            .filter(entry -> Files.exists(entry.path()))
            .map(entry -> entry.path().toString())
            .toList();
    }

    /** The graph's membership rows, as a set for order-free comparison. */
    private static Set<String> membership(DSLContext dsl, String graphName) {
        return new LinkedHashSet<>(dsl
            .select(STORE_GRAPH_SOURCE.SOURCE_NAME)
            .from(STORE_GRAPH_SOURCE)
            .where(STORE_GRAPH_SOURCE.GRAPH_NAME.eq(graphName))
            .fetch(STORE_GRAPH_SOURCE.SOURCE_NAME));
    }

    /**
     * The registry's source-name census, mirroring the SDL reading's own enumeration (types,
     * scalars, directive definitions, the schema definition and its extensions, and every
     * type-extension map), which is what makes the membership comparison a second reduction of the
     * same input.
     */
    private static Set<String> sdlSourceNames(TypeDefinitionRegistry registry) {
        var names = new LinkedHashSet<String>();
        registry.schemaDefinition().ifPresent(node -> addSourceName(names, node));
        registry.getSchemaExtensionDefinitions().forEach(node -> addSourceName(names, node));
        registry.getDirectiveDefinitions().values().forEach(node -> addSourceName(names, node));
        registry.types().values().forEach(node -> addSourceName(names, node));
        registry.scalars().values().forEach(node -> addSourceName(names, node));
        registry.objectTypeExtensions().values().forEach(sites -> sites.forEach(node -> addSourceName(names, node)));
        registry.interfaceTypeExtensions().values().forEach(sites -> sites.forEach(node -> addSourceName(names, node)));
        registry.unionTypeExtensions().values().forEach(sites -> sites.forEach(node -> addSourceName(names, node)));
        registry.enumTypeExtensions().values().forEach(sites -> sites.forEach(node -> addSourceName(names, node)));
        registry.scalarTypeExtensions().values().forEach(sites -> sites.forEach(node -> addSourceName(names, node)));
        registry.inputObjectTypeExtensions().values().forEach(sites -> sites.forEach(node -> addSourceName(names, node)));
        // Every capture reads the specification's document too, and no registry holds it, the
        // schema not being composed from it.
        names.add(SchemaLoader.SPECIFICATION_SOURCE_NAME);
        return names;
    }

    private static void addSourceName(Set<String> names, graphql.language.Node<?> node) {
        if (node != null && node.getSourceLocation() != null
                && node.getSourceLocation().getSourceName() != null) {
            names.add(node.getSourceLocation().getSourceName());
        }
    }
}
