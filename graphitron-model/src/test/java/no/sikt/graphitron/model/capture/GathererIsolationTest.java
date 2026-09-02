package no.sikt.graphitron.model.capture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One package per gatherer, and what a gatherer keeps to itself stays there. Each package under
 * {@code no.sikt.graphitron.model.capture} holds exactly one gatherer, the stage that reads one of
 * the consumer's sources and writes what it found, plus the helpers that only that stage uses. This
 * test says the second half out loud: a type in a gatherer's package other than the gatherer itself
 * is named from inside that package and nowhere else.
 *
 * <p>The gatherer itself is deliberately outside the rule. It is the tier's surface: the
 * orchestrator names the eight it runs, a gatherer names a sibling whose output it needs, and the
 * plugin names the two that record a dev session's own observations. What must not happen is a
 * second reader of a gatherer's <em>internals</em>, because that is the edge that turns two stages
 * into one and cannot be undone by moving a file afterwards.
 *
 * <p><b>Why a test rather than the compiler.</b> A package-private type would say this to javac,
 * and four of the helpers here are package-private for exactly that reason. It stops being enough
 * the moment a helper has to be reachable from the module's own tests, or from a vocabulary package
 * that the split put on the other side of a package boundary; the rule is about which code may read
 * a helper, and Java has no way to say "this package and no other" once the type is public. So the
 * rule is written down here, over sources, where it holds for a public helper too.
 *
 * <p>The gatherer roll is spelled out rather than derived, and {@link #everyCapturePackageIsRolled}
 * fails when a package appears under {@code capture} that this file does not name. Adding a
 * gatherer is a decision, so it costs a line here.
 */
class GathererIsolationTest {

    private static final Path MAIN = Path.of("src/main/java/no/sikt/graphitron/model");
    private static final Path CAPTURE = MAIN.resolve("capture");

    /** Each gatherer package, keyed by its directory, valued by the gatherer it exists for. */
    private static final Map<String, String> GATHERERS = gatherers();

    private static Map<String, String> gatherers() {
        var roll = new LinkedHashMap<String, String>();
        roll.put("capture", "FactCapture");
        roll.put("capture/config", "ConfigurationFactCapture");
        roll.put("capture/catalog", "CatalogFactCapture");
        roll.put("capture/sdl", "SdlFactCapture");
        roll.put("capture/verdict", "SdlVerdictCapture");
        roll.put("capture/graphitron", "GraphitronFactCapture");
        roll.put("capture/macro", "MacroCapture");
        roll.put("capture/java", "JavaSourceFacts");
        roll.put("capture/compile", "CompileFacts");
        return Map.copyOf(roll);
    }

    @Test
    void noGathererHelperIsNamedFromOutsideItsPackage() throws IOException {
        var violations = new ArrayList<String>();
        for (var gatherer : GATHERERS.entrySet()) {
            Path dir = MAIN.resolve(gatherer.getKey());
            for (Path helper : declaredIn(dir)) {
                String simple = simpleName(helper);
                if (simple.equals(gatherer.getValue())) {
                    continue;
                }
                for (Path reader : mainSourcesOutside(dir)) {
                    if (names(reader, simple)) {
                        violations.add(simple + " (" + gatherer.getKey() + ")  read by  "
                            + MAIN.relativize(reader));
                    }
                }
            }
        }
        assertThat(violations)
            .as("a gatherer's private helper read from outside its package; either the reader is"
                + " part of that gatherer and belongs in the package, or the helper is tier"
                + " vocabulary and belongs in a package that is nobody's gatherer")
            .isEmpty();
    }

    @Test
    void everyCapturePackageIsRolled() throws IOException {
        var found = new TreeSet<String>();
        try (var paths = Files.walk(CAPTURE)) {
            paths.filter(Files::isDirectory)
                .forEach(d -> found.add(MAIN.relativize(d).toString().replace('\\', '/')));
        }
        assertThat(found)
            .as("a package under capture/ that names no gatherer; add it to GATHERERS with the"
                + " gatherer it exists for, or put its contents in a vocabulary package")
            .containsExactlyInAnyOrderElementsOf(GATHERERS.keySet());
    }

    @Test
    void everyRolledGathererIsOnDisk() {
        var missing = new ArrayList<String>();
        GATHERERS.forEach((dir, gatherer) -> {
            if (!Files.exists(MAIN.resolve(dir).resolve(gatherer + ".java"))) {
                missing.add(dir + "/" + gatherer);
            }
        });
        assertThat(missing)
            .as("a rolled gatherer that is not on disk; a stale roll would pass the isolation"
                + " check vacuously for its whole package")
            .isEmpty();
    }

    private static List<Path> declaredIn(Path dir) throws IOException {
        try (var paths = Files.list(dir)) {
            return paths.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".java"))
                .sorted()
                .toList();
        }
    }

    private static List<Path> mainSourcesOutside(Path dir) throws IOException {
        try (var paths = Files.walk(MAIN)) {
            return paths.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".java"))
                .filter(p -> !p.getParent().equals(dir))
                .sorted()
                .toList();
        }
    }

    /**
     * Whether {@code file} names {@code simple} as an identifier in code. Comments are stripped
     * first: a gatherer's javadoc explaining why a helper is private is prose about the rule, not
     * a reader of the helper, and the whole point of the helper being named there is that nobody
     * else calls it.
     */
    private static boolean names(Path file, String simple) throws IOException {
        String code = stripComments(Files.readString(file));
        return Pattern.compile("\\b" + Pattern.quote(simple) + "\\b").matcher(code).find();
    }

    private static String stripComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
    }

    private static String simpleName(Path file) {
        String name = file.getFileName().toString();
        return name.substring(0, name.length() - ".java".length());
    }
}
