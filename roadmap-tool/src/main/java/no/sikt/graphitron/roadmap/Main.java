package no.sikt.graphitron.roadmap;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads {@code *.md} files in the roadmap directory, parses YAML front-matter,
 * and renders a {@code README.md} that aggregates them into Active and Backlog
 * sections.
 *
 * <p>Two modes:
 * <ul>
 *   <li>{@code generate &lt;dir&gt;}: write {@code dir/README.md}.</li>
 *   <li>{@code verify &lt;dir&gt;}: exit non-zero if {@code dir/README.md} is
 *       out of date relative to the current item files.</li>
 * </ul>
 *
 * <p>Validation runs in both modes and fails the run on any of the following:
 * unknown {@code theme:}, unknown {@code depends-on:} slug, dependency cycle.
 */
public final class Main {

    private static final List<String> ACTIVE_STATES = List.of("Spec", "Ready", "In Progress", "In Review");
    private static final List<String> BACKLOG_BUCKETS = List.of("architecture", "stubs", "cleanup", "validation");

    /**
     * Per-item identifier shape: literal {@code R} followed by a positive
     * integer. IDs are monotonic across the roadmap and never reused, so a
     * reference like "R24" stays meaningful even after the item file is
     * deleted on Done. The next-id allocator returns {@code R<max+1>}.
     */
    private static final Pattern ID_PATTERN = Pattern.compile("^R(\\d+)$");

    /**
     * Closed set of cross-cutting tags. {@code theme:} answers "what area is
     * this in?" orthogonal to {@code bucket:} (which answers "what kind of
     * work?"). Adding a new theme requires editing this list and the
     * accompanying docs.
     */
    static final List<String> VALID_THEMES = List.of(
        "classification-model",
        "diagnostics",
        "routine",
        "nodeid",
        "interface-union",
        "service",
        "mutation-write",
        "error-channel",
        "pagination",
        "runtime-connection",
        "search",
        "lsp",
        "dev-loop",
        "codegen-correctness",
        "model-cleanup",
        "legacy-migration",
        "docs",
        "tooling",
        "testing"
    );

