package no.sikt.graphitron.model.sources;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Recovers Java declaration positions and Javadoc from the consumer's
 * {@code .java} sources: where each class, method and field is written, and
 * what its doc comment says.
 *
 * <p>The parse's product is {@link #walkFiles}, one {@link ParsedFile} per
 * source in walk order carrying its {@link Declaration}s as the parse read
 * them. That is what the store's {@code java_} family is written from, and it
 * is the shape the facts have: every overload is its own declaration, and
 * nothing is dropped for being hard to key. A keyed projection over the same
 * declarations used to ship beside it, carrying a resolution policy the keys
 * forced on it; the store's row grain answers the same questions without one,
 * so the walk has a single product again.
 *
 * <p>The parse uses the JDK's own Compiler Tree API
 * ({@link com.sun.source.util}); there is no external dependency. The walk is
 * <em>parse-only</em>: {@link JavacTask#parse()} returns
 * {@link CompilationUnitTree}s without attribution, so no classpath
 * resolution is required and the walk is fast and tolerant of unresolved
 * symbols (an unresolved {@code import} or type reference does not stop the
 * file from yielding declaration positions).
 *
 * <p><b>Hot-path caching contract.</b> The walk is driven on the {@code .java}
 * (source) cadence by the dev goal's source-root watcher. Source positions
 * change only when a {@code .java} changes, so declarations are cached per
 * source file (keyed by absolute path, invalidated by last-modified time) and
 * only changed files are re-parsed; a refresh that touches no parsed file
 * re-parses nothing. The cache is an <em>instance</em> field, owned by whoever
 * drives the walk: there is no process-wide static cache to couple distinct
 * cadences or distinct workspaces. Construct one {@code SourceWalker} per
 * long-lived driver and reuse it across refreshes so the cache stays warm.
 * The mtime check is the walk's own cheap gate and is independent of the
 * store's content-hash stamp, which decides the same question across process
 * lifetimes where a timestamp cannot be trusted.
 *
 * <p><b>Doc-comment retention.</b> {@link Trees#getDocComment(TreePath)}
 * returns the Javadoc only when the parse keeps doc comments. The
 * {@link JavacTask} obtained through the standard tool API keeps them, so no
 * extra option is needed; this is asserted by {@code SourceWalkerTest} so the
 * "Javadoc comes back empty" failure surfaces as a test, not in an editor.
 */
public final class SourceWalker {

    /** Per-instance per-file cache: absolute path -> (mtime, declarations in source order). */
    private final Map<Path, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(long mtime, List<Declaration> declarations) {}

    /**
     * One declaration the parse read, in the source language's terms. Sealed on
     * the three shapes the walk records, because they carry different things: a
     * class is named by its own dotted name, a method adds a name and an arity,
     * a field adds a name. A flat record with unused components for the arms
     * that lack them would leave every reader asking which fields mean anything
     * for the row in hand.
     *
     * <p>Positions are 1-based, the Compiler Tree API's own convention, and
     * {@code -1} where the parse reported none. An editor surface converts;
     * this is what the parse said.
     */
    public sealed interface Declaration {

        /** The declaring class's dotted name; for a class declaration, its own. */
        String className();

        /** 1-based declaration line, or -1 where the parse reported no position. */
        int line();

        /** 1-based declaration column, on the same terms as {@link #line}. */
        int column();

        /** The stripped doc comment, or the empty string when the declaration carries none. */
        String javadoc();

        /** A class, interface, enum, record or annotation declaration. */
        record ClassDecl(String className, int line, int column, String javadoc)
            implements Declaration {}

        /**
         * A method declaration, arity-bearing. Every overload is its own
         * declaration: the parse has no descriptor to key on, and a reader that
         * needs one answer per name resolves that from the rows rather than
         * having the walk pick for it.
         */
        record MethodDecl(
            String className, String methodName, int parameterCount,
            int line, int column, String javadoc
        ) implements Declaration {}

        /** A field declaration whose immediate encloser is a class. */
        record FieldDecl(String className, String fieldName, int line, int column, String javadoc)
            implements Declaration {}
    }

    /**
     * One source file's parse product: the root it was reached under, the file,
     * and its declarations in source order. The root is carried because it is the
     * scope a walk owns, so a consumer pruning what left the walk can tell its
     * own files from a sibling module's.
     */
    public record ParsedFile(Path sourceRoot, Path file, List<Declaration> declarations) {

        public ParsedFile {
            declarations = List.copyOf(declarations);
        }
    }

    /**
     * Parses every {@code .java} under {@code sourceRoots} and returns one
     * {@link ParsedFile} per source, roots in the given order and files in walk
     * order within each. Only files whose last-modified time changed since the
     * previous walk are re-parsed; the rest come from the cache.
     *
     * <p>A file reachable under two roots is attributed to the first root that
     * reached it and appears once. Returns an empty list when there are no roots
     * or no system Java compiler (running on a JRE, say), which is the same
     * answer as a workspace with no sources: absence, not a failure.
     */
    public List<ParsedFile> walkFiles(List<Path> sourceRoots) {
        if (sourceRoots == null || sourceRoots.isEmpty()) return List.of();

        var roots = new LinkedHashMap<Path, Path>();
        for (Path root : sourceRoots) {
            if (root == null || !Files.isDirectory(root)) continue;
            Path normalised = root.toAbsolutePath().normalize();
            try (Stream<Path> w = Files.walk(root)) {
                w.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .map(p -> p.toAbsolutePath().normalize())
                    .forEach(p -> roots.putIfAbsent(p, normalised));
            } catch (IOException e) {
                throw new UncheckedIOException("source walk failed at " + root, e);
            }
        }
        if (roots.isEmpty()) return List.of();

        var toParse = new ArrayList<Path>();
        for (Path f : roots.keySet()) {
            CacheEntry ce = cache.get(f);
            if (ce == null || ce.mtime() != mtimeOf(f)) {
                toParse.add(f);
            }
        }
        if (!toParse.isEmpty()) {
            var parsed = parse(toParse);
            for (Path f : toParse) {
                cache.put(f, new CacheEntry(mtimeOf(f), parsed.getOrDefault(f, List.of())));
            }
        }
        var out = new ArrayList<ParsedFile>(roots.size());
        roots.forEach((file, root) ->
            out.add(new ParsedFile(root, file, cache.get(file).declarations())));
        return List.copyOf(out);
    }

    private static long mtimeOf(Path f) {
        try {
            return Files.getLastModifiedTime(f).toMillis();
        } catch (IOException e) {
            return -1L;
        }
    }

    /**
     * Parses {@code files} with a single {@link JavacTask} and reads the
     * declarations off each resulting compilation unit. A single broken file does
     * not poison the batch: if the batch parse throws, every file is retried
     * individually and the offenders are skipped, so their declarations simply do
     * not appear and every consumer reads that as absence.
     */
    private static Map<Path, List<Declaration>> parse(List<Path> files) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) return Map.of();
        try {
            return parseBatch(compiler, files);
        } catch (RuntimeException batchFailure) {
            var out = new HashMap<Path, List<Declaration>>();
            for (Path f : files) {
                try {
                    out.putAll(parseBatch(compiler, List.of(f)));
                } catch (RuntimeException ignored) {
                    // Skip the single offending file; the rest are already in.
                }
            }
            return out;
        }
    }

    private static Map<Path, List<Declaration>> parseBatch(JavaCompiler compiler, List<Path> files) {
        var result = new HashMap<Path, List<Declaration>>();
        try (StandardJavaFileManager fm =
                 compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromPaths(files);
            JavacTask task = (JavacTask) compiler.getTask(
                null, fm, diagnostic -> { }, List.of(), null, units);
            Trees trees = Trees.instance(task);
            SourcePositions positions = trees.getSourcePositions();
            for (CompilationUnitTree cu : task.parse()) {
                Path path = pathOf(cu);
                if (path == null) continue;
                var scanner = new DeclarationScanner(trees, positions, cu);
                scanner.scan(cu, null);
                result.put(path, scanner.declarations());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("source parse failed", e);
        }
        return result;
    }

    private static Path pathOf(CompilationUnitTree cu) {
        try {
            return Path.of(cu.getSourceFile().toUri()).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * {@link TreePathScanner} that records every class and method declaration and
     * every field declaration (a {@link VariableTree} whose immediate encloser is
     * a class, never a parameter or local), in source order.
     *
     * <p>Nothing is deduplicated or dropped here: two same-arity overloads are two
     * declarations, because that is what the file says. What to do about a name
     * that resolves to more than one declaration is a reader's question, and the
     * store answers it with a row count.
     */
    private static final class DeclarationScanner extends TreePathScanner<Void, Void> {
        private final Trees trees;
        private final SourcePositions positions;
        private final CompilationUnitTree cu;
        private final String packageName;
        private final List<Declaration> declarations = new ArrayList<>();

        DeclarationScanner(Trees trees, SourcePositions positions, CompilationUnitTree cu) {
            this.trees = trees;
            this.positions = positions;
            this.cu = cu;
            this.packageName = cu.getPackageName() == null ? "" : cu.getPackageName().toString();
        }

        List<Declaration> declarations() {
            return List.copyOf(declarations);
        }

        @Override
        public Void visitClass(ClassTree node, Void unused) {
            String fqn = classFqn(getCurrentPath());
            if (fqn != null && !fqn.isEmpty()) {
                long start = startOf(node);
                declarations.add(new Declaration.ClassDecl(
                    fqn, lineOf(start), columnOf(start), docOf()));
            }
            return super.visitClass(node, unused);
        }

        @Override
        public Void visitMethod(MethodTree node, Void unused) {
            String fqn = classFqn(getCurrentPath().getParentPath());
            if (fqn != null && !fqn.isEmpty()) {
                long start = startOf(node);
                declarations.add(new Declaration.MethodDecl(
                    fqn, node.getName().toString(), node.getParameters().size(),
                    lineOf(start), columnOf(start), docOf()));
            }
            return super.visitMethod(node, unused);
        }

        @Override
        public Void visitVariable(VariableTree node, Void unused) {
            Tree enclosing = getCurrentPath().getParentPath() == null
                ? null : getCurrentPath().getParentPath().getLeaf();
            if (enclosing instanceof ClassTree) {
                String fqn = classFqn(getCurrentPath().getParentPath());
                if (fqn != null && !fqn.isEmpty()) {
                    long start = startOf(node);
                    declarations.add(new Declaration.FieldDecl(
                        fqn, node.getName().toString(), lineOf(start), columnOf(start), docOf()));
                }
            }
            return super.visitVariable(node, unused);
        }

        private String docOf() {
            String doc = trees.getDocComment(getCurrentPath());
            return doc == null ? "" : doc.strip();
        }

        private long startOf(Tree node) {
            return positions.getStartPosition(cu, node);
        }

        /** 1-based line, or -1 where the parse positioned nothing. */
        private int lineOf(long start) {
            return start < 0 ? -1 : (int) cu.getLineMap().getLineNumber(start);
        }

        /** 1-based column, or -1 where the parse positioned nothing. */
        private int columnOf(long start) {
            return start < 0 ? -1 : (int) cu.getLineMap().getColumnNumber(start);
        }

        /**
         * Fully-qualified name of the innermost class on {@code path}: the
         * package name plus the dotted chain of enclosing class simple names.
         * Returns null when no class is on the path.
         */
        private String classFqn(TreePath path) {
            var names = new ArrayDeque<String>();
            for (TreePath p = path; p != null; p = p.getParentPath()) {
                if (p.getLeaf() instanceof ClassTree ct) {
                    var simple = ct.getSimpleName();
                    if (simple != null && !simple.isEmpty()) {
                        names.addFirst(simple.toString());
                    }
                }
            }
            if (names.isEmpty()) return null;
            String nested = String.join(".", names);
            return packageName.isEmpty() ? nested : packageName + "." + nested;
        }
    }
}
