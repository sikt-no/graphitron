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
 * <p>Two habitats, one rule. {@link #SCANNED_DOCS} is a fixed list of the prose documents an agent
 * session reads before acting. {@link #SCANNED_TREES} is a walked tree of published documentation
 * pages, added because the architecture pages carry the same rot and nothing scanned them: the
 * Java-source guard parses comment and string regions, which an {@code .adoc} file has none of,
 * and the fixed list never reached {@code docs/}. Those pages render to a public site where the
 * {@code roadmap/} directory is not the reader's to search, so a stale id there resolves to
 * nothing at all.
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
     * The walked habitat: doc trees whose pages carry the same rule. These are a tree rather than
     * a list because the pages move between quadrants, and enumerating nineteen paths would put a
     * scan behind every page rename. The floor that keeps a list honest applies here too, one
     * level up: a declared tree that yields no pages fails, so a renamed directory cannot quietly
     * shrink the scan to nothing.
     *
     * <p>{@code docs/architecture} is the tree the rot was found in, and the narrow scope is
     * deliberate. {@code docs/manual} is author-facing and cites nothing from {@code roadmap/},
     * so widening buys nothing today; widening it later is one line.
     */
    static final List<String> SCANNED_TREES = List.of(
        "docs/architecture"
    );

    /** The extension a page in a scanned tree must carry to be read. */
    private static final String PAGE_SUFFIX = ".adoc";

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
    private static final Set<String> PERMANENT_ARTIFACTS =
        Set.of("changelog.md", "workflow.adoc", "README.md", "index.adoc");

    /**
     * Citations already in the walked habitat when the walk was added, each one real. Carrying
     * them lets the guard land before the pages are rewritten rather than after, which is the
     * order that makes the rewrite verifiable: the mechanism that will hold the pages is in place
     * while they are edited. Every entry is {@code <repo-relative page>|<citation>}.
     *
     * <p>This is a burn-down list, not a suppression list, and {@link #staleBaselineEntries} is
     * what keeps the distinction real: an entry whose citation is gone fails the check, so the set
     * empties itself as the pages are cleaned and cannot survive as a permanent exemption. When it
     * reaches empty, delete it, {@link #staleBaselineEntries}, and this javadoc with it.
     */
    static final Set<String> KNOWN_CITATIONS = knownCitations();

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

        List<String> stale = isBaselineRoot(root) ? staleBaselineEntries(root, KNOWN_CITATIONS) : List.of();
        if (!stale.isEmpty()) {
            System.err.println("check-transient-citations: " + stale.size()
                + " baseline entr(ies) in TransientCitationCheck.KNOWN_CITATIONS name a citation"
                + " that is no longer in the tree. The baseline is a burn-down list: delete the"
                + " entry in the commit that removed the citation, so it cannot survive as a"
                + " permanent exemption.");
            for (String entry : stale) {
                System.err.println("  " + entry);
            }
            throw new BuildFailure("stale transient-citation baseline entries");
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
        for (String tree : SCANNED_TREES) {
            List<Path> pages = pagesUnder(root.resolve(tree));
            if (pages.isEmpty()) {
                missing.add(tree + " (declared tree, no " + PAGE_SUFFIX + " pages found)");
                continue;
            }
            for (Path page : pages) {
                scanned++;
                String relative = root.relativize(page).toString().replace('\\', '/');
                for (Finding f : scanFile(page)) {
                    if (KNOWN_CITATIONS.contains(relative + "|" + f.citation())) continue;
                    findings.add(new Finding(relative, f.line(), f.citation(), f.content()));
                }
            }
        }
        return new Result(findings, missing, scanned);
    }

    /**
     * Pages under one declared tree, sorted so a failure list is stable. Excludes {@code target/}:
     * the docs module stages a rendered copy of these trees there, and a citation would be
     * reported twice, once at a path nobody edits.
     */
    static List<Path> pagesUnder(Path tree) throws IOException {
        if (!Files.isDirectory(tree)) return List.of();
        try (var paths = Files.walk(tree)) {
            return paths.filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(PAGE_SUFFIX))
                .filter(p -> !p.toString().contains("/target/"))
                .sorted()
                .toList();
        }
    }

    /**
     * Baseline entries whose citation is no longer in the tree. Each one is an edit that cleaned a
     * page without deleting the line that recorded it, which is how a burn-down list turns into a
     * permanent exemption if nothing checks.
     */
    static List<String> staleBaselineEntries(Path root, Set<String> baseline) throws IOException {
        Set<String> live = new java.util.LinkedHashSet<>();
        for (String tree : SCANNED_TREES) {
            for (Path page : pagesUnder(root.resolve(tree))) {
                String relative = root.relativize(page).toString().replace('\\', '/');
                for (Finding f : scanFile(page)) {
                    live.add(relative + "|" + f.citation());
                }
            }
        }
        return baseline.stream().filter(entry -> !live.contains(entry)).sorted().toList();
    }

    /**
     * Whether {@code root} is the repository the baseline was written against, by the same
     * {@code roadmap/workflow.adoc} anchor the Java-source guards walk up to find. The baseline
     * names paths in this repository, so against any other root every entry would read as stale
     * and the check would fail for a reason that has nothing to do with a citation.
     */
    static boolean isBaselineRoot(Path root) {
        return Files.isRegularFile(root.resolve("roadmap/workflow.adoc"));
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


    private static Set<String> knownCitations() {
        Set<String> known = new java.util.LinkedHashSet<>();
        // explanation/dispatch-axes.adoc
        known.add("docs/architecture/explanation/dispatch-axes.adoc|R305");
        known.add("docs/architecture/explanation/dispatch-axes.adoc|R314");
        known.add("docs/architecture/explanation/dispatch-axes.adoc|R431");
        known.add("docs/architecture/explanation/dispatch-axes.adoc|R432");
        // explanation/typed-rejection.adoc
        known.add("docs/architecture/explanation/typed-rejection.adoc|R96");
        known.add("docs/architecture/explanation/typed-rejection.adoc|R181");
        known.add("docs/architecture/explanation/typed-rejection.adoc|R188");
        known.add("docs/architecture/explanation/typed-rejection.adoc|R190");
        known.add("docs/architecture/explanation/typed-rejection.adoc|R215");
        known.add("docs/architecture/explanation/typed-rejection.adoc|R238");
        known.add("docs/architecture/explanation/typed-rejection.adoc|R244");
        known.add("docs/architecture/explanation/typed-rejection.adoc|R246");
        known.add("docs/architecture/explanation/typed-rejection.adoc|R256");
        known.add("docs/architecture/explanation/typed-rejection.adoc|R261");
        known.add("docs/architecture/explanation/typed-rejection.adoc|R266");
        known.add("docs/architecture/explanation/typed-rejection.adoc|R275");
        known.add("docs/architecture/explanation/typed-rejection.adoc|R308");
        known.add("docs/architecture/explanation/typed-rejection.adoc|R322");
        known.add("docs/architecture/explanation/typed-rejection.adoc|R354");
        known.add("docs/architecture/explanation/typed-rejection.adoc|R453");
        known.add("docs/architecture/explanation/typed-rejection.adoc|R457");
        known.add("docs/architecture/explanation/typed-rejection.adoc|R501");
        // how-to/dev-loop-internals.adoc
        known.add("docs/architecture/how-to/dev-loop-internals.adoc|R118");
        known.add("docs/architecture/how-to/dev-loop-internals.adoc|R385");
        // how-to/release-natives.adoc
        known.add("docs/architecture/how-to/release-natives.adoc|R401");
        // principles/development-principles.adoc
        known.add("docs/architecture/principles/development-principles.adoc|R50");
        known.add("docs/architecture/principles/development-principles.adoc|R79");
        known.add("docs/architecture/principles/development-principles.adoc|R239");
        known.add("docs/architecture/principles/development-principles.adoc|R240");
        known.add("docs/architecture/principles/development-principles.adoc|R260");
        known.add("docs/architecture/principles/development-principles.adoc|R268");
        known.add("docs/architecture/principles/development-principles.adoc|R334");
        // reference/code-generation-triggers.adoc
        known.add("docs/architecture/reference/code-generation-triggers.adoc|R145");
        known.add("docs/architecture/reference/code-generation-triggers.adoc|R431");
        known.add("docs/architecture/reference/code-generation-triggers.adoc|R432");
        // reference/modules.adoc
        known.add("docs/architecture/reference/modules.adoc|R118");
        known.add("docs/architecture/reference/modules.adoc|R385");
        known.add("docs/architecture/reference/modules.adoc|R399");
        known.add("docs/architecture/reference/modules.adoc|R416");
        // reference/runtime-extension-points.adoc
        known.add("docs/architecture/reference/runtime-extension-points.adoc|R45");
        known.add("docs/architecture/reference/runtime-extension-points.adoc|R190");
        known.add("docs/architecture/reference/runtime-extension-points.adoc|R192");
        known.add("docs/architecture/reference/runtime-extension-points.adoc|R429");
        return Set.copyOf(known);
    }

    record Finding(String doc, int line, String citation, String content) {}

    /** Outcome of a scan: what was cited, which declared documents were absent, how many were read. */
    record Result(List<Finding> findings, List<String> missing, int scanned) {}
}
