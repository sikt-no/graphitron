package no.sikt.graphitron.model.classpath;

import no.sikt.graphitron.model.config.ClasspathEntry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * The consumer's declared compile classpath as the completion vocabulary spells it: the public
 * top-level classes and their public methods, so the LSP can offer them as completion / hover /
 * diagnostic targets for {@code @service} / {@code @condition} / {@code @record} /
 * {@code @scalarType}, and so the fact store's class census is the set a schema is permitted to
 * name.
 *
 * <p>It reads no bytes. {@link ClassfileCensus} is the reader, and this is a projection of what it
 * found into the shape these surfaces and the {@code jvm_} relations were written against. The two
 * were separate readings of the same classfiles for a while, with their own scope rules and their
 * own spelling of the signature walk, which is a duplication that can only be paid for by staying
 * identical. Now there is one reading. This layer is scaffolding with a stated end: it keeps the
 * {@code jvm_} census answering while the {@code code_} family grows into what reads it, and it
 * goes when that census does.
 *
 * <p>Directories and jars alike, but declared jars only. The scan used to skip anything that was
 * not a directory, on the premise that consumer vocabulary lives in reactor source rather than in
 * third-party libraries. {@code @scalarType(scalar: "graphql.scalars.ExtendedScalars.Date")}
 * falsified that outright: it generated fine, because codegen resolves the constant reflectively,
 * and red-squiggled in the editor, because this scan never opened the jar. Jars are therefore in.
 * The transitive tail is out again, by classification rather than by category: a
 * {@link ClasspathEntry.Origin#TRANSITIVE} entry is skipped before it is opened, because nothing
 * an author writes may name a class from a dependency the module did not declare. The codegen
 * loader still resolves such a class; {@code ClasspathNameability} is what makes naming one a
 * build failure instead of a silent divergence between the census and the loader.
 *
 * <p>The class filter is generous on purpose: enums and interfaces stay in,
 * because consumers do reference them as {@code @record} class names and as
 * service-method-bearing interfaces. Picking the wrong one is a one-keystroke
 * fix; missing a valid one in the list is a worse failure. Classes under the
 * jOOQ-generated package are excluded: they are referenced through
 * {@code @table} / {@code @reference} (catalog concepts, not classpath ones),
 * or reflection-only through the {@code <sessionState>} {@code <mount>} /
 * {@code <unmount>} method references (resolved at build time with no census
 * row needed), never through {@code @service}. Admitting the routine surface
 * would grow the {@code jvm_} fact relations for no present consumer.
 *
 * <p>Method and record-component type information arrives in both forms: the erasure the JVM
 * descriptor carries, and the declared form the {@code Signature} attribute carries where the
 * compiler emitted one. A surface spelling a signature for an author wants the declared form, a
 * check on a type's identity wants the erasure, and a walk following an accessor into a container's
 * element type can only use the declared one. Parameter names follow the
 * {@link CompletionData.Parameter#name()} null-when-unavailable contract, which the census carries
 * unchanged: a null name, never a synthesised {@code arg0}, is the signal that the class was
 * compiled without {@code -parameters}.
 */
public final class ClasspathScanner {

    private ClasspathScanner() {}

    /**
     * Walks one classpath entry, classified {@link ClasspathEntry.Origin#PROJECT}. Convenience
     * overload kept for tests and single-entry callers; production reads from
     * {@link #scan(List, String)}.
     */
    public static List<CompletionData.ExternalReference> scan(Path entry, String jooqPackage) {
        return scan(List.of(ClasspathEntry.project(entry)), jooqPackage);
    }

    /**
     * Walks every non-{@code TRANSITIVE} entry in {@code classpathEntries} and returns class
     * records in deterministic order. A directory is walked; a {@code .jar} is opened and its
     * entries fed through the same filter, which is already byte-oriented. A
     * {@link ClasspathEntry.Origin#TRANSITIVE} entry is skipped before it is opened: decompression
     * dominates the scan's cost, so the only cut that moves the number is opening fewer jars, and
     * the census's claim is what an author may name, which a transitive jar's classes are not.
     *
     * <p>Each entry is treated independently and FQNs are deduplicated across them, so a class
     * present under more than one entry surfaces once, at the entry that comes first in classpath
     * order. That is where a classloader would resolve it, so the census and the codegen loader
     * agree on which copy is the one.
     *
     * <p>Entries that do not exist on disk are skipped silently; the normal pre-{@code mvn compile}
     * state has zero existing entries and returns an empty list.
     *
     * <p>Each reference carries the entry it was read from, which is what makes the census
     * partitionable: a refresh re-reads one entry rather than discarding the whole scan, and the
     * most expensive thing in the store stops being thrown away by any edit that invalidates
     * anything.
     */
    public static List<CompletionData.ExternalReference> scan(List<ClasspathEntry> classpathEntries, String jooqPackage) {
        var perEntry = new ArrayList<List<CompletionData.ExternalReference>>();
        for (ClasspathEntry classified : classpathEntries) {
            if (classified.origin() == ClasspathEntry.Origin.TRANSITIVE) {
                continue;
            }
            perEntry.add(scanEntry(classified.path(), jooqPackage));
        }
        return compose(perEntry);
    }

    /**
     * One entry's references in walk order, <em>not</em> deduplicated. The caller composes; see
     * {@link #compose}. A path that is neither a directory nor a readable jar yields nothing,
     * which is the normal pre-{@code mvn compile} state rather than an error.
     *
     * <p>Exposed for {@link ClasspathCensus}, which holds one entry's result across rounds and
     * re-reads only the entries whose bytes moved. Deduplicating here would make that impossible:
     * a class dropped from the first entry has to surface from the second, so an entry cannot be
     * recomputed alone once it has been folded against its neighbours.
     */
    public static List<CompletionData.ExternalReference> scanEntry(Path entry, String jooqPackage) {
        String source = entry.toString();
        return ClassfileCensus.readEntry(entry, source, jooqPackage).stream()
            .map(ClasspathScanner::reference)
            .toList();
    }

    /**
     * One classfile's reference, or empty where the file is not a candidate: an excluded name, an
     * unparseable file, or a class the census does not carry. {@code source} is the classpath entry
     * the file was found under rather than the file itself, so a reference reads the same whether
     * the whole entry was scanned or this one file was re-read.
     *
     * <p>Exposed for {@link ClasspathCensus}, which re-reads the classfiles a compile rewrote and
     * keeps the rest.
     */
    public static Optional<CompletionData.ExternalReference> readClassFile(
            Path file, String jooqPackage, String source) {
        return ClassfileCensus.readFile(file, source, jooqPackage).map(ClasspathScanner::reference);
    }

    /**
     * Flattens per-entry references into the census, keeping the first occurrence of each FQN.
     * Entry order is classpath order, so the copy that survives is the one a classloader would
     * resolve, and the census and the codegen loader agree on which copy is the one.
     */
    public static List<CompletionData.ExternalReference> compose(
            List<List<CompletionData.ExternalReference>> perEntry) {
        var seen = new LinkedHashSet<String>();
        var refs = new ArrayList<CompletionData.ExternalReference>();
        for (List<CompletionData.ExternalReference> entry : perEntry) {
            for (CompletionData.ExternalReference ref : entry) {
                if (seen.add(ref.className())) {
                    refs.add(ref);
                }
            }
        }
        return List.copyOf(refs);
    }

    private static String jooqPrefix(String jooqPackage) {
        return jooqPackage.isEmpty() ? null : jooqPackage + ".";
    }

    /** A classpath entry that is a jar file present on disk. */
    public static boolean isJar(Path entry) {
        return entry.getFileName() != null
            && entry.getFileName().toString().endsWith(".jar")
            && Files.isRegularFile(entry);
    }
    /**
     * One census class as the completion vocabulary spells it.
     *
     * <p>The whole of what this layer is: the bytes are read once, by
     * {@link ClassfileCensus}, and rendered here into the shape the editor's surfaces and the
     * {@code jvm_} relations were written against. Two renderings differ and neither is a loss.
     * The census names erased types fully qualified, because the arms over it compare them against
     * {@code org.jooq.Condition} and a package-less name cannot be compared for identity; this
     * vocabulary drops the package, because what reads it renders a signature for a person. The
     * qualified name determines the short one, so the derivation goes this way and not the other,
     * and the qualified names a caller does need are in the type references, which pass through
     * unchanged.
     *
     * <p>Scaffolding with a stated end. It exists to keep the {@code jvm_} census answering while
     * the {@code code_} family grows into what reads it, and it goes when that census does.
     */
    private static CompletionData.ExternalReference reference(ClassfileCensus.ClassAt at) {
        var methods = at.methods().stream().map(ClasspathScanner::method).toList();
        var components = at.components().stream().map(ClasspathScanner::component).toList();
        var supertypes = at.supertypes().stream()
            .map(supertype -> new CompletionData.Supertype(supertype.name(), supertype.declaredVia()))
            .toList();
        return new CompletionData.ExternalReference(at.className(), at.className(), "",
            methods, components, at.kind(), at.source(), supertypes);
    }

    private static CompletionData.Method method(ClassfileCensus.MethodAt at) {
        return new CompletionData.Method(at.name(), displayName(at.returnType()), "",
            at.parameters().stream().map(ClasspathScanner::parameter).toList(),
            at.descriptor(), at.declaredReturnType(), typeRefs(at.returnTypeRefs()));
    }

    private static CompletionData.Parameter parameter(ClassfileCensus.ParameterAt at) {
        return new CompletionData.Parameter(at.name(), displayName(at.type()), null, "",
            at.declaredType(), typeRefs(at.typeRefs()));
    }

    private static CompletionData.RecordComponent component(ClassfileCensus.ComponentAt at) {
        return new CompletionData.RecordComponent(at.name(), displayName(at.type()),
            at.declaredType(), typeRefs(at.typeRefs()));
    }

    private static List<CompletionData.TypeRef> typeRefs(List<ClassfileCensus.TypeRefAt> refs) {
        return refs.stream()
            .map(ref -> new CompletionData.TypeRef(ref.path(), ref.referencedClass(), ref.variance()))
            .toList();
    }

    /**
     * A qualified erasure without its package, which is what every display form here is.
     *
     * <p>A string cut rather than a second reading, and the two agree by construction: the census
     * builds the qualified name as the package, a dot, and the name the JDK renders, so removing
     * everything through the last dot leaves exactly that name. It holds for the cases that look
     * like exceptions. A nested class keeps the {@code $} the JVM writes, which carries no dot. An
     * array and a primitive are already package-less when the census names them, and neither
     * rendering contains a dot to cut at.
     */
    private static String displayName(String qualified) {
        int lastDot = qualified.lastIndexOf('.');
        return lastDot < 0 ? qualified : qualified.substring(lastDot + 1);
    }
}
