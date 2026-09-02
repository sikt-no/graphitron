package no.sikt.graphitron.rewrite.classifieddsl;

import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.rewrite.CapturedStore;
import no.sikt.graphitron.rewrite.GraphQLRewriteGenerator;
import no.sikt.graphitron.model.jooq.JooqCatalog;
import no.sikt.graphitron.model.config.RunContext;
import no.sikt.graphitron.model.diagnostics.ValidationFailedException;
import no.sikt.graphitron.rewrite.classifieddsl.CorpusDocuments.Document;
import no.sikt.graphitron.model.schema.input.SchemaInput;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static no.sikt.graphitron.model.Tables.INTENT_RESOLVED_FIELD_CLAIM;

/**
 * Renders the outcome block beside a worked example's SDL: what the pipeline makes of the coordinates
 * that document shows.
 *
 * <p><b>What it is for.</b> The SDL half of a worked example renders from the corpus, so a schema
 * pattern shown to a reader cannot drift from the fixture the classifier runs on. The other half,
 * what graphitron does with that pattern, was ungated prose beside it. This renders that half from a
 * real run, and {@link CorpusFragmentRenderer} joins the two into the fragment
 * {@link CorpusFragmentTest} holds.
 *
 * <p><b>Two refusals define the content.</b> It renders <em>no command rows</em>: row identity is
 * not a shipped obligation, and a doc-guarded verbatim command-row block would reinstate it over a
 * vocabulary that is being dismantled. It renders <em>no generated bodies</em>, per the tier rule
 * that code-string assertions on bodies are banned everywhere; a name and a parameter list is the
 * ceiling. What is left is invariant across the walk's retirement by that work's own gates:
 * verdicts land in the store as views, and output identity holds.
 *
 * <p><b>Lean by design.</b> A coordinate's verdict is what {@code intent_resolved_field_claim}
 * carries for it, classifier and tier, and nothing else. A coordinate with no row there carries no
 * claiming directive and matches no column; the block says so rather than reaching for a fuller
 * projection of the authored claims behind the resolution.
 *
 * <p><b>Not every fixture generates, and the block says which.</b> The corpus is a classification
 * corpus: a fixture earns its place by pinning a verdict, not by producing output. Some pin a
 * verdict on a pattern the generator then rejects, so there are no emitted names to render. Rather
 * than omit the column and leave a reader to guess, such a block renders the verdicts and states
 * that the pattern generates nothing. "No emitted names" becomes a visible fact about the document
 * instead of a hole in the table.
 *
 * <p><b>Clean against the oracle rule.</b> The block compares this run's own output against a
 * checked-in expectation. It never compares a store-derived answer against a walk-derived one, so
 * the walk still feeding the plan is immaterial to it.
 */
final class OutcomeBlockRenderer {

    /** The package the fixtures generate into, and the prefix a rendered unit name drops. */
    private static final String OUTPUT_PACKAGE = TestConfiguration.DEFAULT_OUTPUT_PACKAGE;

    /** What a coordinate's verdict cell says when the store derives no claim for it. */
    private static final String NO_CLAIM = "no claiming directive";

    private OutcomeBlockRenderer() {}

    /** One coordinate's row: what the store says, and what the generator emitted for it. */
    record Outcome(String coordinate, String verdict, List<String> emitted) {}

    /**
     * What one fixture produced. {@code generated} is false when the generator rejected the
     * pattern, which is a fact about the document rather than a failure of this renderer.
     */
    record Run(List<Outcome> outcomes, boolean generated) {}

    /**
     * The rendered block for one document, ready to paste under its SDL. Needs a directory to
     * capture and generate in; callers pass a JUnit temp directory.
     */
    static String render(Document document, Path workDir) throws IOException {
        Run run = run(document, workDir);

        StringBuilder out = new StringBuilder();
        // No generated-region marker here: the block is one half of a fragment, and
        // CorpusFragmentRenderer puts the marker at the fragment's head so it covers both halves.
        out.append(".What the pipeline makes of it\n");
        out.append(run.generated() ? "[cols=\"1,1,2\"]\n" : "[cols=\"1,1\"]\n");
        out.append("|===\n");
        out.append(run.generated() ? "| Coordinate | Verdict | Emitted\n\n" : "| Coordinate | Verdict\n\n");
        for (Outcome outcome : run.outcomes()) {
            out.append("| `").append(outcome.coordinate()).append("`\n")
               .append("| ").append(outcome.verdict()).append("\n");
            if (run.generated()) {
                out.append("| ").append(outcome.emitted().isEmpty()
                        ? "no method of its own"
                        : String.join(" +\n", outcome.emitted().stream().map(e -> "`" + e + "`").toList()))
                   .append("\n");
            }
            out.append("\n");
        }
        out.append("|===\n");
        if (!run.generated()) {
            out.append("""

                NOTE: This pattern classifies but does not generate: the build rejects it, so there
                are no emitted names to show. The verdicts above are what the store derives either
                way. See xref:../explanation/typed-rejection.adoc[Typed rejection] for what a
                rejection carries.
                """);
        }
        return out.toString();
    }

