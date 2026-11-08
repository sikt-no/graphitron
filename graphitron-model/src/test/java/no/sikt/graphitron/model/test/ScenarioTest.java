package no.sikt.graphitron.model.test;

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
 * the folder, when it asks for a capture, and which claims it hands over, in what order.
 */
class ScenarioTest {

    private static final String PRELUDE = """
        directive @assertEmpty(relation: String!, where: String) repeatable on SCHEMA
        directive @withFacts(docs: [FactDoc!]!) repeatable on SCHEMA
        input FactDoc { path: String!, as: String }
        directive @tick(ms: Int!) repeatable on SCHEMA
        type Query { anchor: Int }
        """;

    @TempDir
    Path folder;

    /** What a run did: each capture as the folder's files then, and each claim as it arrived. */
    private record Run(List<Capture> captures, List<Scenario.Claim> claims) {}

    private record Capture(Instant at, Map<String, String> files, Map<String, Instant> times) {}

    @Test
    @DisplayName("the scenario's own file is rendered at t0 beside the prelude, its lines kept and its claims blanked")
    void ownFileAtT0() throws IOException {
        var scenario = """
            # A film.
            extend schema @assertEmpty(relation: "graphitron_entry_defect")
            type Film { title: String }
            """;
        var run = run("scenarios/film", Map.of("scenarios/film", scenario));

        assertThat(run.captures()).singleElement().satisfies(capture -> {
            assertThat(capture.at()).isEqualTo(Scenario.T0);
            assertThat(capture.files()).containsOnlyKeys("_prelude", "scenarios/film");
            assertThat(capture.times().values()).containsOnly(Scenario.T0);
            var lines = capture.files().get("scenarios/film").split("\n", -1);
            assertThat(lines[0]).isEqualTo("# A film.");
            assertThat(lines[1]).isBlank().hasSize(lines[1].length());
            assertThat(lines[2]).isEqualTo("type Film { title: String }");
            assertThat(capture.files().get("_prelude"))
                .doesNotContain("withFacts").doesNotContain("FactDoc").doesNotContain("@tick")
                .contains("directive @assertEmpty").contains("type Query");
        });
        assertThat(run.claims()).singleElement().satisfies(claim -> {
            assertThat(claim.line()).isEqualTo(2);
            assertThat(claim.sdl()).contains("@assertEmpty(relation: \"graphitron_entry_defect\")");
        });
    }

    @Test
    @DisplayName("@withFacts renders each document, and first what it pulls in, as the file its entry names")
    void pullsInRecursively() throws IOException {
        var run = run("scenarios/s", Map.of(
            "scenarios/s", """
                extend schema @withFacts(docs: [{path: "layouts/core"}, {path: "shapes/b", as: "shapes/renamed"}])
                extend type Film { rating: Int }
                extend schema @assertEmpty(relation: "r")
                """,
            "layouts/core", """
                extend schema @withFacts(docs: [{path: "shapes/a"}])
                extend schema @assertEmpty(relation: "never handed over")
                """,
            "shapes/a", "type Film { title: String }\n",
            "shapes/b", "type Language { name: String }\n"));

        assertThat(run.captures()).singleElement().satisfies(capture -> assertThat(capture.files())
            .containsOnlyKeys("_prelude", "scenarios/s", "layouts/core", "shapes/a", "shapes/renamed"));
        assertThat(run.claims()).extracting(claim -> claim.sdl()).singleElement().asString().contains("\"r\"");
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
            assertThat(capture.times().values()).containsOnly(Scenario.T0);
        });
    }

    @Test
    @DisplayName("a claim captures only when something was rendered since the last capture, at the clock's time, and arrives once")
    void capturesWhenChangedAndClaimsOnce() throws IOException {
        var run = run("scenarios/s", Map.of(
            "scenarios/s", """
                type Film { title: String }
                extend schema @assertEmpty(relation: "first") @assertEmpty(relation: "second")
                extend schema @tick(ms: 1500)
                extend schema @assertEmpty(relation: "no change, no capture")
                extend schema @tick(ms: 500)
                extend schema @withFacts(docs: [{path: "shapes/a"}])
                extend schema @assertEmpty(relation: "third")
                """,
            "shapes/a", "type Language { name: String }\n"));

        assertThat(run.captures()).extracting(Capture::at)
            .containsExactly(Scenario.T0, Scenario.T0.plusMillis(2000));
        assertThat(run.captures().getLast().times())
            .containsEntry("scenarios/s", Scenario.T0)
            .containsEntry("shapes/a", Scenario.T0.plusMillis(2000));
        assertThat(run.claims()).extracting(claim -> claim.at())
            .containsExactly(Scenario.T0, Scenario.T0, Scenario.T0.plusMillis(1500),
                Scenario.T0.plusMillis(2000));
        assertThat(run.claims()).extracting(claim -> claim.line()).containsExactly(2, 2, 4, 7);
    }

    @Test
    @DisplayName("a directive that is not setup or a claim stays where it was written, beside a blanked claim")
    void otherDirectivesStay() throws IOException {
        var run = run("scenarios/s", Map.of("scenarios/s", """
            extend schema @link(url: "https://specs.apollo.dev/federation/v2.3") @assertEmpty(relation: "r")
            """));

        var line = run.captures().getFirst().files().get("scenarios/s").split("\n")[0];
        assertThat(line).startsWith("extend schema @link(url: \"https://specs.apollo.dev/federation/v2.3\") ")
            .doesNotContain("assertEmpty");
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
    @DisplayName("a step whose documents do not merge is refused; a conflict for the capture spans two steps")
    void aStepMustMerge() throws IOException {
        var documents = Map.of(
            "scenarios/s", """
                extend schema @withFacts(docs: [{path: "shapes/a"}, {path: "shapes/b"}])
                """,
            "scenarios/t", """
                extend schema @withFacts(docs: [{path: "shapes/a"}])
                extend schema @withFacts(docs: [{path: "shapes/b"}])
                extend schema @assertEmpty(relation: "r")
                """,
            "shapes/a", "type Film { title: String }\n",
            "shapes/b", "type Film { name: String }\n");
        assertThatThrownBy(() -> run("scenarios/s", documents))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("shapes/b")
            .hasMessageContaining("across two @withFacts");

        assertThat(run("scenarios/t", documents).captures().getFirst().files())
            .containsKeys("shapes/a", "shapes/b");
    }

    @Test
    @DisplayName("a document that does not exist is refused, naming who named it")
    void missingDocumentsAreRefused() {
        assertThatThrownBy(() -> run("scenarios/s", Map.of(
                "scenarios/s", "extend schema @withFacts(docs: [{path: \"shapes/none\"}])\n")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("scenarios/s names the fact document 'shapes/none'");
    }

    private Run run(String path, Map<String, String> documents) throws IOException {
        var captures = new ArrayList<Capture>();
        var claims = new ArrayList<Scenario.Claim>();
        Scenario.load(path, p -> Optional.ofNullable(documents.get(p)), PRELUDE)
            .run(folder, (dir, at) -> captures.add(snapshot(dir, at)), claims::add);
        return new Run(captures, claims);
    }

    private static Capture snapshot(Path dir, Instant at) {
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
        return new Capture(at, files, times);
    }
}
