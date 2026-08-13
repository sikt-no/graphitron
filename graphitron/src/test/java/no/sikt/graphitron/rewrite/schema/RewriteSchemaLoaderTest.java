package no.sikt.graphitron.rewrite.schema;

import graphql.language.SourceLocation;
import no.sikt.graphitron.rewrite.SchemaParseException;
import no.sikt.graphitron.rewrite.schema.input.SchemaSource;
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
import static org.assertj.core.api.Assertions.catchThrowable;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;

/**
 * Unit coverage for {@link RewriteSchemaLoader}. Exercises the build-time schema parse
 * path: auto-injection of {@code directives.graphqls}, multi-source aggregation from
 * filesystem paths, missing-source error surface, that the reader cascade closes, and the
 * per-source parse's own contract, that a rejected source subtracts nothing from its siblings.
 */
@UnitTier
class RewriteSchemaLoaderTest {

    @Test
    void aRejectedSourceDoesNotSubtractFromItsSiblings(@TempDir Path tmp) throws IOException {
        // The property the per-source split exists for. One file will not parse; the facts the
        // other files declare are still the answer to any question about the broken one, so the
        // registry must carry them, and the bundled directive vocabulary with them.
        Path good = tmp.resolve("good.graphqls");
        Files.writeString(good, """
            type Foo @table(name: "foo_tbl") {
              id: ID!
            }
            """);
        Path broken = tmp.resolve("broken.graphqls");
        Files.writeString(broken, """
            type Bar {
              id: ID!
            }
            strayTokenHere
            """);
        Path alsoGood = tmp.resolve("also-good.graphqls");
        Files.writeString(alsoGood, "type Baz { id: ID! }\n");

        var parse = RewriteSchemaLoader.parsePerSource(List.of(
            SchemaSource.file(good), SchemaSource.file(broken), SchemaSource.file(alsoGood)));

        // The sibling on each side of the broken file landed, not just the one parsed before it.
        assertThat(parse.registry().getTypeOrNull("Foo")).isNotNull();
        assertThat(parse.registry().getTypeOrNull("Baz")).isNotNull();
        assertThat(parse.registry().getDirectiveDefinition("table")).isPresent();
        // Nothing from the rejected source leaks in: its Bar parsed fine as text, but the source
        // it belongs to did not, and a half-read file is not a fact.
        assertThat(parse.registry().getTypeOrNull("Bar")).isNull();

        assertThat(parse.failures()).hasSize(1);
        var failure = parse.failures().getFirst();
        assertThat(failure.sourceName()).isEqualTo(broken.toString());
        assertThat(failure.brief()).isNotBlank();
        assertThat(failure.location()).isNotNull();
        assertThat(failure.location().getSourceName()).isEqualTo(broken.toString());
        assertThat(failure.location().getLine()).isEqualTo(4);
        // The attributed message is what load() throws, derived here rather than stored twice.
        assertThat(failure.attributedMessage())
            .isEqualTo("Schema parse failed in " + broken + " at line 4 column "
                + failure.location().getColumn() + ": " + failure.brief());
    }

    @Test
    void everyRejectedSourceIsReportedNotJustTheFirst(@TempDir Path tmp) throws IOException {
        // A whole-document parse could only ever name the first syntax error in the
        // concatenation. Per source, each rejection is its own fact, which is what lets a
        // consumer answer "does this file have a syntax error" for a file that is not the first
        // broken one.
        Path brokenA = tmp.resolve("a.graphqls");
        Files.writeString(brokenA, "type Foo { id: ID! } strayA\n");
        Path brokenB = tmp.resolve("b.graphqls");
        Files.writeString(brokenB, "type Bar { id: ID! } strayB\n");

        var parse = RewriteSchemaLoader.parsePerSource(
            List.of(SchemaSource.file(brokenA), SchemaSource.file(brokenB)));

        assertThat(parse.failures()).extracting(RewriteSchemaLoader.SyntaxFailure::sourceName)
            .containsExactly(brokenA.toString(), brokenB.toString());
        // load() collapses the set to the first in parse order; the set itself is the wider fact.
        assertThatThrownBy(() -> RewriteSchemaLoader.load(
                List.of(SchemaSource.file(brokenA), SchemaSource.file(brokenB))))
            .isInstanceOf(SchemaParseException.class)
            .hasMessageContaining(brokenA.toString());
    }

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

        var registry = RewriteSchemaLoader.load(List.of(SchemaSource.file(schemaA), SchemaSource.file(schemaB)));

