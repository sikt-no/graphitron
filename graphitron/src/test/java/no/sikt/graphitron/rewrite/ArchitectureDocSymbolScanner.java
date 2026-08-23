package no.sikt.graphitron.rewrite;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/**
 * The extractor and the universe behind {@link ArchitectureDocSymbolGuardTest}: which
 * backtick-delimited spans in an architecture page are claims about a Java type, and which type
 * names the reactor actually declares.
 *
 * <p><b>The universe comes from the classpath.</b> {@link #classpathTypeNames} indexes the class
 * files on {@code java.class.path}, directories and jars alike, never a regex over source files.
 * This is the same judgment {@code SchemaIdentifierDriftCheck} makes when it boots the store
 * instead of parsing the DDL: two mechanisms of different fidelity answering "what exists" is the
 * defect, not the fix. A source scan would also answer a subtly different question, since it sees
 * files this tier could not load.
 *
 * <p><b>What counts as a citation.</b> A span is a candidate when its leading dotted run reads as
 * a type reference: the first segment starts uppercase and carries at least one lowercase letter,
 * so screaming-snake constants ({@code PAGE_CANDIDATES}) and bare acronyms are out, and any span
 * carrying a space or a slash is out because it is prose or a path, not a name. Method tails,
 * generic arguments and array brackets are stripped before resolution, so
 * {@code SelectedField.getArguments()} is a citation of {@code SelectedField}. Nested types are
 * resolved by their dotted suffix first ({@code AuthorError.TypeConflict}) and by the outermost
 * simple name second, which is how the pages actually spell them.
 *
 * <p><b>The gap this does not close.</b> Resolution is by simple name, so a cited name resolves
 * whenever <em>any</em> type on the classpath carries it. A page naming an axis value
 * {@code Column} passes because jOOQ declares a {@code Column}, not because the axis value exists.
 * The guard therefore catches a name nothing on the classpath declares, which is the rot the
 * survey found, and does not catch a name that collides with an unrelated type. Closing that would
 * need the pages to cite fully-qualified names, which is a change to how the prose reads rather
 * than to this scan.
 */
public final class ArchitectureDocSymbolScanner {

    /** The docs tree this guard is scoped to, relative to the repository root. */
    static final String ARCHITECTURE_DOCS = "docs/architecture";

    /**
     * The comment a machine-rendered block opens with, and the reason this scan skips one.
     *
     * <p>A generated block is not an authored citation, and it is already held to something
     * stronger than name resolution: the renderer that produced it runs the real pipeline, and its
     * doc guard asserts the block on the page verbatim. Scanning it too would report the GraphQL
     * types in a rendered coordinate ({@code Individual.birthDate}) as dangling Java types, which
     * is the extractor being wrong rather than the page. Marking the region is also what tells a
     * human not to hand-edit it.
     */
    public static final String GENERATED_BLOCK_MARKER = "// Generated from the corpus.";

    /** Anything between backticks. AsciiDoc's {@code +...+} passthrough form is stripped separately. */
    private static final Pattern BACKTICK_SPAN = Pattern.compile("`([^`]+)`");

    /** A type-shaped first segment: starts uppercase, carries a lowercase letter, no underscore. */
    private static final Pattern TYPE_SEGMENT = Pattern.compile("[A-Z][A-Za-z0-9]*[a-z][A-Za-z0-9]*");

    private ArchitectureDocSymbolScanner() {}

    /** One cited span, with where it was written, for a failure message that can be acted on. */
    record Citation(String page, int line, String symbol) {
        @Override
        public String toString() {
            return page + ":" + line + ": `" + symbol + "`";
        }
    }

