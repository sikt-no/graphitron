package no.sikt.graphitron.rewrite.test.internal;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Markdown twin of {@link ManualXrefIntegrityTest}: drift protection for relative
 * links in the repo's reader-facing {@code README.md} files. Walks every
 * {@code README.md} in the repository (skipping build output and hidden
 * directories), finds every inline markdown link or image target, resolves
 * relative targets against the README's own directory, and asserts the target
 * exists as a file or directory.
 *
 * <p>Catches the drift class where code moves or is deleted and a README link to
 * it ships dangling. GitHub renders a dangling relative link identically to a
 * live one, so a casual review misses it; this guard is why READMEs should link
 * files rather than name them in backticks, since only a link is visible here.
 *
 * <p>External URLs ({@code http(s)://}, {@code mailto:}) and pure-anchor links
 * ({@code #section}) are skipped. An anchor suffix on a file target is stripped:
 * the guard verifies the file is reachable, not that the anchor exists.
 */
@UnitTier
class ReadmeLinkIntegrityTest {

    /**
     * Matches the target of an inline markdown link or image, {@code ](target)}
     * with an optional quoted title. Group 1 is the target. The target excludes
     * whitespace and {@code )}; resolution decides whether it exists.
     */
    private static final Pattern LINK_TARGET = Pattern.compile("]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)");

    @Test
    void everyReadmeRelativeLinkResolvesToAnExistingPath() throws IOException {
        Path repoRoot = locateRepoRoot();
        List<String> failures = new ArrayList<>();

        for (Path readme : readerFacingReadmes(repoRoot)) {
            collectFailures(readme, repoRoot, failures);
        }

        assertThat(failures)
            .as("dangling relative link in a README.md; each entry is "
                + "<readme-relative-to-repo-root>:<line-number>: (<target>) -> "
                + "<unresolved-absolute-path>")
            .isEmpty();
    }

    /**
     * Every reader-facing {@code README.md} under the root. A README is reader-facing unless it
     * sits under build output ({@code target/}), a dependency cache ({@code node_modules/}), or a
     * hidden directory ({@code .git/} and friends), and the walk never enters those: a parallel
     * build writes and deletes other modules' {@code target/} trees while this runs, so a walk
     * that entered them and filtered afterwards could list a report file another fork then
     * removed. A file that still vanishes between listing and visiting is skipped, for the same
     * reason.
     */
    private static List<Path> readerFacingReadmes(Path repoRoot) throws IOException {
        List<Path> readmes = new ArrayList<>();
        Files.walkFileTree(repoRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(repoRoot)) {
                    return FileVisitResult.CONTINUE;
                }
                String name = dir.getFileName().toString();
                return name.equals("target") || name.equals("node_modules") || name.startsWith(".")
                    ? FileVisitResult.SKIP_SUBTREE
                    : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.getFileName().toString().equals("README.md")) {
                    readmes.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) throws IOException {
                if (e instanceof NoSuchFileException) {
                    return FileVisitResult.CONTINUE;
                }
                throw e;
            }
        });
        return readmes;
    }

    private static void collectFailures(Path readme, Path repoRoot, List<String> failures) {
        String text;
        try {
            text = Files.readString(readme, StandardCharsets.UTF_8);
        } catch (IOException e) {
            failures.add(readme + ": failed to read: " + e.getMessage());
            return;
        }
        String[] lines = text.split("\n", -1);
        for (int lineNo = 0; lineNo < lines.length; lineNo++) {
            Matcher m = LINK_TARGET.matcher(lines[lineNo]);
            while (m.find()) {
                String target = m.group(1);
                if (target.startsWith("http://") || target.startsWith("https://")
                    || target.startsWith("mailto:") || target.startsWith("#")) {
                    continue;
                }
                String filePart = target;
                int hash = target.indexOf('#');
                if (hash >= 0) filePart = target.substring(0, hash);
                if (filePart.isEmpty()) continue;
                Path resolved = readme.getParent().resolve(filePart).normalize();
                if (Files.exists(resolved)) continue;
                Path sourceRel = repoRoot.relativize(readme);
                failures.add(sourceRel + ":" + (lineNo + 1)
                    + ": (" + target + ") -> " + resolved + " (missing)");
            }
        }
    }

    /**
     * Walks up from the test working directory until it finds the reactor root,
     * identified by the {@code roadmap/} directory next to a {@code pom.xml}.
     * Surefire runs from the module directory; the root is normally one parent up.
     */
    private static Path locateRepoRoot() {
        Path cwd = Path.of("").toAbsolutePath();
        for (Path p = cwd; p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve("roadmap")) && Files.isRegularFile(p.resolve("pom.xml"))) {
                return p;
            }
        }
        throw new IllegalStateException("Could not locate the reactor root by walking up from " + cwd);
    }
}
