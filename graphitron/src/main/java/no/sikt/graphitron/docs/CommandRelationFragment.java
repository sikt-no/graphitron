package no.sikt.graphitron.docs;

import no.sikt.graphitron.model.catalog.GrainSentence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/**
 * Renders the command-relation table the architecture reference includes: one row per command
 * relation, with the grain sentence that relation's own javadoc states.
 *
 * <p><b>Why generated rather than enumeration-gated.</b> A meta-test asserting the relation set is
 * complete would close membership and leave the load-bearing column, what one row of the relation
 * asserts, to rot silently beside it. The grain sentence is already written down exactly once, in
 * each relation's javadoc, which is the plan tier's analogue of the store's {@code COMMENT ON}
 * text; rendering from there means the table cannot disagree with the type it describes. The
 * fragment is generated at build and never committed, so there is no checked-in copy to drift and
 * no verify guard to forget.
 *
 * <p><b>Two sources, one universe.</b> Which relations exist comes from the types on the
 * classpath, never from listing source files: that is the same rule the store's schema reference
 * follows when it boots rather than parsing the DDL. The prose comes from the source javadoc,
 * because javadoc is not retained at runtime and a sentence has nowhere else to live. The split is
 * deliberate and does not reintroduce two answers to "what exists": the source tree is read only
 * for the prose of a type the classpath already named, and a relation whose source is missing
 * fails the render rather than rendering a blank cell.
 *
 * <p><b>The geography is scanned, not assumed.</b> {@code KeyProjectionRelation} lives in
 * {@code no.sikt.graphitron.command} while its siblings live in {@code no.sikt.graphitron.plan},
 * so the scan covers both packages. A table that assumed the relations were all in one place
 * would teach the wrong shape on the page whose job is to teach that shape.
 */
public final class CommandRelationFragment {

    /** The packages a command relation may live in. Both, because one of them does. */
    private static final List<String> SCANNED_PACKAGES =
        List.of("no.sikt.graphitron.plan", "no.sikt.graphitron.command");

    /** The naming convention that makes a type a command relation. */
    private static final String RELATION_SUFFIX = "Relation";

    /**
     * A floor under the rendered row count. The reactor holds seven relations today; a render that
     * found one or none means the scan stopped reaching the packages, and an almost-empty table is
     * exactly the plausible-looking wrong answer this fragment exists to prevent.
     */
    private static final int MIN_RELATIONS = 4;

    /** The class-level javadoc block immediately preceding the type declaration. */
    private static final Pattern CLASS_JAVADOC = Pattern.compile(
        "/\\*\\*(.*?)\\*/\\s*(?:@\\w+(?:\\([^)]*\\))?\\s*)*public\\s+(?:final\\s+)?(?:record|class|interface)\\s+(\\w+)",
        Pattern.DOTALL);

    /**
     * An inline javadoc tag. The two families read differently and conflating them corrupts the
     * text: {@code @code} and {@code @literal} take one span that is the content, parentheses and
     * commas included, while {@code @link} and {@code @linkplain} take a reference optionally
     * followed by a label.
     */
    private static final Pattern INLINE_TAG = Pattern.compile("\\{@(code|link|literal|linkplain)\\s+([^}]*)}");

    private CommandRelationFragment() {}

    /** One rendered row: which relation, where it lives, and what one of its rows asserts. */
    public record Row(String simpleName, String packageName, String grain) {}

