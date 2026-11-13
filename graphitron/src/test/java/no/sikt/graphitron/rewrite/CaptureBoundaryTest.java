package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural guard over where capture is called: capture code lives in the model, and this module
 * captures only through the model's test harnesses. A store a test reads comes from
 * {@code FactGraphs}, and a run a test drives end to end comes from {@code CapturedGraphs}, so the
 * stores tests capture into are kept in one place, where their reuse is managed.
 *
 * <p>Calls rather than mentions: comments and string literals are blanked before the scan, so a
 * javadoc naming the capture entry points, or a guard's own needles, is not a finding.
 */
@UnitTier
class CaptureBoundaryTest {

    /** Each way into a capture, as code calls it. */
    private static final List<Pattern> CAPTURE_CALLS = List.of(
        Pattern.compile("\\bGraphitronStore\\s*(?:\\.|::)\\s*(?:captured\\w*|capture)\\b"),
        Pattern.compile("\\bModelCapture\\b"),
        Pattern.compile("\\bCapturedStore\\b"),
        Pattern.compile("\\bInlineCaptures\\b"));

    /** {@code InlineCaptureGateTest} counts inline captures with {@code InlineCaptures}; it runs none. */
    private static final Set<String> EXEMPT = Set.of("InlineCaptureGateTest.java");

    /** Literals and comments, blanked so only code is scanned. */
    private static final Pattern NOT_CODE = Pattern.compile(
        "\"\"\"[\\s\\S]*?\"\"\"|\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*'|/\\*[\\s\\S]*?\\*/|//[^\\n]*");

    /** Floors on the scanned-file counts: a walk that reached nothing would otherwise pass. */
    private static final int MIN_MAIN_FILES = 200;
    private static final int MIN_TEST_FILES = 200;

    @Test
    void mainSourcesCaptureNothing() throws IOException {
        assertThat(scan(Path.of("src/main/java"), MIN_MAIN_FILES))
            .as("the generator is handed a captured graph and captures nothing itself; whoever "
                + "opens the store captures into it, and that is a goal or a test harness")
            .isEmpty();
    }

    @Test
    void testsCaptureOnlyThroughTheModelsHarnesses() throws IOException {
        assertThat(scan(Path.of("src/test/java"), MIN_TEST_FILES))
            .as("take the store from FactGraphs, or a whole run's captured graph from "
                + "CapturedGraphs.open; a test of what capture writes belongs in graphitron-model")
            .isEmpty();
    }

    private static List<String> scan(Path root, int floor) throws IOException {
        var findings = new ArrayList<String>();
        int scanned = 0;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                scanned++;
                if (EXEMPT.contains(path.getFileName().toString())) continue;
                String code = NOT_CODE.matcher(Files.readString(path)).replaceAll(" ");
                for (var call : CAPTURE_CALLS) {
                    var found = call.matcher(code);
                    while (found.find()) {
                        findings.add(root.relativize(path) + ": " + found.group());
                    }
                }
            }
        }
        assertThat(scanned)
            .as("the walk under %s reached too few files to be scanning the module at all", root)
            .isGreaterThanOrEqualTo(floor);
        return findings;
    }
}
