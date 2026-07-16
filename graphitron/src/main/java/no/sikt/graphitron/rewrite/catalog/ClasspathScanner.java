package no.sikt.graphitron.rewrite.catalog;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.MethodParametersAttribute;
import java.lang.classfile.attribute.RecordAttribute;
import java.lang.constant.ClassDesc;
import java.lang.reflect.AccessFlag;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Stream;

/**
 * Walks the consumer's compiled class output directory and enumerates the
 * public top-level classes plus their public methods so the LSP can offer
 * them as completion / hover / diagnostic targets for {@code @service} /
 * {@code @condition} / {@code @record}. Reads {@code .class} bytes via the
 * JDK 25 stdlib {@link java.lang.classfile} API; no external dependency.
 *
 * <p>Class filter: public access, top-level (no {@code $} in the simple
 * name), not synthetic, not {@code module-info} / {@code package-info},
 * and not under the jOOQ-generated package (those are referenced through
 * {@code @table} / {@code @reference}, not {@code @service}). The filter
 * is generous on purpose: enums and interfaces stay in, because consumers
 * do reference them as {@code @record} class names and as service-method-
 * bearing interfaces. Picking the wrong one is a one-keystroke fix;
 * missing a valid one in the list is a worse failure.
 *
 * <p>Method filter: public, not synthetic, not a constructor / class
 * initializer ({@code <init>} / {@code <clinit>}). Type information comes
 * off the JVM method descriptor — erased / unparameterised, but enough
 * for hover signatures and unknown-method diagnostics. Parameter names
 * come from the {@code MethodParameters} attribute when {@code -parameters}
 * was set on the consumer's compile; absent that attribute, the
 * {@link CompletionData.Parameter#name()} slot is {@code null} (intentionally
 * not the synthesised {@code arg0}/{@code arg1} reflection emits, since the
 * {@code -parameters}-missing LSP diagnostic uses {@code name == null}
 * as its detection signal — a synthesised name would silently disable that
 * warning).
 */
public final class ClasspathScanner {

    /** JVM field descriptor of {@code org.jooq.Condition}; the exact return-type match for the condition fact. */
    private static final String JOOQ_CONDITION_DESCRIPTOR = "Lorg/jooq/Condition;";

    /** JVM field descriptor of {@code graphql.schema.GraphQLScalarType}; the exact field-type match for @scalarType completion.*/
    private static final String GRAPHQL_SCALAR_TYPE_DESCRIPTOR = "Lgraphql/schema/GraphQLScalarType;";

    private ClasspathScanner() {}

    /**
     * Walks one classes directory. Convenience overload kept for tests and
     * single-root callers; production reads from {@link #scan(List, String)}.
     */
    public static List<CompletionData.ExternalReference> scan(Path classesRoot, String jooqPackage) {
        return scan(List.of(classesRoot), jooqPackage);
    }

