package no.sikt.graphitron.lsp;

import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.model.boot.StoreReader;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.rewrite.NodeDeclaration;
import no.sikt.graphitron.rewrite.capture.FactCapture;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.schema.RewriteSchemaLoader;
import no.sikt.graphitron.rewrite.schema.input.SchemaInput;
import no.sikt.graphitron.rewrite.schema.input.SchemaInputAttribution;
import no.sikt.graphitron.rewrite.schema.input.SchemaSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * A booted fact store with one or more graphs captured into it, for the tests that read the store.
 *
 * <p>Stood up by real capture over an SDL fixture rather than by inserting rows, so a fixture cannot
 * encode a state capture never writes. The classpath census goes in the same way: capture takes the
 * class list as input today, so a test hands over the same references the projection-era fixtures
 * declared and the store ends up holding what a scan of those classes would have produced.
 *
 * <p>Owns the store's lifetime so a test can query after capture. {@link #handle} is over the store's
 * own connection rather than a reader's: what a provider needs is a scoped query surface, and the
 * reader's transaction and graph resolution are {@code StoreAccess}'s own business, tested through
 * {@link #reader()}.
 */
final class StoreFixture implements AutoCloseable {

    /** The graph every fixture captures under, unless a test needs to name a second one. */
    static final String GRAPH = "fixture";

    /** SDL for a fixture whose whole subject is the classpath, so its schema is beside the point. */
    private static final String PLACEHOLDER_SDL = "type Query { placeholder: Int }\n";

    private final GraphitronModelStore store;
    private final String graphName;
    private final Path file;

    private StoreFixture(GraphitronModelStore store, String graphName, Path file) {
        this.store = store;
        this.graphName = graphName;
        this.file = file;
    }

    /** Captures {@code sdl} alone: the shape for arms answered by SDL-derived facts. */
    static StoreFixture of(Path directory, String sdl) {
        return of(directory, GRAPH, sdl, List.of());
    }

    /** Captures {@code sdl} plus a classpath census: the shape for the {@code jvm_} arms. */
    static StoreFixture of(Path directory, String sdl, List<CompletionData.ExternalReference> classpath) {
        return of(directory, GRAPH, sdl, classpath);
    }

    /** An SDL fixture with nothing in it, for the arms whose whole subject is the classpath. */
    static StoreFixture ofClasspath(Path directory, List<CompletionData.ExternalReference> classpath) {
        return of(directory, GRAPH, PLACEHOLDER_SDL, classpath);
    }

    static StoreFixture of(Path directory, String graphName, String sdl,
                           List<CompletionData.ExternalReference> classpath) {
        Path file = write(directory, graphName, sdl);
        var store = GraphitronModelStore.open();
        capture(store, file, directory, graphName, classpath);
        return new StoreFixture(store, graphName, file);
    }

    /** Captures a second graph, over a schema file of its own, into this same store. */
    StoreFixture andGraph(Path directory, String otherGraph, String sdl,
                          List<CompletionData.ExternalReference> classpath) {
        capture(store, write(directory, otherGraph, sdl), directory, otherGraph, classpath);
        return this;
    }

    /**
     * Captures a second graph over the <em>same</em> schema file this fixture already captured, which
     * is the shared-file case: one document, two memberships, both true.
     */
    StoreFixture andGraphSharingTheFile(Path directory, String otherGraph) {
        capture(store, file, directory, otherGraph, List.of());
        return this;
    }

    private static void capture(GraphitronModelStore store, Path file, Path directory, String graphName,
                                List<CompletionData.ExternalReference> classpath) {
        var registry = RewriteSchemaLoader.load(List.of(SchemaSource.file(file)));
        var attribution = SchemaInputAttribution.build(List.of(SchemaInput.file(file)));
        FactCapture.capture(store.dsl(), new FactCapture.GraphIdentity(graphName, directory),
            FactCapture.SubjectConfig.none(), registry, attribution, null, classpath,
            new NodeDeclaration(null));
    }

    private static Path write(Path directory, String graphName, String sdl) {
        Path path = directory.resolve(graphName + ".graphqls");
        try {
            Files.writeString(path, sdl);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return path;
    }

    /** A reader of this store, for the cases whose subject is the read boundary rather than a query. */
    StoreReader reader() {
        return store.reader();
    }

    /** The schema file this fixture captured, spelled as the store's {@code source_name} spells it. */
    String sourceName() {
        return SchemaSource.file(file).sourceName();
    }

    /** The scoped query surface a provider takes. */
    StoreHandle handle() {
        return new StoreHandle(store.dsl(), graphName);
    }

    /** The same store seen as another graph, for asserting one graph cannot read another's rows. */
    StoreHandle handleFor(String otherGraph) {
        return new StoreHandle(store.dsl(), otherGraph);
    }

    /** A reference to a class the scan found inside a jar. */
    static CompletionData.ExternalReference jarClass(String className, List<CompletionData.Method> methods) {
        return reference(className, methods, List.of(), "/nonexistent/lib.jar");
    }

    /**
     * A reference to a class the scan found in a compiled directory, so reactor-resident rather than
     * jar-resident. The directory has to exist, since that is how capture tells the two apart.
     */
    static CompletionData.ExternalReference reactorClass(
        Path classesDirectory, String className, List<CompletionData.Method> methods
    ) {
        return reference(className, methods, List.of(), classesDirectory.toString());
    }

    /** A class carrying {@code GraphQLScalarType} constants, jar-resident like the libraries are. */
    static CompletionData.ExternalReference scalarHolder(String className, String... fieldNames) {
        return reference(className, List.of(),
            Arrays.stream(fieldNames).map(CompletionData.ScalarConstant::new).toList(),
            "/nonexistent/scalars.jar");
    }

    static CompletionData.ExternalReference reference(
        String className, List<CompletionData.Method> methods,
        List<CompletionData.ScalarConstant> scalarConstants, String sourceName
    ) {
        return new CompletionData.ExternalReference(
            className.substring(className.lastIndexOf('.') + 1), className, "",
            methods, List.of(), scalarConstants, "CLASS", sourceName);
    }

    /** A method whose descriptor is synthesised from its parameter types, enough to key it apart. */
    static CompletionData.Method method(String name, String returnType, CompletionData.Parameter... parameters) {
        return new CompletionData.Method(
            name, returnType, "", List.of(parameters), false,
            "(" + Arrays.stream(parameters).map(CompletionData.Parameter::type)
                .reduce("", String::concat) + ")" + returnType);
    }

    static CompletionData.Parameter parameter(String name, String type) {
        return new CompletionData.Parameter(name, type, null, "");
    }

    @Override
    public void close() {
        store.close();
    }
}
