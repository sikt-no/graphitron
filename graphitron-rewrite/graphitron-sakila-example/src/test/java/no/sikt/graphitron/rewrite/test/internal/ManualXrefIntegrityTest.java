package no.sikt.graphitron.rewrite.test.internal;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drift-protection seam for AsciiDoc cross-references in {@code docs/manual/}.
 * Walks every {@code .adoc} file under the manual subtree, finds every
 * {@code xref:<target>[<text>]} occurrence, resolves the target relative to the
 * source file, and asserts the target file exists.
 *
 * <p>Catches the most common editorial-drift class: a recipe is renamed or moved,
 * outbound links from sibling pages are missed, and the dangling xref ships. A
 * dangling xref renders as a literal "&lt;file&gt;.adoc" with no link in
 * AsciiDoctor, which a casual review easily misses.
 *
 * <p>Anchors after {@code #} are ignored: the test verifies the file is reachable,
 * not that the anchor exists. Pure-anchor xrefs ({@code xref:#anchor[]}, with no
 * file before the {@code #}) are skipped because they reference the current file.
 * URLs ({@code http(s)://}) are not xrefs and never match the pattern in the first
 * place.
 */
@UnitTier
class ManualXrefIntegrityTest {

    private static final String DOCS_MANUAL_PATH = "docs/manual";

    /**
     * Matches an AsciiDoc {@code xref:<target>[<text>]}.
     * Group 1 is the target (file path with optional {@code #anchor}); group 2 is
     * the link text. The target excludes whitespace and the {@code [} that opens
     * the text. We don't constrain the suffix; resolution decides whether the
     * file exists.
     */
    private static final Pattern XREF = Pattern.compile("xref:([^\\s\\[]+)\\[([^]]*)]");

    @Test
    void everyManualXrefResolvesToAnExistingFile() throws IOException {
        Path manualDir = locateManualDir();
        List<String> failures = new ArrayList<>();

        try (Stream<Path> files = Files.walk(manualDir)) {
            files.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".adoc"))
                .forEach(adoc -> collectFailures(adoc, manualDir, failures));
        }

        assertThat(failures)
            .as("dangling xref:<target>[] in docs/manual/; each entry is "
                + "<source-relative-to-manual>:<line-number>: xref:<target> -> "
                + "<unresolved-absolute-path>")
            .isEmpty();
    }

    private static void collectFailures(Path adoc, Path manualDir, List<String> failures) {
        String text;
        try {
            text = Files.readString(adoc, StandardCharsets.UTF_8);
        } catch (IOException e) {
            failures.add(adoc + ": failed to read: " + e.getMessage());
            return;
        }
        String[] lines = text.split("\n", -1);
        for (int lineNo = 0; lineNo < lines.length; lineNo++) {
            Matcher m = XREF.matcher(lines[lineNo]);
            while (m.find()) {
                String target = m.group(1);
                // Pure-anchor xref to the current file; no file resolution needed.
                if (target.startsWith("#")) continue;
                String filePart = target;
                int hash = target.indexOf('#');
                if (hash >= 0) filePart = target.substring(0, hash);
                if (filePart.isEmpty()) continue; // edge: xref:#anchor[] forms after a stripped path
                Path resolved = adoc.getParent().resolve(filePart).normalize();
                if (!Files.isRegularFile(resolved)) {
                    Path sourceRel = manualDir.relativize(adoc);
                    failures.add(sourceRel + ":" + (lineNo + 1)
                        + ": xref:" + target + " -> " + resolved + " (missing)");
                }
            }
        }
    }

    /**
     * Walks up from the test working directory until it finds
     * {@code docs/manual/}. Surefire runs from the module directory; the
     * directory is normally two parents up.
     */
    private static Path locateManualDir() {
        Path cwd = Path.of("").toAbsolutePath();
        for (Path p = cwd; p != null; p = p.getParent()) {
            Path candidate = p.resolve(DOCS_MANUAL_PATH);
            if (Files.isDirectory(candidate)) return candidate;
        }
        throw new IllegalStateException(
            "Could not locate " + DOCS_MANUAL_PATH + " by walking up from " + cwd);
    }
}