    private Main() {}

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            usage();
        }
        String mode = args[0];
        if ("directive-support".equals(mode)) {
            int rc = DirectiveSupportReport.run(sliceArgs(args, 1));
            if (rc != 0) System.exit(rc);
            return;
        }
        if ("leaf-coverage".equals(mode)) {
            int rc = LeafCoverageReport.run(sliceArgs(args, 1));
            if (rc != 0) System.exit(rc);
            return;
        }
        if ("check-adoc-tables".equals(mode)) {
            int rc = AdocMarkdownTableCheck.run(sliceArgs(args, 1));
            if (rc != 0) System.exit(rc);
            return;
        }
        if (args.length < 2) {
            usage();
        }
        Path dir = Path.of(args[1]).toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            System.err.println("not a directory: " + dir);
            System.exit(64);
        }

        switch (mode) {
            case "generate" -> runGenerate(dir);
            case "verify" -> runVerify(dir);
            case "next-id" -> runNextId(dir);
            case "create" -> runCreate(dir, sliceArgs(args, 2));
            case "status" -> runStatus(dir, sliceArgs(args, 2));
            case "render-adoc" -> runRenderAdoc(dir, sliceArgs(args, 2));
            default -> usage();
        }
    }

    private static void usage() {
        System.err.println("usage:");
        System.err.println("  generate    <roadmap-dir>");
        System.err.println("  verify      <roadmap-dir>");
        System.err.println("  next-id     <roadmap-dir>");
        System.err.println("  create      <roadmap-dir> <slug> --title \"<title>\""
            + " [--bucket <bucket>] [--priority <n>] [--theme <theme>]");
        System.err.println("  status      <roadmap-dir> <R<n>-or-slug> <new-state>");
        System.err.println("  render-adoc <roadmap-dir> <output-dir>");
        System.err.println("  directive-support <legacy-directives.graphqls>"
            + " <rewrite-directives.graphqls> <fixture-dir>[:<fixture-dir>...]"
            + " [--mode=migration] [--output=<path>] [--verify]");
        System.err.println("  leaf-coverage <root-dir> [--verify] [--mode=migration]"
            + " [--output=<path>]");
        System.err.println("  check-adoc-tables <root-dir>...");
        System.exit(64);
    }

    private static List<String> sliceArgs(String[] args, int from) {
        List<String> out = new ArrayList<>();
        for (int i = from; i < args.length; i++) out.add(args[i]);
        return out;
    }

    private static void runGenerate(Path dir) throws IOException {
        List<Item> items = readItems(dir);
        validate(items);
        Path readme = dir.resolve("README.md");
        Files.writeString(readme, render(items, ConceptIndex.of(items, ConceptPages.readPages(dir))));
        System.out.println("wrote " + readme);
    }

    private static void runVerify(Path dir) throws IOException {
        List<Item> items = readItems(dir);
        validate(items);
        Path readme = dir.resolve("README.md");
        String rendered = render(items, ConceptIndex.of(items, ConceptPages.readPages(dir)));
        String existing = Files.exists(readme) ? Files.readString(readme) : "";
        if (!existing.equals(rendered)) {
            System.err.println("roadmap README.md is out of date. Regenerate with:");
            System.err.println("  mvn -pl :graphitron-roadmap-tool exec:java");
            // Throw rather than System.exit: this runs via exec-maven-plugin's `java` goal in
            // the Maven JVM, so System.exit would kill Maven before it prints BUILD FAILURE.
            // The exception lets the plugin wrap it as MojoExecutionException; the stderr above
            // stays readable in the execution's INFO block. See BuildFailure for the rationale.
            throw new BuildFailure("roadmap README.md drift");
        }
        System.out.println("roadmap README.md is up to date.");
    }

    private static void runNextId(Path dir) throws IOException {
        List<Item> items = readItems(dir);
        // No validate() here so a malformed sibling doesn't block ID allocation
        // for the file the caller is about to write.
        System.out.println(nextId(items, readChangelogNextId(dir)));
    }

    /**
     * Returns the next available ID as {@code R<n>}. The authoritative counter
     * lives in {@code changelog.md}'s front-matter as {@code next-id: R<n>};
     * this method takes its current value and reconciles it with the highest
     * ID observed on live item files. The returned ID is the larger of the
     * two, so a counter that has fallen behind (e.g. an item file authored
     * with an explicit higher ID before the counter was advanced) self-heals
     * on the next allocation. Returns {@code R1} when neither source carries
     * a value. Numbers are never reused: gaps left by deleted items stay as
     * gaps so historical references in {@code changelog.md} or commit
     * messages don't collide with future allocations.
     */
    static String nextId(List<Item> items, int changelogCounter) {
        int observed = 0;
        for (Item i : items) {
            if (i.id() == null) continue;
            var m = ID_PATTERN.matcher(i.id());
            if (m.matches()) {
                observed = Math.max(observed, Integer.parseInt(m.group(1)));
            }
        }
        return "R" + Math.max(changelogCounter, observed + 1);
    }

    /**
     * Reads the {@code next-id:} field from {@code changelog.md}'s front-matter,
     * or {@code 0} if the file or field is absent. Throws if the field exists
     * but is malformed.
     */
    static int readChangelogNextId(Path roadmapDir) throws IOException {
        Path changelog = roadmapDir.resolve("changelog.md");
        if (!Files.exists(changelog)) return 0;
        ParsedFile parsed = parseFrontMatter(Files.readString(changelog));
        Object raw = parsed.frontMatter().get("next-id");
        if (raw == null) return 0;
        var m = ID_PATTERN.matcher(raw.toString());
        if (!m.matches()) {
            throw new IllegalArgumentException("changelog.md next-id: '" + raw
                + "' is malformed. Expected R<positive integer>.");
        }
        return Integer.parseInt(m.group(1));
    }

    /**
     * Updates {@code changelog.md}'s front-matter so {@code next-id:} reflects
     * {@code "R" + nextId}, preserving any other front-matter keys and the
     * body verbatim. Creates the front-matter block if absent.
     */
    static void writeChangelogNextId(Path roadmapDir, int nextId) throws IOException {
        Path changelog = roadmapDir.resolve("changelog.md");
        String existing = Files.exists(changelog) ? Files.readString(changelog) : "";
        boolean hasFrontMatter =
            existing.startsWith("---\n") || existing.startsWith("---\r\n");

        if (hasFrontMatter) {
            // Patch next-id: in place, preserving every other front-matter
            // line and the body byte-for-byte; same safe write path as
            // applyStatusTransition. R<n> is always a quote-safe plain scalar.
            Map<String, String> updates = new LinkedHashMap<>();
            updates.put("next-id", "R" + nextId);
            Files.writeString(changelog, patchFrontMatter(existing, updates));
            return;
        }

        // No front-matter block yet: create one, preserving any existing body.
        StringBuilder out = new StringBuilder("---\n");
        out.append("next-id: R").append(nextId).append('\n');
        out.append("---\n");
        if (!existing.isEmpty()) {
            if (!existing.startsWith("\n")) out.append('\n');
            out.append(existing);
        }
        Files.writeString(changelog, out.toString());
    }

    private static void runCreate(Path dir, List<String> rest) throws IOException {
        if (rest.isEmpty()) {
            usage();
        }
        String slug = rest.get(0);
        Map<String, String> opts = parseOptions(rest.subList(1, rest.size()));
        String title = opts.get("title");
        if (title == null || title.isBlank()) {
            System.err.println("create: --title is required");
            System.exit(64);
        }
        Path target = dir.resolve(slug + ".md");
        if (Files.exists(target)) {
            System.err.println("create: file already exists: " + target);
            System.exit(1);
        }
        if (!slug.matches("[a-z0-9][a-z0-9-]*")) {
            System.err.println("create: slug must be lowercase kebab-case: " + slug);
            System.exit(64);
        }
        List<Item> items = readItems(dir);
        validate(items);
        int counter = readChangelogNextId(dir);
        String id = nextId(items, counter);
        int allocated = Integer.parseInt(id.substring(1));

        String today = LocalDate.now().toString();
        StringBuilder fm = new StringBuilder();
        fm.append("---\n");
        fm.append("id: ").append(id).append('\n');
        fm.append("title: \"").append(title.replace("\"", "\\\"")).append("\"\n");
        fm.append("status: Backlog\n");
        if (opts.containsKey("bucket")) fm.append("bucket: ").append(opts.get("bucket")).append('\n');
        if (opts.containsKey("priority")) fm.append("priority: ").append(opts.get("priority")).append('\n');
        if (opts.containsKey("theme")) fm.append("theme: ").append(opts.get("theme")).append('\n');
        fm.append("depends-on: []\n");
        fm.append("created: ").append(today).append('\n');
        fm.append("last-updated: ").append(today).append('\n');
        fm.append("---\n\n");
        fm.append("# ").append(title).append("\n\n");
        fm.append("<One-paragraph problem statement: what is missing or broken, and why it matters."
            + " Replace this and add a plan body when the item moves to Spec.>\n");

        Files.writeString(target, fm.toString());
        writeChangelogNextId(dir, allocated + 1);
        System.out.println("created " + target + " (" + id + ")");

        // Refresh the rolled-up README so the new item shows up immediately.
        runGenerate(dir);
    }

    /**
     * Valid {@code Ready ← In Progress ← …} transitions accepted by the
     * {@code status} subcommand. {@code Done} and {@code Discarded} are
     * file-deletion transitions per {@code workflow.adoc} and remain manual.
     * The reviewer-rule guard ("reviewer ≠ last committer" /
     * "reviewer ≠ implementer") is the human/agent gate that happens before
     * the call; the tool performs the mechanical edit unconditionally.
     */
    private static final Set<String> TARGET_STATES = Set.of(
        "Backlog", "Spec", "Ready", "In Progress", "In Review");

    private static final Map<String, Set<String>> ALLOWED_TRANSITIONS = Map.of(
        "Backlog", Set.of("Spec"),
        "Spec", Set.of("Spec", "Ready"),
        "Ready", Set.of("Spec", "In Progress"),
        "In Progress", Set.of("In Review"),
        "In Review", Set.of("Ready")
    );

    private static void runStatus(Path dir, List<String> rest) throws IOException {
        if (rest.size() < 2) {
            System.err.println("status: usage: status <roadmap-dir> <R<n>-or-slug> <new-state>");
            System.exit(64);
        }
        String idOrSlug = rest.get(0);
        String newState = rest.get(1);

        Path target = resolveItemFile(dir, idOrSlug);
        if (target == null) {
            System.err.println("status: no roadmap item matches '" + idOrSlug + "' in " + dir);
            System.exit(1);
        }

        String previousState;
        try {
            previousState = applyStatusTransition(target, newState);
        } catch (IllegalArgumentException e) {
            System.err.println("status: " + e.getMessage());
            System.exit(1);
            return; // unreachable
        }
        System.out.println("status: " + target.getFileName() + ": "
            + previousState + " -> " + newState);

        // Refresh the rolled-up README so the new state shows up immediately.
        runGenerate(dir);
    }

    /**
     * Validates and applies a status transition to a single roadmap item file.
     * Writes the new {@code status:} value and a fresh {@code last-updated:
     * <today>}, leaves {@code created:} strictly untouched, and returns the
     * previous {@code status:} value (or {@code "Backlog"} if absent).
     *
     * <p>Throws {@link IllegalArgumentException} if {@code newState} is not in
     * {@link #TARGET_STATES} (which excludes {@code Done} and {@code Discarded}:
     * those are file-deletion transitions per {@code workflow.adoc} and remain
     * manual) or if the transition from the file's current status is not in
     * {@link #ALLOWED_TRANSITIONS}. The reviewer-rule guard is not enforced
     * here; the caller (the skill) is responsible for that gate.
     */
    static String applyStatusTransition(Path target, String newState) throws IOException {
        if (!TARGET_STATES.contains(newState)) {
            throw new IllegalArgumentException("'" + newState
                + "' is not an accepted target state. Accepted: " + TARGET_STATES
                + ". Done and Discarded are file-deletion transitions per workflow.adoc"
                + " and remain manual.");
        }

        String content = Files.readString(target);
        ParsedFile parsed = parseFrontMatter(content);
        String currentState = (String) parsed.frontMatter().getOrDefault("status", "Backlog");
        Set<String> allowed = ALLOWED_TRANSITIONS.getOrDefault(currentState, Set.of());
        if (!allowed.contains(newState)) {
            throw new IllegalArgumentException("cannot transition '" + currentState
                + "' -> '" + newState + "'. Allowed from '" + currentState + "': " + allowed + ".");
        }

        // Patch status: and last-updated: in place, leaving every other line
        // (notably an already-quoted title:) byte-for-byte untouched. created:
        // is strictly untouched; never invented, never overwritten.
        Map<String, String> updates = new LinkedHashMap<>();
        updates.put("status", newState);
        updates.put("last-updated", LocalDate.now().toString());
        Files.writeString(target, patchFrontMatter(content, updates));
        return currentState;
    }

    /**
     * Resolves an item reference to its file path. Accepts either a slug
     * (matched by filename) or an {@code R<n>} ID (matched by greping
     * front-matter). Returns {@code null} if no item matches.
     */
    static Path resolveItemFile(Path dir, String idOrSlug) throws IOException {
        Path bySlug = dir.resolve(idOrSlug + ".md");
        if (Files.exists(bySlug)) return bySlug;
        if (!ID_PATTERN.matcher(idOrSlug).matches()) return null;
        try (Stream<Path> s = Files.list(dir)) {
            return s
                .filter(p -> p.getFileName().toString().endsWith(".md"))
                .filter(p -> !p.getFileName().toString().equals("README.md"))
                .filter(p -> !p.getFileName().toString().equals("changelog.md"))
                .filter(p -> {
                    try {
                        ParsedFile pf = parseFrontMatter(Files.readString(p));
                        return idOrSlug.equals(pf.frontMatter().get("id"));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })
                .findFirst()
                .orElse(null);
        }
    }

    /**
     * Emit the roadmap as a tree of {@code .adoc} files into {@code outDir} so the
     * documentation site (R9) can pick it up. Outputs:
     *
     * <ul>
     *   <li>{@code index.adoc}: status board (Active grouped by status, Backlog
     *       grouped by bucket).</li>
     *   <li>{@code by-theme.adoc}: cross-cutting "by theme" index.</li>
     *   <li>{@code changelog.adoc}: converted from {@code changelog.md}.</li>
     *   <li>{@code plans/<slug>.adoc}: one page per Active or Backlog item, with
     *       a small attribute box and the markdown body lightly converted to
     *       AsciiDoc (option 1, "pass-through with header" per R9 plan).</li>
     * </ul>
     *
     * <p>The status board and per-plan pages do their own markdown→AsciiDoc
     * link rewriting so cross-references to the rendered architecture pages
     * (under {@code architecture/}) and to other rendered plans land at working
     * {@code xref:} targets.
     */
    private static void runRenderAdoc(Path roadmapDir, List<String> rest) throws IOException {
        if (rest.isEmpty()) {
            System.err.println("render-adoc: <output-dir> is required");
            System.exit(64);
        }
        Path outDir = Path.of(rest.get(0)).toAbsolutePath().normalize();
        Files.createDirectories(outDir);
        Path plansDir = outDir.resolve("plans");
        Files.createDirectories(plansDir);

        List<Item> items = readItems(roadmapDir);
        validate(items);
        ConceptIndex concepts = ConceptIndex.of(items, ConceptPages.readPages(roadmapDir));

        Files.writeString(outDir.resolve("index.adoc"), renderAdocStatusBoard(items, concepts));
        Files.writeString(outDir.resolve("by-theme.adoc"), renderAdocByTheme(items));

        Path changelog = roadmapDir.resolve("changelog.md");
        if (Files.exists(changelog)) {
            Files.writeString(outDir.resolve("changelog.adoc"),
                renderAdocChangelog(Files.readString(changelog)));
        }

        for (Item i : items) {
            Files.writeString(plansDir.resolve(i.slug() + ".adoc"), renderAdocPlan(i));
        }

        // Stage the leaf-coverage report alongside index/by-theme/changelog so the rendered
        // roadmap site links to a real page rather than a GitHub source URL. The report is
        // already a fully-formed AsciiDoc page (= title + :description: + tables); copying it
        // verbatim is enough.
        Path inferenceReport = roadmapDir.resolve("inference-axis-coverage.adoc");
        if (Files.exists(inferenceReport)) {
            Files.copy(inferenceReport, outDir.resolve("inference-axis-coverage.adoc"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        // Stage concept explainer pages (R486). Unlike the .adoc copy above, raw
        // HTML gets no downstream asciidoctor pass, so href values are rewritten
        // from the repo layout to the site layout at this point; page content is
        // otherwise untouched, and the shared assets are copied byte-for-byte.
        ConceptPages.stage(roadmapDir, outDir.resolve("concepts"));

        System.out.println("rendered " + items.size() + " plans + roadmap/by-theme/changelog into " + outDir);
    }

    /** Status board: Active by status, Backlog by bucket. */
    static String renderAdocStatusBoard(List<Item> items) {
        return renderAdocStatusBoard(items, ConceptIndex.empty());
    }

    /** Status board with concept-explainer cross-links derived from {@code concepts} (R488). */
    static String renderAdocStatusBoard(List<Item> items, ConceptIndex concepts) {
        StringBuilder sb = new StringBuilder();
        sb.append("= Rewrite Roadmap\n");
        sb.append(":description: Active and Backlog work on the Graphitron rewrite generator.\n\n");
        sb.append("This is the rendered roadmap. Plans are authored as markdown in ")
          .append("`roadmap/`; this view derives from the per-item front-matter ")
          .append("and the plan bodies. For the model taxonomy, see ")
          .append("xref:../architecture/reference/code-generation-triggers.adoc[Code Generation Triggers]. ")
          .append("For design principles, see ")
          .append("xref:../architecture/explanation/development-principles.adoc[Graphitron Development Principles]. ")
          .append("For per-leaf classifier coverage, see ")
          .append("xref:inference-axis-coverage.adoc[Inference-axis coverage report]. ")
          .append("Or jump to the xref:by-theme.adoc[by-theme view] or the xref:changelog.adoc[changelog]. ")
          .append("Back to xref:../index.adoc[home].\n\n");

        sb.append("== Active\n\n");
        List<Item> active = items.stream()
            .filter(i -> ACTIVE_STATES.contains(i.status()))
            .sorted(Comparator
                .comparingInt((Item i) -> i.priority() == null ? Integer.MAX_VALUE : i.priority())
                .thenComparingInt(i -> ACTIVE_STATES.indexOf(i.status()))
                .thenComparing(Item::title))
            .toList();
        if (active.isEmpty()) {
            sb.append("_(none)_\n\n");
        } else {
            sb.append("[cols=\"1,4,1,1,1\", options=\"header\"]\n");
            sb.append("|===\n");
            sb.append("| ID | Item | Status | Updated | Plan\n");
            for (Item i : active) {
                String status = i.status() + (i.deferred() ? " (deferred)" : "");
                sb.append("| `").append(i.id()).append("`\n");
                sb.append("| ").append(escapeAdocCell(i.title()));
                if (!i.dependsOn().isEmpty()) {
                    sb.append(" +\n_blocked by: ").append(linkAdocSlugs(i.dependsOn(), ChangelogContext.STANDALONE)).append("_");
                }
                sb.append("\n| ").append(status).append("\n");
                sb.append("| ").append(renderUpdatedCellAdoc(i)).append("\n");
                sb.append("| xref:plans/").append(i.slug()).append(".adoc[plan]")
                  .append(adocExplainerSuffix(concepts, i.id())).append("\n");
            }
            sb.append("|===\n\n");
        }

        sb.append("== Backlog\n\n");
        List<Item> backlog = items.stream()
            .filter(i -> "Backlog".equals(i.status()))
            .toList();
        if (backlog.isEmpty()) {
            sb.append("_(none)_\n\n");
        } else {
            List<Item> activeBacklog = backlog.stream().filter(i -> !i.deferred()).toList();
            List<Item> deferredBacklog = backlog.stream().filter(Item::deferred).toList();

            for (String bucket : BACKLOG_BUCKETS) {
                List<Item> b = activeBacklog.stream()
                    .filter(i -> bucket.equals(i.bucket()))
                    .sorted(Comparator
                        .comparingInt((Item i) -> i.priority() == null ? Integer.MAX_VALUE : i.priority())
                        .thenComparing(Item::title))
                    .toList();
                if (b.isEmpty()) continue;
                sb.append("=== ").append(capitalize(bucket)).append("\n\n");
                for (Item i : b) appendBacklogAdocLine(sb, i, concepts);
                sb.append("\n");
            }
            List<Item> orphans = activeBacklog.stream()
                .filter(i -> i.bucket() == null || !BACKLOG_BUCKETS.contains(i.bucket()))
                .sorted(Comparator.comparing(Item::title))
                .toList();
            if (!orphans.isEmpty()) {
                sb.append("=== Other\n\n");
                for (Item i : orphans) appendBacklogAdocLine(sb, i, concepts);
                sb.append("\n");
            }
            if (!deferredBacklog.isEmpty()) {
                sb.append("=== Deferred\n\n");
                sb.append("Items parked until a blocking concern is resolved or re-prioritised. ");
                sb.append("Set `deferred: false` (or remove the field) to return an item to the active backlog.\n\n");
                deferredBacklog.stream()
                    .sorted(Comparator
                        .comparingInt((Item i) -> i.priority() == null ? Integer.MAX_VALUE : i.priority())
                        .thenComparing(Item::title))
                    .forEach(i -> {
                        sb.append("* `").append(i.id()).append("` xref:plans/")
                          .append(i.slug()).append(".adoc[").append(escapeAdocCell(i.title())).append("]");
                        sb.append(adocExplainerParenthetical(concepts, i.id()));
                        if (i.notes() != null && !i.notes().isBlank()) {
                            sb.append(": _").append(i.notes().strip()).append("_");
                        }
                        if (!i.dependsOn().isEmpty()) {
                            sb.append(" _(blocked by ").append(linkAdocSlugs(i.dependsOn(), ChangelogContext.STANDALONE)).append(")_");
                        }
                        sb.append("\n");
                    });
                sb.append("\n");
            }
        }

        if (!concepts.isEmpty()) {
            sb.append("== Concept explainers\n\n");
            sb.append("Intuition-first background pages for dense or recurring roadmap concepts, ");
            sb.append("rendered as interactive HTML. Authored with the `explainer` skill; ");
            sb.append("this listing derives from `roadmap/concepts/*.html`, never by hand.\n\n");
            concepts.pages().forEach((slug, page) -> {
                sb.append("* link:concepts/").append(slug).append(".html[")
                  .append(escapeAdocCell(page.title())).append("]")
                  .append(adocBacks(concepts.anchorsFor(slug)))
                  .append("\n");
            });
            sb.append("\n");
        }

        sb.append("== Done\n\n");
        sb.append("See xref:changelog.adoc[Changelog] for the historical record of shipped rewrite work. ");
        sb.append("Plan files are deleted on Done; git history preserves them.\n");
        return sb.toString();
    }

    private static void appendBacklogAdocLine(StringBuilder sb, Item i, ConceptIndex concepts) {
        sb.append("* `").append(i.id()).append("` xref:plans/").append(i.slug()).append(".adoc[")
          .append(escapeAdocCell(i.title())).append("]");
        sb.append(adocExplainerParenthetical(concepts, i.id()));
        String description = firstNonHeadingParagraph(i.body());
        if (!description.isEmpty()) {
            sb.append(": ").append(escapeAdocInline(description));
        }
        String dateAnnotation = renderBacklogDateAnnotationAdoc(i);
        if (!dateAnnotation.isEmpty()) {
            sb.append(" ").append(dateAnnotation);
        }
        if (!i.dependsOn().isEmpty()) {
            sb.append(" _(blocked by ").append(linkAdocSlugs(i.dependsOn(), ChangelogContext.STANDALONE)).append(")_");
        }
        sb.append("\n");
    }

    /**
     * AsciiDoc "Updated" cell: {@code last-updated:} on the main line; if
     * {@code created:} differs, append a line-break and a small-text
     * {@code created Y-M-D} annotation underneath. Empty cell when
     * {@code last-updated:} is absent.
     */
    private static String renderUpdatedCellAdoc(Item i) {
        if (i.lastUpdated() == null) return "";
        if (i.created() != null && !i.created().equals(i.lastUpdated())) {
            return i.lastUpdated() + " +\n[small]#created " + i.created() + "#";
        }
        return i.lastUpdated().toString();
    }

    /**
     * AsciiDoc backlog date annotation: same shape as the markdown version,
     * rendered as italic prose: {@code _(updated Y-M-D)_} or {@code _(updated
     * Y-M-D, created Y-M-D)_}. Empty when {@code last-updated:} is absent.
     */
    private static String renderBacklogDateAnnotationAdoc(Item i) {
        if (i.lastUpdated() == null) return "";
        if (i.created() != null && !i.created().equals(i.lastUpdated())) {
            return "_(updated " + i.lastUpdated() + ", created " + i.created() + ")_";
        }
        return "_(updated " + i.lastUpdated() + ")_";
    }

    /** Cross-cutting view by {@code theme:}. */
    static String renderAdocByTheme(List<Item> items) {
        StringBuilder sb = new StringBuilder();
        sb.append("= Rewrite Roadmap, by theme\n");
        sb.append(":description: Cross-cutting view of every Active and Backlog item by `theme:`.\n\n");
        sb.append("Themes are a closed set; bucket and theme are orthogonal. ");
        sb.append("See xref:index.adoc[Roadmap] for the status board, ")
          .append("or back to xref:../index.adoc[home].\n\n");

        Map<String, List<Item>> byTheme = new TreeMap<>();
        for (Item i : items) {
            if (!"Backlog".equals(i.status()) && !ACTIVE_STATES.contains(i.status())) continue;
            String t = i.theme() == null ? "(untagged)" : i.theme();
            byTheme.computeIfAbsent(t, k -> new ArrayList<>()).add(i);
        }
        for (String theme : VALID_THEMES) {
            List<Item> in = byTheme.getOrDefault(theme, List.of());
            if (in.isEmpty()) continue;
            sb.append("== ").append(theme).append("\n\n");
            in.stream()
                .sorted(Comparator
                    .comparingInt((Item i) -> i.priority() == null ? Integer.MAX_VALUE : i.priority())
                    .thenComparing(Item::title))
                .forEach(i -> {
                    sb.append("* `").append(i.id()).append("` xref:plans/")
                      .append(i.slug()).append(".adoc[*").append(escapeAdocCell(i.title()))
                      .append("*]: ").append(i.status());
                    if (i.bucket() != null) sb.append(", ").append(i.bucket());
                    if (!i.dependsOn().isEmpty()) {
                        sb.append(", blocked by ").append(linkAdocSlugs(i.dependsOn(), ChangelogContext.STANDALONE));
                    }
                    sb.append("\n");
                });
            sb.append("\n");
        }
        List<Item> untagged = byTheme.getOrDefault("(untagged)", List.of());
        if (!untagged.isEmpty()) {
            sb.append("== (untagged)\n\n");
            untagged.stream()
                .sorted(Comparator.comparing(Item::title))
                .forEach(i -> sb.append("* `").append(i.id()).append("` xref:plans/")
                    .append(i.slug()).append(".adoc[*").append(escapeAdocCell(i.title()))
                    .append("*]\n"));
            sb.append("\n");
        }
        return sb.toString();
    }

    /** changelog.md → changelog.adoc. */
    static String renderAdocChangelog(String md) {
        // Strip the next-id-counter front-matter; it's tool state, not prose.
        String body = parseFrontMatter(md).body();
        StringBuilder sb = new StringBuilder();
        sb.append("= Rewrite Changelog\n");
        sb.append(":description: Recently shipped rewrite work, by landing date.\n");
        sb.append(":doctype: book\n\n");
        sb.append("Plan files are deleted on Done; this is the historical record. ");
        sb.append("See xref:index.adoc[Roadmap] for in-flight work, ")
          .append("or back to xref:../index.adoc[home].\n\n");
        sb.append(mdBodyToAdoc(body, ChangelogContext.STANDALONE));
        if (!sb.toString().endsWith("\n")) sb.append('\n');
        return sb.toString();
    }

    /** One plan page: header + attribute box + body (md→adoc). */
    static String renderAdocPlan(Item i) {
        StringBuilder sb = new StringBuilder();
        sb.append("= ").append(i.title()).append("\n");
        sb.append(":description: ").append(i.id()).append(" plan, ")
          .append(i.status().toLowerCase()).append(".\n");
        // book doctype: source plans freely skip heading levels (Markdown allows
        // it, AsciiDoc warns under article doctype). Plans are document-shaped
        // long-form anyway, so book is a fit and silences the warnings.
        sb.append(":doctype: book\n\n");

        sb.append("[cols=\"1h,3\", frame=ends, grid=rows]\n");
        sb.append("|===\n");
        sb.append("| ID | `").append(i.id()).append("`\n");
        String status = i.status() + (i.deferred() ? " (deferred)" : "");
        sb.append("| Status | ").append(status).append("\n");
        if (i.bucket() != null) sb.append("| Bucket | ").append(i.bucket()).append("\n");
        if (i.priority() != null) sb.append("| Priority | ").append(i.priority()).append("\n");
        if (i.theme() != null) sb.append("| Theme | ").append(i.theme()).append("\n");
        if (!i.dependsOn().isEmpty()) {
            sb.append("| Blocked by | ").append(linkAdocSlugs(i.dependsOn(), ChangelogContext.PLAN)).append("\n");
        }
        if (i.created() != null) sb.append("| Created | ").append(i.created()).append("\n");
        if (i.lastUpdated() != null) sb.append("| Updated | ").append(i.lastUpdated()).append("\n");
        sb.append("|===\n\n");

        sb.append(mdBodyToAdoc(i.body(), ChangelogContext.PLAN));
        if (!sb.toString().endsWith("\n")) sb.append('\n');
        return sb.toString();
    }

    /** Where in the rendered output a markdown body is being converted; affects link rewrites. */
    enum ChangelogContext {
        /** Top-level under {@code _generated/} (changelog.adoc, index.adoc). */
        STANDALONE,
        /** Under {@code _generated/plans/}; sibling plans, parent is _generated/. */
        PLAN,
    }

    private static String linkAdocSlugs(List<String> slugs, ChangelogContext ctx) {
        String prefix = ctx == ChangelogContext.PLAN ? "" : "plans/";
        StringBuilder out = new StringBuilder();
        for (int idx = 0; idx < slugs.size(); idx++) {
            if (idx > 0) out.append(", ");
            String s = slugs.get(idx);
            out.append("xref:").append(prefix).append(s).append(".adoc[").append(s).append("]");
        }
        return out.toString();
    }

    /**
     * Item-side explainer links for the status-board Active table (R488): one
     * {@code · link:concepts/<slug>.html[explainer]} per backing page in slug
     * order, following the item's plan link. Empty when the item backs no
     * page; a shipped anchor contributes nothing (the item is not listed).
     */
    private static String adocExplainerSuffix(ConceptIndex concepts, String itemId) {
        StringBuilder out = new StringBuilder();
        for (String slug : concepts.explainerSlugsFor(itemId)) {
            out.append(" · link:concepts/").append(slug).append(".html[explainer]");
        }
        return out.toString();
    }

    /**
     * Item-side explainer links for the status-board Backlog and Deferred lines
     * (R488): the same per-page links, {@code ·}-separated inside a single
     * parenthesized group placed after the item's title link. Empty when the
     * item backs no live page.
     */
    private static String adocExplainerParenthetical(ConceptIndex concepts, String itemId) {
        List<String> slugs = concepts.explainerSlugsFor(itemId);
        if (slugs.isEmpty()) return "";
        StringBuilder out = new StringBuilder(" (");
        for (int idx = 0; idx < slugs.size(); idx++) {
            if (idx > 0) out.append(" · ");
            out.append("link:concepts/").append(slugs.get(idx)).append(".html[explainer]");
        }
        return out.append(")").toString();
    }

    /**
     * Concept-side {@code (backs ...)} annotation for the status-board Concept
     * explainers listing (R488): each declared anchor in declared order, a
     * {@link ConceptIndex.Live} one linking to the item's plan and a
     * {@link ConceptIndex.Shipped} one rendered as plain id text. The items
     * contract guarantees at least one anchor, so this is never empty.
     */
    private static String adocBacks(List<ConceptIndex.ItemAnchor> anchors) {
        StringBuilder out = new StringBuilder(" (backs ");
        for (int idx = 0; idx < anchors.size(); idx++) {
            if (idx > 0) out.append(", ");
            out.append(switch (anchors.get(idx)) {
                case ConceptIndex.Live l -> "xref:plans/" + l.itemSlug() + ".adoc[" + l.itemId() + "]";
                case ConceptIndex.Shipped s -> s.itemId();
            });
        }
        return out.append(")").toString();
    }

    /** Best-effort mechanical markdown → AsciiDoc body conversion. */
    static String mdBodyToAdoc(String md, ChangelogContext ctx) {
        StringBuilder out = new StringBuilder();
        boolean inFence = false;
        // Stack of source heading levels for normalising AsciiDoc output. Source
        // markdown allows skips (h1 → h3); AsciiDoc warns. Each new heading's
        // emit-depth is the stack size after popping levels deeper than the
        // current source level and pushing the current source level.
        java.util.Deque<Integer> headingStack = new java.util.ArrayDeque<>();
        String[] lines = md.split("\n", -1);
        Pattern fence = Pattern.compile("^```(\\w*)\\s*$");
        Pattern heading = Pattern.compile("^(#+)\\s+(.*)$");
        for (int idx = 0; idx < lines.length; idx++) {
            String raw = lines[idx];
            var fm = fence.matcher(raw);
            if (fm.matches()) {
                if (!inFence) {
                    String lang = fm.group(1);
                    if (!lang.isEmpty()) out.append("[source,").append(lang).append("]\n");
                    out.append("----\n");
                    inFence = true;
                } else {
                    out.append("----\n");
                    inFence = false;
                }
                continue;
            }
            if (inFence) {
                out.append(raw).append('\n');
                continue;
            }
            var hm = heading.matcher(raw);
            if (hm.matches()) {
                int sourceLevel = hm.group(1).length();
                while (!headingStack.isEmpty() && headingStack.peek() >= sourceLevel) {
                    headingStack.pop();
                }
                headingStack.push(sourceLevel);
                // Plus 1 because the page's own `= Title` is level 0; first body
                // heading should be level 1 (`==`). Cap at AsciiDoc's max of 5.
                int emitLevel = Math.min(headingStack.size() + 1, 5);
                String title = hm.group(2);
                title = transformAdocLinks(title, ctx);
                title = title.replaceAll("\\*\\*([^*]+)\\*\\*", "*$1*");
                out.append("=".repeat(emitLevel)).append(' ').append(title).append('\n');
                continue;
            }
            // Markdown table: a pipe-delimited header row followed by a separator
            // row (`|---|---|`). AsciiDoctor renders bare markdown tables as
            // paragraph text with literal pipes, so convert to a `|===` block.
            if (MD_TABLE_ROW.matcher(raw).matches()
                && idx + 1 < lines.length
                && MD_TABLE_SEP.matcher(lines[idx + 1]).matches()) {
                java.util.List<String> header = parseMdTableCells(raw);
                idx++; // consume separator
                java.util.List<java.util.List<String>> body = new java.util.ArrayList<>();
                while (idx + 1 < lines.length && MD_TABLE_ROW.matcher(lines[idx + 1]).matches()) {
                    idx++;
                    body.add(parseMdTableCells(lines[idx]));
                }
                emitAdocTable(out, header, body, ctx);
                continue;
            }
            String line = raw;
            if (line.equals("---")) {
                out.append("'''\n");
                continue;
            }
            // Markdown ordered-list markers ("1. ", "2. ", "a. ", "b. ") -> AsciiDoc ". ":
            // AsciiDoc's ordered-list parser is strict about sequential indices
            // when explicit numbers or letters are used; using "." defers numbering
            // to the renderer and avoids "list item index: expected N, got M" /
            // "expected a, got c" warnings when intervening code blocks or
            // paragraphs break the run.
            line = line.replaceAll("^(\\s*)(?:\\d+|[a-zA-Z])\\.\\s", "$1. ");
            line = line.replaceAll("\\*\\*([^*]+)\\*\\*", "*$1*");
            line = transformAdocLinks(line, ctx);
            // Em-dash sweep: codebase rule.
            line = line.replace("—", ";");
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static final Pattern MD_TABLE_ROW = Pattern.compile("^\\s*\\|.*\\|\\s*$");
    private static final Pattern MD_TABLE_SEP =
        Pattern.compile("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)+\\|?\\s*$");

    /**
     * Splits a markdown table row into cells. Strips the conventional leading and
     * trailing pipes, splits on unescaped pipes, and unescapes {@code \|} to a
     * literal pipe. Pipes appearing inside backtick code spans are treated as
     * cell content rather than delimiters so cells like {@code `Map<K|V>`}
     * survive the split intact.
     */
    static java.util.List<String> parseMdTableCells(String row) {
        String s = row.strip();
        if (s.startsWith("|")) s = s.substring(1);
        if (s.endsWith("|") && !(s.length() >= 2 && s.charAt(s.length() - 2) == '\\')) {
            s = s.substring(0, s.length() - 1);
        }
        java.util.List<String> cells = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inCode = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length() && s.charAt(i + 1) == '|') {
                cur.append('|');
                i++;
            } else if (c == '`') {
                inCode = !inCode;
                cur.append(c);
            } else if (c == '|' && !inCode) {
                cells.add(cur.toString().strip());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        cells.add(cur.toString().strip());
        return cells;
    }

    /**
     * Emit an AsciiDoc table block from a parsed markdown table. One cell per line
     * with a blank line between header and body and between body rows; this keeps
     * the source readable and matches the style used elsewhere in this file. Cell
     * content runs through the same markdown→AsciiDoc transforms as body prose
     * (bold, links, em-dash sweep), and any literal pipes are escaped.
     */
    private static void emitAdocTable(
        StringBuilder out,
        java.util.List<String> header,
        java.util.List<java.util.List<String>> body,
        ChangelogContext ctx
    ) {
        int colCount = Math.max(1, header.size());
        // Explicit cols="N*": without it, AsciiDoctor infers the column count
        // from the first line of the block. Since we emit one cell per line, it
        // would see one cell and render a single-column table with every cell
        // stacked vertically. N equal-width columns is the safe default; no
        // %autowidth so the table fills the container and reflows on mobile.
        out.append("[cols=\"").append(colCount).append("*\", options=\"header\"]\n");
        out.append("|===\n");
        for (String cell : header) {
            out.append("| ").append(transformAdocTableCell(cell, ctx)).append('\n');
        }
        for (java.util.List<String> row : body) {
            out.append('\n');
            for (int c = 0; c < colCount; c++) {
                String cell = c < row.size() ? row.get(c) : "";
                out.append("| ").append(transformAdocTableCell(cell, ctx)).append('\n');
            }
        }
        out.append("|===\n");
    }

    private static String transformAdocTableCell(String cell, ChangelogContext ctx) {
        String c = cell.replaceAll("\\*\\*([^*]+)\\*\\*", "*$1*");
        c = transformAdocLinks(c, ctx);
        c = c.replace("—", ";");
        c = c.replace("|", "\\|");
        return c;
    }

    private static final Pattern MD_LINK = Pattern.compile("\\[([^\\]]+)\\]\\(([^)]+)\\)");
    private static final String GH_BASE = "https://github.com/sikt-no/graphitron";
    private static final String GH_REWRITE_BRANCH = "claude/graphitron-rewrite";

    /**
     * Rewrite markdown links to AsciiDoc equivalents based on staging-tree layout.
     * For STANDALONE context (roadmap/changelog at _generated/), sibling plan
     * links go to {@code plans/<slug>.adoc}. For PLAN context (under plans/),
     * sibling plan links resolve directly.
     */
    private static String transformAdocLinks(String line, ChangelogContext ctx) {
        return MD_LINK.matcher(line).replaceAll(m -> {
            String text = m.group(1);
            String target = m.group(2);
            String anchor = "";
            if (target.contains("#")) {
                int hash = target.indexOf('#');
                anchor = target.substring(hash);
                target = target.substring(0, hash);
            }
            String mapped = mapAdocTarget(target, anchor, ctx);
            return java.util.regex.Matcher.quoteReplacement(
                mapped == null ? "[" + text + "](" + m.group(2) + ")" : mapped.replace("$$TEXT$$", text));
        });
    }

    /**
     * The AsciiDoc side of the two-emitter link model: formats a
     * {@link LinkTarget} classification into the staged-tree {@code xref:} /
     * {@code link:} grammar. The concepts-page href direction is
     * {@link ConceptPages#mapHref}; both read the one classifier so the target
     * taxonomy cannot drift between them.
     */
    static String mapAdocTarget(String target, String anchor, ChangelogContext ctx) {
        String rootPrefix = ctx == ChangelogContext.PLAN ? "../" : "";
        return switch (LinkTarget.classify(target)) {
            case LinkTarget.External(String url) -> url + anchor + "[$$TEXT$$]";
            case LinkTarget.SamePageAnchor() when !anchor.isEmpty() ->
                "<<" + anchor.substring(1) + ",$$TEXT$$>>";
            case LinkTarget.SiblingItem(String slug) -> {
                String prefix = ctx == ChangelogContext.PLAN ? "" : "plans/";
                yield "xref:" + prefix + slug + ".adoc" + anchor + "[$$TEXT$$]";
            }
            case LinkTarget.Readme() -> "xref:" + rootPrefix + "index.adoc" + anchor + "[$$TEXT$$]";
            case LinkTarget.Changelog() -> "xref:" + rootPrefix + "changelog.adoc" + anchor + "[$$TEXT$$]";
            // workflow.adoc left the site entirely and co-locates with the roadmap
            // items, so it maps to a roadmap sibling, not an /architecture/ page.
            case LinkTarget.Workflow() -> "xref:" + rootPrefix + "workflow.adoc" + anchor + "[$$TEXT$$]";
            // Concept pages are raw HTML, not adoc: link:, not xref:, so the
            // WARN-fail asciidoctor log handler stays quiet.
            case LinkTarget.ConceptPage(String slug) ->
                "link:" + rootPrefix + "concepts/" + slug + ".html" + anchor + "[$$TEXT$$]";
            case LinkTarget.ArchitectureDoc(String slug, String quadrant) -> {
                String archPrefix = ctx == ChangelogContext.PLAN ? "../../architecture/" : "../architecture/";
                String path = quadrant != null ? quadrant + "/" + slug : slug;
                yield "xref:" + archPrefix + path + ".adoc" + anchor + "[$$TEXT$$]";
            }
            case LinkTarget.TopLevelDoc(String slug) -> {
                String prefix = ctx == ChangelogContext.PLAN ? "../../" : "../";
                yield "xref:" + prefix + slug + ".adoc" + anchor + "[$$TEXT$$]";
            }
            case LinkTarget.LegacyModulePath(String path) ->
                GH_BASE + "/tree/main/" + path + anchor + "[$$TEXT$$]";
            case LinkTarget.WebEnvironmentRedirect() ->
                GH_BASE + "/blob/" + GH_REWRITE_BRANCH + "/.claude/web-environment.md" + anchor + "[$$TEXT$$]";
            // Deep docs paths and anything unrecognised: leave the original as a
            // link: macro (asciidoctor accepts relative link: targets that don't
            // resolve to a staged file, without producing a WARN).
            default -> "link:" + target + anchor + "[$$TEXT$$]";
        };
    }

    /** Escape characters that confuse AsciiDoc table cells. */
    private static String escapeAdocCell(String s) {
        return s.replace("|", "\\|");
    }

    /** Light inline escape for non-cell contexts (currently a no-op stub). */
    private static String escapeAdocInline(String s) {
        return s;
    }

    private static Map<String, String> parseOptions(List<String> tokens) {
        Map<String, String> out = new HashMap<>();
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (!t.startsWith("--")) {
                System.err.println("create: unexpected positional argument: " + t);
                System.exit(64);
            }
            if (i + 1 >= tokens.size()) {
                System.err.println("create: missing value for " + t);
                System.exit(64);
            }
            out.put(t.substring(2), tokens.get(++i));
        }
        return out;
    }

    static List<Item> readItems(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s
                .filter(p -> p.getFileName().toString().endsWith(".md"))
                .filter(p -> !p.getFileName().toString().equals("README.md"))
                .filter(p -> !p.getFileName().toString().equals("changelog.md"))
                .map(Main::readItem)
                .sorted(Comparator
                    .comparingInt((Item i) -> i.priority() == null ? Integer.MAX_VALUE : i.priority())
                    .thenComparing(Item::slug))
                .toList();
        }
    }

    static Item readItem(Path file) {
        try {
            String content = Files.readString(file);
            ParsedFile parsed = parseFrontMatter(content);
            String slug = file.getFileName().toString().replaceFirst("\\.md$", "");
            return Item.from(slug, parsed.frontMatter(), parsed.body());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static ParsedFile parseFrontMatter(String content) {
        if (!content.startsWith("---\n") && !content.startsWith("---\r\n")) {
            return new ParsedFile(Map.of(), content);
        }
        int afterFirst = content.indexOf('\n') + 1;
        int closing = content.indexOf("\n---", afterFirst);
        if (closing < 0) {
            throw new IllegalArgumentException("front-matter not closed");
        }
        String yaml = content.substring(afterFirst, closing);
        int bodyStart = content.indexOf('\n', closing + 4) + 1;
        if (bodyStart == 0) {
            bodyStart = content.length();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) new Yaml().load(yaml);
        return new ParsedFile(map == null ? Map.of() : map, content.substring(bodyStart));
    }

    /**
     * Rewrites the value of each named front-matter key in place, preserving
     * every other line, the body, and the surrounding fences byte-for-byte.
     * A key already present has its value replaced on its own line; a key not
     * yet present is appended just before the closing {@code ---} fence, in
     * {@code updates} iteration order.
     *
     * <p>This is the safe write path for front-matter mutation. Unlike a
     * {@code parseFrontMatter}-then-hand-serialize round-trip, it never reads a
     * value back through snakeyaml and re-emits it unquoted, so a correctly
     * quoted {@code title: "subtitle: detail"} (and any list or date the load
     * would otherwise reformat) is left exactly as authored. Callers pass plain
     * scalars for the values being written ({@code status}, an ISO date,
     * {@code R<n>}); those need no quoting, and this writer deliberately does
     * none, which is why it must only ever be handed quote-safe values.
     */
    static String patchFrontMatter(String content, Map<String, String> updates) {
        if (!content.startsWith("---\n") && !content.startsWith("---\r\n")) {
            throw new IllegalArgumentException("front-matter not found");
        }
        int afterFirst = content.indexOf('\n') + 1;
        int closing = content.indexOf("\n---", afterFirst);
        if (closing < 0) {
            throw new IllegalArgumentException("front-matter not closed");
        }
        String prefix = content.substring(0, afterFirst);
        String fmBody = content.substring(afterFirst, closing);
        String suffix = content.substring(closing);

        Set<String> remaining = new LinkedHashSet<>(updates.keySet());
        String[] lines = fmBody.split("\n", -1);
        StringBuilder patched = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String key = frontMatterKey(line);
            if (key != null && updates.containsKey(key)) {
                String cr = line.endsWith("\r") ? "\r" : "";
                patched.append(key).append(": ").append(updates.get(key)).append(cr);
                remaining.remove(key);
            } else {
                patched.append(line);
            }
            if (i < lines.length - 1) {
                patched.append('\n');
            }
        }
        StringBuilder appended = new StringBuilder();
        for (String key : remaining) {
            appended.append('\n').append(key).append(": ").append(updates.get(key));
        }
        return prefix + patched + appended + suffix;
    }

    /**
     * Returns the top-level mapping key a front-matter line introduces, or
     * {@code null} if the line is not a {@code key: ...} entry (a list item,
     * a block-scalar continuation, a comment, or blank). A top-level key has
     * no leading whitespace and no internal whitespace, so indented list rows
     * and wrapped values are left untouched by {@link #patchFrontMatter}.
     */
    private static String frontMatterKey(String line) {
        int colon = line.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        String key = line.substring(0, colon);
        for (int i = 0; i < key.length(); i++) {
            if (Character.isWhitespace(key.charAt(i))) {
                return null;
            }
        }
        return key;
    }

    /**
     * Fails the run on any of: unknown {@code theme:}, {@code depends-on:}
     * slug that does not match an existing item, or a dependency cycle.
     * A {@code depends-on:} entry that has shipped is expected to be removed
     * from the dependent item by the author who closes the dep; that keeps
     * {@code depends-on:} a record of <em>currently</em> blocking work only.
     */
    static void validate(List<Item> items) {
        Set<String> known = new java.util.HashSet<>();
        for (Item i : items) {
            known.add(i.slug());
        }
        List<String> errors = new ArrayList<>();
        Map<String, String> idOwner = new HashMap<>();
        for (Item i : items) {
            if (i.id() == null) {
                errors.add(i.slug() + ": missing required 'id:' front-matter field. "
                    + "Use the create subcommand to allocate one, or run next-id to "
                    + "see what the next free ID would be.");
            } else if (!ID_PATTERN.matcher(i.id()).matches()) {
                errors.add(i.slug() + ": malformed id '" + i.id()
                    + "'. Expected R<positive integer>, e.g. R24.");
            } else {
                String prev = idOwner.put(i.id(), i.slug());
                if (prev != null) {
                    errors.add(i.id() + ": duplicate id, used by '" + prev
                        + "' and '" + i.slug() + "'. IDs are monotonic and never reused.");
                }
            }
            if (i.theme() != null && !VALID_THEMES.contains(i.theme())) {
                errors.add(i.slug() + ": unknown theme '" + i.theme()
                    + "'. Valid themes: " + VALID_THEMES);
            }
            for (String dep : i.dependsOn()) {
                if (!known.contains(dep)) {
                    errors.add(i.slug() + ": depends-on slug '" + dep
                        + "' does not match any roadmap item file. Either fix the typo, "
                        + "or remove it (if the dep has shipped, remove it from depends-on).");
                }
            }
        }

        // Cycle detection (DFS over depends-on edges)
        Map<String, Item> bySlug = new java.util.HashMap<>();
        for (Item i : items) bySlug.put(i.slug(), i);
        Set<String> visited = new java.util.HashSet<>();
        Set<String> onStack = new java.util.LinkedHashSet<>();
        for (Item i : items) {
            detectCycle(i.slug(), bySlug, visited, onStack, errors);
        }

        if (!errors.isEmpty()) {
            System.err.println("roadmap front-matter validation failed:");
            for (String e : errors) System.err.println("  " + e);
            // See BuildFailure / runVerify: this runs in the Maven JVM via exec:java, so a
            // System.exit would kill Maven before it can print BUILD FAILURE.
            throw new BuildFailure("roadmap front-matter validation failed");
        }
    }

    private static void detectCycle(
        String slug,
        Map<String, Item> bySlug,
        Set<String> visited,
        Set<String> onStack,
        List<String> errors
    ) {
        if (visited.contains(slug)) return;
        if (onStack.contains(slug)) {
            errors.add("dependency cycle: " + String.join(" -> ", onStack) + " -> " + slug);
            return;
        }
        Item it = bySlug.get(slug);
        if (it == null) return;
        onStack.add(slug);
        for (String dep : it.dependsOn()) {
            detectCycle(dep, bySlug, visited, onStack, errors);
        }
        onStack.remove(slug);
        visited.add(slug);
    }

    static String render(List<Item> items) {
        return render(items, ConceptIndex.empty());
    }

    /** README roll-up with concept-explainer cross-links derived from {@code concepts} (R488). */
    static String render(List<Item> items, ConceptIndex concepts) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Rewrite Roadmap\n\n");
        sb.append("_Generated by `graphitron-roadmap-tool`. ");
        sb.append("Regenerate with `mvn -pl :graphitron-roadmap-tool exec:java` ");
        sb.append("(or `mise r roadmap`). Edit per-item `*.md` files in this directory; ");
        sb.append("never edit this file by hand._\n\n");

        sb.append("Tracks remaining generator work. ");
        sb.append("For the model taxonomy, see [Code Generation Triggers](../docs/architecture/reference/code-generation-triggers.adoc). ");
        sb.append("For design principles, see [Graphitron Development Principles](../docs/architecture/explanation/development-principles.adoc). ");
        sb.append("For workflow conventions, see [Workflow](workflow.adoc). ");
        sb.append("For per-leaf classifier coverage, see [Inference-axis coverage report](inference-axis-coverage.adoc) ");
        sb.append("(regenerated by `mvn verify -Pleaf-coverage`).\n\n");

        sb.append("**First time contributing?** Read in this order: ");
        sb.append("[Workflow](workflow.adoc), ");
        sb.append("[Graphitron Development Principles](../docs/architecture/explanation/development-principles.adoc), ");
        sb.append("[Code Generation Triggers](../docs/architecture/reference/code-generation-triggers.adoc). ");
        sb.append("Then read an Active plan to see the shape, and pick a Backlog item or take a Ready item from Active.\n\n");

        sb.append("**Front-matter dimensions.** Each item carries `id:` (monotonic `R<n>`, never "
            + "reused), `status:`, `bucket:`, `priority:`, ");
        sb.append("`theme:` (cross-cutting tag, see the *By theme* index), `depends-on:` ");
        sb.append("(slugs of items that must ship first), `deferred:` (boolean; moves the item to ");
        sb.append("the **Deferred** sub-section of Backlog so the active list stays actionable), ");
        sb.append("`notes:` (short inline annotation, shown on deferred items as the parking reason), ");
        sb.append("and `created:` / `last-updated:` (ISO `YYYY-MM-DD`, stamped by the `create` and ");
        sb.append("`status` subcommands of `graphitron-roadmap-tool`; pre-R143 items render ");
        sb.append("`last-updated:` only once they next transition, and `created:` is never backfilled). ");
        sb.append("When a dep ships, the dep file is deleted; ");
        sb.append("the author closing it is responsible for removing the slug from any dependents' ");
        sb.append("`depends-on:` list. The validator fails the build on a stale slug.\n\n");

        sb.append("---\n\n");
        renderActive(sb, items, concepts);
        sb.append("\n---\n\n");
        renderBacklog(sb, items, concepts);
        sb.append("\n---\n\n");
        renderByTheme(sb, items);
        sb.append("\n---\n\n");
        renderConceptExplainers(sb, concepts);
        sb.append("## Done\n\n");
        sb.append("See [`changelog.md`](changelog.md) for the historical record of shipped rewrite work. ");
        sb.append("Plan files are deleted on Done; git history preserves them.\n");
        return sb.toString();
    }

    private static void renderActive(StringBuilder sb, List<Item> items, ConceptIndex concepts) {
        List<Item> active = items.stream()
            .filter(i -> ACTIVE_STATES.contains(i.status()))
            .sorted(Comparator
                .comparingInt((Item i) -> i.priority() == null ? Integer.MAX_VALUE : i.priority())
                .thenComparingInt(i -> ACTIVE_STATES.indexOf(i.status()))
                .thenComparing(Item::title))
            .toList();
        sb.append("## Active\n\n");
        if (active.isEmpty()) {
            sb.append("_(none)_\n");
            return;
        }
        sb.append("| ID | Item | Status | Updated | Plan |\n|---|---|---|---|---|\n");
        for (Item i : active) {
            String status = i.status() + (i.deferred() ? " (deferred)" : "");
            String item = i.title();
            if (i.notes() != null && !i.notes().isBlank()) {
                item += " <sub>" + i.notes().strip() + "</sub>";
            }
            if (!i.dependsOn().isEmpty()) {
                item += " <sub>blocked by: " + linkSlugs(i.dependsOn()) + "</sub>";
            }
            sb.append("| `").append(i.id()).append("` | ")
              .append(item).append(" | ")
              .append(status).append(" | ")
              .append(renderUpdatedCellMd(i)).append(" | [plan](")
              .append(i.slug()).append(".md)")
              .append(mdExplainerSuffix(concepts, i.id())).append(" |\n");
        }
    }

    /**
     * Markdown "Updated" cell: {@code last-updated:} on the main line; if
     * {@code created:} differs, append ` <sub>created YYYY-MM-DD</sub>` on the
     * same cell. Both dates absent (pre-R143 item that has not transitioned)
     * renders an empty cell.
     */
    private static String renderUpdatedCellMd(Item i) {
        if (i.lastUpdated() == null) return "";
        if (i.created() != null && !i.created().equals(i.lastUpdated())) {
            return i.lastUpdated() + " <sub>created " + i.created() + "</sub>";
        }
        return i.lastUpdated().toString();
    }

    private static void renderBacklog(StringBuilder sb, List<Item> items, ConceptIndex concepts) {
        sb.append("## Backlog\n\n");
        List<Item> backlog = items.stream()
            .filter(i -> "Backlog".equals(i.status()))
            .toList();
        if (backlog.isEmpty()) {
            sb.append("_(none)_\n");
            return;
        }
        List<Item> active = backlog.stream().filter(i -> !i.deferred()).toList();
        List<Item> deferred = backlog.stream().filter(Item::deferred).toList();

        for (String bucket : BACKLOG_BUCKETS) {
            List<Item> b = active.stream()
                .filter(i -> bucket.equals(i.bucket()))
                .sorted(Comparator
                    .comparingInt((Item i) -> i.priority() == null ? Integer.MAX_VALUE : i.priority())
                    .thenComparing(Item::title))
                .toList();
            if (b.isEmpty()) {
                continue;
            }
            sb.append("### ").append(capitalize(bucket)).append("\n\n");
            for (Item i : b) {
                appendBacklogLine(sb, i, concepts);
            }
            sb.append("\n");
        }

        List<Item> orphans = active.stream()
            .filter(i -> i.bucket() == null || !BACKLOG_BUCKETS.contains(i.bucket()))
            .sorted(Comparator.comparing(Item::title))
            .toList();
        if (!orphans.isEmpty()) {
            sb.append("### Other\n\n");
            for (Item i : orphans) {
                appendBacklogLine(sb, i, concepts);
            }
            sb.append("\n");
        }

        if (!deferred.isEmpty()) {
            sb.append("### Deferred\n\n");
            sb.append("_Items parked until a blocking concern is resolved or re-prioritised. ");
            sb.append("Set `deferred: false` (or remove the field) to return an item to the active backlog._\n\n");
            deferred.stream()
                .sorted(Comparator
                    .comparingInt((Item i) -> i.priority() == null ? Integer.MAX_VALUE : i.priority())
                    .thenComparing(Item::title))
                .forEach(i -> appendDeferredLine(sb, i, concepts));
            sb.append("\n");
        }
    }

    /**
     * Secondary index: items grouped by {@code theme:}. Lets a reader see
     * cross-cutting clusters that the bucket grouping spreads across multiple
     * sections (e.g. all docs work, all interface/union work). Includes both
     * Active and Backlog items so the cluster view is complete.
     */
    private static void renderByTheme(StringBuilder sb, List<Item> items) {
        sb.append("## By theme\n\n");
        sb.append("Cross-cutting view of every Active and Backlog item by `theme:`. ");
        sb.append("Themes are a closed set; bucket and theme are orthogonal.\n\n");

        Map<String, List<Item>> byTheme = new TreeMap<>();
        for (Item i : items) {
            if (!"Backlog".equals(i.status()) && !ACTIVE_STATES.contains(i.status())) {
                continue;
            }
            String t = i.theme() == null ? "(untagged)" : i.theme();
            byTheme.computeIfAbsent(t, k -> new ArrayList<>()).add(i);
        }

        for (String theme : VALID_THEMES) {
            List<Item> in = byTheme.getOrDefault(theme, List.of());
            if (in.isEmpty()) continue;
            sb.append("### ").append(theme).append("\n\n");
            in.stream()
                .sorted(Comparator
                    .comparingInt((Item i) -> i.priority() == null ? Integer.MAX_VALUE : i.priority())
                    .thenComparing(Item::title))
                .forEach(i -> {
                    sb.append("- `").append(i.id()).append("` [**")
                      .append(i.title()).append("**](")
                      .append(i.slug()).append(".md) — ")
                      .append(i.status());
                    if (i.bucket() != null) {
                        sb.append(", ").append(i.bucket());
                    }
                    if (!i.dependsOn().isEmpty()) {
                        sb.append(", blocked by ").append(linkSlugs(i.dependsOn()));
                    }
                    sb.append("\n");
                });
            sb.append("\n");
        }

        List<Item> untagged = byTheme.getOrDefault("(untagged)", List.of());
        if (!untagged.isEmpty()) {
            sb.append("### (untagged)\n\n");
            untagged.stream()
                .sorted(Comparator.comparing(Item::title))
                .forEach(i -> sb.append("- `").append(i.id()).append("` [**")
                    .append(i.title()).append("**](")
                    .append(i.slug()).append(".md)\n"));
            sb.append("\n");
        }
    }

    /**
     * Concept explainers listing (R486): every {@code concepts/<slug>.html} by
     * its {@code data-concept-title}, sorted by slug. Derived by scanning the
     * directory, never hand-maintained, so there is no second source of truth;
     * verify-mode README comparison catches listing drift, and the title
     * contract in {@link ConceptPages#extractTitle} covers unextractable pages.
     * Emits nothing when there are no pages.
     */
    private static void renderConceptExplainers(StringBuilder sb, ConceptIndex concepts) {
        if (concepts.isEmpty()) {
            return;
        }
        sb.append("## Concept explainers\n\n");
        sb.append("_Intuition-first background pages for dense or recurring roadmap concepts, ");
        sb.append("rendered as interactive HTML. Authored with the `explainer` skill; ");
        sb.append("this listing derives from `concepts/*.html`, never by hand._\n\n");
        concepts.pages().forEach((slug, page) ->
            sb.append("- [").append(page.title()).append("](concepts/").append(slug).append(".html)")
              .append(mdBacks(concepts.anchorsFor(slug))).append("\n"));
        sb.append("\n---\n\n");
    }

    /**
     * Item-side explainer links for the README Active table (R488): one
     * {@code · [explainer](concepts/<slug>.html)} per backing page in slug
     * order, following the item's plan link. Empty when the item backs no live
     * page.
     */
    private static String mdExplainerSuffix(ConceptIndex concepts, String itemId) {
        StringBuilder out = new StringBuilder();
        for (String slug : concepts.explainerSlugsFor(itemId)) {
            out.append(" · [explainer](concepts/").append(slug).append(".html)");
        }
        return out.toString();
    }

    /**
     * Item-side explainer links for the README Backlog and Deferred lines
     * (R488): the same per-page links, {@code ·}-separated inside a single
     * parenthesized group placed after the item's title link. Empty when the
     * item backs no live page.
     */
    private static String mdExplainerParenthetical(ConceptIndex concepts, String itemId) {
        List<String> slugs = concepts.explainerSlugsFor(itemId);
        if (slugs.isEmpty()) return "";
        StringBuilder out = new StringBuilder(" (");
        for (int idx = 0; idx < slugs.size(); idx++) {
            if (idx > 0) out.append(" · ");
            out.append("[explainer](concepts/").append(slugs.get(idx)).append(".html)");
        }
        return out.append(")").toString();
    }

    /**
     * Concept-side {@code (backs ...)} annotation for the README Concept
     * explainers listing (R488): each declared anchor in declared order, a
     * {@link ConceptIndex.Live} one linking to the item's plan and a
     * {@link ConceptIndex.Shipped} one rendered as plain id text. The items
     * contract guarantees at least one anchor, so this is never empty.
     */
    private static String mdBacks(List<ConceptIndex.ItemAnchor> anchors) {
        StringBuilder out = new StringBuilder(" (backs ");
        for (int idx = 0; idx < anchors.size(); idx++) {
            if (idx > 0) out.append(", ");
            out.append(switch (anchors.get(idx)) {
                case ConceptIndex.Live l -> "[" + l.itemId() + "](" + l.itemSlug() + ".md)";
                case ConceptIndex.Shipped s -> s.itemId();
            });
        }
        return out.append(")").toString();
    }

    private static String linkSlugs(List<String> slugs) {
        StringBuilder out = new StringBuilder();
        for (int idx = 0; idx < slugs.size(); idx++) {
            if (idx > 0) out.append(", ");
            String s = slugs.get(idx);
            out.append("[").append(s).append("](").append(s).append(".md)");
        }
        return out.toString();
    }

    private static void appendBacklogLine(StringBuilder sb, Item i, ConceptIndex concepts) {
        sb.append("- `").append(i.id()).append("` [**").append(i.title()).append("**](")
          .append(i.slug()).append(".md)");
        sb.append(mdExplainerParenthetical(concepts, i.id()));
        String description = firstNonHeadingParagraph(i.body());
        if (!description.isEmpty()) {
            sb.append(": ").append(description);
        }
        String dateAnnotation = renderBacklogDateAnnotationMd(i);
        if (!dateAnnotation.isEmpty()) {
            sb.append(" ").append(dateAnnotation);
        }
        if (!i.dependsOn().isEmpty()) {
            sb.append(" _(blocked by ").append(linkSlugs(i.dependsOn())).append(")_");
        }
        sb.append("\n");
    }

    /**
     * Markdown backlog date annotation: {@code <sub>updated Y-M-D</sub>} on its
     * own when {@code created:} matches or is absent; {@code <sub>updated Y-M-D,
     * created Y-M-D</sub>} when the two differ. Empty when {@code last-updated:}
     * is absent.
     */
    private static String renderBacklogDateAnnotationMd(Item i) {
        if (i.lastUpdated() == null) return "";
        if (i.created() != null && !i.created().equals(i.lastUpdated())) {
            return "<sub>updated " + i.lastUpdated() + ", created " + i.created() + "</sub>";
        }
        return "<sub>updated " + i.lastUpdated() + "</sub>";
    }

    private static void appendDeferredLine(StringBuilder sb, Item i, ConceptIndex concepts) {
        sb.append("- `").append(i.id()).append("` [**").append(i.title()).append("**](")
          .append(i.slug()).append(".md)");
        sb.append(mdExplainerParenthetical(concepts, i.id()));
        if (i.notes() != null && !i.notes().isBlank()) {
            sb.append(" — _").append(i.notes().strip()).append("_");
        }
        if (!i.dependsOn().isEmpty()) {
            sb.append(" _(blocked by ").append(linkSlugs(i.dependsOn())).append(")_");
        }
        sb.append("\n");
    }

    private static String firstNonHeadingParagraph(String body) {
        String trimmed = body.strip();
        if (trimmed.isEmpty()) {
            return "";
        }
        for (String para : trimmed.split("\n\n+")) {
            String stripped = para.strip();
            if (stripped.isEmpty()) {
                continue;
            }
            if (stripped.startsWith("#")) {
                continue;
            }
            return stripped.replace("\n", " ");
        }
        return "";
    }

    private static String capitalize(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    record ParsedFile(Map<String, Object> frontMatter, String body) {}

    record Item(String slug, String id, String title, String status, String bucket,
                Integer priority, boolean deferred, String notes,
                String theme, List<String> dependsOn,
                LocalDate created, LocalDate lastUpdated, String body) {

        static Item from(String slug, Map<String, Object> fm, String body) {
            String id = (String) fm.get("id");
            String title = (String) fm.getOrDefault("title", slug);
            String status = (String) fm.getOrDefault("status", "Backlog");
            String bucket = (String) fm.get("bucket");
            Integer priority = fm.get("priority") instanceof Integer p ? p : null;
            boolean deferred = Boolean.TRUE.equals(fm.get("deferred"));
            String notes = (String) fm.get("notes");
            String theme = (String) fm.get("theme");
            List<String> dependsOn = parseSlugList(fm.get("depends-on"));
            LocalDate created = parseDate(slug, "created", fm.get("created"));
            LocalDate lastUpdated = parseDate(slug, "last-updated", fm.get("last-updated"));
            return new Item(slug, id, title, status, bucket, priority, deferred, notes,
                theme, dependsOn, created, lastUpdated, body);
        }

        // SnakeYAML auto-parses ISO dates as java.util.Date; bare strings stay String.
        // Accept either shape and reject anything else by failing the parse loudly.
        private static LocalDate parseDate(String slug, String key, Object raw) {
            if (raw == null) return null;
            if (raw instanceof java.util.Date d) {
                return d.toInstant().atZone(java.time.ZoneOffset.UTC).toLocalDate();
            }
            try {
                return LocalDate.parse(raw.toString());
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException(slug + ": " + key + ": '" + raw
                    + "' is not a valid YYYY-MM-DD date.");
            }
        }

        @SuppressWarnings("unchecked")
        private static List<String> parseSlugList(Object raw) {
            if (raw == null) return List.of();
            if (raw instanceof List<?> list) {
                List<String> out = new ArrayList<>();
                for (Object o : list) {
                    if (o == null) continue;
                    out.add(o.toString());
                }
                return List.copyOf(out);
            }
            throw new IllegalArgumentException(
                "expected a YAML list for depends-on, got: " + raw.getClass().getName());
        }
    }
}
