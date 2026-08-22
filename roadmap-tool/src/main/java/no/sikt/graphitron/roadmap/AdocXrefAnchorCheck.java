package no.sikt.graphitron.roadmap;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scans the staged AsciiDoc site for cross-file {@code xref:<file>.adoc} references, anchored
 * or not, whose target page or named anchor does not exist.
 *
 * <p>A dangling anchored reference renders as a working link that goes nowhere useful: the
 * reader lands at the top of the right page instead of the section. Asciidoctor does not and
 * cannot report it, because the target is a separate document that it never resolves, so the
 * mistake ships silently and reproduces. The same-file forms are out of scope; Asciidoctor
 * already reports those, at INFO.
 *
 * <p><b>The rule is that a cross-file anchored xref must target an explicit block anchor</b>
 * ({@code [#id]} or {@code [[id]]}) on the target section, never an auto-generated heading id.
 * Resolving against explicit anchors only is what keeps this check from reimplementing
 * Asciidoctor's id-generation algorithm, which would be a second source of truth free to drift
 * from the renderer at every AsciidoctorJ upgrade. It also makes the referenced headings
 * rename-safe, which is the population that most needs to be.
 *
 * <p><b>It runs against staging, not the source tree</b>, inverting the {@code target/} skip
 * {@link AdocMarkdownTableCheck} deliberately applies. Staging is the tree Asciidoctor itself
 * resolves against, so path resolution is exact and free. The source tree is neither: staging
 * flattens two source roots into one (repo-root {@code roadmap/} lands beside {@code docs/}'s
 * own subtree) and the pages under {@code roadmap/plans/} exist as {@code .adoc} only after
 * rendering, so a source-tree checker would need a mount table mirroring the staging steps and
 * still could not resolve a target that has no authored {@code .adoc} at all. It also means
 * generated pages with no authored source (the roadmap status boards, the schema reference)
 * are inside the scan, which no authored-tree walker could manage. The cost is that every path
 * in a finding is build output; {@link #authoredSource} pays it back.
 *
 * <p><b>The verdict on an unresolvable target is a function of the source page's provenance.</b>
 * A reference whose target {@code .adoc} is absent from staging is a wrong <em>path</em>: it
 * 404s on the first click, so on a published page it is self-reporting in a way a wrong anchor
 * never is. Where the source is roadmap prose (an item body, the changelog, a plan page), that
 * self-reporting argument holds and a wrong path is counted, not failed: item bodies quote
 * example paths, and failing on those would make every quoted example a build break. Where the
 * source is authored under {@code docs/} or is a generated page with no prose in it, a wrong
 * path is an authoring or emitter defect with nothing to excuse it, and it fails the build.
 *
 * <p><b>A scan floor guards the widened population against going vacuous.</b> The optional
 * second argument pins a minimum reference count, wired in {@code docs/pom.xml} next to the
 * staging path; a collector regression that silently stops seeing references fails the build
 * instead of passing an empty scan.
 */
final class AdocXrefAnchorCheck {

    /**
     * An xref macro with an attrlist. Detection keys on the bracket rather than on the shape of
     * the target, because the majority of this corpus is {@code ../}-relative: narrowing by the
     * target's opening character would skip most of the population.
     *
     * <p>This is not faithful to Asciidoctor in one corner. Asciidoctor matches a target and an
     * attrlist across a line break, and across intervening markup, so prose quoting a bare
     * {@code xref:} target near a bracketed one can have the two spliced into a single macro.
     * A same-line rule under-reports there. That is the right direction to be wrong in: it
     * catches every reference an author writes on purpose, and modelling the cross-span match
     * would be the id-algorithm mistake in another costume.
     */
    private static final Pattern XREF = Pattern.compile("xref:([^\\s\\[\\]]+)\\[");

    /**
     * A block anchor in its own attrlist ({@code [#id]}, including the shorthand's optional
     * trailing role and option segments and any further attributes) or the double-bracket form
     * ({@code [[id]]}, with an optional reftext). Both are live in this tree, so a collector
     * reading only one of them would report a live link as dangling.
     *
     * <p>Anchor collection errs towards over-collecting: a missed declaration on a target page
     * fails a reference that works, and a false failure is worse here than an under-report,
     * since the author it stops has no way to satisfy the check.
     */
    private static final Pattern BLOCK_ANCHOR = Pattern.compile("^\\[#([^\\s,.%\\]]+)");
    private static final Pattern INLINE_ANCHOR = Pattern.compile("\\[\\[([^\\s,\\]]+)(?:,[^\\]]*)?]]");
    private static final Pattern ANCHOR_MACRO = Pattern.compile("\\banchor:([^\\s\\[\\]]+)\\[");

    private AdocXrefAnchorCheck() {}

    /**
     * A cross-file reference, as collected from one staged page. {@code anchor} is null for a
     * plain {@code xref:<file>.adoc[...]} with no fragment.
     */
    record Reference(Path file, int line, String targetPath, String anchor) {}

    /** A reference that resolves to a staged page which publishes no such explicit anchor. */
    record Finding(Reference reference, Path target, Set<String> published) {}

    /**
     * Entry point invoked by {@link Main}. Args:
     *
     * <ul>
     *   <li>{@code <staging-dir>} — the populated staging tree to scan.</li>
     *   <li>{@code [min-references]} — optional scan floor: fail when fewer cross-file
     *       references are collected, per the vacuity-guard argument in the class javadoc.</li>
     * </ul>
     *
     * <p>Returns 0 when every checked reference resolves, and 64 when invoked with a wrong
     * argument shape or a non-directory root. A dangling anchor or path throws
     * {@link BuildFailure} rather than returning non-zero, for the reason
     * {@link AdocMarkdownTableCheck#run} spells out: {@link Main} turns a non-zero return into
     * {@code System.exit}, and this check runs in the Maven JVM via {@code exec:java}, where
     * that would kill Maven before it prints {@code BUILD FAILURE}.
     */
    static int run(List<String> args) throws IOException {
        if (args.isEmpty() || args.size() > 2) {
            System.err.println("usage: check-adoc-xrefs <staging-dir> [min-references]");
            return 64;
        }
        Path root = Path.of(args.get(0)).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            System.err.println("not a directory: " + root);
            return 64;
        }
        int floor = 0;
        if (args.size() == 2) {
            try {
                floor = Integer.parseInt(args.get(1));
            } catch (NumberFormatException e) {
                System.err.println("min-references is not a number: " + args.get(1));
                return 64;
            }
        }

        List<Reference> references = collect(root);
        if (references.size() < floor) {
            throw new BuildFailure("check-adoc-xrefs collected " + references.size()
                + " cross-file reference(s), below the floor of " + floor
                + "; the collector has gone vacuous, not the corpus clean. Fix the collector,"
                + " or lower the floor in docs/pom.xml if the corpus genuinely shrank.");
        }

        List<Finding> danglingAnchors = new ArrayList<>();
        List<Reference> danglingPaths = new ArrayList<>();
        List<Reference> reportOnly = new ArrayList<>();
        for (Reference r : references) {
            Path target = resolve(root, r);
            if (target == null) {
                (failsOnDanglingPath(root, r.file()) ? danglingPaths : reportOnly).add(r);
                continue;
            }
            if (r.anchor() == null) {
                continue;
            }
            Set<String> published = anchorsOf(target);
            if (!published.contains(r.anchor())) {
                danglingAnchors.add(new Finding(r, target, published));
            }
        }

        if (!reportOnly.isEmpty()) {
            // Printed, not failed: under-coverage stays visible without a wrong path in quoted
            // roadmap prose becoming a build break. A non-zero count is a finding to look at,
            // not noise to tune out.
            System.out.println("check-adoc-xrefs: " + reportOnly.size()
                + " reference(s) in roadmap prose could not be resolved to a staged .adoc and were not checked.");
            for (Reference r : reportOnly) {
                System.out.println("  " + describe(root, r) + " -> " + r.targetPath() + " (no such staged page)");
            }
        }

        if (danglingAnchors.isEmpty() && danglingPaths.isEmpty()) {
            System.out.println("check-adoc-xrefs: " + references.size()
                + " cross-file reference(s), every path staged and every anchor explicit.");
            return 0;
        }

        if (!danglingPaths.isEmpty()) {
            System.err.println("check-adoc-xrefs: found " + danglingPaths.size()
                + " cross-file xref(s) on published docs pages whose target .adoc is not staged."
                + " These render as links that 404 on the site. Fix the path, or stage the"
                + " target.");
            for (Reference r : danglingPaths) {
                System.err.println("  " + describe(root, r) + " -> " + r.targetPath() + " (no such staged page)");
            }
        }
        if (!danglingAnchors.isEmpty()) {
            System.err.println("check-adoc-xrefs: found " + danglingAnchors.size()
                + " cross-file xref(s) naming an anchor the target page does not publish."
                + " Asciidoctor renders these as working links that land the reader at the top of the"
                + " page, and reports nothing. Add an explicit anchor ([#id] or [[id]]) on the target"
                + " section, or repoint the reference at one it already publishes.");
            for (Finding f : danglingAnchors) {
                Reference r = f.reference();
                System.err.println("  " + describe(root, r) + ": #" + r.anchor()
                    + " is not published by " + describe(root, f.target()));
                System.err.println("      fix in " + describe(root, f.target())
                    + ", which publishes: " + (f.published().isEmpty() ? "(no explicit anchors)"
                        : String.join(", ", f.published())));
            }
        }
        throw new BuildFailure("cross-file xrefs whose target pages or anchors do not exist");
    }

    /**
     * Whether a wrong path in {@code file} fails the build. True for every page outside
     * {@code roadmap/} (authored under {@code docs/}) and for the generated roadmap pages with
     * no authored prose behind them ({@link #authoredSource} empty: the status boards); false
     * for roadmap prose, the population the report-only argument in the class javadoc was
     * written for.
     */
    private static boolean failsOnDanglingPath(Path root, Path file) {
        String relative = root.relativize(file).toString().replace('\\', '/');
        return !relative.startsWith("roadmap/") || authoredSource(relative).isEmpty();
    }

    /** Every cross-file reference in the staged tree, anchored or not, in walk order. */
    static List<Reference> collect(Path root) throws IOException {
        List<Reference> references = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!file.getFileName().toString().endsWith(".adoc")) {
                    return FileVisitResult.CONTINUE;
                }
                try {
                    references.addAll(collectFrom(file, Files.readString(file)));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return references;
    }

    /**
     * The cross-file references in one page's {@code adoc} source, anchored or not.
     *
     * <p>Whether a quoted reference counts is not a question this check answers for itself. A
     * markdown code span is literal by definition, but a single-backtick AsciiDoc span applies
     * the normal substitution group with macros included, so backticks alone publish a live
     * link on a hand-authored page. {@link InertSpans#maskInert} holds the one definition of
     * which span forms are inert, shared with the emitters that produce them, so a bare
     * backtick span still counts here and both inert forms do not.
     */
    static List<Reference> collectFrom(Path file, String adoc) {
        List<Reference> references = new ArrayList<>();
        String[] lines = adoc.split("\n", -1);
        InertSpans.BlockContext block = new InertSpans.BlockContext();
        for (int n = 0; n < lines.length; n++) {
            if (!block.isProse(lines[n])) {
                continue;
            }
            Matcher m = XREF.matcher(InertSpans.maskInert(lines[n]));
            while (m.find()) {
                String target = m.group(1);
                int hash = target.indexOf('#');
                // A same-file reference carries no path before the hash; Asciidoctor already
                // reports those at INFO, and they are out of this check's scope.
                if (hash == 0) {
                    continue;
                }
                String path = hash < 0 ? target : target.substring(0, hash);
                // Only .adoc paths are checkable: an attribute-bearing target is resolved by
                // Asciidoctor after substitution this check does not perform, and a non-.adoc
                // target (an image, a URL) is not a page staging can vouch for.
                if (!path.endsWith(".adoc") || path.contains("{")) {
                    continue;
                }
                references.add(new Reference(
                    file, n + 1, path, hash < 0 ? null : target.substring(hash + 1)));
            }
        }
        return references;
    }

    /** The staged page {@code reference} names, or null when staging holds no such page. */
    private static Path resolve(Path root, Reference reference) {
        Path target = reference.file().getParent().resolve(reference.targetPath()).normalize();
        return target.startsWith(root) && Files.isRegularFile(target) ? target : null;
    }

    /**
     * The explicit anchors {@code page} publishes. Auto-generated heading ids are deliberately
     * absent: deriving them would mean owning a copy of Asciidoctor's id-generation algorithm,
     * and a reference resting on one breaks the next time the heading is reworded.
     */
    static Set<String> anchorsOf(Path page) throws IOException {
        return anchorsIn(Files.readString(page));
    }

    /** The explicit anchors declared in {@code adoc}, in declaration order. */
    static Set<String> anchorsIn(String adoc) {
        Set<String> anchors = new LinkedHashSet<>();
        InertSpans.BlockContext block = new InertSpans.BlockContext();
        for (String raw : adoc.split("\n", -1)) {
            if (!block.isProse(raw)) {
                continue;
            }
            String line = InertSpans.maskInert(raw);
            Matcher shorthand = BLOCK_ANCHOR.matcher(line.strip());
            if (shorthand.find()) {
                anchors.add(shorthand.group(1));
            }
            Matcher inline = INLINE_ANCHOR.matcher(line);
            while (inline.find()) {
                anchors.add(inline.group(1));
            }
            Matcher macro = ANCHOR_MACRO.matcher(line);
            while (macro.find()) {
                anchors.add(macro.group(1));
            }
        }
        return anchors;
    }

    /** A staged path with the authored file behind it, as {@code staged (authored)}. */
    private static String describe(Path root, Reference reference) {
        return describe(root, reference.file()) + ":" + reference.line();
    }

    private static String describe(Path root, Path staged) {
        String relative = root.relativize(staged).toString().replace('\\', '/');
        return authoredSource(relative)
            .map(authored -> relative + " (" + authored + ")")
            .orElse(relative);
    }

    /**
     * The authored file behind a staged path, relative to the repo root, or empty when the page
     * is written from item front-matter and has no single authored source.
     *
     * <p>All of staging is build output an author cannot edit, so a finding that named only the
     * staged path would send its reader to a file that does not exist in git. Staging is
     * populated three ways and they map back differently: the copied {@code .adoc} trees
     * (including root-level pages) come straight from {@code docs/}, the roadmap plan pages are
     * rendered from a <em>different markup</em> at {@code roadmap/<slug>.md}, and the rest of
     * {@code roadmap/} splits again between verbatim copies of authored {@code .adoc}, the
     * changelog's markdown source, and the two status boards that have no authored source at
     * all. The plan-page rule is the plan-page rule; applying it to the rest would report paths
     * that do not exist, in the one place whose whole job is correct provenance.
     */
    static Optional<String> authoredSource(String stagedRelativePath) {
        if (!stagedRelativePath.startsWith("roadmap/")) {
            return Optional.of("docs/" + stagedRelativePath);
        }
        String withinRoadmap = stagedRelativePath.substring("roadmap/".length());
        if (withinRoadmap.startsWith("plans/") && withinRoadmap.endsWith(".adoc")) {
            String slug = withinRoadmap.substring("plans/".length(), withinRoadmap.length() - ".adoc".length());
            return Optional.of("roadmap/" + slug + ".md");
        }
        if (withinRoadmap.equals("changelog.adoc")) {
            return Optional.of("roadmap/changelog.md");
        }
        if (withinRoadmap.equals("index.adoc") || withinRoadmap.equals("by-theme.adoc")) {
            return Optional.empty();
        }
        return Optional.of("roadmap/" + withinRoadmap);
    }
}
