package no.sikt.graphitron.model.test;

import no.sikt.graphitron.model.schema.input.SchemaInput;
import no.sikt.graphitron.model.schema.input.SchemaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The scenario engine alone, over documents held in memory and with no store: what it renders into
 * the folder, when it captures, and which claims it hands over, in what order.
 */
class ScenarioTest {

    @TempDir
    Path folder;

    /** What a run did: each capture as the folder's files then, and each claim as it arrived. */
    private record Run(List<Capture> captures, List<Scenario.Claim> claims) {}

    private record Capture(Instant at, boolean first, Map<String, String> files, Map<String, Instant> times,
                           String configuration) {}

    @Test
    @DisplayName("the scenario's own definitions are rendered and captured at t0, lines kept and engine directives blanked")
    void ownFileAtT0() throws IOException {
        var scenario = """
            # A film.
            extend schema @withConfiguration(maxAllowedPageSize: 10)
            type Film { title: String }
            extend schema @assertEmpty(relation: "graphitron_entry_defect")
            """;
        var run = run("scenarios/film", Map.of("scenarios/film", scenario));

        assertThat(run.captures()).singleElement().satisfies(capture -> {
            assertThat(capture.at()).isEqualTo(Scenario.T0);
            assertThat(capture.first()).isTrue();
            assertThat(capture.files()).containsOnlyKeys("scenarios/film");
            var lines = capture.files().get("scenarios/film").split("\n", -1);
            assertThat(lines[0]).isEqualTo("# A film.");
            assertThat(lines[1]).isBlank();
            assertThat(lines[2]).isEqualTo("type Film { title: String }");
            assertThat(lines[3]).isBlank();
        });
        assertThat(run.claims()).singleElement().satisfies(claim -> {
            assertThat(claim.line()).isEqualTo(4);
            assertThat(claim.sdl()).contains("@assertEmpty(relation: \"graphitron_entry_defect\")");
        });
    }

    @Test
    @DisplayName("@withFacts renders each document, and first what it pulls in, as the file its entry names, and captures")
    void pullsInRecursively() throws IOException {
        var run = run("scenarios/s", Map.of(
            "scenarios/s", """
                extend schema @withFacts(docs: [{path: "layouts/core"}, {path: "shapes/b", as: "shapes/renamed"}])
                type Rating { stars: Int }
                extend schema @assertEmpty(relation: "r")
                """,
            "layouts/core", """
                extend schema @withFacts(docs: [{path: "shapes/a"}])
                extend schema @withConfiguration(maxAllowedPageSize: 3)
                extend schema @assertEmpty(relation: "never handed over")
                """,
            "shapes/a", "type Film { title: String }\n",
            "shapes/b", "type Language { name: String }\n"));

        assertThat(run.captures()).extracting(Capture::first).containsExactly(true, false);
        assertThat(run.captures().getFirst().files())
            .as("a layout of nothing but engine directives renders no file of its own")
            .containsOnlyKeys("shapes/a", "shapes/renamed");
        assertThat(run.captures().getLast().files())
            .containsOnlyKeys("scenarios/s", "shapes/a", "shapes/renamed");
        assertThat(run.claims()).extracting(Scenario.Claim::sdl).singleElement().asString().contains("\"r\"");
    }

    @Test
    @DisplayName("files rendered at one clock time are dated in the order they were written")
    void writeOrderWithinAnInstant() throws IOException {
        var run = run("scenarios/s", Map.of(
            "scenarios/s", """
                extend schema @withFacts(docs: [{path: "shapes/a"}, {path: "shapes/b"}])
                type Rating { stars: Int }
                extend schema @assertEmpty(relation: "r")
                """,
            "shapes/a", "type Film { title: String }\n",
            "shapes/b", "type Language { name: String }\n"));

        var times = run.captures().getLast().times();
        assertThat(times.get("shapes/a")).isEqualTo(Scenario.T0);
        assertThat(times.get("shapes/b")).isAfter(times.get("shapes/a"));
        assertThat(times.get("scenarios/s")).isAfter(times.get("shapes/b"))
            .isBefore(Scenario.T0.plusMillis(1));
    }

