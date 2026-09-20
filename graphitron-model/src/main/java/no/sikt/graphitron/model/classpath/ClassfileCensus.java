package no.sikt.graphitron.model.classpath;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodSignature;
import java.lang.classfile.Signature;
import java.lang.classfile.attribute.MethodParametersAttribute;
import java.lang.classfile.attribute.SignatureAttribute;
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

    /**
     * One public method, with its type information in all three forms the classfile carries.
     *
     * <p>{@code returnType} is the descriptor's erasure, fully qualified. {@code declaredReturnType}
     * is what the source wrote, type arguments kept and the package dropped, which is a rendering
     * for a reader rather than a name to compare. Neither subsumes the other: erasure maps a type
     * variable to its bound, which the declaration does not name, and the declaration names a
     * container's element type, which the erasure does not. {@code returnTypeRefs} is what resolves
     * either question, being qualified names at stated positions.
     */
    public record MethodAt(String name, String descriptor, String returnType,
                           String declaredReturnType, boolean isStatic, List<ParameterAt> parameters,
                           List<TypeRefAt> returnTypeRefs, List<String> declaredExceptions) {}

    /** One parameter, on {@link MethodAt}'s terms; {@code name} is null without {@code -parameters}. */
    public record ParameterAt(int position, String name, String type, String declaredType,
                              List<TypeRefAt> typeRefs) {}

    /** One record component, on {@link MethodAt}'s terms. */
    public record ComponentAt(String name, int position, String type, String declaredType,
                              List<TypeRefAt> typeRefs) {}

    public record FieldAt(String name, String type, boolean isStatic) {}

    /**
     * One class a declared type names, at one position within it.
     *
     * <p>{@code path} is read outside in: the empty string is the type itself, a digit a 0-based
     * type-argument index, {@code []} an array's component, joined by dots. So {@code List<Film>}
     * names its element at {@code 0} and {@code Map<String, List<Film>>} names {@code Film} at
     * {@code 1.0}. A position naming no class yields no row at all rather than one with a
     * placeholder, which covers a primitive, an array, a type variable and an unbounded wildcard.
     *
     * <p>{@code variance} is {@code NONE}, {@code EXTENDS} or {@code SUPER}. Carried because the
     * three declare different things and the class name cannot tell them apart, so a reader peeling
     * an element out of {@code ? super Film} would otherwise read {@code Film} and be wrong about
     * which way the values flow.
     */
    public record TypeRefAt(String path, String referencedClass, String variance) {}

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
     * @param skipPrefix the package to leave out, or null to leave nothing out. A package name and
     *                   not a character prefix; {@link #excludedPackage} is where the difference
     *                   between the two is spelled
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
            for (ClassAt at : readEntry(entry, source, skipPrefix)) {
                if (seen.add(at.className())) {
                    classes.add(at);
                }
            }
        }
        return new Census(List.copyOf(read), List.copyOf(classes));
    }

    /**
     * One entry's classes in walk order, <em>not</em> deduplicated against any other entry, and
     * with no entry row: what a caller holding one entry's result across rounds re-reads when that
     * entry's bytes move. Deduplicating here would make that impossible, a class dropped from the
     * first entry having to surface from the second, so an entry cannot be recomputed alone once it
     * has been folded against its neighbours.
     *
     * <p>A path that is neither a directory nor a readable jar yields nothing, which is the normal
     * state before a first compile rather than an error.
     *
     * @param source     the entry a class is recorded under, which is the entry rather than the
     *                   file, so a class reads the same whether the whole entry was walked or this
     *                   one file was re-read
     * @param skipPrefix the package to leave out, on {@link #read}'s terms
     */
    public static List<ClassAt> readEntry(Path entry, String source, String skipPrefix) {
        String excluded = excludedPackage(skipPrefix);
        if (Files.isDirectory(entry)) {
            return readDirectory(entry, source, excluded);
        }
        return Files.isRegularFile(entry) && entry.toString().endsWith(".jar")
            ? readJar(entry, source, excluded)
            : List.of();
    }

    /**
     * One classfile's class, or nothing where it is not one this reading carries. What a caller
     * re-reads for the files a compile rewrote, keeping the rest of the entry.
     */
    public static Optional<ClassAt> readFile(Path file, String source, String skipPrefix) {
        return read(bytes(file), file.getFileName().toString(), source, excludedPackage(skipPrefix));
    }

    /**
     * The caller's package as a test a class name can be put to: the name and the dot below it, or
     * null where there is nothing to exclude.
     *
     * <p>The dot is the whole of it, and leaving it off is not a near miss. A package name is
     * routinely a character prefix of a class name in the package above it, so a bare
     * {@code startsWith} drops classes no caller meant to name: excluding {@code graphql.Assert}
     * would take {@code graphql.AssertException} with it. The failure is silent in the direction
     * that hides, a class simply absent from an arm reading exactly like a classpath that does not
     * carry it.
     *
     * <p>An empty name excludes nothing rather than everything, which is the other end of the same
     * confusion: every class name starts with the empty string, so a caller declaring no package
     * would have had a reading admit nothing at all and report it as a classpath with no members.
     */
    private static String excludedPackage(String skipPrefix) {
        return skipPrefix == null || skipPrefix.isEmpty() ? null : skipPrefix + ".";
    }

    private static List<ClassAt> readDirectory(Path directory, String source, String excluded) {
        try (Stream<Path> files = Files.walk(directory)) {
            var classes = new ArrayList<ClassAt>();
            files.filter(Files::isRegularFile)
                .filter(file -> file.getFileName().toString().endsWith(".class"))
                .forEach(file -> read(bytes(file), file.getFileName().toString(), source, excluded)
                    .ifPresent(classes::add));
            return classes;
        } catch (IOException e) {
            return List.of();
        }
    }

    private static List<ClassAt> readJar(Path jar, String source, String excluded) {
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
                read(bytes, fileName, source, excluded).ifPresent(classes::add);
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
                                                    String excluded) {
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
        if (excluded != null && className.startsWith(excluded)) {
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
     *
     * <p>The clause is decided by the declaring class's own form rather than by the slot the name
     * sits in. The JVM keeps an interface's super-interfaces in the same array as a class's
     * implements list, while the source writes them after {@code extends}, so reading the slot
     * alone misreports every interface.
     */
    private static List<SupertypeAt> supertypesOf(ClassModel classfile) {
        String clause = classfile.flags().has(AccessFlag.INTERFACE) ? "EXTENDS" : "IMPLEMENTS";
        var supertypes = new ArrayList<SupertypeAt>();
        classfile.superclass()
            .map(entry -> entry.asInternalName().replace('/', '.'))
            .filter(name -> !"java.lang.Object".equals(name))
            .ifPresent(name -> supertypes.add(new SupertypeAt(name, "EXTENDS")));
        classfile.interfaces().forEach(entry ->
            supertypes.add(new SupertypeAt(entry.asInternalName().replace('/', '.'), clause)));
        return supertypes;
    }

    /**
     * The public methods, each with its erasure and, where the compiler emitted a
     * {@code Signature}, the declaration the erasure lost.
     *
     * <p>The signature's argument list is used only when it is the same length as the descriptor's.
     * A compiler-synthesised parameter appears in one list and not the other and there is no
     * position-wise correction for that, so a length mismatch falls back wholesale rather than
     * pairing a declared form with the wrong parameter.
     */
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
            var signature = method.findAttribute(Attributes.signature())
                .map(SignatureAttribute::asMethodSignature);
            var declaredParameters = signature
                .map(MethodSignature::arguments)
                .filter(arguments -> arguments.size() == type.parameterCount())
                .orElseGet(List::of);
            var names = parameterNames(method);
            var parameters = new ArrayList<ParameterAt>();
            for (int i = 0; i < type.parameterCount(); i++) {
                ClassDesc erased = type.parameterType(i);
                Optional<Signature> declared = i < declaredParameters.size()
                    ? Optional.of(declaredParameters.get(i))
                    : Optional.empty();
                parameters.add(new ParameterAt(i, i < names.size() ? names.get(i) : null,
                    binaryName(erased),
                    declared.map(ClassfileCensus::declaredName).orElseGet(() -> displayName(erased)),
                    typeRefs(declared, erased)));
            }
            var result = signature.map(MethodSignature::result);
            methods.add(new MethodAt(name, type.descriptorString(),
                binaryName(type.returnType()),
                result.map(ClassfileCensus::declaredName)
                    .orElseGet(() -> displayName(type.returnType())),
                flags.has(AccessFlag.STATIC), parameters,
                typeRefs(result, type.returnType()), declaredExceptions(method)));
        }
        return methods;
    }

    /**
     * The classes the {@code throws} clause names, in the order the attribute lists them. Read from
     * the {@code Exceptions} attribute, which the compiler emits for checked exceptions only: an
     * unchecked one is thrown past the signature and is why the throwable arm reads the classpath
     * rather than any signature.
     */
    private static List<String> declaredExceptions(java.lang.classfile.MethodModel method) {
        return method.findAttribute(Attributes.exceptions())
            .map(attribute -> attribute.exceptions().stream()
                .map(entry -> entry.asInternalName().replace('/', '.'))
                .toList())
            .orElseGet(List::of);
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

    /**
     * The record's components in header order. A component carries its own {@code Signature}, so
     * the declared form is read per component rather than off the accessor the record generates:
     * the component is where the declaration is written.
     */
    private static List<ComponentAt> componentsOf(ClassModel classfile) {
        return classfile.findAttribute(Attributes.record())
            .map(record -> {
                var components = new ArrayList<ComponentAt>();
                var declared = record.components();
                for (int i = 0; i < declared.size(); i++) {
                    var component = declared.get(i);
                    ClassDesc erased =
                        ClassDesc.ofDescriptor(component.descriptor().stringValue());
                    Optional<Signature> signature = component
                        .findAttribute(Attributes.signature())
                        .map(SignatureAttribute::asTypeSignature);
                    components.add(new ComponentAt(component.name().stringValue(), i,
                        binaryName(erased),
                        signature.map(ClassfileCensus::declaredName)
                            .orElseGet(() -> displayName(erased)),
                        typeRefs(signature, erased)));
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

    /**
     * A type's name without its package, which is the form a signature is rendered in and the form
     * a surface displays. A nested class keeps the {@code $} the JVM writes it with.
     */
    private static String displayName(ClassDesc type) {
        return type.displayName();
    }

    /**
     * The qualified classes a declared type names, one per position within it.
     *
     * <p>Two entry points because the classfile has two encodings of one thing: a {@code Signature}
     * where the compiler emitted one, and the descriptor where it did not, which for a non-generic
     * type is always. They are not alternatives of differing quality. Absence of the attribute
     * means the erasure <em>is</em> the declared form, so both readings are the declaration and
     * they agree wherever both exist.
     */
    private static List<TypeRefAt> typeRefs(Optional<Signature> signature, ClassDesc erased) {
        var refs = new ArrayList<TypeRefAt>();
        signature.ifPresentOrElse(
            declared -> collectRefs(declared, "", "NONE", refs),
            () -> collectRefs(erased, "", refs));
        return List.copyOf(refs);
    }

    /** Walks a signature, emitting a reference for every position that names a class. */
    private static void collectRefs(Signature signature, String path, String variance,
                                    List<TypeRefAt> into) {
        switch (signature) {
            // A primitive and a type variable name no class. The variable is the case worth noting:
            // its erasure is its bound, Object absent a declared one, so the erased column reads a
            // class here where the declaration named none, and this walk follows the declaration.
            case Signature.BaseTypeSig ignored -> { }
            case Signature.TypeVarSig ignored -> { }
            case Signature.ArrayTypeSig array ->
                collectRefs(array.componentSignature(), step(path, "[]"), variance, into);
            case Signature.ClassTypeSig cls -> {
                into.add(new TypeRefAt(path, binaryName(cls.classDesc()), variance));
                int index = 0;
                for (var argument : cls.typeArgs()) {
                    String argumentPath = step(path, String.valueOf(index++));
                    switch (argument) {
                        // A bare `?` bounds at Object, which nothing here records as a declaration,
                        // so the position stays empty rather than naming it.
                        case Signature.TypeArg.Unbounded ignored -> { }
                        case Signature.TypeArg.Bounded bounded -> collectRefs(bounded.boundType(),
                            argumentPath, bounded.wildcardIndicator().name(), into);
                    }
                }
            }
        }
    }

    /**
     * Walks a descriptor, for a type the compiler stored no signature for. A descriptor carries no
     * type arguments and no wildcards, so the only structure to descend is array nesting and every
     * position it does name is invariant.
     */
    private static void collectRefs(ClassDesc type, String path, List<TypeRefAt> into) {
        if (type.isPrimitive()) {
            return;
        }
        if (type.isArray()) {
            collectRefs(type.componentType(), step(path, "[]"), into);
            return;
        }
        into.add(new TypeRefAt(path, binaryName(type), "NONE"));
    }

    /** One step deeper into a type; the root path is empty, so the first step carries no dot. */
    private static String step(String path, String next) {
        return path.isEmpty() ? next : path + "." + next;
    }

    /**
     * The declared form of one type as a signature spells it: package-less like
     * {@link #displayName}, with type arguments kept. {@code List<Film>} rather than the
     * {@code List} its descriptor erases to, which is the whole reason the signature is read.
     *
     * <p>A wildcard renders as the author wrote it and a type variable as the variable's own
     * identifier, which is the one place this form carries strictly less than the erasure:
     * {@code T} does not say what {@code T} erases to. Neither form subsumes the other, which is
     * why both are kept.
     */
    private static String declaredName(Signature signature) {
        return switch (signature) {
            case Signature.BaseTypeSig base ->
                displayName(ClassDesc.ofDescriptor(String.valueOf(base.baseType())));
            case Signature.ArrayTypeSig array -> declaredName(array.componentSignature()) + "[]";
            case Signature.TypeVarSig variable -> variable.identifier();
            case Signature.ClassTypeSig cls -> {
                if (cls.typeArgs().isEmpty()) {
                    yield displayName(cls.classDesc());
                }
                var arguments = new ArrayList<String>(cls.typeArgs().size());
                for (var argument : cls.typeArgs()) {
                    arguments.add(declaredArgument(argument));
                }
                yield displayName(cls.classDesc()) + "<" + String.join(", ", arguments) + ">";
            }
        };
    }

    /** One type argument in the form the author wrote it, wildcard bound included. */
    private static String declaredArgument(Signature.TypeArg argument) {
        return switch (argument) {
            case Signature.TypeArg.Unbounded ignored -> "?";
            case Signature.TypeArg.Bounded bounded -> switch (bounded.wildcardIndicator()) {
                case NONE -> declaredName(bounded.boundType());
                case EXTENDS -> "? extends " + declaredName(bounded.boundType());
                case SUPER -> "? super " + declaredName(bounded.boundType());
            };
        };
    }

    private static byte[] bytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read " + file, e);
        }
    }
}
