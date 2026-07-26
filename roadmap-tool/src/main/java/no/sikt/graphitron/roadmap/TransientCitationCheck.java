package no.sikt.graphitron.roadmap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fails the build when an agent-onboarding prose document cites a roadmap item by id
 * ({@code R<n>}) or by {@code roadmap/<slug>} path. Items are renumbered, ship and leave a
 * numbering gap, or are discarded, so a citation is stale the moment the item moves and a
 * reader with no {@code roadmap/} directory has nothing to resolve it against. The durable
 * reference (a live symbol, a published doc page, or simply the fact) is what belongs in
 * prose instead.
 *
 * <p>The rule this enforces is stated in {@code CLAUDE.md} under "Javadoc conventions" and is
 * already enforced across Java sources by the generator module's roadmap-reference guard test.
 * That test parses Java comment and string-literal regions, which markdown documents have
 * none of, so the same rule needs a second enforcement site for the prose habitat. This is it.
 *
 * <p>Build configuration is a third habitat this check does not reach: {@code pom.xml} comments
 * carry item ids too, and no scan covers them.
 */
final class TransientCitationCheck {

    /**
     * The scanned habitat: prose documents an agent session reads in full and acts on before
     * opening any other file, so a citation that rots here costs a wrong action rather than a
     * puzzled reader. Every entry must exist; a missing path fails the check rather than
     * silently shrinking the scan, which is the floor against a walk that reaches nothing.
     * Adding a document is one line.
     *
     * <p>Deliberately excludes {@code .claude/skills/}: skill documents carry item ids as
     * worked-example syntax ("move R24 to Ready"), where the id illustrates a command shape
     * and makes no provenance claim, so rotting is harmless and a guard over them would be
     * suppressed rather than obeyed. This is the same judgment the Java-source guard makes
     * when it excludes test-source string literals: scan the habitat where a stale id
     * misleads, not every habitat where the characters appear.
     */
    static final List<String> SCANNED_DOCS = List.of(
        "CLAUDE.md",
        ".claude/web-environment.md"
    );

    /**
     * Item id: literal {@code R} followed by digits. The {@code R<n>} placeholder that the rule
     * itself uses when stating the forbidden shape does not match, so a document may quote the
     * rule without tripping it.
     */
    private static final Pattern ITEM_ID = Pattern.compile("\\bR\\d+\\b");

    /**
     * A path under {@code roadmap/}. A bare {@code roadmap/} directory mention and the
     * {@code roadmap/<slug>} placeholder both fail to match, since neither is followed by a
     * character this class accepts.
     */
    private static final Pattern ROADMAP_PATH = Pattern.compile("\\broadmap/([A-Za-z0-9._-]+)");

    /**
     * The three roadmap artifacts that outlive every individual item and may therefore be
     * cited by path: the changelog, the workflow reference, and the generated roll-up.
     */
    private static final Set<String> PERMANENT_ARTIFACTS = Set.of("changelog.md", "workflow.adoc", "README.md");

    private TransientCitationCheck() {}

    /**
     * Entry point invoked by {@link Main}. Takes one argument, the repository root that
     * {@link #SCANNED_DOCS} resolves against.
     *
     * <p>Returns 0 when every scanned document is present and free of transient citations,
     * and 64 when invoked with the wrong number of arguments or a non-directory root. Throws
     * {@link BuildFailure} on a finding or a missing document, for the reason spelled out on
     * that class: this check runs in the Maven JVM at {@code verify}, so returning a code the
     * dispatcher turns into {@code System.exit} would kill Maven before it prints its summary.
     */
    static int run(List<String> args) throws IOException {
        if (args.size() != 1) {
            System.err.println("usage: check-transient-citations <repo-root>");
            return 64;
        }
        Path root = Path.of(args.get(0)).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            System.err.println("not a directory: " + root);
            return 64;
        }

        Result result = scan(root);

        if (!result.missing().isEmpty()) {
            System.err.println("check-transient-citations: declared document(s) not found under " + root
                + ". Repoint the scan list in TransientCitationCheck to where the document moved;"
                + " a scan that silently reaches nothing would pass vacuously.");
            for (String m : result.missing()) {
                System.err.println("  " + m);
            }
            throw new BuildFailure("agent-onboarding document declared for scanning does not exist");
        }
        if (result.findings().isEmpty()) {
            System.out.println("check-transient-citations: no transient roadmap citations in "
                + result.scanned() + " agent-onboarding document(s).");
            return 0;
        }

        System.err.println("check-transient-citations: found " + result.findings().size()
            + " transient roadmap citation(s) in agent-onboarding prose."
            + " Replace each with a durable reference (a live symbol, a published docs page) or"
            + " state the fact and drop the citation; the permanent artifacts"
            + " roadmap/changelog.md, roadmap/workflow.adoc and roadmap/README.md stay citable.");
        for (Finding f : result.findings()) {
            System.err.println("  " + f.doc() + ":" + f.line() + ": " + f.citation() + " in: " + f.content().strip());
        }
        throw new BuildFailure("transient roadmap citations in agent-onboarding prose");
    }

    /**
     * Resolves {@link #SCANNED_DOCS} against {@code root} and scans each one, reporting both
     * the citations found and any declared document that is absent.
     */
    static Result scan(Path root) throws IOException {
        List<Finding> findings = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        int scanned = 0;
        for (String doc : SCANNED_DOCS) {
            Path file = root.resolve(doc);
            if (!Files.isRegularFile(file)) {
                missing.add(doc);
                continue;
            }
            scanned++;
            for (Finding f : scanFile(file)) {
                findings.add(new Finding(doc, f.line(), f.citation(), f.content()));
            }
        }
        return new Result(findings, missing, scanned);
    }

    /**
     * Flags every item-id and non-permanent {@code roadmap/} path citation in {@code file}.
     * The {@code doc} field of each finding carries the file's own name; {@link #scan} rewrites
     * it to the repo-relative path it walked.
     */
    static List<Finding> scanFile(Path file) throws IOException {
        List<Finding> findings = new ArrayList<>();
        List<String> lines = Files.readAllLines(file);
        String name = file.getFileName().toString();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher ids = ITEM_ID.matcher(line);
            while (ids.find()) {
                findings.add(new Finding(name, i + 1, ids.group(), line));
            }
            Matcher paths = ROADMAP_PATH.matcher(line);
            while (paths.find()) {
                if (!PERMANENT_ARTIFACTS.contains(paths.group(1))) {
                    findings.add(new Finding(name, i + 1, paths.group(), line));
                }
            }
        }
        return findings;
    }

    record Finding(String doc, int line, String citation, String content) {}

    /** Outcome of a scan: what was cited, which declared documents were absent, how many were read. */
    record Result(List<Finding> findings, List<String> missing, int scanned) {}
}
