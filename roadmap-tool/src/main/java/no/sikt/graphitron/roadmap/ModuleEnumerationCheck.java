package no.sikt.graphitron.roadmap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fails the build when a document that enumerates the reactor's modules has fallen behind the
 * root pom's {@code <modules>} block. The enumeration is the cheapest orientation an agent
 * session gets, and it drifts silently: a module added to the reactor changes nothing that any
 * build step reads, so both the onboarding file and the module reference page kept claiming a
 * smaller reactor than the one they described until this check existed.
 *
 * <p>Checked in one direction only: every declared module must be named in every enumerating
 * document. A document naming a module the pom no longer declares is not flagged, because a
 * module identifier and an ordinary backticked word are not distinguishable in prose without a
 * heuristic that would fire on the reactor's own vocabulary. Module removal is rare and shows up
 * as a build failure elsewhere; module addition is the drift that happened.
 */
final class ModuleEnumerationCheck {

    /**
     * Documents that enumerate the modules and must therefore name all of them. Every entry must
     * exist; a missing path fails the check rather than silently shrinking the scan. When a new
     * module joins the reactor these are the files the failure points at, which is also what
     * keeps the module count each one states in prose honest.
     */
    static final List<String> ENUMERATING_DOCS = List.of(
        "CLAUDE.md",
        "docs/architecture/reference/modules.adoc"
    );

    /** A {@code <module>name</module>} entry of the root pom's reactor list. */
    private static final Pattern MODULE_ELEMENT = Pattern.compile("<module>\\s*([^<>\\s]+)\\s*</module>");

    private ModuleEnumerationCheck() {}

    /**
     * Entry point invoked by {@link Main}. Takes one argument, the repository root holding the
     * reactor {@code pom.xml} that {@link #ENUMERATING_DOCS} is checked against.
     *
     * <p>Returns 0 when every declared module is named in every enumerating document, and 64 on
     * a usage or non-directory-root error. Throws {@link BuildFailure} on a missing name, a
     * missing document, or a root pom that yields no modules at all.
     */
    static int run(List<String> args) throws IOException {
        if (args.size() != 1) {
            System.err.println("usage: check-module-enumeration <repo-root>");
            return 64;
        }
        Path root = Path.of(args.get(0)).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            System.err.println("not a directory: " + root);
            return 64;
        }

        Path pom = root.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            System.err.println("check-module-enumeration: no pom.xml under " + root);
            throw new BuildFailure("reactor pom.xml not found");
        }
        Set<String> modules = declaredModules(Files.readString(pom));
        if (modules.isEmpty()) {
            // A root that parses to no modules means the pom moved or its <modules> block changed
            // shape; without this the check would pass vacuously against every document.
            System.err.println("check-module-enumeration: no <module> entries in " + pom
                + ". The reactor declares modules, so a parse yielding none means this check is"
                + " reading the wrong file or the wrong shape.");
            throw new BuildFailure("reactor pom declares no modules");
        }

        List<String> problems = new ArrayList<>();
        for (String doc : ENUMERATING_DOCS) {
            Path file = root.resolve(doc);
            if (!Files.isRegularFile(file)) {
                problems.add(doc + ": document not found; repoint ModuleEnumerationCheck to where it moved");
                continue;
            }
            for (String missing : unnamedModules(Files.readString(file), modules)) {
                problems.add(doc + ": does not name module `" + missing + "`");
            }
        }

        if (problems.isEmpty()) {
            System.out.println("check-module-enumeration: all " + modules.size()
                + " reactor modules named in " + ENUMERATING_DOCS.size() + " enumerating document(s).");
            return 0;
        }
        System.err.println("check-module-enumeration: " + problems.size()
            + " problem(s). Each enumerating document must name every module the root pom declares,"
            + " written as a backticked identifier; update the enumeration (and any module count it"
            + " states in the same breath) to match the reactor.");
        for (String p : problems) {
            System.err.println("  " + p);
        }
        throw new BuildFailure("module enumeration out of date with the reactor pom");
    }

    /** Parses the {@code <module>} entries out of a reactor pom, in declaration order. */
    static Set<String> declaredModules(String pomXml) {
        Set<String> modules = new LinkedHashSet<>();
        Matcher m = MODULE_ELEMENT.matcher(pomXml);
        while (m.find()) {
            modules.add(m.group(1));
        }
        return modules;
    }

    /**
     * The declared modules that {@code docText} does not name. A name counts as present only when
     * written as a backticked identifier, which is how both documents write module names and what
     * keeps a generic identifier such as {@code docs} from matching incidental prose.
     */
    static List<String> unnamedModules(String docText, Set<String> modules) {
        List<String> unnamed = new ArrayList<>();
        for (String module : modules) {
            if (!docText.contains("`" + module + "`")) {
                unnamed.add(module);
            }
        }
        return unnamed;
    }
}
