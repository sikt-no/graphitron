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
 * Fails the build when a pom outside the two roadmap-reading modules configures a build step over
 * {@code roadmap/}. This is the pom half of the claim underneath the scoped verification build
 * documented in {@code CLAUDE.md} under "Building and testing": a tree whose own commits are
 * entirely under {@code roadmap/} is verified by {@code roadmap-tool} and {@code docs} rather than
 * by the whole reactor, which holds only while those two are the only modules reading the
 * directory at build time. A third one joining changes nothing any build step reads, which is the
 * same silence {@link ModuleEnumerationCheck} exists for; the Java-source half of the claim is
 * guarded by the roadmap-consumer guard in the {@code graphitron} test tier.
 *
 * <p>The rule is path-shaped rather than word-shaped: a reference matches as an optional relative
 * prefix, then {@code roadmap}, then either a path separator and a segment or the end of the
 * reference. Spelled as "names the roadmap directory" it would take {@code roadmap-tool} as a
 * substring hit, and the root pom declares exactly that as a module.
 *
 * <p>Comments are stripped before matching, the same way {@link CoverageAgentWiringCheck} does it,
 * so a pom documenting the rule never trips it.
 */
final class RoadmapConsumerPomCheck {

    /** Modules allowed to configure build steps over the roadmap directory. */
    static final Set<String> ROADMAP_READING_MODULES = Set.of("roadmap-tool", "docs");

    private static final Pattern XML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    /**
     * A roadmap path in pom configuration: {@code roadmap} at a word boundary, optionally followed
     * by a separator and a path segment, and not followed by a name character. The trailing
     * exclusion is what keeps {@code roadmap-tool} out, since {@code -} continues the name.
     */
    private static final Pattern ROADMAP_PATH =
        Pattern.compile("(?<![A-Za-z0-9._-])roadmap(?:/[A-Za-z0-9._-]+)*(?![A-Za-z0-9._-])");

    private RoadmapConsumerPomCheck() {}

    /**
     * Entry point invoked by {@link Main}. Takes one argument, the repository root; walks the root
     * pom plus every module pom the root's {@code <modules>} block declares, the same enumeration
     * {@link ModuleEnumerationCheck} reads.
     *
     * <p>Returns 0 when no pom outside the allowed modules names a roadmap path, and 64 on a usage
     * or non-directory-root error. Throws {@link BuildFailure} on a violation or a declared module
     * whose pom is missing.
     */
    static int run(List<String> args) throws IOException {
        if (args.size() != 1) {
            System.err.println("usage: check-roadmap-consumers <repo-root>");
            return 64;
        }
        Path root = Path.of(args.get(0)).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            System.err.println("not a directory: " + root);
            return 64;
        }
        Path rootPom = root.resolve("pom.xml");
        if (!Files.isRegularFile(rootPom)) {
            System.err.println("check-roadmap-consumers: no pom.xml under " + root);
            throw new BuildFailure("reactor pom.xml not found");
        }

        String rootXml = Files.readString(rootPom);
        Set<String> modules = ModuleEnumerationCheck.declaredModules(rootXml);
        if (modules.isEmpty()) {
            // A root that parses to no modules means the pom moved or its <modules> block changed
            // shape; without this the check would pass vacuously against every module.
            System.err.println("check-roadmap-consumers: no <module> entries in " + rootPom
                + ". The reactor declares modules, so a parse yielding none means this check is"
                + " reading the wrong file or the wrong shape.");
            throw new BuildFailure("reactor pom declares no modules");
        }

        List<String> problems = new ArrayList<>(checkPom("(root pom)", rootXml));
        for (String module : modules) {
            if (ROADMAP_READING_MODULES.contains(module)) continue;
            Path pom = root.resolve(module).resolve("pom.xml");
            if (!Files.isRegularFile(pom)) {
                problems.add(module + ": declared in the reactor but has no pom.xml; the walk"
                    + " cannot vouch for a module it cannot read");
                continue;
            }
            problems.addAll(checkPom(module, Files.readString(pom)));
        }

        if (problems.isEmpty()) {
            System.out.println("check-roadmap-consumers: no pom outside "
                + ROADMAP_READING_MODULES + " configures a build step over roadmap/.");
            return 0;
        }
        System.err.println("check-roadmap-consumers: " + problems.size() + " problem(s). The"
            + " scoped verification build for a roadmap-only diff (see CLAUDE.md \"Building and"
            + " testing\") runs only the modules that read roadmap/. A build step elsewhere in the"
            + " reactor reading that directory is outside what the scoped build covers, so either"
            + " the step belongs in one of those modules, or the scoped-build rule is no longer"
            + " true and has to change.");
        for (String p : problems) {
            System.err.println("  " + p);
        }
        throw new BuildFailure("roadmap read configured outside the roadmap-reading modules");
    }

    /** Every roadmap path named in one pom's configuration, comments stripped first. */
    static List<String> checkPom(String module, String pomXml) {
        String text = XML_COMMENT.matcher(pomXml).replaceAll("");
        List<String> problems = new ArrayList<>();
        Matcher m = ROADMAP_PATH.matcher(text);
        while (m.find()) {
            problems.add(module + ": names `" + m.group() + "` in pom configuration");
        }
        return problems;
    }
}
