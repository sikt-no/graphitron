package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.parsing.SchemaCoordinate;
import no.sikt.graphitron.model.capture.document.GraphQLAssemblyCapture;
import no.sikt.graphitron.model.capture.document.GraphQLAstCapture;
import no.sikt.graphitron.model.capture.document.GraphQLSourceCapture;
import no.sikt.graphitron.model.capture.document.GraphitronAstCapture;
import no.sikt.graphitron.model.run.GraphIdentity;
import no.sikt.graphitron.model.run.SubjectConfig;
import no.sikt.graphitron.model.schema.input.SchemaRecipe;
import no.sikt.graphitron.model.test.FactStores;
import no.sikt.graphitron.model.test.SeededStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_DEPRECATED;

/**
 * Every coordinate graphitron's shipped {@code directives.graphqls} marks deprecated, in either of
 * the two marker conventions graphitron unifies.
 *
 * <p>Test support, and deliberately so. This used to be a method on the vocabulary, which meant the
 * language server carried a deprecation reader on the request path for a question no request ever
 * asked: nothing an editor shows is keyed on it. Its one consumer is
 * {@link SdlActionDriftTest}, whose subject is the shipped file rather than any session, so the
 * reading belongs here alongside the assertion that uses it.
 *
 * <p>It used to parse the shipped resource with graphql-java, and said why: deprecation was not a
 * fact capture wrote, a marker on a directive definition's formal argument having no relation to
 * land in, so a store-shaped reader would have answered for two of the three markers and missed the
 * third. Capture writes all three now, into one relation keyed by the coordinate, so the reading is
 * a single select and the only work left here is spelling each coordinate back into the shape this
 * module's own type wants. The language server still neither parses this file nor asks this
 * question.
 *
 * <p>Nothing is configured but an empty document: capture reads the bundled vocabulary alongside
 * whatever it is given, so the shipped markers arrive without this fixture naming the file.
 */
final class SdlDeprecations {

    private SdlDeprecations() {}

    private static final String GRAPH = "shipped-deprecations";

    /** The shipped deprecation markers, as coordinates. */
    static Set<SchemaCoordinate> shipped() {
        Path directory = temporaryDirectory();
        try (var store = FactStores.inMemory()) {
            SeededStore.seedGraph(store.dsl(), GRAPH);
            var graph = new GraphIdentity(GRAPH, directory);
            var config = config(directory);
            var readAt = LocalDateTime.now();
            var documents = GraphQLSourceCapture.capture(store.dsl(), graph, config, readAt);
            GraphQLAstCapture.capture(store.dsl(), graph, documents, readAt);
            GraphitronAstCapture.capture(store.dsl(), graph, documents, readAt);
            GraphQLAssemblyCapture.capture(store.dsl(), graph, documents, readAt);
            var dsl = store.dsl();
            var out = new LinkedHashSet<SchemaCoordinate>();
            var deprecated = GRAPHITRON_DEPRECATED;
            dsl.select(deprecated.COORDINATE).from(deprecated)
                .where(deprecated.GRAPH_NAME.eq(GRAPH))
                .fetch(deprecated.COORDINATE)
                .forEach(coordinate -> out.add(coordinateOf(coordinate)));
            return out;
        }
    }

    /**
     * One stored coordinate as the shape this module names it by.
     *
     * <p>Three cases and the grammar tells them apart, which is why the relation they come from
     * needs no column saying which of three things a row is about: an at sign opens a directive,
     * a parenthesis after one opens its argument, and anything else is a field of an input object.
     */
    private static SchemaCoordinate coordinateOf(String coordinate) {
        if (!coordinate.startsWith("@")) {
            int dot = coordinate.indexOf('.');
            return new SchemaCoordinate.InputField(
                coordinate.substring(0, dot), coordinate.substring(dot + 1));
        }
        int open = coordinate.indexOf('(');
        if (open < 0) {
            return new SchemaCoordinate.Directive(coordinate.substring(1));
        }
        return new SchemaCoordinate.DirectiveArg(coordinate.substring(1, open),
            coordinate.substring(open + 1, coordinate.indexOf(':', open)));
    }

    /** An empty document on disk, which is all capture needs to reach the bundled vocabulary. */
    private static SubjectConfig config(Path directory) {
        try {
            Files.writeString(directory.resolve("schema.graphqls"),
                "type Query { placeholder: Int }\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return SubjectConfig.of(new SchemaRecipe(directory.resolve("pom.xml"),
            List.of(SchemaRecipe.Binding.pattern("*.graphqls")), List.of("graphqls")));
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("sdl-deprecations");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
