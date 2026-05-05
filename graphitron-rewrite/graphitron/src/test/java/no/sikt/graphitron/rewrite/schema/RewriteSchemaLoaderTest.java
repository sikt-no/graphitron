package no.sikt.graphitron.rewrite.schema;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;

/**
 * Unit coverage for {@link RewriteSchemaLoader}. Exercises the build-time schema parse
 * path: auto-injection of {@code directives.graphqls}, multi-source aggregation from
 * filesystem paths, missing-source error surface, and that the reader cascade closes.
 */
@UnitTier
class RewriteSchemaLoaderTest {

    @Test
    void loadsMultipleFilesAndAutoInjectsDirectives(@TempDir Path tmp) throws IOException {
        Path schemaA = tmp.resolve("a.graphqls");
        Files.writeString(schemaA, """
            type Foo @table(name: "foo_tbl") {
              id: ID!
            }
            """);
        Path schemaB = tmp.resolve("b.graphqls");
        Files.writeString(schemaB, """
            type Bar {
              id: ID!
            }
            """);

        var registry = RewriteSchemaLoader.load(List.of(schemaA.toString(), schemaB.toString()));

        assertThat(registry.getTypeOrNull("Foo")).isNotNull();
        assertThat(registry.getTypeOrNull("Bar")).isNotNull();
        // @table comes from the auto-injected directives.graphqls; if caller had to
        // supply it, parse of schemaA would have failed with "Unknown directive '@table'".
        assertThat(registry.getDirectiveDefinition("table")).isPresent();
    }

    @Test
    void unterminatedFirstSourceDoesNotBleedSourceNameIntoSecond(@TempDir Path tmp) throws IOException {
        // Regression ratchet for the terminated() Reader wrapper in RewriteSchemaLoader.
        // MultiSourceReader attributes source names line-by-line; without the wrapper,
        // an input whose final line is not newline-terminated bleeds into the next
        // source and the second source's first definition carries the wrong source-name.
        // This test writes a first file WITHOUT a trailing newline (intentionally a raw
        // string, not a text block) and asserts the second source's node still reports
        // its own file as the source. Deleting the terminated() wrapper would fail here.
        Path first = tmp.resolve("first.graphqls");
        Files.writeString(first, "type Foo { id: ID! }", StandardCharsets.UTF_8);
        assertThat(Files.readString(first)).doesNotEndWith("\n");  // pin the fixture shape

        Path second = tmp.resolve("second.graphqls");
        Files.writeString(second, """
            type Bar { id: ID! }
            """);

        var registry = RewriteSchemaLoader.load(List.of(first.toString(), second.toString()));

        var bar = registry.getTypeOrNull("Bar");
        assertThat(bar).isNotNull();
        var location = bar.getSourceLocation();
        assertThat(location).isNotNull();
        assertThat(location.getSourceName()).isEqualTo(second.toString());
    }

    @Test
    void parseErrorMessageNamesOffendingFileAndLocation(@TempDir Path tmp) throws IOException {
        // Two well-formed sources flank a malformed one; the parser sees one combined
        // input, but with trackData(true) on MultiSourceReader the SourceLocation on
        // the exception carries the source-relative file/line. RewriteSchemaLoader
        // must surface that into the message; otherwise users get "line N column M"
        // with no way to know which schema file is at fault.
        Path good = tmp.resolve("good.graphqls");
        Files.writeString(good, "type Foo { id: ID! }\n");
        Path broken = tmp.resolve("broken.graphqls");
        Files.writeString(broken, """
            type Bar {
              id: ID!
            }
            strayTokenHere
            """);

        assertThatThrownBy(() -> RewriteSchemaLoader.load(List.of(good.toString(), broken.toString())))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining(broken.toString())
            .hasMessageContaining("line ")
            .hasMessageContaining("column ")
            // Upstream's "Offending token 'X' at line N column M" tail is redundant
            // once the file:line:column prefix is in place; we strip it.
            .hasMessageNotContaining("Offending token");
    }

    @Test
    void missingSourceThrowsRuntimeExceptionWithPathInMessage() {
        String missing = "/nope/absolutely-does-not-exist.graphqls";
        assertThatThrownBy(() -> RewriteSchemaLoader.load(List.of(missing)))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining(missing);
    }

    @Test
    void userFileReaderIsClosedAfterLoad(@TempDir Path tmp) throws IOException {
        Path schema = tmp.resolve("a.graphqls");
        Files.writeString(schema, "type Foo { id: ID! }\n");

        // Count filesystem descriptors by repeated loads — if close() didn't cascade,
        // the JVM would eventually exhaust handles. Proxy-free black-box check: load a
        // couple of thousand times, each call opens + should-close one descriptor. If the
        // loader regresses we'd see an IOException from the filesystem; the assertion is
        // implicit in "no throw". Cheap and deterministic.
        for (int i = 0; i < 2000; i++) {
            var registry = RewriteSchemaLoader.load(List.of(schema.toString()));
            assertThat(registry.getTypeOrNull("Foo")).isNotNull();
        }
    }

    @Test
    void closeCascadeToSourcePartReaders(@TempDir Path tmp) throws IOException {
        Path schema = tmp.resolve("a.graphqls");
        Files.writeString(schema, "type Foo { id: ID! }\n");
        byte[] bytes = Files.readAllBytes(schema);

        // Direct cascade check: wrap the source stream in a close-tracking reader and
        // route it through MultiSourceReader the same way RewriteSchemaLoader does. If
        // graphql-java's MultiSourceReader.close() fails to cascade, this catches it.
        AtomicBoolean closed = new AtomicBoolean(false);
        var tracking = new java.io.InputStreamReader(
            new java.io.ByteArrayInputStream(bytes) {
                @Override
                public void close() throws IOException {
                    closed.set(true);
                    super.close();
                }
            },
            StandardCharsets.UTF_8);

        var multi = graphql.parser.MultiSourceReader.newMultiSourceReader()
            .reader(tracking, "fixture")
            .trackData(true)
            .build();
        multi.close();

        assertThat(closed).isTrue();
    }
}
