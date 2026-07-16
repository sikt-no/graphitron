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
import java.util.Optional;
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
 * <p><b>Hot-path caching contract.</b> The walk is driven on the {@code .java}
 * (source) cadence by the dev goal's source-root watcher, which refreshes the
 * LSP-owned index on every source change. Source positions change only when a
 * {@code .java} changes, so the index is cached per source file (keyed by
 * absolute path, invalidated by last-modified time) and only changed files are
 * re-parsed; a refresh that touches no parsed file re-parses nothing. The cache
 * is an <em>instance</em> field, owned by the same party that owns the index
 * (the LSP {@code Workspace}): there is no process-wide static cache to
 * couple distinct cadences or distinct workspaces. Construct one
 * {@code SourceWalker} per long-lived index owner and reuse it across refreshes
 * so the cache stays warm.
 *
 * <p><b>Doc-comment retention.</b> {@link Trees#getDocComment(TreePath)}
 * returns the Javadoc only when the parse keeps doc comments. The
 * {@link JavacTask} obtained through the standard tool API keeps them, so no
 * extra option is needed; this is asserted by {@code SourceWalkerTest} so the
 * "Javadoc comes back empty" failure surfaces as a test, not in an editor.
 */
public final class SourceWalker {

    /** Per-instance per-file cache: absolute path -> (mtime, parsed file index). */
    private final Map<Path, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(long mtime, FileIndex index) {}

    /**
     * Declaration position plus Javadoc for a class, method, or field.
     * {@code javadoc} is the empty string when the declaration carries no
     * doc comment.
     */
    public record Decl(CompletionData.SourceLocation location, String javadoc) {}

    /** Join key for a method: fully-qualified class name, method name, parameter count. */
    public record MethodKey(String className, String methodName, int paramCount) {}

    /**
     * Join key for the name-level method view: fully-qualified class name plus
     * method name, with no arity. Unlike {@link MethodKey}, a key here is never
     * dropped on an overload collision, so it is the floor a consumer falls back
     * to when the arity-keyed lookup misses (an absent arity, or a same-arity
     * overload collision the arity map discarded).
     */
    public record MethodNameKey(String className, String methodName) {}

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
     * set). The set is the union of intra-file and cross-file collisions,
     * matching exactly the keys removed from {@code methods}.
     *
     * <p>{@code methodsByName} is the never-dropped name-level view (keyed by
     * {@link MethodNameKey}, first declaration wins, overload collisions kept):
     * the floor {@link #resolveMethod} falls back to when the arity-keyed lookup
     * misses, so a same-arity overload still lands on a declaration adjacent to
     * the overload set rather than declining.
     */
    public record Index(
        Map<String, Decl> classes,
        Map<MethodKey, Decl> methods,
        Map<FieldKey, Decl> fields,
        Set<MethodKey> ambiguousMethods,
        Map<MethodNameKey, Decl> methodsByName
    ) {
        public static final Index EMPTY = new Index(Map.of(), Map.of(), Map.of(), Set.of());

        /**
         * Back-compat constructor for fixtures that predate the name-level view:
         * derives {@code methodsByName} from {@code methods}. The production index
         * is built by {@link SourceWalker#merge} through the canonical constructor,
         * which keeps overload-collided names that {@code methods} dropped; a
         * derived view cannot recover those, which is fine for the (collision-free)
         * fixtures that use this overload.
         */
        public Index(
            Map<String, Decl> classes, Map<MethodKey, Decl> methods,
            Map<FieldKey, Decl> fields, Set<MethodKey> ambiguousMethods
        ) {
            this(classes, methods, fields, ambiguousMethods, deriveByName(methods));
        }

        private static Map<MethodNameKey, Decl> deriveByName(Map<MethodKey, Decl> methods) {
            var byName = new LinkedHashMap<MethodNameKey, Decl>();
            methods.forEach((k, v) ->
                byName.putIfAbsent(new MethodNameKey(k.className(), k.methodName()), v));
            return Map.copyOf(byName);
        }

        /**
         * Two-step method resolution: the precise {@code (class, name, arity)} key
         * first, then the never-dropped {@code (class, name)} view as a floor. The
         * arity key lands on the correct overload when it resolves; the name floor
         * guarantees a jump when the arity key is absent or was dropped as a
         * same-arity collision. Empty only when the class carries no declaration of
         * that name at all (or is not indexed). Shared by goto-definition and the
         * declaration-name hover overlay so the two cannot diverge.
         */
        public Optional<Decl> resolveMethod(String className, String methodName, int paramCount) {
            var byArity = methods.get(new MethodKey(className, methodName, paramCount));
            if (byArity != null) return Optional.of(byArity);
            return methodByName(className, methodName);
        }

        /**
         * The never-dropped name-level view: any indexed declaration of
         * {@code methodName} on {@code className}, ignoring arity. The floor for a
         * same-arity overload collision the arity-keyed {@link #methods} dropped.
         */
        public Optional<Decl> methodByName(String className, String methodName) {
            return Optional.ofNullable(methodsByName.get(new MethodNameKey(className, methodName)));
        }

        public boolean isEmpty() {
            return classes.isEmpty() && methods.isEmpty() && fields.isEmpty();
        }
    }

    /** Per-file parse result before merge; carries its own overload-ambiguity set. */
    private record FileIndex(
        Map<String, Decl> classes,
        Map<MethodKey, Decl> methods,
        Map<FieldKey, Decl> fields,
        Set<MethodKey> ambiguousMethods,
        Map<MethodNameKey, Decl> methodsByName
    ) {
        static final FileIndex EMPTY =
            new FileIndex(Map.of(), Map.of(), Map.of(), Set.of(), Map.of());
    }

    /**
     * Walks every {@code .java} under {@code sourceRoots}, parsing only files
     * whose last-modified time changed since the previous walk, and returns the
     * merged {@link Index}. Returns {@link Index#EMPTY} when there are no roots
     * or no system Java compiler (e.g. running on a JRE).
     */
    public Index walk(List<Path> sourceRoots) {
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
            CacheEntry ce = cache.get(f);
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
                cache.put(f, new CacheEntry(mtimeOf(f), idx));
                perFile.put(f, idx);
            }
        }
        return merge(perFile.values());
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
        var methodsByName = new LinkedHashMap<MethodNameKey, Decl>();
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
            // First declaration wins across the merge, matching the class / field
            // policy above; the name-level view is never dropped on a collision.
            part.methodsByName().forEach(methodsByName::putIfAbsent);
        }
        for (MethodKey k : ambiguousMethods) {
            methods.remove(k);
        }
        return new Index(
            Map.copyOf(classes), Map.copyOf(methods),
            Map.copyOf(fields), Set.copyOf(ambiguousMethods), Map.copyOf(methodsByName));
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
        private final Map<MethodNameKey, Decl> methodsByName = new LinkedHashMap<>();

        FileIndexBuilder(Trees trees, SourcePositions positions, CompilationUnitTree cu) {
            this.trees = trees;
            this.positions = positions;
            this.cu = cu;
            this.packageName = cu.getPackageName() == null ? "" : cu.getPackageName().toString();
        }

        FileIndex build() {
            return new FileIndex(
                Map.copyOf(classes), Map.copyOf(methods),
                Map.copyOf(fields), Set.copyOf(ambiguousMethods), Map.copyOf(methodsByName));
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
                String name = node.getName().toString();
                var decl = declOf(node);
                var key = new MethodKey(fqn, name, node.getParameters().size());
                if (methods.putIfAbsent(key, decl) != null) {
                    ambiguousMethods.add(key);
                }
                // Name-level view: first declaration in source order wins, never
                // dropped, so a same-arity overload collision still has a floor.
                methodsByName.putIfAbsent(new MethodNameKey(fqn, name), decl);
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