        assertThat(registry.getTypeOrNull("Foo")).isNotNull();
        assertThat(registry.getTypeOrNull("Bar")).isNotNull();
        // @table comes from the auto-injected directives.graphqls; if caller had to
        // supply it, parse of schemaA would have failed with "Unknown directive '@table'".
        assertThat(registry.getDirectiveDefinition("table")).isPresent();
    }

    @Test
    void unterminatedFirstSourceDoesNotBleedSourceNameIntoSecond(@TempDir Path tmp) throws IOException {
        // Every definition must carry its own file as its source name: the tag and
        // description-note appliers key on SourceLocation.getSourceName() matching the
        // SchemaInput, and capture derives each row's source from the same field, so a
        // misattributed definition is a correctness failure. An unterminated final line is
        // the shape that historically broke it, because MultiSourceReader attributes source
        // names line-by-line and an unterminated line used to run into the next reader.
        // Parsing one source per reader is what makes the attribution structural rather than
        // defended, and this test is the ratchet on the property, not on the mechanism: the
        // first file is written WITHOUT a trailing newline (intentionally a raw string, not a
        // text block) and the second source's node must still report its own file.
        Path first = tmp.resolve("first.graphqls");
        Files.writeString(first, "type Foo { id: ID! }", StandardCharsets.UTF_8);
        assertThat(Files.readString(first)).doesNotEndWith("\n");  // pin the fixture shape

        Path second = tmp.resolve("second.graphqls");
        Files.writeString(second, """
            type Bar { id: ID! }
            """);

        var registry = RewriteSchemaLoader.load(List.of(SchemaSource.file(first), SchemaSource.file(second)));

        var bar = registry.getTypeOrNull("Bar");
        assertThat(bar).isNotNull();
        var location = bar.getSourceLocation();
        assertThat(location).isNotNull();
        assertThat(location.getSourceName()).isEqualTo(second.toString());

        // Both sides, so the assertion pins attribution rather than one file's name being
        // the one every definition happens to get.
        var foo = registry.getTypeOrNull("Foo");
        assertThat(foo).isNotNull();
        assertThat(foo.getSourceLocation().getSourceName()).isEqualTo(first.toString());
    }

    @Test
    void parseErrorThrowsSchemaParseExceptionNamingOffendingFileAndLocation(@TempDir Path tmp) throws IOException {
        // Two well-formed sources flank a malformed one; the parser sees one combined
        // input, but with trackData(true) on MultiSourceReader the SourceLocation on
        // the exception carries the source-relative file/line. RewriteSchemaLoader
        // surfaces that as a typed SchemaParseException whose message names the file;
        // otherwise users get "line N column M" with no way to know which schema file
        // is at fault, buried under the dev-loop infrastructure stack trace.
        Path good = tmp.resolve("good.graphqls");
        Files.writeString(good, "type Foo { id: ID! }\n");
        Path broken = tmp.resolve("broken.graphqls");
        Files.writeString(broken, """
            type Bar {
              id: ID!
            }
            strayTokenHere
            """);

        Throwable thrown = catchThrowable(
            () -> RewriteSchemaLoader.load(List.of(SchemaSource.file(good), SchemaSource.file(broken))));

        assertThat(thrown).isInstanceOf(SchemaParseException.class);
        SchemaParseException ex = (SchemaParseException) thrown;

        // The structured location is carried for the deferred LSP-squiggle surface;
        // it names the offending file with source-relative coordinates. Pin the line, not
        // just the source name: the location is the one non-derivable field this exception
        // carries (the brief is a slice of getMessage()), so its correctness is what makes
        // carrying it worthwhile. strayTokenHere sits on line 4 of broken.graphqls, and
        // trackData(true) gives source-relative coordinates, so the location must point there.
        SourceLocation location = ex.location();
        assertThat(location).isNotNull();
        assertThat(location.getSourceName()).isEqualTo(broken.toString());
        assertThat(location.getLine()).isEqualTo(4);
        assertThat(ex.brief()).isNotBlank();

        // getMessage() is the file-attributed one-liner, NOT a wrapper / count string.
        // The dev goal's quiet catch(RuntimeException) paths print getMessage() verbatim,
        // so this format is what keeps file:line:col attribution on those paths. Pinning
        // it to the location + brief is the regression guard for that contract.
        String expected = "Schema parse failed in " + location.getSourceName()
            + " at line " + location.getLine() + " column " + location.getColumn()
            + ": " + ex.brief();
        assertThat(ex.getMessage()).isEqualTo(expected);
        // Upstream's "Offending token 'X' at line N column M" tail is redundant once the
        // file:line:column prefix is in place; we strip it.
        assertThat(ex.getMessage()).doesNotContain("Offending token");
    }

    @Test
    void missingSourceThrowsBareRuntimeException_notSchemaParseException() {
        // A missing / unreadable file is a genuine infrastructure failure, not an
        // author-correctable syntax error; it must stay a bare RuntimeException so the
        // dev loop keeps its diagnostic stack trace rather than the clean parse surface.
        String missing = "/nope/absolutely-does-not-exist.graphqls";
        assertThatThrownBy(() -> RewriteSchemaLoader.load(List.of(SchemaSource.file(Path.of(missing)))))
            .isInstanceOf(RuntimeException.class)
            .isNotInstanceOf(SchemaParseException.class)
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
            var registry = RewriteSchemaLoader.load(List.of(SchemaSource.file(schema)));
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
