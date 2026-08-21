package no.sikt.graphitron.roadmap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fails the build when a module that carries the ONNX/tokenizer dependency stack binds an
 * in-process {@code exec:java} execution. Such an execution loads a native library inside the
 * Maven JVM behind a class loader {@code exec-maven-plugin} rebuilds per execution, and the JVM
 * binds a loaded native library to one class loader for the life of the process, so the second
 * build that reaches the load fails with "already loaded in another classloader".
 *
 * <p>The invariant is worth a build gate because of how the failure hides. It needs a JVM serving
 * two builds, which is what an mvnd daemon is for and what this project recommends, and it stays
 * invisible to CI, where every job is a fresh JVM however many modules run in parallel. So the
 * shape reverts to the cheaper-looking {@code java} goal under review with nothing red to show for
 * it, and reappears on a developer's machine as a message naming neither the module nor the step.
 *
 * <p>Compliance is a fork: {@code <goal>exec</goal>} with {@code ${java.home}/bin/java}, the
 * classpath through {@code <classpath/>}, and the main class as an argument. A fresh process has a
 * class loader that has loaded nothing, which makes the step idempotent across builds rather than
 * merely across processes.
 *
 * <p>A pom is in scope when it names one of {@link #NATIVE_STACK_MARKERS}, in a dependency or in
 * dependency management: an execution the root pom binds is inherited by the marked module, so the
 * declaring pom is where the fix belongs. An {@code exec-maven-plugin} block with configuration but
 * no {@code <execution>} binds nothing and is not a violation, the same way this repo's other
 * wiring check treats a configuration-only failsafe.
 */
final class NativeLoadIsolationCheck {

    /**
     * Artifacts whose presence means a load through {@code System.load} can happen in this module.
     * The bge module is the one the dependency quarantine confines to {@code graphitron-mcp}; it
     * pulls ONNX Runtime JNI and the DJL tokenizer transitively, and the tokenizer is what resolves
     * its library from a fixed cached path. {@code onnxruntime} is listed so a future direct
     * dependency on the runtime is covered without editing this check.
     */
    private static final List<String> NATIVE_STACK_MARKERS =
        List.of("langchain4j-embeddings", "onnxruntime");

    private static final Pattern XML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    /** One {@code <plugin>} block. The pom schema does not nest them, so non-greedy is exact. */
    private static final Pattern PLUGIN_BLOCK = Pattern.compile("<plugin>(.*?)</plugin>", Pattern.DOTALL);

    private static final Pattern EXECUTION_BLOCK =
        Pattern.compile("<execution>(.*?)</execution>", Pattern.DOTALL);

    private static final Pattern ARTIFACT_ID =
        Pattern.compile("<artifactId>\\s*(.*?)\\s*</artifactId>", Pattern.DOTALL);

    private static final Pattern EXECUTION_ID = Pattern.compile("<id>\\s*(.*?)\\s*</id>", Pattern.DOTALL);

    /** The in-process goal. {@code exec} forks and is what this check exists to require. */
    private static final Pattern IN_PROCESS_GOAL = Pattern.compile("<goal>\\s*java\\s*</goal>");

    private NativeLoadIsolationCheck() {}

    /**
     * Entry point invoked by {@link Main}. Takes one argument, the repository root; walks the root
     * pom plus every module pom its {@code <modules>} block declares (the same enumeration
     * {@link ModuleEnumerationCheck} reads).
     *
     * <p>Returns 0 when no marked pom binds an in-process execution, and 64 on a usage or
     * non-directory-root error. Throws {@link BuildFailure} on a violation or a declared module
     * whose pom is missing.
     */
    static int run(List<String> args) throws IOException {
        if (args.size() != 1) {
            System.err.println("usage: check-native-load-isolation <repo-root>");
            return 64;
        }
        Path root = Path.of(args.get(0)).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            System.err.println("not a directory: " + root);
            return 64;
        }
        Path rootPom = root.resolve("pom.xml");
        if (!Files.isRegularFile(rootPom)) {
            System.err.println("check-native-load-isolation: no pom.xml under " + root);
            throw new BuildFailure("reactor pom.xml not found");
        }

        List<String> problems = new ArrayList<>();
        String rootXml = Files.readString(rootPom);
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
            System.out.println("check-native-load-isolation: every native-loading module runs its"
                + " exec-maven-plugin executions in a forked JVM.");
            return 0;
        }
        System.err.println("check-native-load-isolation: " + problems.size() + " problem(s)."
            + " A build that runs this step once still passes, which is why this is a gate and not"
            + " a test: the failure needs one JVM serving two builds, and CI never has one.");
        for (String p : problems) {
            System.err.println("  " + p);
        }
        throw new BuildFailure("native load runs in the Maven JVM");
    }

    /** The invariant over one pom, comments stripped first so documentation never trips the scan. */
    static List<String> checkPom(String module, String pomXml) {
        String text = XML_COMMENT.matcher(pomXml).replaceAll("");
        String marker = nativeStackMarker(text);
        if (marker == null) {
            return List.of();
        }

        List<String> problems = new ArrayList<>();
        Matcher plugin = PLUGIN_BLOCK.matcher(text);
        while (plugin.find()) {
            String block = plugin.group(1);
            if (!block.contains("exec-maven-plugin")) {
                continue;
            }
            Matcher execution = EXECUTION_BLOCK.matcher(block);
            while (execution.find()) {
                String body = execution.group(1);
                if (!IN_PROCESS_GOAL.matcher(body).find()) {
                    continue;
                }
                Matcher id = EXECUTION_ID.matcher(body);
                String name = id.find() ? "'" + id.group(1) + "'" : "(unnamed)";
                problems.add(module + ": exec-maven-plugin execution " + name + " binds"
                    + " <goal>java</goal>, in a module carrying " + marker + ". exec:java runs in"
                    + " the Maven JVM behind a class loader the plugin rebuilds per execution, and"
                    + " a native library loaded through System.load stays bound to the loader that"
                    + " loaded it until the process exits, so the second build in a reused JVM (an"
                    + " mvnd daemon) fails with \"already loaded in another classloader\". Fork it:"
                    + " <goal>exec</goal> with <executable>${java.home}/bin/java</executable>,"
                    + " <classpath/>, and the main class as an argument.");
            }
        }
        return problems;
    }

    /** The marker artifact this pom names, or {@code null} when it carries no native stack. */
    private static String nativeStackMarker(String text) {
        Matcher artifact = ARTIFACT_ID.matcher(text);
        while (artifact.find()) {
            String id = artifact.group(1);
            for (String marker : NATIVE_STACK_MARKERS) {
                if (id.startsWith(marker)) {
                    return id;
                }
            }
        }
        return null;
    }
}