    /**
     * Walks every directory in {@code classesRoots} and returns class records
     * in deterministic walk order. Each root is treated independently; FQNs
     * deduplicated across roots so a class compiled into more than one
     * directory (rare but possible with overlapping reactor configurations)
     * surfaces once.
     *
     * <p>Roots that do not exist on disk are skipped silently; the normal
     * pre-{@code mvn compile} state has zero existing roots and returns
     * an empty list.
     */
    public static List<CompletionData.ExternalReference> scan(List<Path> classesRoots, String jooqPackage) {
        var jooqPrefix = jooqPackage.isEmpty() ? null : jooqPackage + ".";
        var seen = new LinkedHashSet<String>();
        var refs = new ArrayList<CompletionData.ExternalReference>();
        for (Path classesRoot : classesRoots) {
            if (!Files.isDirectory(classesRoot)) continue;
            try (Stream<Path> walk = Files.walk(classesRoot)) {
                walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".class"))
                    .forEach(p -> {
                        var ref = readIfCandidate(p, jooqPrefix);
                        if (ref != null && seen.add(ref.className())) {
                            refs.add(ref);
                        }
                    });
            } catch (IOException e) {
                throw new UncheckedIOException("classpath scan failed at " + classesRoot, e);
            }
        }
        return List.copyOf(refs);
    }

    private static CompletionData.ExternalReference readIfCandidate(Path classFile, String jooqPrefix) {
        String fileName = classFile.getFileName().toString();
        if ("module-info.class".equals(fileName) || "package-info.class".equals(fileName)) {
            return null;
        }
        // `Outer$Inner.class` and lambda/synthetic `$1.class` files are
        // skipped on filename alone; saves a parse on the common case.
        String simple = fileName.substring(0, fileName.length() - ".class".length());
        if (simple.indexOf('$') >= 0) {
            return null;
        }
        ClassModel cm;
        try {
            cm = ClassFile.of().parse(Files.readAllBytes(classFile));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read " + classFile, e);
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
        return new CompletionData.ExternalReference(fqn, fqn, "", methods, recordComponents, scalarConstants);
    }

    /**
     * Reads {@code public static} fields whose JVM type descriptor is exactly
     * {@code Lgraphql/schema/GraphQLScalarType;} so the LSP can complete
     * {@code @scalarType(scalar:)} from the {@code GraphQLScalarType} constants
     * actually present on the consumer's codegen classpath (their own and any
 * library's), rather than a hardcoded convention list.
     *
     * <p>Exact descriptor compare, not assignability, mirroring
     * {@link #JOOQ_CONDITION_DESCRIPTOR}: the parse-only scan resolves no type
     * hierarchy, and the constant is declared as {@code GraphQLScalarType}
     * directly, so exact match is both sufficient and all the scanner can do.
     * {@code final} is intentionally not required: the reflective resolver binds
     * a non-final constant just as well, so requiring it here would drop a
     * genuinely resolvable completion candidate. The scan sees the field
     * <em>type</em>, not its runtime value or {@code Coercing} generics; it
     * offers a candidate FQN, and the reflective resolver / diagnostics remain
     * the source of truth that rejects a null-at-codegen or erased constant at
     * build time (the same best-effort contract method completion lives under).
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
            String displayType = displayName(ClassDesc.ofDescriptor(descriptor));
            components.add(new CompletionData.RecordComponent(name, displayType));
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
            // Classify the return type against jOOQ's Condition here, at the
            // parse boundary, from the un-erased descriptor — before displayName()
            // drops the package below and a simple-name match could no longer
            // tell org.jooq.Condition from a consumer's own type named Condition
            //. Exact descriptor compare, not assignability: the parse-only
            // scan resolves no type hierarchy, and the jOOQ idiom returns
            // Condition directly, so exact match is both sufficient and all the
            // scanner can do.
            boolean returnsCondition = JOOQ_CONDITION_DESCRIPTOR.equals(desc.returnType().descriptorString());
            String returnType = displayName(desc.returnType());
            var paramNames = readParameterNames(m, desc.parameterList().size());
            var parameters = new ArrayList<CompletionData.Parameter>();
            for (int i = 0; i < desc.parameterList().size(); i++) {
                ClassDesc paramType = desc.parameterList().get(i);
                parameters.add(new CompletionData.Parameter(
                    paramNames.get(i),
                    displayName(paramType),
                    null,
                    ""
                ));
            }
            methods.add(new CompletionData.Method(name, returnType, "", List.copyOf(parameters), returnsCondition));
        }
        return List.copyOf(methods);
    }

    /**
     * Reads parameter names off the {@code MethodParameters} attribute when
     * present (i.e. the class was compiled with {@code -parameters}).
     * Returns a list of {@code null}s otherwise, per the
     * {@link CompletionData.Parameter#name()} contract: a null name is
     * the detection signal the LSP diagnostic uses to warn the
     * schema author that parameter help is unavailable until the class
     * is recompiled with {@code -parameters}.
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
        // ClassDesc.displayName returns the simple Java type name:
        // "String" for java/lang/String, "int" for I, "List" for
        // java/util/List, "int[]" for [I.
        return desc.displayName();
    }
}
