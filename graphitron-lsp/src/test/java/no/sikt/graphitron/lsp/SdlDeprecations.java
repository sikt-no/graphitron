package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.parsing.SchemaCoordinate;
import no.sikt.graphitron.model.capture.document.SdlCapture;
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

import static no.sikt.graphitron.model.Tables.GRAPHITRON_DEPRECATED_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DEPRECATED_DIRECTIVE_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DEPRECATED_INPUT_FIELD;

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
 * third. Capture writes all three now, so the reading is three selects and the coordinates are the
 * rows. The language server still neither parses this file nor asks this question.
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
            SdlCapture.capture(store.dsl(), new GraphIdentity(GRAPH, directory),
                config(directory), LocalDateTime.now());
            var dsl = store.dsl();
            var out = new LinkedHashSet<SchemaCoordinate>();

            var directives = GRAPHITRON_DEPRECATED_DIRECTIVE;
            dsl.select(directives.DIRECTIVE_NAME).from(directives)
                .where(directives.GRAPH_NAME.eq(GRAPH))
                .fetch(directives.DIRECTIVE_NAME)
                .forEach(name -> out.add(new SchemaCoordinate.Directive(name)));

            var arguments = GRAPHITRON_DEPRECATED_DIRECTIVE_ARGUMENT;
            dsl.select(arguments.DIRECTIVE_NAME, arguments.ARGUMENT_NAME).from(arguments)
                .where(arguments.GRAPH_NAME.eq(GRAPH))
                .forEach(row -> out.add(new SchemaCoordinate.DirectiveArg(row.value1(), row.value2())));

            var fields = GRAPHITRON_DEPRECATED_INPUT_FIELD;
            dsl.select(fields.TYPE_NAME, fields.FIELD_NAME).from(fields)
                .where(fields.GRAPH_NAME.eq(GRAPH))
                .forEach(row -> out.add(new SchemaCoordinate.InputField(row.value1(), row.value2())));

            return out;
        }
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