    /**
     * One row per coordinate the document's projection query touches, in a stable order, plus
     * whether the fixture generated at all.
     */
    static Run run(Document document, Path workDir) throws IOException {
        Map<String, Set<String>> touched =
            QueryViewRenderer.touchedCoordinates(document.sdl(), document.projection());
        Map<String, String> verdicts = verdicts(document, workDir.resolve("store"));
        Optional<Map<String, List<String>>> emitted = emitted(document, touched, workDir.resolve("generate"));

        List<Outcome> outcomes = new ArrayList<>();
        for (String parent : new TreeSet<>(touched.keySet())) {
            for (String field : new TreeSet<>(touched.get(parent))) {
                String coordinate = parent + "." + field;
                outcomes.add(new Outcome(
                    coordinate,
                    verdicts.getOrDefault(coordinate, NO_CLAIM),
                    emitted.map(byCoordinate -> byCoordinate.getOrDefault(coordinate, List.<String>of()))
                        .orElse(List.of())));
            }
        }
        return new Run(outcomes, emitted.isPresent());
    }

    /**
     * Each coordinate's resolved claim, spelled in the view's own closed vocabulary with the tier
     * that produced it. Captured through the standard catalog, which is what the corpus fixtures
     * classify against, so a column match resolves the way it does in a real run.
     */
    private static Map<String, String> verdicts(Document document, Path directory) throws IOException {
        Files.createDirectories(directory);
        RunContext ctx = TestConfiguration.testContext();
        var store = CapturedStore.ofCatalog(directory, CorpusDocuments.prelude() + "\n" + document.sdl(),
            new JooqCatalog(ctx.jooqPackage(), ctx.codegenLoader()));

        Map<String, String> verdicts = new LinkedHashMap<>();
        store.dsl()
            .select(INTENT_RESOLVED_FIELD_CLAIM.TYPE_NAME,
                    INTENT_RESOLVED_FIELD_CLAIM.FIELD_NAME,
                    INTENT_RESOLVED_FIELD_CLAIM.CLASSIFIER,
                    INTENT_RESOLVED_FIELD_CLAIM.TIER)
            .from(INTENT_RESOLVED_FIELD_CLAIM)
            .fetch()
            .forEach(row -> verdicts.put(
                row.value1() + "." + row.value2(),
                "`" + row.value3() + "`, " + row.value4().toLowerCase()));
        return verdicts;
    }

    /**
     * The methods each coordinate produces, read off a real generation run, or empty when the
     * generator rejected the pattern.
     *
     * <p>Attribution is by the single-mint naming rule rather than by a plan lookup: a
     * coordinate's DataFetcher entry method is the field's own name on its parent's fetchers
     * class, which {@code LauncherRelationClosureTest} pins at the run level. Reading it that way
     * keeps the block clear of the command tier entirely, which is the first of the two refusals.
     */
    private static Optional<Map<String, List<String>>> emitted(
            Document document, Map<String, Set<String>> touched, Path directory) throws IOException {
        Files.createDirectories(directory);
        Path schemaFile = directory.resolve("schema.graphqls");
        Files.writeString(schemaFile, CorpusDocuments.prelude() + "\n" + document.sdl());
        RunContext ctx = new RunContext(
            List.of(SchemaInput.file(schemaFile)),
            directory, "outcome-block", directory.resolve("generated-sources"),
            OUTPUT_PACKAGE, TestConfiguration.DEFAULT_JOOQ_PACKAGE);

        Map<String, ? extends Object> units;
        try {
            units = new GraphQLRewriteGenerator(ctx).generate().emittedUnits();
        } catch (ValidationFailedException rejected) {
            // A classification fixture that pins a verdict on a pattern the build refuses. The
            // block says so; every other exception is this renderer's problem and propagates.
            return Optional.empty();
        }

        Map<String, List<String>> byCoordinate = new LinkedHashMap<>();
        for (var parent : touched.entrySet()) {
            String fetchers = OUTPUT_PACKAGE + ".fetchers." + parent.getKey() + "Fetchers";
            Object unit = units.get(fetchers);
            if (!(unit instanceof no.sikt.graphitron.javapoet.TypeSpec spec)) continue;
            for (String field : parent.getValue()) {
                List<String> names = spec.methodSpecs().stream()
                    .filter(method -> method.name().equals(field))
                    .map(method -> shortName(fetchers) + "#" + method.name() + signature(method))
                    .distinct()
                    .sorted()
                    .toList();
                if (!names.isEmpty()) byCoordinate.put(parent.getKey() + "." + field, names);
            }
        }
        return Optional.of(byCoordinate);
    }

    /** The unit name without the fixture's output package, which is noise on every row. */
    private static String shortName(String fqcn) {
        return fqcn.startsWith(OUTPUT_PACKAGE + ".") ? fqcn.substring(OUTPUT_PACKAGE.length() + 1) : fqcn;
    }

    /** The parameter types only: a signature, never a body. */
    private static String signature(MethodSpec method) {
        return "(" + String.join(", ", method.parameters().stream()
            .map(parameter -> simpleTypeName(parameter.type().toString()))
            .toList()) + ")";
    }

    private static String simpleTypeName(String type) {
        int lastDot = type.lastIndexOf('.');
        return lastDot < 0 ? type : type.substring(lastDot + 1);
    }
}
