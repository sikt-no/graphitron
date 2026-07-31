package no.sikt.graphitron.roadmap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fails the build when a pom breaks the JaCoCo agent wiring the opt-in {@code coverage}
 * profile depends on. Both failure modes are silent under {@code -Pcoverage} and invisible
 * without it, which is why this runs on every build: a profile-conditional check would never
 * fire for anyone.
 *
 * <p>Two invariants, one walker, named for the invariant rather than either symptom so the
 * next wiring rule has an obvious home:
 *
 * <ul>
 *   <li>A surefire (or any) {@code <argLine>} must lead with {@code @{argLine}}. The coverage
 *       profile's {@code prepare-agent} writes the agent flags into the {@code argLine}
 *       property; an override that drops the placeholder drops the agent, and the module
 *       reports a false {@code 0%} that reads identically to "this module has no tests".</li>
 *   <li>A module must run exactly one test-executing plugin execution per fork, because the
 *       profile sets {@code append=false} (so a rerun on an uncleaned {@code target/} never
 *       reports coverage from code it did not execute). Binding failsafe without a JaCoCo
 *       {@code prepare-agent} execution writing a distinct {@code <destFile>} lets the second
 *       execution overwrite the first's exec data into a plausible partial figure with no
 *       tell at all. Any {@code forkCount} other than {@code 1} fails outright: parallel
 *       forks share one {@code destFile} and truncate each other, and {@code forkCount=0}
 *       skips the {@code argLine} agent entirely, so no distinct-destFile shape repairs it.</li>
 * </ul>
 */
final class CoverageAgentWiringCheck {

    /** An {@code <argLine>value</argLine>} element anywhere in the pom, comments stripped. */
    private static final Pattern ARG_LINE = Pattern.compile("<argLine>\\s*(.*?)\\s*</argLine>", Pattern.DOTALL);

    private static final Pattern FORK_COUNT = Pattern.compile("<forkCount>\\s*(.*?)\\s*</forkCount>", Pattern.DOTALL);

    /** One {@code <plugin>} block. The pom schema does not nest them, so non-greedy is exact. */
    private static final Pattern PLUGIN_BLOCK = Pattern.compile("<plugin>(.*?)</plugin>", Pattern.DOTALL);

    private static final Pattern XML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    private CoverageAgentWiringCheck() {}

    /**
     * Entry point invoked by {@link Main}. Takes one argument, the repository root; walks the
     * root pom plus every module pom its {@code <modules>} block declares (the same enumeration
     * {@link ModuleEnumerationCheck} reads).
     *
     * <p>Returns 0 when every pom satisfies both invariants, and 64 on a usage or
     * non-directory-root error. Throws {@link BuildFailure} on a violation or a declared module
     * whose pom is missing.
     */
    static int run(List<String> args) throws IOException {
        if (args.size() != 1) {
            System.err.println("usage: check-coverage-agent-wiring <repo-root>");
            return 64;
        }
        Path root = Path.of(args.get(0)).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            System.err.println("not a directory: " + root);
            return 64;
        }
        Path rootPom = root.resolve("pom.xml");
        if (!Files.isRegularFile(rootPom)) {
            System.err.println("check-coverage-agent-wiring: no pom.xml under " + root);
            throw new BuildFailure("reactor pom.xml not found");
        }

        List<String> problems = new ArrayList<>();
        String rootXml = Files.readString(rootPom);
        // The root pom joins the walk: an execution it binds in <build><plugins> is inherited
        // by every module, so a wiring break there breaks everything at once.
        problems.addAll(checkPom("(root pom)", rootXml));
        for (String module : ModuleEnumerationCheck.declaredModules(rootXml)) {
            Path pom = root.resolve(module).resolve("pom.xml");
            if (!Files.isRegularFile(pom)) {
                problems.add(module + ": declared in the reactor but has no pom.xml; the walk"
                    + " cannot vouch for a module it cannot read");
                continue;
            }
            problems.addAll(checkPom(module, Files.readString(pom)));
        }

        if (problems.isEmpty()) {
            System.out.println("check-coverage-agent-wiring: agent wiring intact in the root pom"
                + " and all reactor modules.");
            return 0;
        }
        System.err.println("check-coverage-agent-wiring: " + problems.size() + " problem(s)."
            + " These shapes only misbehave under -Pcoverage (see the coverage profile in the"
            + " root pom), but a wrong coverage number that looks plausible is worse than this"
            + " build failure, so the check runs on every build.");
        for (String p : problems) {
            System.err.println("  " + p);
        }
        throw new BuildFailure("JaCoCo agent wiring broken");
    }

    /** Both invariants over one pom, comments stripped first so documentation never trips the scan. */
    static List<String> checkPom(String module, String pomXml) {
        String text = XML_COMMENT.matcher(pomXml).replaceAll("");
        List<String> problems = new ArrayList<>();

        Matcher arg = ARG_LINE.matcher(text);
        while (arg.find()) {
            String value = arg.group(1);
            if (!value.isEmpty() && !value.startsWith("@{argLine}")) {
                problems.add(module + ": <argLine>" + value + "</argLine> does not lead with"
                    + " @{argLine}. Under -Pcoverage the JaCoCo agent flags live in the argLine"
                    + " property; this override drops the agent and the module reports a false 0%"
                    + " that reads like \"no tests here\". Write <argLine>@{argLine} "
                    + value + "</argLine>.");
            }
        }

        boolean failsafeExecutes = false;
        boolean distinctDestFile = false;
        Matcher plugin = PLUGIN_BLOCK.matcher(text);
        while (plugin.find()) {
            String block = plugin.group(1);
            if (block.contains("maven-failsafe-plugin") && block.contains("<execution>")) {
                failsafeExecutes = true;
            }
            if (block.contains("jacoco-maven-plugin") && block.contains("prepare-agent")
                    && block.contains("<destFile>")) {
                distinctDestFile = true;
            }
        }
        if (failsafeExecutes && !distinctDestFile) {
            problems.add(module + ": binds maven-failsafe-plugin executions without a JaCoCo"
                + " prepare-agent execution writing a distinct <destFile>. Under -Pcoverage the"
                + " surefire and failsafe forks would both write target/jacoco.exec with"
                + " append=false, the second silently overwriting the first into a plausible"
                + " partial coverage figure. Add a prepare-agent-integration execution with its"
                + " own <destFile> (and a report execution reading it).");
        }

        Matcher fork = FORK_COUNT.matcher(text);
        while (fork.find()) {
            String value = fork.group(1);
            if (!value.equals("1")) {
                problems.add(module + ": <forkCount>" + value + "</forkCount>. Under -Pcoverage,"
                    + " parallel forks share one destFile and append=false makes them truncate"
                    + " each other's exec data (forkCount=0 skips the argLine agent entirely),"
                    + " so the module would report a plausible partial figure. Coverage assumes"
                    + " one fork per test-executing execution; a module that genuinely needs"
                    + " more must carry per-fork JaCoCo wiring and extend this check.");
            }
        }
        return problems;
    }
}
