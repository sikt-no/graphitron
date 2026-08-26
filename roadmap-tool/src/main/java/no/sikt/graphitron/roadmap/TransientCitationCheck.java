package no.sikt.graphitron.roadmap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Fails the build when an agent-onboarding prose document cites a roadmap item by id
 * ({@code R<n>}), by {@code roadmap/<slug>} path, or by its bare slug in a code span. Items are
 * renumbered, ship and leave a numbering gap, or are discarded, so a citation is stale the moment
 * the item moves and a reader with no {@code roadmap/} directory has nothing to resolve it
 * against. The durable reference (a live symbol, a published doc page, or simply the fact) is what
 * belongs in prose instead.
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
 * <p><b>Three citation shapes, not two.</b> An id and a {@code roadmap/} path are both spelled with
 * something the regex can anchor on. A bare slug is not: stripped of its directory it is just a
 * hyphenated word, indistinguishable by shape from any other, and one reached a published
 * architecture page for exactly that reason. {@link #liveItemSlugs} closes the gap by resolving the
 * candidate instead of matching it, so a span counts as a citation when a file of that name exists
 * under {@code roadmap/}.
 *
 * <p>That third pattern is deliberately weaker than the other two in one direction and stricter in
 * another. Weaker, because it goes quiet once the item ships and its file is deleted, which is the
 * moment the citation becomes least resolvable; it catches the citation when it is written, not
 * after it rots. Stricter, because the span must be backticked. Dropping that requirement is not an
 * improvement available for free: slugs are named after the work they describe, so a slug's own
 * words recur in ordinary prose about the same subject, and a hyphenated compound adjective in a
 * sentence can spell one exactly. A survey of the scanned trees found such a collision already
 * present in the manual. Requiring backticks separates a name being used from words being written,
 * and keeps the check free of the one thing that would make it expensive, an exemption list.
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
     * <p>Both published trees are in scope. {@code docs/architecture} is where the rot was found;
     * {@code docs/manual} was left out at first on the claim that it cited nothing from
     * {@code roadmap/}, which was half right, since it carried no path citations but did carry
     * three item ids. The rule does not weaken across the seam: both trees render to the same
     * public site, where the {@code roadmap/} directory is not the reader's to search, so an id is
     * exactly as unresolvable on an author-facing page as on a contributor-facing one.
     *
     * <p>This scan widens where the sibling symbol gate deliberately does not. The two have
     * different costs: text matching has no exemption surface to grow, while resolving a cited
     * type against a classpath does, and the manual names consumer-visible generated types the
     * reactor never declares. Cheap here, not cheap there.
     */
    static final List<String> SCANNED_TREES = List.of(
        "docs/architecture",
        "docs/manual"
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

    /** The directory the third pattern resolves a candidate slug against. */
    private static final String ROADMAP_DIR = "roadmap";

    /**
     * A backticked span, and the slug candidate inside it. Only the content is examined, so a
     * cited slug is caught wherever a page happens to wrap it, and a bare word in running prose
     * is not a candidate at all.
     */
    private static final Pattern BACKTICKED_SLUG = Pattern.compile("`([a-z0-9]+(?:-[a-z0-9]+)+)`");

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
            + " An id, a roadmap/ path, and a bare slug in a code span all name a file that moves,"
            + " ships, or is discarded. Replace each with a durable reference (a live symbol, a"
            + " published docs page) or state the fact and drop the citation; the permanent"
            + " artifacts roadmap/changelog.md, roadmap/workflow.adoc and roadmap/README.md stay"
            + " citable.");
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
        Set<String> slugs = liveItemSlugs(root);
        int scanned = 0;
        for (String doc : SCANNED_DOCS) {
            Path file = root.resolve(doc);
            if (!Files.isRegularFile(file)) {
                missing.add(doc);
                continue;
            }
            scanned++;
            for (Finding f : scanFile(file, slugs)) {
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
                for (Finding f : scanFile(page, slugs)) {
                    findings.add(new Finding(relative, f.line(), f.citation(), f.content()));
                }
            }
        }
        return new Result(findings, missing, scanned);
    }

    /**
     * The slug of every item file living under {@code roadmap/} right now, which is the universe
     * the bare-slug pattern resolves a candidate against. The permanent artifacts are excluded,
     * since prose may name them.
     *
     * <p>An empty set is a legitimate answer rather than a failure: it says no item is currently
     * filed, and then no bare slug can be cited either. This is not the vacuous-scan case the two
     * habitats above guard against, where an empty result would mean the scan looked in the wrong
     * place. Here the scan looks at the same tree the rest of the tooling writes to, and the tree
     * being absent is caught by every other roadmap command long before this one runs.
     */
    static Set<String> liveItemSlugs(Path root) throws IOException {
        Path dir = root.resolve(ROADMAP_DIR);
        if (!Files.isDirectory(dir)) return Set.of();
        try (var paths = Files.list(dir)) {
            return paths.filter(Files::isRegularFile)
                .map(p -> p.getFileName().toString())
                .filter(name -> name.endsWith(".md"))
                .filter(name -> !PERMANENT_ARTIFACTS.contains(name))
                .map(name -> name.substring(0, name.length() - ".md".length()))
                .collect(Collectors.toUnmodifiableSet());
        }
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
     * Scans {@code file} against the two pattern-matched citation shapes only. The bare-slug
     * shape needs a slug universe to resolve against, which a lone file does not carry; the
     * overload below takes one.
     */
    static List<Finding> scanFile(Path file) throws IOException {
        return scanFile(file, Set.of());
    }

    /**
     * Flags every item-id citation, non-permanent {@code roadmap/} path citation, and backticked
     * span naming one of {@code liveSlugs} in {@code file}. The {@code doc} field of each finding
     * carries the file's own name; {@link #scan} rewrites it to the repo-relative path it walked.
     */
    static List<Finding> scanFile(Path file, Set<String> liveSlugs) throws IOException {
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
            if (liveSlugs.isEmpty()) continue;
            Matcher slugs = BACKTICKED_SLUG.matcher(line);
            while (slugs.find()) {
                String candidate = slugs.group(1);
                // A path citation already reported this line's slug; reporting it twice would
                // make one sentence look like two problems.
                if (liveSlugs.contains(candidate) && !line.contains(ROADMAP_DIR + "/" + candidate)) {
                    findings.add(new Finding(name, i + 1, candidate, line));
                }
            }
        }
        return findings;
    }



    record Finding(String doc, int line, String citation, String content) {}

    /** Outcome of a scan: what was cited, which declared documents were absent, how many were read. */
    record Result(List<Finding> findings, List<String> missing, int scanned) {}
}