    /**
     * CLI entry point: {@code <source-root> <output-file>}. The source root is the
     * {@code src/main/java} the relations' javadoc is read from.
     */
    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("usage: CommandRelationFragment <src-main-java> <output-file>");
            System.exit(64);
            return;
        }
        Path sourceRoot = Path.of(args[0]).toAbsolutePath().normalize();
        Path output = Path.of(args[1]).toAbsolutePath().normalize();

        List<Row> rows = rows(sourceRoot);
        Files.createDirectories(output.getParent());
        Files.writeString(output, render(rows));
        System.out.println("command-relation fragment: " + rows.size() + " relations into " + output);
    }

    /**
     * Every command relation on the classpath, with its grain sentence, ordered by name so the
     * fragment is byte-identical across runs.
     */
    public static List<Row> rows(Path sourceRoot) throws IOException {
        List<Row> rows = new ArrayList<>();
        for (String binaryName : relationTypes()) {
            int lastDot = binaryName.lastIndexOf('.');
            String packageName = binaryName.substring(0, lastDot);
            String simpleName = binaryName.substring(lastDot + 1);
            Path source = sourceRoot.resolve(binaryName.replace('.', '/') + ".java");
            if (!Files.isRegularFile(source)) {
                throw new IllegalStateException("command-relation fragment: " + binaryName
                    + " is on the classpath but its source is not under " + sourceRoot
                    + ". The fragment renders each relation's grain from its own javadoc, so a"
                    + " relation with no readable source would render a blank cell instead.");
            }
            String grain = grainOf(Files.readString(source), simpleName);
            if (grain.isBlank()) {
                throw new IllegalStateException("command-relation fragment: " + binaryName
                    + " has no class javadoc, so it states no grain. Every command relation owes"
                    + " one sentence saying what one of its rows is; write it on the type.");
            }
            rows.add(new Row(simpleName, packageName, grain));
        }
        rows.sort(Comparator.comparing(Row::simpleName));

        if (rows.size() < MIN_RELATIONS) {
            throw new IllegalStateException("command-relation fragment: found only " + rows.size()
                + " relation(s) across " + SCANNED_PACKAGES + ". The scan reads the classpath, so"
                + " a count this low means it stopped reaching those packages rather than that the"
                + " relations went away.");
        }
        return rows;
    }

    /** The fragment, as an AsciiDoc table. Never committed; the page includes it. */
    public static String render(List<Row> rows) {
        StringBuilder out = new StringBuilder();
        out.append("// Generated at build by ")
           .append(CommandRelationFragment.class.getName())
           .append(". Do not edit and do not commit.\n")
           .append("// Each row's grain sentence is the first sentence of that relation's own javadoc.\n\n")
           .append("[cols=\"1,1,3\"]\n")
           .append("|===\n")
           .append("| Relation | Package | What one row is\n\n");
        for (Row row : rows) {
            out.append("| `").append(row.simpleName()).append("`\n")
               .append("| `").append(row.packageName()).append("`\n")
               .append("| ").append(escapeCell(row.grain())).append("\n\n");
        }
        out.append("|===\n");
        return out.toString();
    }

    /**
     * The grain sentence for {@code simpleName}: the first sentence of its class javadoc, with
     * javadoc markup reduced to the AsciiDoc the page renders.
     */
    static String grainOf(String source, String simpleName) {
        Matcher matcher = CLASS_JAVADOC.matcher(source);
        while (matcher.find()) {
            if (!matcher.group(2).equals(simpleName)) continue;
            return GrainSentence.of(plainText(matcher.group(1)));
        }
        return "";
    }

    /** Javadoc body to AsciiDoc: drop leading asterisks, inline tags to code spans, HTML out. */
    static String plainText(String javadoc) {
        String text = javadoc.replaceAll("(?m)^\\s*\\*\\s?", " ");
        int firstParagraphEnd = text.indexOf("<p>");
        if (firstParagraphEnd > 0) text = text.substring(0, firstParagraphEnd);
        text = INLINE_TAG.matcher(text).replaceAll(match -> {
            String tag = match.group(1);
            String body = match.group(2).strip();
            if (tag.equals("code") || tag.equals("literal")) {
                return Matcher.quoteReplacement("`" + body + "`");
            }
            String[] parts = body.split("\\s+", 2);
            return Matcher.quoteReplacement("`" + (parts.length > 1 ? parts[1] : lastSegment(parts[0])) + "`");
        });
        text = text.replaceAll("</?(?:em|b|i|strong|code)>", "");
        return text.replaceAll("\\s+", " ").strip();
    }

    /** {@code a.b.C#member} to {@code C.member}, so a cell names the symbol and not its address. */
    private static String lastSegment(String reference) {
        String withoutMember = reference.replace('#', '.');
        String[] segments = withoutMember.split("\\.");
        for (int i = 0; i < segments.length; i++) {
            if (!segments[i].isEmpty() && Character.isUpperCase(segments[i].charAt(0))) {
                return String.join(".", List.of(segments).subList(i, segments.length));
            }
        }
        return withoutMember;
    }

    /** An AsciiDoc cell ends at an unescaped pipe, and a grain sentence may hold one. */
    private static String escapeCell(String text) {
        return text.replace("|", "\\|");
    }

    /**
     * Binary names of every {@code *Relation} type in the scanned packages, resolved through the
     * class loader rather than {@code java.class.path}. The property names the launching JVM's own
     * classpath, which under a Maven {@code exec:java} is Maven's and not this project's; asking
     * the loader for the package instead answers for whichever classpath actually loaded these
     * types, jar or directory alike.
     */
    private static List<String> relationTypes() {
        TreeSet<String> found = new TreeSet<>();
        ClassLoader loader = CommandRelationFragment.class.getClassLoader();
        for (String packageName : SCANNED_PACKAGES) {
            String resourcePath = packageName.replace('.', '/');
            try {
                var urls = loader.getResources(resourcePath);
                while (urls.hasMoreElements()) {
                    scan(urls.nextElement(), packageName, resourcePath, found);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(
                    "command-relation fragment: could not enumerate " + packageName, e);
            }
        }
        return List.copyOf(found);
    }

    /** One classpath location holding a scanned package: a directory on disk or an entry in a jar. */
    private static void scan(java.net.URL url, String packageName, String resourcePath, TreeSet<String> found) {
        try {
            if ("file".equals(url.getProtocol())) {
                scanDirectory(Path.of(url.toURI()), packageName, found);
            } else if ("jar".equals(url.getProtocol())) {
                String file = url.getPath();
                Path jar = Path.of(java.net.URI.create(file.substring(0, file.indexOf("!/"))));
                scanJar(jar, packageName, resourcePath, found);
            }
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException("command-relation fragment: unreadable classpath URL " + url, e);
        }
    }

    private static void scanJar(Path jar, String packageName, String resourcePath, TreeSet<String> found) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            zip.stream()
                .map(java.util.zip.ZipEntry::getName)
                .filter(name -> name.startsWith(resourcePath + "/") && name.endsWith(".class"))
                .map(name -> name.substring(resourcePath.length() + 1, name.length() - ".class".length()))
                .forEach(simpleName -> consider(packageName, simpleName, found));
        } catch (IOException e) {
            throw new UncheckedIOException("command-relation fragment: unreadable classpath jar " + jar, e);
        }
    }

    private static void scanDirectory(Path dir, String packageName, TreeSet<String> found) {
        try (Stream<Path> paths = Files.list(dir)) {
            paths.map(p -> p.getFileName().toString())
                .filter(name -> name.endsWith(".class"))
                .map(name -> name.substring(0, name.length() - ".class".length()))
                .forEach(simpleName -> consider(packageName, simpleName, found));
        } catch (IOException e) {
            throw new UncheckedIOException("command-relation fragment: unreadable classpath directory " + dir, e);
        }
    }

    /** Keeps a top-level {@code *Relation}; a nested type and any other name are out. */
    private static void consider(String packageName, String simpleName, TreeSet<String> found) {
        if (simpleName.indexOf('$') >= 0 || !simpleName.endsWith(RELATION_SUFFIX)) return;
        found.add(packageName + "." + simpleName);
    }
}