    /**
     * Every architecture page under {@code root}, sorted, so a failure list is stable across runs.
     * Excludes {@code target/}: the docs module stages a rendered copy of this very tree there, and
     * scanning it would double every finding.
     */
    static List<Path> pages(Path root) throws IOException {
        Path tree = root.resolve(ARCHITECTURE_DOCS);
        if (!Files.isDirectory(tree)) return List.of();
        try (Stream<Path> paths = Files.walk(tree)) {
            return paths.filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".adoc"))
                .filter(p -> !p.toString().contains(File.separator + "target" + File.separator))
                .sorted()
                .toList();
        }
    }

    /** Every type citation on one page, in reading order, duplicates included. */
    static List<Citation> scanPage(Path root, Path page) throws IOException {
        String relative = root.relativize(page).toString();
        List<Citation> citations = new ArrayList<>();
        List<String> lines = Files.readAllLines(page);
        int generatedTableDelimiters = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith(GENERATED_BLOCK_MARKER)) {
                generatedTableDelimiters = 0;
                continue;
            }
            if (generatedTableDelimiters >= 0) {
                // A generated block is one AsciiDoc table, so it holds exactly two `|===` lines:
                // the region ends at the second, the closing one. Ending at the first would leave
                // the table's own rows in scope, which is the whole population being skipped.
                if (line.strip().equals("|===") && ++generatedTableDelimiters == 2) {
                    generatedTableDelimiters = -1;
                }
                continue;
            }
            Matcher spans = BACKTICK_SPAN.matcher(line);
            while (spans.find()) {
                String symbol = symbolOf(spans.group(1));
                if (symbol != null) citations.add(new Citation(relative, i + 1, symbol));
            }
        }
        return citations;
    }

    /**
     * The type name a backticked span cites, or {@code null} when the span is not a type
     * reference. The returned name is the leading dotted run of type-shaped segments, so a nested
     * type keeps the qualifier the page wrote and a method call loses its tail.
     */
    static String symbolOf(String span) {
        String text = span.strip();
        // AsciiDoc passthrough wrapping inside a code span, as the rendered roadmap emits.
        if (text.startsWith("+") && text.endsWith("+") && text.length() > 2) {
            text = text.substring(1, text.length() - 1).strip();
        }
        if (text.isEmpty() || text.indexOf(' ') >= 0 || text.indexOf('/') >= 0) return null;

        // Drop everything from the first character that cannot be part of a dotted type name.
        int cut = text.length();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '.' && c != '$' && !Character.isLetterOrDigit(c) && c != '_') {
                cut = i;
                break;
            }
        }
        text = text.substring(0, cut);
        if (text.isEmpty()) return null;

        List<String> segments = new ArrayList<>();
        for (String segment : text.split("[.$]")) {
            if (!TYPE_SEGMENT.matcher(segment).matches()) break;
            segments.add(segment);
        }
        return segments.isEmpty() ? null : String.join(".", segments);
    }

    /**
     * Simple and dotted-suffix names of every type on {@code java.class.path}. A nested type
     * contributes each of its suffixes ({@code TypeConflict}, {@code AuthorError.TypeConflict},
     * {@code Rejection.AuthorError.TypeConflict}) so a page resolves however deep it chose to
     * qualify, and each of its segments, since the pages name outer types on their own too.
     */
    static Set<String> classpathTypeNames() {
        Set<String> names = new LinkedHashSet<>();
        for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            if (entry.isBlank()) continue;
            Path path = Path.of(entry);
            if (entry.endsWith(".jar") && Files.isRegularFile(path)) {
                indexJar(path, names);
            } else if (Files.isDirectory(path)) {
                indexDirectory(path, names);
            }
        }
        return names;
    }

    private static void indexJar(Path jar, Set<String> names) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            zip.stream()
                .map(java.util.zip.ZipEntry::getName)
                .filter(name -> name.endsWith(".class"))
                .forEach(name -> addBinaryName(name.substring(0, name.length() - ".class".length()), names));
        } catch (IOException e) {
            // A classpath entry that will not open contributes nothing; the floors below catch a
            // universe that came back empty, which is the failure mode worth failing on.
        }
    }

    private static void indexDirectory(Path dir, Set<String> names) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.filter(p -> p.toString().endsWith(".class"))
                .forEach(p -> {
                    String relative = dir.relativize(p).toString();
                    addBinaryName(relative.substring(0, relative.length() - ".class".length()), names);
                });
        } catch (IOException e) {
            // As above.
        }
    }

    /** Adds every suffix and segment of one binary name, e.g. {@code a/b/Outer$Inner}. */
    private static void addBinaryName(String binaryName, Set<String> names) {
        String withoutPackage = binaryName.replace(File.separatorChar, '/');
        int lastSlash = withoutPackage.lastIndexOf('/');
        if (lastSlash >= 0) withoutPackage = withoutPackage.substring(lastSlash + 1);
        String[] segments = withoutPackage.split("\\$");
        for (int i = 0; i < segments.length; i++) {
            if (segments[i].isEmpty() || Character.isDigit(segments[i].charAt(0))) continue;
            names.add(segments[i]);
            names.add(String.join(".", List.of(segments).subList(i, segments.length)));
        }
    }

    /**
     * The JDK packages a page may name a type from without qualifying it. The JDK ships as
     * modules rather than on {@code java.class.path}, so its types resolve through the class
     * loader in {@link #resolves} instead of through the index.
     */
    private static final List<String> PLATFORM_PACKAGES = List.of(
        "java.lang", "java.util", "java.util.concurrent", "java.util.concurrent.atomic",
        "java.util.function", "java.util.stream", "java.nio.file", "java.io", "java.time",
        "java.math", "java.sql");

    /**
     * Resolves one cited symbol against the classpath index, falling back to the class loader for
     * the JDK's own types, which live in modules rather than on {@code java.class.path}.
     */
    static boolean resolves(String symbol, Set<String> classpathNames) {
        if (classpathNames.contains(symbol)) return true;
        String outermost = symbol.contains(".") ? symbol.substring(0, symbol.indexOf('.')) : symbol;
        if (classpathNames.contains(outermost)) return true;
        for (String pkg : PLATFORM_PACKAGES) {
            try {
                Class.forName(pkg + "." + outermost);
                return true;
            } catch (ClassNotFoundException | LinkageError ignored) {
                // Not this package; try the next.
            }
        }
        return false;
    }
}
