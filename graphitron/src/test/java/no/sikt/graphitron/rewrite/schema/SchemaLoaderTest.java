package no.sikt.graphitron.rewrite.schema;

import graphql.language.SourceLocation;
import graphql.schema.idl.errors.SchemaProblem;
import no.sikt.graphitron.model.diagnostics.SchemaParseException;
import no.sikt.graphitron.model.schema.input.SchemaSource;
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
import no.sikt.graphitron.model.schema.SchemaError;
import no.sikt.graphitron.model.schema.SchemaLoader;

/**
 * Unit coverage for {@link SchemaLoader}. Exercises the build-time schema parse
 * path: auto-injection of {@code directives.graphqls}, multi-source aggregation from
 * filesystem paths, missing-source error surface, that the reader cascade closes, and the
 * contract both reading stages here share, that a refusal subtracts nothing from what survived
 * it, at the grain of a source and at the grain of a declaration.
 */
@UnitTier
class SchemaLoaderTest {

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

        var parse = SchemaLoader.parsePerSource(List.of(
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

        var parse = SchemaLoader.parsePerSource(
            List.of(SchemaSource.file(brokenA), SchemaSource.file(brokenB)));

        assertThat(parse.failures()).extracting(SchemaLoader.SyntaxFailure::sourceName)
            .containsExactly(brokenA.toString(), brokenB.toString());
        // load() collapses the set to the first in parse order; the set itself is the wider fact.
        assertThatThrownBy(() -> SchemaLoader.load(
                List.of(SchemaSource.file(brokenA), SchemaSource.file(brokenB))))
            .isInstanceOf(SchemaParseException.class)
            .hasMessageContaining(brokenA.toString());
    }

    @Test
    void aRefusedDeclarationDoesNotSubtractFromTheRestOfItsFile(@TempDir Path tmp) throws IOException {
        // The parse split's property one level down. Two files declare Dup, so the registry can
        // admit only one; admitting definitions one at a time rather than through buildRegistry's
        // all-or-nothing throw is what keeps the loser's file-mates. Without it a single duplicated
        // type name would leave the whole source set with no registry at all, which is the same
        // cliff the whole-document parse had one stage earlier.
        Path first = tmp.resolve("first.graphqls");
        Files.writeString(first, """
            type Dup { a: String }
            type OnlyInFirst { id: ID! }
            """);
        Path second = tmp.resolve("second.graphqls");
        Files.writeString(second, """
            type Dup { b: String }
            type OnlyInSecond { id: ID! }
            """);

        var parse = SchemaLoader.parsePerSource(
            List.of(SchemaSource.file(first), SchemaSource.file(second)));

        assertThat(parse.failures()).isEmpty();
        assertThat(parse.registryErrors()).hasSize(1);
        var refusal = parse.registryErrors().getFirst();
        assertThat(refusal.stage()).isEqualTo(SchemaError.Stage.REGISTRY);
        assertThat(refusal.errorClass()).isEqualTo("TypeRedefinitionError");
        // Both files keep everything the collision did not touch, and the surviving Dup is the
        // first declaration in merge order.
        assertThat(parse.registry().getTypeOrNull("OnlyInFirst")).isNotNull();
        assertThat(parse.registry().getTypeOrNull("OnlyInSecond")).isNotNull();
        assertThat(parse.registry().getTypeOrNull("Dup").getSourceLocation().getSourceName())
            .isEqualTo(first.toString());

        // load()'s contract is unchanged: a registry refusal is still the SchemaProblem
        // graphql-java's own buildRegistry raised, carrying the same error.
        assertThatThrownBy(() -> SchemaLoader.load(
                List.of(SchemaSource.file(first), SchemaSource.file(second))))
            .isInstanceOf(SchemaProblem.class);
    }

    @Test
    void aSyntaxErrorIsReportedAheadOfTheRegistryRefusalsItCauses(@TempDir Path tmp) throws IOException {
        // Both stages refuse, and load() names the parse failure: the syntax error is the edit that
        // fixes both, and a registry refusal downstream of a file that never parsed is a
        // consequence rather than a cause.
        Path broken = tmp.resolve("broken.graphqls");
        Files.writeString(broken, "type Dup { a: String }\nstrayTokenHere\n");
        Path alsoDeclaresDup = tmp.resolve("other.graphqls");
        Files.writeString(alsoDeclaresDup, "type Dup { b: String }\ntype Dup { c: String }\n");

        var sources = List.of(SchemaSource.file(broken), SchemaSource.file(alsoDeclaresDup));
        var parse = SchemaLoader.parsePerSource(sources);

        assertThat(parse.failures()).hasSize(1);
        assertThat(parse.registryErrors()).isNotEmpty();
        assertThat(parse.rejectedAnything()).isTrue();
        assertThatThrownBy(() -> SchemaLoader.load(sources))
            .isInstanceOf(SchemaParseException.class)
            .hasMessageContaining(broken.toString());
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

        var registry = SchemaLoader.load(List.of(SchemaSource.file(schemaA), SchemaSource.file(schemaB)));

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

        var registry = SchemaLoader.load(List.of(SchemaSource.file(first), SchemaSource.file(second)));

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
        // the exception carries the source-relative file/line. SchemaLoader
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
            () -> SchemaLoader.load(List.of(SchemaSource.file(good), SchemaSource.file(broken))));

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

        // What the one-liner has to earn: the parser's explanation reaches the reader. This is the
        // whole point of the clause, and it is what a first-sentence cut silently destroyed. On
        // graphql-java's explained shapes the explanation sits between the "Invalid syntax
        // encountered." lead and the offending-token sentence, so taking the first sentence left
        // four words that say nothing an author can act on, and the dev log's one clean line was a
        // line with no content in it.
        assertThat(ex.getMessage())
            .as("the parser's own explanation, which is the only actionable part of the line")
            .contains("There are extra tokens in the text that have not been consumed")
            .contains("strayTokenHere")
            .doesNotContain("Invalid syntax encountered");
        // And the two coordinates are stated once, by our prefix, not twice.
        assertThat(ex.getMessage().split("at line", -1))
            .as("upstream's trailing coordinates are dropped, since the prefix carries them")
            .hasSize(2);
    }

