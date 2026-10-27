package no.sikt.graphitron.rewrite.compile;

import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import no.sikt.graphitron.plan.GeneratedUnits;

import javax.lang.model.element.Element;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.UnionType;
import javax.lang.model.type.WildcardType;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * The compile-graph oracle's reference account, read from javac rather than reconstructed from the
 * emitted {@code TypeSpec}s: compiles the generated sources once and, from each analysed tree,
 * collects which other generated units the unit references.
 *
 * <p>For every tree node it takes the element javac resolved and the type javac gave the node,
 * decomposed through type arguments, array components, wildcard and type-variable bounds,
 * intersections and unions, and maps each to its outermost enclosing class. Types matter beside
 * elements because some dependencies have no identifier: in {@code foo(Gen.make())}, the overload
 * javac picks depends on the type {@code make()} returns, which no name in the source spells.
 *
 * <p>Reading analysed trees rather than class files is what makes the account complete:
 * same-package simple names, code a generator baked as a string, and compile-time constants are
 * all resolved there, while javac inlines a constant's value at its use site, so a class-file scan
 * would miss that reference.
 */
final class JavacReferenceIndex {

    private JavacReferenceIndex() {
    }

    /**
     * Resolves each unit's {@code .java} file under {@code sourceRoot} (the run's generated-sources
     * root) and indexes them; fails if a unit has no file there.
     */
    static Map<String, Set<String>> ofGeneratedSources(Path sourceRoot, Set<String> units, List<Path> classpath) {
        var files = new ArrayList<Path>();
        for (String fqcn : units) {
            Path file = sourceRoot.resolve(GeneratedUnits.toRelativeJavaPath(fqcn));
            if (!Files.isRegularFile(file)) {
                throw new IllegalStateException("no source file for generated unit " + fqcn + " at " + file);
            }
            files.add(file);
        }
        return of(files, units, classpath);
    }

    /**
     * Compiles {@code sources} against {@code classpath} and returns, for each of {@code units},
     * the other members of {@code units} it references. A compile that fails throws with javac's
     * diagnostics.
     */
    static Map<String, Set<String>> of(Collection<Path> sources, Set<String> units, List<Path> classpath) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("no system Java compiler available; the reference index needs a JDK");
        }
        var references = new TreeMap<String, Set<String>>();
        units.forEach(unit -> references.put(unit, new TreeSet<>()));
        Path classOutput = null;
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
            classOutput = Files.createTempDirectory("javac-reference-index");
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(classOutput.toFile()));
            fileManager.setLocation(StandardLocation.CLASS_PATH, classpath.stream().map(Path::toFile).toList());
            var diagnostics = new DiagnosticCollector<JavaFileObject>();
            var task = (JavacTask) compiler.getTask(null, fileManager, diagnostics, List.of("-proc:none"), null,
                fileManager.getJavaFileObjectsFromPaths(sources));
            Trees trees = Trees.instance(task);
            task.addTaskListener(new TaskListener() {
                @Override
                public void finished(TaskEvent event) {
                    if (event.getKind() != TaskEvent.Kind.ANALYZE) {
                        return;
                    }
                    String unit = topLevelName(event.getTypeElement());
                    Set<String> found = unit == null ? null : references.get(unit);
                    if (found == null) {
                        return;
                    }
                    new Collector(trees, unit, units, found)
                        .scan(new TreePath(event.getCompilationUnit()), null);
                }
            });
            if (!task.call()) {
                throw new IllegalStateException("generated sources do not compile:\n" + format(diagnostics));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            deleteRecursively(classOutput);
        }
        return references;
    }

    /** Collects the generated units one compilation unit's tree references, besides its own. */
    private static final class Collector extends TreePathScanner<Void, Void> {
        private final Trees trees;
        private final String self;
        private final Set<String> generated;
        private final Set<String> found;
        private final Set<Element> visitedTypeVariables = new HashSet<>();

        Collector(Trees trees, String self, Set<String> generated, Set<String> found) {
            this.trees = trees;
            this.self = self;
            this.generated = generated;
            this.found = found;
        }

        @Override
        public Void scan(Tree tree, Void unused) {
            if (tree != null) {
                var path = new TreePath(getCurrentPath(), tree);
                addElement(trees.getElement(path));
                addType(trees.getTypeMirror(path));
            }
            return super.scan(tree, unused);
        }

        private void addElement(Element element) {
            String unit = topLevelName(element);
            if (unit != null && !unit.equals(self) && generated.contains(unit)) {
                found.add(unit);
            }
        }

        private void addType(TypeMirror type) {
            if (type == null) {
                return;
            }
            switch (type) {
                case DeclaredType declared -> {
                    addElement(declared.asElement());
                    addType(declared.getEnclosingType());
                    declared.getTypeArguments().forEach(this::addType);
                }
                case ArrayType array -> addType(array.getComponentType());
                case WildcardType wildcard -> {
                    addType(wildcard.getExtendsBound());
                    addType(wildcard.getSuperBound());
                }
                case TypeVariable variable -> {
                    if (visitedTypeVariables.add(variable.asElement())) {
                        addType(variable.getUpperBound());
                        addType(variable.getLowerBound());
                    }
                }
                case IntersectionType intersection -> intersection.getBounds().forEach(this::addType);
                case UnionType union -> union.getAlternatives().forEach(this::addType);
                case ExecutableType executable -> {
                    addType(executable.getReturnType());
                    executable.getParameterTypes().forEach(this::addType);
                    executable.getThrownTypes().forEach(this::addType);
                    executable.getTypeVariables().forEach(this::addType);
                }
                default -> { }
            }
        }
    }

    /** The qualified name of the top-level class enclosing {@code element}, or null if there is none. */
    private static String topLevelName(Element element) {
        Element current = element;
        while (current != null) {
            Element enclosing = current.getEnclosingElement();
            if (current instanceof TypeElement type && (enclosing == null || enclosing instanceof PackageElement)) {
                return type.getQualifiedName().toString();
            }
            current = enclosing;
        }
        return null;
    }

    private static String format(DiagnosticCollector<JavaFileObject> diagnostics) {
        var out = new StringBuilder();
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            out.append(diagnostic).append('\n');
        }
        return out.toString();
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                Files.delete(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
