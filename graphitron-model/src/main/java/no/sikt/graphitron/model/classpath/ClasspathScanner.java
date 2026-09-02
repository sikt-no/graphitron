package no.sikt.graphitron.model.classpath;

import no.sikt.graphitron.model.config.ClasspathEntry;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodSignature;
import java.lang.classfile.Signature;
import java.lang.classfile.attribute.MethodParametersAttribute;
import java.lang.classfile.attribute.RecordAttribute;
import java.lang.classfile.attribute.SignatureAttribute;
import java.lang.constant.ClassDesc;
import java.lang.reflect.AccessFlag;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Walks the consumer's declared compile classpath and enumerates the public top-level classes plus
 * their public methods so the LSP can offer them as completion / hover / diagnostic targets for
 * {@code @service} / {@code @condition} / {@code @record} / {@code @scalarType}, and so the fact
 * store's class census is the set a schema is permitted to name. Reads {@code .class} bytes via
 * the stdlib {@link java.lang.classfile} API; no external dependency.
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
 * <p>Method and record-component type information is read in both forms: the erasure the JVM
 * descriptor carries, and the declared form the {@code Signature} attribute carries where the
 * compiler emitted one. A surface spelling a signature for an author wants the declared form,
 * a check on a type's identity wants the erasure, and a walk following an accessor into a
 * container's element type can only use the declared one. Parameter names follow the
 * {@link CompletionData.Parameter#name()} null-when-unavailable contract; see
 * {@link #readParameterNames}.
 *
 * <p>Each class also carries the supertypes it declares, which is what lets a consumer answer
 * assignability without a loader; see {@link #readSupertypes}.
 */
public final class ClasspathScanner {

    /** The implicit superclass the JVM writes for anything with no {@code extends} clause. */
    private static final String OBJECT = "java.lang.Object";

    /** JVM field descriptor of {@code org.jooq.Condition}; the exact return-type match for the condition fact. */
    private static final String JOOQ_CONDITION_DESCRIPTOR = "Lorg/jooq/Condition;";

    /** JVM field descriptor of {@code graphql.schema.GraphQLScalarType}; the exact field-type match for @scalarType completion.*/
    private static final String GRAPHQL_SCALAR_TYPE_DESCRIPTOR = "Lgraphql/schema/GraphQLScalarType;";

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
        var jooqPrefix = jooqPackage.isEmpty() ? null : jooqPackage + ".";
        var seen = new LinkedHashSet<String>();
        var refs = new ArrayList<CompletionData.ExternalReference>();
        for (ClasspathEntry classified : classpathEntries) {
            if (classified.origin() == ClasspathEntry.Origin.TRANSITIVE) {
                continue;
            }
            Path entry = classified.path();
            String source = entry.toString();
            if (Files.isDirectory(entry)) {
                scanDirectory(entry, jooqPrefix, source, seen, refs);
            } else if (isJar(entry)) {
                scanJar(entry, jooqPrefix, source, seen, refs);
            }
        }
        return List.copyOf(refs);
    }

    /** A classpath entry that is a jar file present on disk. */
    public static boolean isJar(Path entry) {
        return entry.getFileName() != null
            && entry.getFileName().toString().endsWith(".jar")
            && Files.isRegularFile(entry);
    }

    private static void scanDirectory(Path root, String jooqPrefix, String source,
                                      LinkedHashSet<String> seen,
                                      List<CompletionData.ExternalReference> refs) {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".class"))
                .forEach(p -> {
                    byte[] bytes;
                    try {
                        bytes = Files.readAllBytes(p);
                    } catch (IOException e) {
                        throw new UncheckedIOException("failed to read " + p, e);
                    }
                    collect(p.getFileName().toString(), bytes, jooqPrefix, source, seen, refs);
                });
        } catch (IOException e) {
            throw new UncheckedIOException("classpath scan failed at " + root, e);
        }
    }

    /**
     * A jar's class entries through the same filter. A jar that cannot be opened is skipped rather
     * than failing the catalog build: an unreadable dependency is the resolver's problem to report
     * at the coordinate that names a class in it, and a scan that dies takes every other entry's
     * classes with it.
     */
    private static void scanJar(Path jar, String jooqPrefix, String source,
                                LinkedHashSet<String> seen,
                                List<CompletionData.ExternalReference> refs) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.endsWith(".class")) {
                    continue;
                }
                String fileName = name.substring(name.lastIndexOf('/') + 1);
                if (skipOnName(fileName)) {
                    continue;
                }
                byte[] bytes;
                try (InputStream in = zip.getInputStream(entry)) {
                    bytes = in.readAllBytes();
                } catch (IOException e) {
                    continue;
                }
                collect(fileName, bytes, jooqPrefix, source, seen, refs);
            }
        } catch (IOException e) {
            // Not a readable jar. See the method comment.
        }
    }

    private static void collect(String fileName, byte[] bytes, String jooqPrefix, String source,
                                LinkedHashSet<String> seen,
                                List<CompletionData.ExternalReference> refs) {
        var ref = readIfCandidate(fileName, bytes, jooqPrefix, source);
        if (ref != null && seen.add(ref.className())) {
            refs.add(ref);
        }
    }

    /**
     * The filters a file name alone decides, so the common case skips a parse: the two
     * package-level pseudo-classes, and any {@code Outer$Inner.class} or synthetic {@code $1.class}.
     * The nested-class exclusion is disclosed on {@code jvm_class}, because a nested class named in
     * {@code @record} resolves through the codegen loader and reads as unknown here.
     */
    private static boolean skipOnName(String fileName) {
        if ("module-info.class".equals(fileName) || "package-info.class".equals(fileName)) {
            return true;
        }
        String simple = fileName.substring(0, fileName.length() - ".class".length());
        return simple.indexOf('$') >= 0;
    }

    private static CompletionData.ExternalReference readIfCandidate(String fileName, byte[] bytes,
                                                                    String jooqPrefix, String source) {
        if (skipOnName(fileName)) {
            return null;
        }
        ClassModel cm;
        try {
            cm = ClassFile.of().parse(bytes);
        } catch (IllegalArgumentException e) {
            // Stray non-class file or a malformed class. Skip rather than
            // fail the catalog build; broken classes surface elsewhere.
            return null;
        }
        var flags = cm.flags();
        if (!flags.has(AccessFlag.PUBLIC)) return null;
        if (flags.has(AccessFlag.SYNTHETIC)) return null;
        String fqn = cm.thisClass().asInternalName().replace('/', '.');
        if (jooqPrefix != null && fqn.startsWith(jooqPrefix)) return null;
        var methods = readMethods(cm);
        var recordComponents = readRecordComponents(cm);
        var scalarConstants = readScalarConstants(cm);
        return new CompletionData.ExternalReference(fqn, fqn, "", methods, recordComponents,
            scalarConstants, declaredKind(cm), source, readSupertypes(cm));
    }

    /**
     * The names the classfile declares above itself: its superclass, then its interfaces in
     * declaration order. The one thing a bytecode-only scan holds that answers assignability,
     * which is otherwise a live loader's question and is why a walk over accessor return types
     * needed one.
     *
     * <p>{@code java.lang.Object} is dropped rather than recorded. The JVM writes it as the
     * superclass of every class with no {@code extends} clause and of every interface, so a row
     * would report a declaration the source never wrote, and what is left is exactly the extends
     * clause an author typed.
     *
     * <p>An interface's super-interfaces sit in the same array as a class's implements list, so
     * the clause is decided by the declaring class's own form. Nothing filters the names: a
     * supertype the scan will never reach is the whole point, a chain's last hop usually being a
     * JDK interface nobody scans.
     */
    private static List<CompletionData.Supertype> readSupertypes(ClassModel cm) {
        String clause = cm.flags().has(AccessFlag.INTERFACE) ? "EXTENDS" : "IMPLEMENTS";
        var supertypes = new ArrayList<CompletionData.Supertype>();
        cm.superclass()
            .map(entry -> entry.asInternalName().replace('/', '.'))
            .filter(name -> !OBJECT.equals(name))
            .ifPresent(name -> supertypes.add(new CompletionData.Supertype(name, "EXTENDS")));
        for (var declared : cm.interfaces()) {
            supertypes.add(new CompletionData.Supertype(
                declared.asInternalName().replace('/', '.'), clause));
        }
        return List.copyOf(supertypes);
    }

    /**
     * The classfile's declared form. Read from the access flags rather than inferred from the
     * reference's own components, because this is the one producer holding the bytecode: an
     * interface and a class are indistinguishable once the scan has reduced them to a method list.
     * Annotation is checked before interface, which it also sets.
     */
    private static String declaredKind(ClassModel cm) {
        var flags = cm.flags();
        if (flags.has(AccessFlag.ANNOTATION)) return "ANNOTATION";
        if (flags.has(AccessFlag.INTERFACE)) return "INTERFACE";
        if (flags.has(AccessFlag.ENUM)) return "ENUM";
        if (cm.findAttribute(Attributes.record()).isPresent()) return "RECORD";
        return "CLASS";
    }

    /**
     * Reads {@code public static} fields whose JVM type descriptor is exactly
     * {@code Lgraphql/schema/GraphQLScalarType;} so the LSP can complete
     * {@code @scalarType(scalar:)} from the constants actually present on the
     * consumer's codegen classpath rather than a hardcoded convention list.
     *
     * <p>Exact descriptor compare, not assignability: the parse-only scan
     * resolves no type hierarchy, and the constant is declared as
     * {@code GraphQLScalarType} directly. {@code final} is intentionally not
     * required: the reflective resolver binds a non-final constant just as
     * well. The scan offers a candidate FQN only; the reflective resolver and
     * diagnostics remain the source of truth that reject a bad constant at
     * build time.
     */
    private static List<CompletionData.ScalarConstant> readScalarConstants(ClassModel cm) {
        var constants = new ArrayList<CompletionData.ScalarConstant>();
        for (FieldModel f : cm.fields()) {
            if (!f.flags().has(AccessFlag.PUBLIC)) continue;
            if (!f.flags().has(AccessFlag.STATIC)) continue;
            if (!GRAPHQL_SCALAR_TYPE_DESCRIPTOR.equals(f.fieldTypeSymbol().descriptorString())) continue;
            constants.add(new CompletionData.ScalarConstant(f.fieldName().stringValue()));
        }
        return List.copyOf(constants);
    }

    /**
     * Reads the JVM {@code Record} attribute on a class file when present:
     * the attribute lists the record's component name + JVM type-descriptor
     * pairs in declaration order. Returns an empty list for non-record
     * classes (the attribute is absent on plain classes, enums, interfaces,
     * abstract classes).
     */
    private static List<CompletionData.RecordComponent> readRecordComponents(ClassModel cm) {
        var attrOpt = cm.findAttribute(Attributes.record());
        if (attrOpt.isEmpty()) return List.of();
        RecordAttribute attr = attrOpt.get();
        var components = new ArrayList<CompletionData.RecordComponent>(attr.components().size());
        for (var info : attr.components()) {
            String name = info.name().stringValue();
            String descriptor = info.descriptor().stringValue();
            ClassDesc erased = ClassDesc.ofDescriptor(descriptor);
            String displayType = displayName(erased);
            // A record component carries its own Signature attribute, so the declared form is read
            // per component rather than off the record's accessor method.
            Optional<Signature> signature = info.findAttribute(Attributes.signature())
                .map(SignatureAttribute::asTypeSignature);
            String declaredType = signature.map(ClasspathScanner::declaredName).orElse(displayType);
            components.add(new CompletionData.RecordComponent(
                name, displayType, declaredType, typeRefs(signature, erased)));
        }
        return List.copyOf(components);
    }

    private static List<CompletionData.Method> readMethods(ClassModel cm) {
        var methods = new ArrayList<CompletionData.Method>();
        for (MethodModel m : cm.methods()) {
            if (!m.flags().has(AccessFlag.PUBLIC)) continue;
            if (m.flags().has(AccessFlag.SYNTHETIC)) continue;
            String name = m.methodName().stringValue();
            // Constructors and class initializers carry name `<init>` /
            // `<clinit>` in the constant pool; skip both.
            if (name.startsWith("<")) continue;
            var desc = m.methodTypeSymbol();
            // Classify the return type at the parse boundary, from the
            // un-erased descriptor: once displayName() drops the package, a
            // simple-name match cannot tell org.jooq.Condition from a
            // consumer's own type named Condition. Exact descriptor compare,
            // not assignability: the parse-only scan resolves no type
            // hierarchy, and the jOOQ idiom returns Condition directly.
            boolean returnsCondition = JOOQ_CONDITION_DESCRIPTOR.equals(desc.returnType().descriptorString());
            String returnType = displayName(desc.returnType());
            // The real JVM descriptor, not a rendering of the erased display names: two public
            // methods taking com.foo.Result and com.bar.Result render identically once the package
            // is gone, and the store keys the method on this.
            String descriptor = desc.descriptorString();
            // The declared forms come off the Signature attribute where the classfile carries one,
            // and fall back to the erasure where it does not, which is what absence means. The
            // signature's argument list is used only when it is the same length as the descriptor's:
            // a compiler-synthesised parameter appears in one list and not the other, and there is
            // no position-wise correction for that, so a length mismatch falls back wholesale rather
            // than pairing a declared form with the wrong parameter.
            var signature = methodSignature(m);
            String declaredReturnType = signature
                .map(s -> declaredName(s.result()))
                .orElse(returnType);
            var declaredParams = signature
                .map(MethodSignature::arguments)
                .filter(args -> args.size() == desc.parameterList().size())
                .orElse(List.of());
            var paramNames = readParameterNames(m, desc.parameterList().size());
            var parameters = new ArrayList<CompletionData.Parameter>();
            for (int i = 0; i < desc.parameterList().size(); i++) {
                ClassDesc paramType = desc.parameterList().get(i);
                String erased = displayName(paramType);
                Optional<Signature> declared = i < declaredParams.size()
                    ? Optional.of(declaredParams.get(i))
                    : Optional.empty();
                parameters.add(new CompletionData.Parameter(
                    paramNames.get(i),
                    erased,
                    null,
                    "",
                    declared.map(ClasspathScanner::declaredName).orElse(erased),
                    typeRefs(declared, paramType)
                ));
            }
            methods.add(new CompletionData.Method(
                name, returnType, "", List.copyOf(parameters), returnsCondition, descriptor,
                declaredReturnType,
                typeRefs(signature.map(MethodSignature::result), desc.returnType())));
        }
        return List.copyOf(methods);
    }

    /**
     * Reads parameter names off the {@code MethodParameters} attribute when
     * present (i.e. the class was compiled with {@code -parameters}).
     * Returns a list of {@code null}s otherwise, per the
     * {@link CompletionData.Parameter#name()} contract: a null name (never a
     * synthesised {@code arg0}) is the detection signal the LSP diagnostic
     * uses to warn the schema author that parameter help is unavailable until
     * the class is recompiled with {@code -parameters}.
     */
    private static List<String> readParameterNames(MethodModel m, int parameterCount) {
        var attrOpt = m.findAttribute(Attributes.methodParameters());
        if (attrOpt.isEmpty()) {
            var names = new ArrayList<String>(parameterCount);
            for (int i = 0; i < parameterCount; i++) names.add(null);
            return java.util.Collections.unmodifiableList(names);
        }
        MethodParametersAttribute attr = attrOpt.get();
        var names = new ArrayList<String>(parameterCount);
        var infos = attr.parameters();
        for (int i = 0; i < parameterCount; i++) {
            if (i >= infos.size()) {
                names.add(null);
                continue;
            }
            var nameOpt = infos.get(i).name();
            names.add(nameOpt.map(n -> n.stringValue()).orElse(null));
        }
        return java.util.Collections.unmodifiableList(names);
    }

    private static String displayName(ClassDesc desc) {
        return desc.displayName();
    }

    /**
     * The qualified classes a declared type names, one per position within it, as
     * {@link CompletionData.TypeRef} states the path grammar and the omission rules.
     *
     * <p>Two entry points because the classfile has two encodings of one thing: a
     * {@code Signature} where the compiler emitted one, and the descriptor where it did not, which
     * for a non-generic type is always. They are not alternatives of differing quality; absence of
     * the attribute means the erasure <em>is</em> the declared form, so both readings are the
     * declaration and they agree wherever both exist.
     */
    private static List<CompletionData.TypeRef> typeRefs(Optional<Signature> signature, ClassDesc erased) {
        var refs = new ArrayList<CompletionData.TypeRef>();
        signature.ifPresentOrElse(
            s -> collectRefs(s, "", "NONE", refs),
            () -> collectRefs(erased, "", refs));
        return List.copyOf(refs);
    }

    /** Walks a signature, emitting a reference for every position that names a class. */
    private static void collectRefs(Signature signature, String path, String variance,
                                    List<CompletionData.TypeRef> into) {
        switch (signature) {
            // A primitive and a type variable name no class. The variable is the case worth
            // noting: its erasure is its bound (Object, absent a declared one), so the census's
            // erased column reads a class here where the declaration named none, and this walk
            // follows the declaration.
            case Signature.BaseTypeSig ignored -> { }
            case Signature.TypeVarSig ignored -> { }
            case Signature.ArrayTypeSig array -> collectRefs(array.componentSignature(), step(path, "[]"), variance, into);
            case Signature.ClassTypeSig cls -> {
                into.add(new CompletionData.TypeRef(path, binaryName(cls.classDesc()), variance));
                int index = 0;
                for (var arg : cls.typeArgs()) {
                    String argPath = step(path, String.valueOf(index++));
                    switch (arg) {
                        // A bare `?` bounds at Object, which no relation here records as a
                        // declaration, so the position stays empty rather than naming it.
                        case Signature.TypeArg.Unbounded ignored -> { }
                        case Signature.TypeArg.Bounded bounded -> collectRefs(
                            bounded.boundType(), argPath, bounded.wildcardIndicator().name(), into);
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
    private static void collectRefs(ClassDesc desc, String path, List<CompletionData.TypeRef> into) {
        if (desc.isPrimitive()) return;
        if (desc.isArray()) {
            collectRefs(desc.componentType(), step(path, "[]"), into);
            return;
        }
        into.add(new CompletionData.TypeRef(path, binaryName(desc), "NONE"));
    }

    /** One step deeper into a type; the root path is empty, so the first step carries no dot. */
    private static String step(String path, String next) {
        return path.isEmpty() ? next : path + "." + next;
    }

    /**
     * The fully-qualified binary name of a class-typed descriptor: {@link ClassDesc#displayName}
     * already spells a nested class with the {@code $} the JVM uses, so the package is all it is
     * missing. Callers exclude arrays and primitives, for which no such name exists.
     */
    private static String binaryName(ClassDesc desc) {
        String packageName = desc.packageName();
        return packageName.isEmpty() ? desc.displayName() : packageName + "." + desc.displayName();
    }

    /**
     * The declared form of one type as a signature spells it: package-less like
     * {@link #displayName}, with type arguments kept. {@code List<Film>} rather than the
     * {@code List} its descriptor erases to, which is the whole reason the signature is read.
     *
     * <p>A wildcard renders as the author wrote it ({@code ?}, {@code ? extends X},
     * {@code ? super X}) and a type variable as the variable's own identifier, which is the one
     * place this form carries strictly less than the erasure: {@code T} does not say what
     * {@code T} erases to. Neither form subsumes the other, which is why the census keeps both.
     */
    private static String declaredName(Signature signature) {
        return switch (signature) {
            case Signature.BaseTypeSig base ->
                displayName(ClassDesc.ofDescriptor(String.valueOf(base.baseType())));
            case Signature.ArrayTypeSig array -> declaredName(array.componentSignature()) + "[]";
            case Signature.TypeVarSig variable -> variable.identifier();
            case Signature.ClassTypeSig cls -> {
                if (cls.typeArgs().isEmpty()) yield displayName(cls.classDesc());
                var args = new ArrayList<String>(cls.typeArgs().size());
                for (var arg : cls.typeArgs()) args.add(declaredArg(arg));
                yield displayName(cls.classDesc()) + "<" + String.join(", ", args) + ">";
            }
        };
    }

    /** One type argument in the form the author wrote it, wildcard bound included. */
    private static String declaredArg(Signature.TypeArg arg) {
        return switch (arg) {
            case Signature.TypeArg.Unbounded ignored -> "?";
            case Signature.TypeArg.Bounded bounded -> switch (bounded.wildcardIndicator()) {
                case NONE -> declaredName(bounded.boundType());
                case EXTENDS -> "? extends " + declaredName(bounded.boundType());
                case SUPER -> "? super " + declaredName(bounded.boundType());
            };
        };
    }

    /**
     * The generic signature a method declares, or empty where the classfile carries none. Absent
     * is the common case and means the descriptor already is the declared form: the compiler emits
     * the attribute only where erasure loses something.
     */
    private static Optional<MethodSignature> methodSignature(MethodModel m) {
        return m.findAttribute(Attributes.signature()).map(SignatureAttribute::asMethodSignature);
    }
}
