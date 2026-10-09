package no.sikt.graphitron.rewrite.capture;

import graphql.language.AstPrinter;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.model.config.RunContext;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.model.run.GraphitronStore;
import no.sikt.graphitron.model.schema.AttributedRegistry;
import no.sikt.graphitron.model.schema.SchemaError;
import no.sikt.graphitron.model.schema.SchemaLoader;
import no.sikt.graphitron.model.schema.input.SchemaInput;
import no.sikt.graphitron.model.test.CorpusDocuments;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two readings of a schema's documents, compared: {@link AttributedRegistry#load}, which reads
 * the files itself, and {@link AttributedRegistry#of} over the reading capture hands back, which is
 * what the generator now classifies.
 *
 * <p>Compared field by field, not by outcome: the registry before synthesis and after it, printed
 * definition by definition in parse order, the names the federation {@code @link} injected, what
 * the parser and the reduce refused, and the exception where either side throws. A generated
 * schema can survive a difference in any of these, which is why agreement on the generated output
 * is not agreement here.
 *
 * <p>One difference is by design and stated in its own case: a type declared twice in one
 * document is resolved by capture, which keeps the older declaration, and refused by the walk.
 *
 * <p>Two producers of one registry is what is being removed. This gate holds them equal while
 * {@code load} still has callers, and goes with it.
 */
@PipelineTier
class CapturedSchemaAgreementTest {

    private static final String PRELUDE_FILE = "schema.graphqls";

    @Test
    @DisplayName("capture's reading and the walk's agree on every corpus document")
    void theReadingsAgreeOverTheCorpus(@TempDir Path tmp) {
        var documents = CorpusDocuments.documents();
        var differed = new ArrayList<String>();
        for (var document : documents) {
            var dir = tmp.resolve(document.id());
            var files = new LinkedHashMap<String, String>();
            files.put(PRELUDE_FILE, CorpusDocuments.prelude() + document.sdl());
            var difference = difference(dir, files, Map.of());
            if (difference != null) {
                differed.add(document.id() + ": " + difference);
            }
        }
        assertThat(documents).as("the corpus loaded").isNotEmpty();
        assertThat(differed).as("over %d corpus documents", documents.size()).isEmpty();
    }

    @Test
    @DisplayName("a type declared in two files is refused the same way by both readings")
    void aCollisionAcrossFiles(@TempDir Path tmp) {
        var files = new LinkedHashMap<String, String>();
        files.put("a.graphqls", "type Query { a: Int }\ntype Shared { x: Int }\n");
        files.put("b.graphqls", "type Shared { y: Int }\n");
        assertThat(difference(tmp, files, Map.of("a.graphqls", 1, "b.graphqls", 2))).isNull();
    }

    /**
     * The one difference by design. Both readings keep the older declaration, but capture resolves
     * a document's own collision to a working schema rather than refusing the run over it, so only
     * the walk reports it.
     */
    @Test
    @DisplayName("a document that declares one type twice keeps the older declaration in both readings")
    void aCollisionInsideOneFile(@TempDir Path tmp) {
        var files = new LinkedHashMap<String, String>();
        files.put("a.graphqls", "type Query { a: Int }\ntype Twice { x: Int }\ntype Twice { y: Int }\n");
        assertThat(difference(tmp, files, Map.of()))
            .startsWith("registry errors, walk [REGISTRY TypeRedefinitionError 'Twice'")
            .endsWith("capture []");
    }

    @Test
    @DisplayName("extensions of one type across files arrive in the same order in both readings")
    void extensionsAcrossFiles(@TempDir Path tmp) {
        var files = new LinkedHashMap<String, String>();
        files.put("a.graphqls", "type Query { a: Int }\nextend type Query { b: Int }\n");
        files.put("b.graphqls", "extend type Query { c: Int }\nextend type Query { d: Int }\n");
        assertThat(difference(tmp, files, Map.of("a.graphqls", 1, "b.graphqls", 2))).isNull();
    }

    @Test
    @DisplayName("a file that does not parse is refused the same way by both readings")
    void aFileThatDoesNotParse(@TempDir Path tmp) {
        var files = new LinkedHashMap<String, String>();
        files.put("a.graphqls", "type Query { a: Int }\n");
        files.put("b.graphqls", "strayTokenHere\n");
        assertThat(difference(tmp, files, Map.of("a.graphqls", 1, "b.graphqls", 2))).isNull();
    }

    /**
     * Where the two readings differ for one set of files, or null where they agree.
     *
     * @param seconds a modification time per file, in whole seconds past a fixed instant, where
     *                the order of the files is part of what is being compared
     */
    private static String difference(Path dir, Map<String, String> files, Map<String, Integer> seconds) {
        var inputs = new ArrayList<SchemaInput>();
        try {
            Files.createDirectories(dir);
            for (var file : files.entrySet()) {
                var path = dir.resolve(file.getKey());
                Files.writeString(path, file.getValue());
                if (seconds.containsKey(file.getKey())) {
                    Files.setLastModifiedTime(path, FileTime.from(
                        Instant.parse("2026-01-01T00:00:00Z").plusSeconds(seconds.get(file.getKey()))));
                }
                inputs.add(SchemaInput.file(path));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var ctx = new RunContext(inputs, dir, "agreement", dir.resolve("generated-sources"),
            TestConfiguration.DEFAULT_OUTPUT_PACKAGE, TestConfiguration.DEFAULT_JOOQ_PACKAGE);
        var jooq = new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader());

        var walk = Reading.of(() -> AttributedRegistry.load(ctx, jooq));
        Reading capture;
        try (var captured = GraphitronStore.captured(ctx)) {
            capture = Reading.of(() -> AttributedRegistry.of(captured.schema().written(), jooq));
        }
        return walk.differenceFrom(capture);
    }

    /** Everything one reading says, as text, or the exception it threw instead. */
    private record Reading(String thrown, List<String> preSynthesis, List<String> registry,
                           Set<String> injectedNames, List<String> syntaxFailures,
                           List<String> registryErrors) {

        static Reading of(Supplier<AttributedRegistry> attribute) {
            AttributedRegistry attributed;
            try {
                attributed = attribute.get();
            } catch (RuntimeException e) {
                return new Reading(e.getClass().getName() + ": " + e.getMessage(),
                    List.of(), List.of(), Set.of(), List.of(), List.of());
            }
            return new Reading(null,
                printed(attributed.preSynthesisRegistry()),
                printed(attributed.registry()),
                attributed.injectedNames(),
                attributed.read().failures().stream()
                    .map(f -> f.sourceName() + " " + f.brief()).toList(),
                attributed.read().registryErrors().stream()
                    .map(CapturedSchemaAgreementTest::described).toList());
        }

        /** The first component the two readings differ in, or null where they agree. */
        String differenceFrom(Reading capture) {
            if (!java.util.Objects.equals(thrown, capture.thrown)) {
                return "walk threw [" + thrown + "], capture threw [" + capture.thrown + "]";
            }
            if (!injectedNames.equals(capture.injectedNames)) {
                return "injected names, walk " + injectedNames + " capture " + capture.injectedNames;
            }
            if (!syntaxFailures.equals(capture.syntaxFailures)) {
                return "syntax failures, walk " + syntaxFailures + " capture " + capture.syntaxFailures;
            }
            var pre = firstDifference(preSynthesis, capture.preSynthesis);
            if (pre != null) {
                return "before synthesis, " + pre;
            }
            var post = firstDifference(registry, capture.registry);
            if (post != null) {
                return "after synthesis, " + post;
            }
            return registryErrors.equals(capture.registryErrors) ? null
                : "registry errors, walk " + registryErrors + " capture " + capture.registryErrors;
        }
    }

    /** A registry's definitions in parse order, each printed with the source it came from. */
    private static List<String> printed(TypeDefinitionRegistry registry) {
        var printed = new ArrayList<String>();
        registry.getParseOrder().getInOrder().forEach((source, definitions) ->
            definitions.forEach(d -> printed.add(source + "\n" + AstPrinter.printAst(d))));
        return printed;
    }

    private static String described(SchemaError error) {
        return error.stage() + " " + error.errorClass() + " " + error.message();
    }

    private static String firstDifference(List<String> walk, List<String> capture) {
        for (int i = 0; i < Math.min(walk.size(), capture.size()); i++) {
            if (!walk.get(i).equals(capture.get(i))) {
                return "definition " + i + ", walk [" + walk.get(i) + "] capture [" + capture.get(i) + "]";
            }
        }
        return walk.size() == capture.size() ? null
            : "walk has " + walk.size() + " definitions, capture " + capture.size();
    }
}