    @Test
    void aShapeWithNoExplanationKeepsTheTokenThatIsItsWholeContent(@TempDir Path tmp) throws IOException {
        // graphql-java writes more than one message shape, and they disagree about what the
        // offending-token clause is for. On the shape above it is a trailing sentence after the
        // explanation; here there is no explanation and the token is the grammatical object of the
        // only sentence there is, so removing that clause would leave "Invalid syntax with" and
        // removing the token would leave a refusal that names nothing. That asymmetry is why the
        // trim subtracts only the coordinates and the lead, and never the token.
        Path unterminated = tmp.resolve("unterminated.graphqls");
        Files.writeString(unterminated, "type Foo {\n  id: ID!\n");

        var parse = SchemaLoader.parsePerSource(List.of(SchemaSource.file(unterminated)));

        assertThat(parse.failures()).hasSize(1);
        assertThat(parse.failures().getFirst().brief())
            .isEqualTo("Invalid syntax with offending token '<EOF>'");
    }

    @Test
    void missingSourceThrowsBareRuntimeException_notSchemaParseException() {
        // A missing / unreadable file is a genuine infrastructure failure, not an
        // author-correctable syntax error; it must stay a bare RuntimeException so the
        // dev loop keeps its diagnostic stack trace rather than the clean parse surface.
        String missing = "/nope/absolutely-does-not-exist.graphqls";
        assertThatThrownBy(() -> SchemaLoader.load(List.of(SchemaSource.file(Path.of(missing)))))
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
            var registry = SchemaLoader.load(List.of(SchemaSource.file(schema)));
            assertThat(registry.getTypeOrNull("Foo")).isNotNull();
        }
    }

    @Test
    void closeCascadeToSourcePartReaders(@TempDir Path tmp) throws IOException {
        Path schema = tmp.resolve("a.graphqls");
        Files.writeString(schema, "type Foo { id: ID! }\n");
        byte[] bytes = Files.readAllBytes(schema);

        // Direct cascade check: wrap the source stream in a close-tracking reader and
        // route it through MultiSourceReader the same way SchemaLoader does. If
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