    @Test
    @DisplayName("a document pulled in gives the last version of each file it writes, and its ticks move no clock")
    void pulledInCollapsesToItsFinalVersion() throws IOException {
        var run = run("scenarios/s", Map.of(
            "scenarios/s", """
                extend schema @withFacts(docs: [{path: "scenarios/stepped"}])
                extend schema @assertEmpty(relation: "r")
                """,
            "scenarios/stepped", """
                extend schema @withFacts(docs: [{path: "shapes/v1", as: "shapes/film"}])
                extend schema @tick(ms: 60000)
                extend schema @withFacts(docs: [{path: "shapes/v2", as: "shapes/film"}])
                """,
            "shapes/v1", "type Film { title: String }\n",
            "shapes/v2", "type Film { name: String }\n"));

        assertThat(run.captures()).singleElement().satisfies(capture -> {
            assertThat(capture.files().get("shapes/film")).isEqualTo("type Film { name: String }\n");
            assertThat(capture.at()).isEqualTo(Scenario.T0);
        });
    }

    @Test
    @DisplayName("every render is captured at the clock's time, and each claim arrives once against the capture before it")
    void capturesAfterEveryRender() throws IOException {
        var run = run("scenarios/s", Map.of(
            "scenarios/s", """
                type Film { title: String }
                extend schema @assertEmpty(relation: "first") @assertEmpty(relation: "second")
                extend schema @tick(ms: 1500)
                extend schema @assertEmpty(relation: "still the first capture")
                extend schema @tick(ms: 500)
                extend schema @withFacts(docs: [{path: "shapes/a"}])
                extend schema @assertEmpty(relation: "third")
                """,
            "shapes/a", "type Language { name: String }\n"));

        assertThat(run.captures()).extracting(Capture::at)
            .containsExactly(Scenario.T0, Scenario.T0.plusMillis(2000));
        assertThat(run.claims()).extracting(Scenario.Claim::at)
            .containsExactly(Scenario.T0, Scenario.T0, Scenario.T0.plusMillis(1500),
                Scenario.T0.plusMillis(2000));
        assertThat(run.claims()).extracting(Scenario.Claim::line).containsExactly(2, 2, 4, 7);
    }

    @Test
    @DisplayName("a type the folder already defines, defined again compatibly, is rendered as an extension adding only what is new")
    void compatibleDuplicatesBecomeExtensions() throws IOException {
        var run = run("scenarios/s", Map.of(
            "scenarios/s", """
                extend schema @withFacts(docs: [{path: "shapes/film"}])
                "A film, as this scenario needs it."
                type Film @table(name: "film") {
                  title: String
                  rating: Int
                }
                type Query { films: [Film!]! }
                extend schema @assertEmpty(relation: "r")
                """,
            "shapes/film", """
                type Film @table(name: "film") { title: String }
                type Query { film: Film }
                """));

        var lines = run.captures().getLast().files().get("scenarios/s").split("\n", -1);
        assertThat(lines[1]).isBlank();
        assertThat(lines[2]).matches("extend type Film +\\{");
        assertThat(lines[3]).isBlank();
        assertThat(lines[4]).isEqualTo("  rating: Int");
        assertThat(lines[6]).isEqualTo("extend type Query { films: [Film!]! }");
    }

    @Test
    @DisplayName("a type defined again in disagreement is rendered as written, for the capture to see")
    void incompatibleDuplicatesStay() throws IOException {
        var run = run("scenarios/s", Map.of(
            "scenarios/s", """
                extend schema @withFacts(docs: [{path: "shapes/film"}])
                type Film @table(name: "language") { title: String }
                extend schema @assertEmpty(relation: "r")
                """,
            "shapes/film", "type Film @table(name: \"film\") { title: Int }\n"));

        assertThat(run.captures().getLast().files().get("scenarios/s").split("\n")[1])
            .isEqualTo("type Film @table(name: \"language\") { title: String }");
    }

