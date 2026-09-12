package no.sikt.graphitron.model.capture.code;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.attribute.MethodParametersAttribute;
import java.lang.constant.ClassDesc;
import java.lang.reflect.AccessFlag;
import java.nio.file.Files;
import java.nio.file.Path;
import no.sikt.graphitron.model.config.ClasspathEntry;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads classfiles and says what they declare.
 *
 * <p>Nothing here knows what a schema is. A class is admitted on the classfile's own terms and
 * every public member it declares is reported; which of them any consumer may name is a question
 * asked of these rows later, never a filter applied while the bytes are read.
 *
 * <p>The one thing a caller may exclude is a package, for the generated code a consumer never names
 * directly. That is the caller's policy, passed in, rather than a rule this reader holds.
 *
 * <p>An entry that cannot be read declares nothing, rather than failing the census: a classpath is
 * a set and the other entries' facts are still worth having. Inside a readable entry the reading is
 * strict, an unreadable classfile being a surprise rather than a state of the world.
 */
public final class ClassfileCensus {

    private ClassfileCensus() {}

    /** What one reading found: the entries it could read, and the classes they declare. */
    public record Census(List<EntryAt> entries, List<ClassAt> classes) {}

    /** A classpath entry this reading read, and which kind of container it is. */
    public record EntryAt(String source, String kind, String origin, String coordinate) {}

    /** One class, and everything public it declares. */
    public record ClassAt(String source, String className, String kind, List<SupertypeAt> supertypes,
                          List<MethodAt> methods, List<ComponentAt> components,
                          List<FieldAt> fields) {}

    /** A name written above a class, and the clause it was written in. */
    public record SupertypeAt(String name, String declaredVia) {}

    public record MethodAt(String name, String descriptor, String returnType, boolean isStatic,
                           List<ParameterAt> parameters) {}

    public record ParameterAt(int position, String name, String type) {}

    public record ComponentAt(String name, int position, String type) {}

    public record FieldAt(String name, String type, boolean isStatic) {}

    /**
     * Reads the given entries, in classpath order, skipping anything that is neither a directory
     * nor a jar.
     *
     * <p>A class name is kept once: the first entry to declare it wins, which is the copy a
     * classloader would resolve, so this census and the loader agree on which copy is the one.
     *
     * <p>Each entry keeps the classification its producer gave it. How an entry reached the
     * classpath cannot be recovered from its path, so a reader that wants the reactor rather than
     * the world would have nothing to scope by if this dropped it.
     *
     * @param entries    directories and jars, in the order a classloader would search them
     * @param skipPrefix a package prefix to leave out, or null to leave nothing out
     */
    public static Census read(List<ClasspathEntry> entries, String skipPrefix) {
        var read = new ArrayList<EntryAt>();
        var classes = new ArrayList<ClassAt>();
        var seen = new LinkedHashSet<String>();
        for (ClasspathEntry classified : entries) {
            Path entry = classified.path();
            String source = entry.toString();
            boolean directory = Files.isDirectory(entry);
            if (!directory && !(Files.isRegularFile(entry) && source.endsWith(".jar"))) {
                continue;
            }
            read.add(new EntryAt(source, directory ? "DIRECTORY" : "JAR",
                classified.origin().name(), classified.coordinate()));
            for (ClassAt at : directory ? readDirectory(entry, source, skipPrefix)
                                        : readJar(entry, source, skipPrefix)) {
                if (seen.add(at.className())) {
                    classes.add(at);
                }
            }
        }
        return new Census(List.copyOf(read), List.copyOf(classes));
    }

    private static List<ClassAt> readDirectory(Path directory, String source, String skipPrefix) {
        try (Stream<Path> files = Files.walk(directory)) {
            var classes = new ArrayList<ClassAt>();
            files.filter(Files::isRegularFile)
                .filter(file -> file.getFileName().toString().endsWith(".class"))
                .forEach(file -> read(bytes(file), file.getFileName().toString(), source, skipPrefix)
                    .ifPresent(classes::add));
            return classes;
        } catch (IOException e) {
            return List.of();
        }
    }

    private static List<ClassAt> readJar(Path jar, String source, String skipPrefix) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var classes = new ArrayList<ClassAt>();
            for (ZipEntry zipped : zip.stream().toList()) {
                if (zipped.isDirectory() || !zipped.getName().endsWith(".class")) {
                    continue;
                }
                byte[] bytes;
                try (var in = zip.getInputStream(zipped)) {
                    bytes = in.readAllBytes();
                }
                String fileName = zipped.getName()
                    .substring(zipped.getName().lastIndexOf('/') + 1);
                read(bytes, fileName, source, skipPrefix).ifPresent(classes::add);
            }
            return classes;
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * One classfile, or nothing where it is not a class this census carries: a name the reader
     * skips, a file that will not parse, a non-public class, or one under the excluded package.
     */
    private static Optional<ClassAt> read(byte[] bytes, String fileName, String source,
                                                    String skipPrefix) {
        if (skipOnName(fileName)) {
            return Optional.empty();
        }
        ClassModel classfile;
        try {
            classfile = ClassFile.of().parse(bytes);
        } catch (IllegalArgumentException e) {
            // A stray file or a malformed class. Broken classes surface where they are compiled.
            return Optional.empty();
        }
        var flags = classfile.flags();
        if (!flags.has(AccessFlag.PUBLIC) || flags.has(AccessFlag.SYNTHETIC)) {
            return Optional.empty();
        }
        String className = classfile.thisClass().asInternalName().replace('/', '.');
        if (skipPrefix != null && className.startsWith(skipPrefix)) {
            return Optional.empty();
        }
        return Optional.of(new ClassAt(source, className, kindOf(classfile),
            supertypesOf(classfile), methodsOf(classfile), componentsOf(classfile),
            fieldsOf(classfile)));
    }

