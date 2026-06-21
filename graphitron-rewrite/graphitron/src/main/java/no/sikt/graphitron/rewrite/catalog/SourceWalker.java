package no.sikt.graphitron.rewrite.catalog;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.LineMap;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Recovers Java declaration positions and Javadoc from the consumer's
 * {@code .java} sources so the LSP can offer goto-definition and Javadoc
 * hover for the directives that name a consumer class or method, plus
 * per-line refinement of jOOQ-generated table / column positions.
 *
 * <p>The parse uses the JDK's own Compiler Tree API
 * ({@link com.sun.source.util}); there is no external dependency. The walk is
 * <em>parse-only</em>: {@link JavacTask#parse()} returns
 * {@link CompilationUnitTree}s without attribution, so no classpath
 * resolution is required and the walk is fast and tolerant of unresolved
 * symbols (an unresolved {@code import} or type reference does not stop the
 * file from yielding declaration positions).
 *
 * <p><b>Hot-path caching contract.</b> {@link CatalogBuilder} runs hot: it is
 * rebuilt on every classpath-watcher trigger, and the watcher fires on
 * {@code .class} changes. Source positions change only when a {@code .java}
 * changes, never when a {@code .class} is recompiled, so the index is cached
 * per source file (keyed by absolute path, invalidated by last-modified time)
 * and only changed files are re-parsed. A trigger that touches no {@code .java}
 * re-parses nothing. The cache is content-addressed (path + mtime), so it is
 * safe to share process-wide across rebuilds and across distinct workspaces;
 * tests parse under per-test temp directories and never collide.
 *
 * <p><b>Doc-comment retention.</b> {@link Trees#getDocComment(TreePath)}
 * returns the Javadoc only when the parse keeps doc comments. The
 * {@link JavacTask} obtained through the standard tool API keeps them, so no
 * extra option is needed; this is asserted by {@code SourceWalkerTest} so the
 * "Javadoc comes back empty" failure surfaces as a test, not in an editor.
 */
public final class SourceWalker {

    private SourceWalker() {}

    /** Process-wide per-file cache: absolute path -> (mtime, parsed file index). */
    private static final Map<Path, CacheEntry> CACHE = new ConcurrentHashMap<>();

    private record CacheEntry(long mtime, FileIndex index) {}

    /**
     * Declaration position plus Javadoc for a class, method, or field.
     * {@code javadoc} is the empty string when the declaration carries no
     * doc comment.
     */
    public record Decl(CompletionData.SourceLocation location, String javadoc) {}

    /** Join key for a method: fully-qualified class name, method name, parameter count. */
    public record MethodKey(String className, String methodName, int paramCount) {}

    /** Join key for a field: fully-qualified declaring class name plus the Java field name. */
    public record FieldKey(String className, String fieldName) {}

    /**
     * The merged index over every source root: classes keyed by FQN, methods
     * keyed by {@link MethodKey} (overload-ambiguous keys removed from
     * {@code methods}, so a positional lookup misses), fields keyed by
     * {@link FieldKey}, and the post-merge set of method keys that were
     * dropped <em>because</em> they were overload-ambiguous.
     *
     * <p>{@code ambiguousMethods} is the certainty the merge already computed
     * but used to discard: it lets a consumer tell "method genuinely not
     * indexed" (key absent everywhere) from "method present but the
     * {@code (class, name, arity)} key cannot pick one overload" (key in this
     * set). The LSP goto-definition path switches the two onto distinct
     * {@code DefinitionTarget} arms instead of collapsing both to a silent
     * no-jump. The set is the union of intra-file and cross-file collisions,
     * matching exactly the keys removed from {@code methods}.
     */
    public record Index(
        Map<String, Decl> classes,
        Map<MethodKey, Decl> methods,
        Map<FieldKey, Decl> fields,
        Set<MethodKey> ambiguousMethods
    ) {
        public static final Index EMPTY = new Index(Map.of(), Map.of(), Map.of(), Set.of());

        public boolean isEmpty() {
            return classes.isEmpty() && methods.isEmpty() && fields.isEmpty();
        }
    }

    /** Per-file parse result before merge; carries its own overload-ambiguity set. */
    private record FileIndex(
        Map<String, Decl> classes,
        Map<MethodKey, Decl> methods,
        Map<FieldKey, Decl> fields,
        Set<MethodKey> ambiguousMethods
    ) {
        static final FileIndex EMPTY =
            new FileIndex(Map.of(), Map.of(), Map.of(), Set.of());
    }

    /**
     * Walks every {@code .java} under {@code sourceRoots}, parsing only files
     * whose last-modified time changed since the previous walk, and returns the
     * merged {@link Index}. Returns {@link Index#EMPTY} when there are no roots
     * or no system Java compiler (e.g. running on a JRE).
     */
    public static Index walk(List<Path> sourceRoots) {
        if (sourceRoots == null || sourceRoots.isEmpty()) return Index.EMPTY;

        var files = new ArrayList<Path>();
        for (Path root : sourceRoots) {
            if (root == null || !Files.isDirectory(root)) continue;
            try (Stream<Path> w = Files.walk(root)) {
                w.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .map(p -> p.toAbsolutePath().normalize())
                    .forEach(files::add);
            } catch (IOException e) {
                throw new UncheckedIOException("source walk failed at " + root, e);
            }
        }
        if (files.isEmpty()) return Index.EMPTY;

        var perFile = new LinkedHashMap<Path, FileIndex>();
        var toParse = new ArrayList<Path>();
        for (Path f : files) {
            CacheEntry ce = CACHE.get(f);
            long mtime = mtimeOf(f);
            if (ce != null && ce.mtime() == mtime) {
                perFile.put(f, ce.index());
            } else {
                toParse.add(f);
            }
        }
        if (!toParse.isEmpty()) {
            var parsed = parse(toParse);
            for (Path f : toParse) {
                FileIndex idx = parsed.getOrDefault(f, FileIndex.EMPTY);
                CACHE.put(f, new CacheEntry(mtimeOf(f), idx));
                perFile.put(f, idx);
            }
        }
        return merge(perFile.values());
    }

    /** Clears the per-file cache. Test seam; not used in production. */
    static void clearCache() {
        CACHE.clear();
    }

    private static long mtimeOf(Path f) {
        try {
            return Files.getLastModifiedTime(f).toMillis();
        } catch (IOException e) {
            return -1L;
        }
    }

    private static Index merge(Collection<FileIndex> parts) {
        var classes = new HashMap<String, Decl>();
        var methods = new HashMap<MethodKey, Decl>();
        var fields = new HashMap<FieldKey, Decl>();
        var ambiguousMethods = new HashSet<MethodKey>();
        for (FileIndex part : parts) {
            // First class / field wins on a cross-file FQN collision (a
            // duplicate top-level class is malformed; either copy is a fine
            // jump target).
            part.classes().forEach(classes::putIfAbsent);
            part.fields().forEach(fields::putIfAbsent);
            ambiguousMethods.addAll(part.ambiguousMethods());
            part.methods().forEach((k, v) -> {
                if (methods.putIfAbsent(k, v) != null) {
                    // Same key from two files: ambiguous, drop later.
                    ambiguousMethods.add(k);
                }
            });
        }
        for (MethodKey k : ambiguousMethods) {
            methods.remove(k);
        }
        return new Index(
            Map.copyOf(classes), Map.copyOf(methods),
            Map.copyOf(fields), Set.copyOf(ambiguousMethods));
    }

    /**
     * Parses {@code files} with a single {@link JavacTask} and indexes each
     * resulting compilation unit. A single broken file does not poison the
     * batch: if the batch parse throws, every file is retried individually and
     * the offenders are skipped (their declarations simply do not appear in the
     * index, so their symbols resolve to {@code UNKNOWN}).
     */
    private static Map<Path, FileIndex> parse(List<Path> files) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) return Map.of();
        try {
            return parseBatch(compiler, files);
        } catch (RuntimeException batchFailure) {
            var out = new HashMap<Path, FileIndex>();
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

    private static Map<Path, FileIndex> parseBatch(JavaCompiler compiler, List<Path> files) {
        var result = new HashMap<Path, FileIndex>();
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
                var builder = new FileIndexBuilder(trees, positions, cu);
                builder.scan(cu, null);
                result.put(path, builder.build());
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
     * {@link TreePathScanner} that records the position + Javadoc of every
     * class and method and the position + Javadoc of every field (a
     * {@link VariableTree} whose immediate encloser is a class, never a
     * parameter or local).
     */
    private static final class FileIndexBuilder extends TreePathScanner<Void, Void> {
        private final Trees trees;
        private final SourcePositions positions;
        private final CompilationUnitTree cu;
        private final String packageName;
        private final Map<String, Decl> classes = new HashMap<>();
        private final Map<MethodKey, Decl> methods = new HashMap<>();
        private final Map<FieldKey, Decl> fields = new HashMap<>();
        private final Set<MethodKey> ambiguousMethods = new HashSet<>();

        FileIndexBuilder(Trees trees, SourcePositions positions, CompilationUnitTree cu) {
            this.trees = trees;
            this.positions = positions;
            this.cu = cu;
            this.packageName = cu.getPackageName() == null ? "" : cu.getPackageName().toString();
        }

        FileIndex build() {
            return new FileIndex(
                Map.copyOf(classes), Map.copyOf(methods),
                Map.copyOf(fields), Set.copyOf(ambiguousMethods));
        }

        @Override
        public Void visitClass(ClassTree node, Void unused) {
            String fqn = classFqn(getCurrentPath());
            if (fqn != null && !fqn.isEmpty()) {
                classes.putIfAbsent(fqn, declOf(node));
            }
            return super.visitClass(node, unused);
        }

        @Override
        public Void visitMethod(MethodTree node, Void unused) {
            String fqn = classFqn(getCurrentPath().getParentPath());
            if (fqn != null && !fqn.isEmpty()) {
                var key = new MethodKey(fqn, node.getName().toString(), node.getParameters().size());
                if (methods.putIfAbsent(key, declOf(node)) != null) {
                    ambiguousMethods.add(key);
                }
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
                    fields.putIfAbsent(new FieldKey(fqn, node.getName().toString()), declOf(node));
                }
            }
            return super.visitVariable(node, unused);
        }

        private Decl declOf(Tree node) {
            return new Decl(locationOf(node), docOf());
        }

        private String docOf() {
            String doc = trees.getDocComment(getCurrentPath());
            return doc == null ? "" : doc.strip();
        }

        private CompletionData.SourceLocation locationOf(Tree node) {
            long start = positions.getStartPosition(cu, node);
            if (start < 0) return CompletionData.SourceLocation.UNKNOWN;
            LineMap lineMap = cu.getLineMap();
            int line = (int) lineMap.getLineNumber(start) - 1;
            int column = (int) lineMap.getColumnNumber(start) - 1;
            String uri = cu.getSourceFile().toUri().toString();
            return new CompletionData.SourceLocation(uri, Math.max(line, 0), Math.max(column, 0));
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