    @Test
    @DisplayName("a document with nothing to render writes no file, takes away its earlier version, and its step still captures")
    void emptyDocumentsWriteNothing() throws IOException {
        var run = run("scenarios/s", Map.of(
            "scenarios/s", """
                extend schema @withFacts(docs: [{path: "shapes/v1", as: "shapes/film"}, {path: "shapes/carrier"}])
                extend schema @assertEmpty(relation: "r")
                extend schema @tick(ms: 1000)
                extend schema @withFacts(docs: [{path: "shapes/emptied", as: "shapes/film"}])
                extend schema @assertEmpty(relation: "r")
                """,
            "shapes/v1", "type Film { title: String }\n",
            "shapes/carrier", "# Claims lived here once.\n",
            "shapes/emptied", "# Nothing left.\n"));

        assertThat(run.captures()).hasSize(2);
        assertThat(run.captures().getFirst().files()).containsOnlyKeys("shapes/film");
        assertThat(run.captures().getLast().files()).isEmpty();
        assertThat(run.captures().getLast().at()).isEqualTo(Scenario.T0.plusMillis(1000));
    }

    @Test
    @DisplayName("a directive that is not the engine's stays where it was written, beside a blanked claim")
    void otherDirectivesStay() throws IOException {
        var run = run("scenarios/s", Map.of("scenarios/s", """
            extend schema @link(url: "https://specs.apollo.dev/federation/v2.3") @assertEmpty(relation: "r")
            """));

        var line = run.captures().getFirst().files().get("scenarios/s").split("\n")[0];
        assertThat(line).startsWith("extend schema @link(url: \"https://specs.apollo.dev/federation/v2.3\") ")
            .doesNotContain("assertEmpty");
        assertThat(run.claims()).hasSize(1);
    }