    /**
     * Names the reader skips. A nested class is left out: its members are reachable through the
     * class that declares it, and admitting the mangled binary name would put a spelling in the
     * census that no source file writes.
     */
    private static boolean skipOnName(String fileName) {
        if ("module-info.class".equals(fileName) || "package-info.class".equals(fileName)) {
            return true;
        }
        return fileName.substring(0, fileName.length() - ".class".length()).indexOf('$') >= 0;
    }

    private static String kindOf(ClassModel classfile) {
        var flags = classfile.flags();
        if (flags.has(AccessFlag.ANNOTATION)) {
            return "ANNOTATION";
        }
        if (flags.has(AccessFlag.INTERFACE)) {
            return "INTERFACE";
        }
        if (flags.has(AccessFlag.ENUM)) {
            return "ENUM";
        }
        return classfile.findAttribute(Attributes.record()).isPresent() ? "RECORD" : "CLASS";
    }

    /**
     * The names written above the class: its superclass, then its interfaces in declaration order.
     * {@code java.lang.Object} is left out, the JVM writing it as the superclass of everything with
     * no extends clause, so what remains is the clause an author typed.
     */
    private static List<SupertypeAt> supertypesOf(ClassModel classfile) {
        var supertypes = new ArrayList<SupertypeAt>();
        classfile.superclass()
            .map(entry -> entry.asInternalName().replace('/', '.'))
            .filter(name -> !"java.lang.Object".equals(name))
            .ifPresent(name -> supertypes.add(new SupertypeAt(name, "EXTENDS")));
        classfile.interfaces().forEach(entry ->
            supertypes.add(new SupertypeAt(entry.asInternalName().replace('/', '.'), "IMPLEMENTS")));
        return supertypes;
    }

    private static List<MethodAt> methodsOf(ClassModel classfile) {
        var methods = new ArrayList<MethodAt>();
        for (var method : classfile.methods()) {
            var flags = method.flags();
            String name = method.methodName().stringValue();
            if (!flags.has(AccessFlag.PUBLIC) || flags.has(AccessFlag.SYNTHETIC)
                || name.startsWith("<")) {
                continue;
            }
            var type = method.methodTypeSymbol();
            var names = parameterNames(method);
            var parameters = new ArrayList<ParameterAt>();
            for (int i = 0; i < type.parameterCount(); i++) {
                parameters.add(new ParameterAt(i, i < names.size() ? names.get(i) : null,
                    binaryName(type.parameterType(i))));
            }
            methods.add(new MethodAt(name, type.descriptorString(),
                binaryName(type.returnType()), flags.has(AccessFlag.STATIC), parameters));
        }
        return methods;
    }

    /**
     * The parameter names the MethodParameters attribute carries, or none. Absence is a compiler
     * flag rather than a fact about the method, which is why the column it fills is nullable.
     */
    private static List<String> parameterNames(java.lang.classfile.MethodModel method) {
        return method.findAttribute(Attributes.methodParameters())
            .map(MethodParametersAttribute::parameters)
            .map(parameters -> parameters.stream()
                .map(parameter -> parameter.name().map(name -> name.stringValue()).orElse(null))
                .toList())
            .orElseGet(List::of);
    }

    private static List<ComponentAt> componentsOf(ClassModel classfile) {
        return classfile.findAttribute(Attributes.record())
            .map(record -> {
                var components = new ArrayList<ComponentAt>();
                var declared = record.components();
                for (int i = 0; i < declared.size(); i++) {
                    components.add(new ComponentAt(declared.get(i).name().stringValue(), i,
                        binaryName(ClassDesc.ofDescriptor(
                            declared.get(i).descriptor().stringValue()))));
                }
                return components;
            })
            .map(List::copyOf)
            .orElseGet(List::of);
    }

    private static List<FieldAt> fieldsOf(ClassModel classfile) {
        var fields = new ArrayList<FieldAt>();
        for (var field : classfile.fields()) {
            var flags = field.flags();
            if (!flags.has(AccessFlag.PUBLIC) || flags.has(AccessFlag.SYNTHETIC)) {
                continue;
            }
            fields.add(new FieldAt(field.fieldName().stringValue(),
                binaryName(field.fieldTypeSymbol()), flags.has(AccessFlag.STATIC)));
        }
        return fields;
    }

    /** A type's binary name: the package and the class, or the rendering for an array or primitive. */
    private static String binaryName(ClassDesc type) {
        if (!type.isClassOrInterface()) {
            return type.displayName();
        }
        String packageName = type.packageName();
        return packageName.isEmpty() ? type.displayName() : packageName + "." + type.displayName();
    }

    private static byte[] bytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read " + file, e);
        }
    }
}
