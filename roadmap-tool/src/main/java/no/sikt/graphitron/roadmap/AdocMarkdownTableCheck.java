package no.sikt.graphitron.roadmap;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Scans authored AsciiDoc files for markdown-formatted tables that have leaked past
 * review. Markdown table syntax (a {@code | col | col |} header followed by a
 * {@code |---|---|} separator) renders in Asciidoctor as paragraph text with literal
 * pipes rather than a table; the bug is invisible until the page ships. The check
 * walks every {@code .adoc} under one or more roots, tracks structural context
 * (inside a {@code |===} table block, a {@code ----} listing block, a {@code ....}
 * literal block, a {@code ////} comment block, or a {@code ++++} passthrough block),
 * and flags any markdown-separator row found outside those blocks.
 *
 * <p>Files under {@code target/} directories are skipped: they hold generated output
 * (including the rendered roadmap), not authored source. {@code .md} files are also
 * out of scope; the markdown-table syntax is the native form there.
 */
final class AdocMarkdownTableCheck {

    /**
     * Markdown table separator row: at least two pipe-delimited segments of dashes
     * (with optional leading/trailing colons for GFM alignment). The leading and
     * trailing pipes are conventional in our docs but optional per the GFM spec, so
     * the regex accepts either shape. Dash count is unbounded down to one to cover
     * the GFM minimum, which means callers must already know they're outside an
     * AsciiDoc structural block before testing; the {@code scanFile} block tracker
     * provides that guarantee.
     */
    private static final Pattern MD_SEPARATOR =
        Pattern.compile("\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)+\\|?\\s*");

    private AdocMarkdownTableCheck() {}

    /**
     * Entry point invoked by {@link Main}. Args:
     *
     * <ul>
     *   <li>{@code <root-dir>...} — one or more directories to walk recursively.
     *       Each is scanned independently; findings are aggregated.</li>
     * </ul>
     *
     * <p>Returns 0 when no markdown tables are found, 1 when at least one is found
     * (with each finding written to stderr as {@code file:line: <line>}), and 64
     * when invoked with no roots or a non-directory root.
     */
    static int run(List<String> args) throws IOException {
        if (args.isEmpty()) {
            System.err.println("usage: check-adoc-tables <root-dir>...");
            return 64;
        }
        List<Path> roots = new ArrayList<>();
        for (String a : args) {
            Path p = Path.of(a).toAbsolutePath().normalize();
            if (!Files.isDirectory(p)) {
                System.err.println("not a directory: " + p);
                return 64;
            }
            roots.add(p);
        }

        List<Finding> findings = new ArrayList<>();
        for (Path root : roots) {
            findings.addAll(scan(root));
        }

        if (findings.isEmpty()) {
            System.out.println("check-adoc-tables: no markdown-formatted tables in authored .adoc files.");
            return 0;
        }
        System.err.println("check-adoc-tables: found " + findings.size()
            + " markdown-formatted table separator row(s) in authored .adoc files."
            + " Convert to AsciiDoc tables (`|===` block) or move to a `----` listing if the"
            + " pipe-and-dash row is meant as literal text.");
        for (Finding f : findings) {
            System.err.println("  " + f.file() + ":" + f.line() + ": " + f.content());
        }
        // Throw rather than return non-zero (which the Main dispatcher turns into System.exit):
        // this check is bound to the `verify` phase and runs in the Maven JVM via exec:java, so
        // System.exit would kill Maven before BUILD FAILURE prints. Usage / not-a-directory
        // errors keep returning 64 above; those are CLI dev errors, not a verify-phase tripwire.
        throw new BuildFailure("markdown-formatted tables in authored .adoc files");
    }

    static List<Finding> scan(Path root) throws IOException {
        List<Finding> findings = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if ("target".equals(name) || "node_modules".equals(name) || ".git".equals(name)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!file.getFileName().toString().endsWith(".adoc")) {
                    return FileVisitResult.CONTINUE;
                }
                try {
                    findings.addAll(scanFile(file));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return findings;
    }

    /**
     * Tracks AsciiDoc structural-block context across {@code lines} and flags every
     * markdown table separator row that appears outside any block. Block delimiters
     * are matched verbatim: AsciiDoc's parser accepts unbalanced delimiter pairs in
     * some contexts, but for this lint a strict toggle is enough; the goal is to
     * avoid false positives on the dashes inside a {@code ----} listing rather than
     * to fully model nested-block semantics.
     */
    static List<Finding> scanFile(Path file) throws IOException {
        List<Finding> findings = new ArrayList<>();
        List<String> lines = Files.readAllLines(file);
        boolean inTable = false;
        boolean inListing = false;
        boolean inLiteral = false;
        boolean inComment = false;
        boolean inPassthrough = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.strip();
            if (trimmed.equals("|===")) {
                inTable = !inTable;
                continue;
            }
            if (!inTable && trimmed.matches("-{4,}")) {
                inListing = !inListing;
                continue;
            }
            if (!inTable && !inListing && trimmed.matches("\\.{4,}")) {
                inLiteral = !inLiteral;
                continue;
            }
            if (!inTable && !inListing && !inLiteral && trimmed.matches("/{4,}")) {
                inComment = !inComment;
                continue;
            }
            if (!inTable && !inListing && !inLiteral && !inComment && trimmed.matches("\\+{4,}")) {
                inPassthrough = !inPassthrough;
                continue;
            }
            if (inTable || inListing || inLiteral || inComment || inPassthrough) {
                continue;
            }
            if (MD_SEPARATOR.matcher(line).matches()) {
                findings.add(new Finding(file, i + 1, line));
            }
        }
        return findings;
    }

    record Finding(Path file, int line, String content) {}
}
