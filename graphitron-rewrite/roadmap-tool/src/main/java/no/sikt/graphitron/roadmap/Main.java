package no.sikt.graphitron.roadmap;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
        "interface-union",
        "nodeid",
        "service",
        "mutations-errors",
        "pagination",
        "model-cleanup",
        "structural-refactor",
        "docs",
        "testing",
        "legacy-migration"
    );

    private Main() {}

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            usage();
        }
        String mode = args[0];
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
        System.err.println("  render-adoc <roadmap-dir> <output-dir>");
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
        Files.writeString(readme, render(items));
        System.out.println("wrote " + readme);
    }

    private static void runVerify(Path dir) throws IOException {
        List<Item> items = readItems(dir);
        validate(items);
        Path readme = dir.resolve("README.md");
        String rendered = render(items);
        String existing = Files.exists(readme) ? Files.readString(readme) : "";
        if (!existing.equals(rendered)) {
            System.err.println("roadmap README.md is out of date. Regenerate with:");
            System.err.println("  mvn -pl :graphitron-roadmap-tool exec:java");
            System.exit(1);
        }
        System.out.println("roadmap README.md is up to date.");
    }

    private static void runNextId(Path dir) throws IOException {
        List<Item> items = readItems(dir);
        // No validate() here so a malformed sibling doesn't block ID allocation
        // for the file the caller is about to write.
        System.out.println(nextId(items));
    }

    /**
     * Returns the next available ID as {@code R<max+1>}, or {@code R1} if no
     * item carries an ID yet. Numbers are never reused: gaps left by deleted
     * items stay as gaps so historical references in {@code changelog.md} or
     * commit messages don't collide with future allocations.
     */
    static String nextId(List<Item> items) {
        int max = 0;
        for (Item i : items) {
            if (i.id() == null) continue;
            var m = ID_PATTERN.matcher(i.id());
            if (m.matches()) {
                max = Math.max(max, Integer.parseInt(m.group(1)));
            }
        }
        return "R" + (max + 1);
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
        String id = nextId(items);

        StringBuilder fm = new StringBuilder();
        fm.append("---\n");
        fm.append("id: ").append(id).append('\n');
        fm.append("title: \"").append(title.replace("\"", "\\\"")).append("\"\n");
        fm.append("status: Backlog\n");
        if (opts.containsKey("bucket")) fm.append("bucket: ").append(opts.get("bucket")).append('\n');
        if (opts.containsKey("priority")) fm.append("priority: ").append(opts.get("priority")).append('\n');
        if (opts.containsKey("theme")) fm.append("theme: ").append(opts.get("theme")).append('\n');
        fm.append("depends-on: []\n");
        fm.append("---\n\n");
        fm.append("# ").append(title).append("\n\n");
        fm.append("<One-paragraph problem statement: what is missing or broken, and why it matters."
            + " Replace this and add a plan body when the item moves to Spec.>\n");

        Files.writeString(target, fm.toString());
        System.out.println("created " + target + " (" + id + ")");

        // Refresh the rolled-up README so the new item shows up immediately.
        runGenerate(dir);
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

        Files.writeString(outDir.resolve("index.adoc"), renderAdocStatusBoard(items));
        Files.writeString(outDir.resolve("by-theme.adoc"), renderAdocByTheme(items));

        Path changelog = roadmapDir.resolve("changelog.md");
        if (Files.exists(changelog)) {
            Files.writeString(outDir.resolve("changelog.adoc"),
                renderAdocChangelog(Files.readString(changelog)));
        }

        for (Item i : items) {
            Files.writeString(plansDir.resolve(i.slug() + ".adoc"), renderAdocPlan(i));
        }

        System.out.println("rendered " + items.size() + " plans + roadmap/by-theme/changelog into " + outDir);
    }

    /** Status board: Active by status, Backlog by bucket. */
    static String renderAdocStatusBoard(List<Item> items) {
        StringBuilder sb = new StringBuilder();
        sb.append("= Rewrite Roadmap\n");
        sb.append(":description: Active and Backlog work on the Graphitron rewrite generator.\n\n");
        sb.append("This is the rendered roadmap. Plans are authored as markdown in ")
          .append("`graphitron-rewrite/roadmap/`; this view derives from the per-item front-matter ")
          .append("and the plan bodies. For the model taxonomy, see ")
          .append("xref:../architecture/code-generation-triggers.adoc[Code Generation Triggers]. ")
          .append("For design principles, see ")
          .append("xref:../architecture/rewrite-design-principles.adoc[Rewrite Design Principles]. ")
          .append("For workflow conventions, see xref:../architecture/workflow.adoc[Workflow]. ")
          .append("Or jump to the xref:by-theme.adoc[by-theme view] or the xref:changelog.adoc[changelog]. ")
          .append("Back to xref:../index.adoc[home].\n\n");

        sb.append("== Active\n\n");
        List<Item> active = items.stream()
            .filter(i -> ACTIVE_STATES.contains(i.status()))
            .sorted(Comparator
                .comparingInt((Item i) -> ACTIVE_STATES.indexOf(i.status()))
                .thenComparingInt(i -> i.priority() == null ? Integer.MAX_VALUE : i.priority())
                .thenComparing(Item::title))
            .toList();
        if (active.isEmpty()) {
            sb.append("_(none)_\n\n");
        } else {
            sb.append("[cols=\"1,4,1,1\", options=\"header\"]\n");
            sb.append("|===\n");
            sb.append("| ID | Item | Status | Plan\n");
            for (Item i : active) {
                String status = i.status() + (i.deferred() ? " (deferred)" : "");
                sb.append("| `").append(i.id()).append("`\n");
                sb.append("| ").append(escapeAdocCell(i.title()));
                if (!i.dependsOn().isEmpty()) {
                    sb.append(" +\n_blocked by: ").append(linkAdocSlugs(i.dependsOn())).append("_");
                }
                sb.append("\n| ").append(status).append("\n");
                sb.append("| xref:plans/").append(i.slug()).append(".adoc[plan]\n");
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
            for (String bucket : BACKLOG_BUCKETS) {
                List<Item> b = backlog.stream()
                    .filter(i -> bucket.equals(i.bucket()))
                    .sorted(Comparator
                        .comparingInt((Item i) -> i.priority() == null ? Integer.MAX_VALUE : i.priority())
                        .thenComparing(Item::title))
                    .toList();
                if (b.isEmpty()) continue;
                sb.append("=== ").append(capitalize(bucket)).append("\n\n");
                for (Item i : b) appendBacklogAdocLine(sb, i);
                sb.append("\n");
            }
            List<Item> orphans = backlog.stream()
                .filter(i -> i.bucket() == null || !BACKLOG_BUCKETS.contains(i.bucket()))
                .sorted(Comparator.comparing(Item::title))
                .toList();
            if (!orphans.isEmpty()) {
                sb.append("=== Other\n\n");
                for (Item i : orphans) appendBacklogAdocLine(sb, i);
                sb.append("\n");
            }
        }

        sb.append("== Done\n\n");
        sb.append("See xref:changelog.adoc[Changelog] for the historical record of shipped rewrite work. ");
        sb.append("Plan files are deleted on Done; git history preserves them.\n");
        return sb.toString();
    }

    private static void appendBacklogAdocLine(StringBuilder sb, Item i) {
        sb.append("* `").append(i.id()).append("` xref:plans/").append(i.slug()).append(".adoc[")
          .append(escapeAdocCell(i.title())).append("]");
        String description = firstNonHeadingParagraph(i.body());
        if (!description.isEmpty()) {
            sb.append(": ").append(escapeAdocInline(description));
        }
        if (!i.dependsOn().isEmpty()) {
            sb.append(" _(blocked by ").append(linkAdocSlugs(i.dependsOn())).append(")_");
        }
        sb.append("\n");
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
                        sb.append(", blocked by ").append(linkAdocSlugs(i.dependsOn()));
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
        StringBuilder sb = new StringBuilder();
        sb.append("= Rewrite Changelog\n");
        sb.append(":description: Recently shipped rewrite work, by landing date.\n");
        sb.append(":doctype: book\n\n");
        sb.append("Plan files are deleted on Done; this is the historical record. ");
        sb.append("See xref:index.adoc[Roadmap] for in-flight work, ")
          .append("or back to xref:../index.adoc[home].\n\n");
        sb.append(mdBodyToAdoc(md, ChangelogContext.STANDALONE));
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
            sb.append("| Blocked by | ").append(linkAdocSlugs(i.dependsOn())).append("\n");
        }
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

    private static String linkAdocSlugs(List<String> slugs) {
        StringBuilder out = new StringBuilder();
        for (int idx = 0; idx < slugs.size(); idx++) {
            if (idx > 0) out.append(", ");
            String s = slugs.get(idx);
            out.append("xref:plans/").append(s).append(".adoc[").append(s).append("]");
        }
        return out.toString();
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
        for (String raw : lines) {
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
            String line = raw;
            if (line.equals("---")) {
                out.append("'''\n");
                continue;
            }
            // Markdown ordered-list markers ("1. ", "2. ") -> AsciiDoc ". ":
            // AsciiDoc's ordered-list parser is strict about sequential indices
            // when explicit numbers are used; using "." defers numbering to the
            // renderer and avoids "list item index: expected N, got M" warnings.
            line = line.replaceAll("^(\\s*)\\d+\\.\\s", "$1. ");
            line = line.replaceAll("\\*\\*([^*]+)\\*\\*", "*$1*");
            line = transformAdocLinks(line, ctx);
            // Em-dash sweep: codebase rule.
            line = line.replace("—", ";");
            out.append(line).append('\n');
        }
        return out.toString();
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

    private static String mapAdocTarget(String target, String anchor, ChangelogContext ctx) {
        // External http(s)
        if (target.startsWith("http://") || target.startsWith("https://")) {
            return target + anchor + "[$$TEXT$$]";
        }
        // Same-page anchor only
        if (target.isEmpty() && !anchor.isEmpty()) {
            return "<<" + anchor.substring(1) + ",$$TEXT$$>>";
        }
        // Sibling plan: <slug>.md
        var simple = Pattern.compile("([\\w-]+)\\.md").matcher(target);
        if (simple.matches()) {
            String slug = simple.group(1);
            if ("README".equals(slug)) {
                String prefix = ctx == ChangelogContext.PLAN ? "../" : "";
                return "xref:" + prefix + "index.adoc" + anchor + "[$$TEXT$$]";
            }
            if ("changelog".equals(slug)) {
                String prefix = ctx == ChangelogContext.PLAN ? "../" : "";
                return "xref:" + prefix + "changelog.adoc" + anchor + "[$$TEXT$$]";
            }
            String prefix = ctx == ChangelogContext.PLAN ? "" : "plans/";
            return "xref:" + prefix + slug + ".adoc" + anchor + "[$$TEXT$$]";
        }
        // ../docs/<file>.{md,adoc} → architecture under staging/. Phase 2 already
        // updated most roadmap-side references to .adoc, but the mapper accepts
        // both extensions so any stragglers also resolve.
        var docs = Pattern.compile("\\.\\./docs/([\\w-]+)\\.(?:md|adoc)").matcher(target);
        if (docs.matches()) {
            String prefix = ctx == ChangelogContext.PLAN ? "../../architecture/" : "../architecture/";
            return "xref:" + prefix + docs.group(1) + ".adoc" + anchor + "[$$TEXT$$]";
        }
        // ../../docs/<file>.{md,adoc} → top-level
        var topdocs = Pattern.compile("\\.\\./\\.\\./docs/([\\w-]+)\\.(?:md|adoc)").matcher(target);
        if (topdocs.matches()) {
            String prefix = ctx == ChangelogContext.PLAN ? "../../" : "../";
            return "xref:" + prefix + topdocs.group(1) + ".adoc" + anchor + "[$$TEXT$$]";
        }
        // Legacy module paths → GitHub URL on main
        var legacy = Pattern.compile("\\.\\./\\.\\./(graphitron-(?:codegen-parent|common|example|maven-plugin|schema-transform|servlet-parent)/.*)").matcher(target);
        if (legacy.matches()) {
            return GH_BASE + "/tree/main/" + legacy.group(1) + anchor + "[$$TEXT$$]";
        }
        // claude-code-web-environment.md → moved to .claude/web-environment.md
        if (target.endsWith("claude-code-web-environment.md")) {
            return GH_BASE + "/blob/" + GH_REWRITE_BRANCH + "/.claude/web-environment.md" + anchor + "[$$TEXT$$]";
        }
        // Anything else: leave the original as a markdown link form (asciidoctor
        // will accept link:<target>[<text>] for relative paths even if they don't
        // resolve to a staged file, without producing a WARN).
        return "link:" + target + anchor + "[$$TEXT$$]";
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
            System.exit(1);
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
        StringBuilder sb = new StringBuilder();
        sb.append("# Rewrite Roadmap\n\n");
        sb.append("_Generated by `graphitron-roadmap-tool`. ");
        sb.append("Regenerate with `mvn -pl :graphitron-roadmap-tool exec:java` ");
        sb.append("(or `mise r roadmap`). Edit per-item `*.md` files in this directory; ");
        sb.append("never edit this file by hand._\n\n");

        sb.append("Tracks remaining generator work. ");
        sb.append("For the model taxonomy, see [Code Generation Triggers](../docs/code-generation-triggers.adoc). ");
        sb.append("For design principles, see [Rewrite Design Principles](../docs/rewrite-design-principles.adoc). ");
        sb.append("For workflow conventions, see [Workflow](../docs/workflow.adoc).\n\n");

        sb.append("**First time contributing?** Read in this order: ");
        sb.append("[Workflow](../docs/workflow.adoc), ");
        sb.append("[Rewrite Design Principles](../docs/rewrite-design-principles.adoc), ");
        sb.append("[Code Generation Triggers](../docs/code-generation-triggers.adoc). ");
        sb.append("Then read an Active plan to see the shape, and pick a Backlog item or take a Ready item from Active.\n\n");

        sb.append("**Front-matter dimensions.** Each item carries `id:` (monotonic `R<n>`, never "
            + "reused), `status:`, `bucket:`, `priority:`, ");
        sb.append("`theme:` (cross-cutting tag, see the *By theme* index), and `depends-on:` ");
        sb.append("(slugs of items that must ship first). When a dep ships, the dep file is deleted; ");
        sb.append("the author closing it is responsible for removing the slug from any dependents' ");
        sb.append("`depends-on:` list. The validator fails the build on a stale slug.\n\n");

        sb.append("---\n\n");
        renderActive(sb, items);
        sb.append("\n---\n\n");
        renderBacklog(sb, items);
        sb.append("\n---\n\n");
        renderByTheme(sb, items);
        sb.append("\n---\n\n");
        sb.append("## Done\n\n");
        sb.append("See [`changelog.md`](changelog.md) for the historical record of shipped rewrite work. ");
        sb.append("Plan files are deleted on Done; git history preserves them.\n");
        return sb.toString();
    }

    private static void renderActive(StringBuilder sb, List<Item> items) {
        List<Item> active = items.stream()
            .filter(i -> ACTIVE_STATES.contains(i.status()))
            .sorted(Comparator
                .comparingInt((Item i) -> ACTIVE_STATES.indexOf(i.status()))
                .thenComparingInt(i -> i.priority() == null ? Integer.MAX_VALUE : i.priority())
                .thenComparing(Item::title))
            .toList();
        sb.append("## Active\n\n");
        if (active.isEmpty()) {
            sb.append("_(none)_\n");
            return;
        }
        sb.append("| ID | Item | Status | Plan |\n|---|---|---|---|\n");
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
              .append(status).append(" | [plan](")
              .append(i.slug()).append(".md) |\n");
        }
    }

    private static void renderBacklog(StringBuilder sb, List<Item> items) {
        sb.append("## Backlog\n\n");
        List<Item> backlog = items.stream()
            .filter(i -> "Backlog".equals(i.status()))
            .toList();
        if (backlog.isEmpty()) {
            sb.append("_(none)_\n");
            return;
        }
        for (String bucket : BACKLOG_BUCKETS) {
            List<Item> b = backlog.stream()
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
                appendBacklogLine(sb, i);
            }
            sb.append("\n");
        }

        List<Item> orphans = backlog.stream()
            .filter(i -> i.bucket() == null || !BACKLOG_BUCKETS.contains(i.bucket()))
            .sorted(Comparator.comparing(Item::title))
            .toList();
        if (!orphans.isEmpty()) {
            sb.append("### Other\n\n");
            for (Item i : orphans) {
                appendBacklogLine(sb, i);
            }
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

    private static String linkSlugs(List<String> slugs) {
        StringBuilder out = new StringBuilder();
        for (int idx = 0; idx < slugs.size(); idx++) {
            if (idx > 0) out.append(", ");
            String s = slugs.get(idx);
            out.append("[").append(s).append("](").append(s).append(".md)");
        }
        return out.toString();
    }

    private static void appendBacklogLine(StringBuilder sb, Item i) {
        sb.append("- `").append(i.id()).append("` [**").append(i.title()).append("**](")
          .append(i.slug()).append(".md)");
        String description = firstNonHeadingParagraph(i.body());
        if (!description.isEmpty()) {
            sb.append(": ").append(description);
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
                String theme, List<String> dependsOn, String body) {

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
            return new Item(slug, id, title, status, bucket, priority, deferred, notes,
                theme, dependsOn, body);
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
