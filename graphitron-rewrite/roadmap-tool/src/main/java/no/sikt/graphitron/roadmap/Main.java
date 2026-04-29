package no.sikt.graphitron.roadmap;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
        if (args.length < 2 || !(args[0].equals("generate") || args[0].equals("verify"))) {
            System.err.println("usage: <generate|verify> <roadmap-dir>");
            System.exit(64);
        }
        String mode = args[0];
        Path dir = Path.of(args[1]).toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) {
            System.err.println("not a directory: " + dir);
            System.exit(64);
        }

        List<Item> items = readItems(dir);
        validate(items);
        String rendered = render(items);
        Path readme = dir.resolve("README.md");

        if (mode.equals("generate")) {
            Files.writeString(readme, rendered);
            System.out.println("wrote " + readme);
        } else {
            String existing = Files.exists(readme) ? Files.readString(readme) : "";
            if (!existing.equals(rendered)) {
                System.err.println("roadmap README.md is out of date. Regenerate with:");
                System.err.println("  mvn -pl :graphitron-roadmap-tool exec:java");
                System.exit(1);
            }
            System.out.println("roadmap README.md is up to date.");
        }
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
        for (Item i : items) {
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
        sb.append("For the model taxonomy, see [Code Generation Triggers](../docs/code-generation-triggers.md). ");
        sb.append("For design principles, see [Rewrite Design Principles](../docs/rewrite-design-principles.md). ");
        sb.append("For workflow conventions, see [Workflow](../docs/workflow.md).\n\n");

        sb.append("**First time contributing?** Read in this order: ");
        sb.append("[Workflow](../docs/workflow.md), ");
        sb.append("[Rewrite Design Principles](../docs/rewrite-design-principles.md), ");
        sb.append("[Code Generation Triggers](../docs/code-generation-triggers.md). ");
        sb.append("Then read an Active plan to see the shape, and pick a Backlog item or take a Ready item from Active.\n\n");

        sb.append("**Front-matter dimensions.** Each item carries `status:`, `bucket:`, `priority:`, ");
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
        sb.append("| Item | Status | Plan |\n|---|---|---|\n");
        for (Item i : active) {
            String status = i.status() + (i.deferred() ? " (deferred)" : "");
            String item = i.title();
            if (i.notes() != null && !i.notes().isBlank()) {
                item += " <sub>" + i.notes().strip() + "</sub>";
            }
            if (!i.dependsOn().isEmpty()) {
                item += " <sub>blocked by: " + linkSlugs(i.dependsOn()) + "</sub>";
            }
            sb.append("| ").append(item).append(" | ")
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
                    sb.append("- [**").append(i.title()).append("**](")
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
                .forEach(i -> sb.append("- [**").append(i.title()).append("**](")
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
        sb.append("- [**").append(i.title()).append("**](").append(i.slug()).append(".md)");
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

    record Item(String slug, String title, String status, String bucket,
                Integer priority, boolean deferred, String notes,
                String theme, List<String> dependsOn, String body) {

        static Item from(String slug, Map<String, Object> fm, String body) {
            String title = (String) fm.getOrDefault("title", slug);
            String status = (String) fm.getOrDefault("status", "Backlog");
            String bucket = (String) fm.get("bucket");
            Integer priority = fm.get("priority") instanceof Integer p ? p : null;
            boolean deferred = Boolean.TRUE.equals(fm.get("deferred"));
            String notes = (String) fm.get("notes");
            String theme = (String) fm.get("theme");
            List<String> dependsOn = parseSlugList(fm.get("depends-on"));
            return new Item(slug, title, status, bucket, priority, deferred, notes,
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