    @Test
    @DisplayName("a claim before anything is rendered is refused")
    void claimsNeedACapture() {
        assertThatThrownBy(() -> run("scenarios/s", Map.of("scenarios/s", """
                extend schema @assertEmpty(relation: "r")
                type Film { title: String }
                """)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("before anything is rendered");
    }

    @Test
    @DisplayName("a cycle of @withFacts is refused, naming it")
    void cyclesAreRefused() {
        assertThatThrownBy(() -> run("scenarios/s", Map.of(
                "scenarios/s", "extend schema @withFacts(docs: [{path: \"shapes/a\"}])\n",
                "shapes/a", "extend schema @withFacts(docs: [{path: \"shapes/b\"}])\n",
                "shapes/b", "extend schema @withFacts(docs: [{path: \"scenarios/s\"}])\n")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("scenarios/s -> shapes/a -> shapes/b -> scenarios/s");
    }

    @Test
    @DisplayName("a document that does not exist is refused, naming who named it")
    void missingDocumentsAreRefused() {
        assertThatThrownBy(() -> run("scenarios/s", Map.of(
                "scenarios/s", "extend schema @withFacts(docs: [{path: \"shapes/none\"}])\n")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("scenarios/s names the fact document 'shapes/none'");
    }

    @Test
    @DisplayName("fileAnnotations gives a rendered file, named as @withFacts wrote it, the tag and the note a schema input configures")
    void fileAnnotationsAnnotateRenderedFiles() throws IOException {
        var inputs = new ArrayList<List<SchemaInput>>();
        Scenario.load("scenarios/s", p -> Optional.ofNullable(Map.of(
                "scenarios/s", """
                    extend schema @withFacts(docs: [{path: "shapes/b", as: "shapes/renamed"}])
                    extend schema @withConfiguration(fileAnnotations: [
                      {file: "shapes/renamed", tag: "catalog"},
                      {file: "scenarios/s", note: "Part of cinema."}])
                    type Rating { stars: Int }
                    """,
                "shapes/b", "type Language { name: String }\n").get(p)))
            .run(folder, (dir, at, first, configuration) -> inputs.add(FactDocuments.annotated(dir,
                    files(dir), FactDocuments.fileAnnotations(configuration))),
                claim -> { });

        assertThat(inputs.getFirst()).as("the first reading ran before any configuration was stated")
            .extracting(SchemaInput::tag, SchemaInput::descriptionNote)
            .containsOnly(org.assertj.core.groups.Tuple.tuple(Optional.empty(), Optional.empty()));
        assertThat(inputs.getLast()).extracting(input -> folder.relativize(
                    ((SchemaSource.File) input.source()).path()).toString(),
                SchemaInput::tag, SchemaInput::descriptionNote)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("scenarios/s.graphqls", Optional.empty(),
                    Optional.of("Part of cinema.")),
                org.assertj.core.groups.Tuple.tuple("shapes/renamed.graphqls", Optional.of("catalog"),
                    Optional.empty()));
    }

    @Test
    @DisplayName("a file annotation naming a file the folder does not hold is refused, the recipe staying the folder's")
    void fileAnnotationsNameOnlyRenderedFiles() {
        assertThatThrownBy(() -> Scenario.load("scenarios/s", p -> Optional.ofNullable(Map.of(
                "scenarios/s", """
                    extend schema @withConfiguration(fileAnnotations: [{file: "shapes/b", tag: "catalog"}])
                    type Rating { stars: Int }
                    """).get(p)))
            .run(folder, (dir, at, first, configuration) -> FactDocuments.annotated(dir, files(dir),
                    FactDocuments.fileAnnotations(configuration)),
                claim -> { }))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("names shapes/b, a file the scenario's folder does not hold")
            .hasMessageContaining("[scenarios/s]");
    }

    @Test
    @DisplayName("a file named twice in fileAnnotations is refused")
    void fileAnnotationsNameAFileOnce() {
        assertThatThrownBy(() -> FactDocuments.fileAnnotations("""
                extend schema @withConfiguration(fileAnnotations: [
                  {file: "scenarios/s", tag: "a"}, {file: "scenarios/s", note: "b"}])
                """))
            .hasMessageContaining("names scenarios/s twice");
    }

    @Test
    @DisplayName("fileAnnotations in a document pulled in is ignored, as the rest of its configuration is")
    void fileAnnotationsPulledInAreIgnored() throws IOException {
        var run = run("scenarios/s", Map.of(
            "scenarios/s", """
                extend schema @withFacts(docs: [{path: "shapes/a"}])
                type Rating { stars: Int }
                """,
            "shapes/a", """
                extend schema @withConfiguration(fileAnnotations: [{file: "shapes/a", tag: "catalog"}])
                type Film { title: String }
                """));

        assertThat(run.captures()).hasSize(2)
            .allSatisfy(capture -> assertThat(FactDocuments.fileAnnotations(capture.configuration()))
                .isEmpty());
    }

    @Test
    @DisplayName("a later @withConfiguration changes the file annotations the readings after it run under")
    void fileAnnotationsChangeBetweenSteps() throws IOException {
        var run = run("scenarios/s", Map.of(
            "scenarios/s", """
                extend schema @withConfiguration(fileAnnotations: [{file: "scenarios/s", tag: "catalog"}])
                type Rating { stars: Int }
                extend schema @tick(ms: 60000)
                extend schema @withConfiguration(fileAnnotations: [{file: "shapes/a", note: "Shared."}])
                extend schema @withFacts(docs: [{path: "shapes/a"}])
                extend schema @tick(ms: 60000)
                extend schema @withConfiguration
                extend schema @withFacts(docs: [{path: "shapes/a"}])
                """,
            "shapes/a", "type Film { title: String }\n"));

        assertThat(run.captures()).extracting(capture -> FactDocuments.fileAnnotations(capture.configuration()))
            .containsExactly(
                List.of(new FactDocuments.FileAnnotation("scenarios/s", Optional.of("catalog"), Optional.empty())),
                List.of(new FactDocuments.FileAnnotation("shapes/a", Optional.empty(), Optional.of("Shared."))),
                List.of());
    }

    private static List<Path> files(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            return paths.filter(file -> file.toString().endsWith(CorpusDocuments.SUFFIX)).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Run run(String path, Map<String, String> documents) throws IOException {
        var captures = new ArrayList<Capture>();
        var claims = new ArrayList<Scenario.Claim>();
        Scenario.load(path, p -> Optional.ofNullable(documents.get(p)))
            .run(folder, (dir, at, first, configuration) -> captures.add(snapshot(dir, at, first, configuration)),
                claims::add);
        return new Run(captures, claims);
    }

    private static Capture snapshot(Path dir, Instant at, boolean first, String configuration) {
        var files = new TreeMap<String, String>();
        var times = new TreeMap<String, Instant>();
        try (Stream<Path> paths = Files.walk(dir)) {
            for (var file : paths.filter(Files::isRegularFile).toList()) {
                var name = dir.relativize(file).toString().replace(CorpusDocuments.SUFFIX, "");
                files.put(name, Files.readString(file));
                times.put(name, Files.getLastModifiedTime(file).toInstant());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Capture(at, first, files, times, configuration);
    }
}
