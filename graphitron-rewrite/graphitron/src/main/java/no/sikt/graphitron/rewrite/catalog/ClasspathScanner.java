package no.sikt.graphitron.rewrite.catalog;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.MethodParametersAttribute;
import java.lang.constant.ClassDesc;
import java.lang.reflect.AccessFlag;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * was set on the consumer's compile; absent that, names fall back to
 * {@code arg0}, {@code arg1}, &hellip; mirroring reflection's behaviour
 * and giving the Phase 5c {@code -parameters}-missing diagnostic a
 * detection signal.
 */
public final class ClasspathScanner {

    private ClasspathScanner() {}

    /**
     * Returns class records in deterministic walk order. Empty when
     * {@code classesRoot} does not exist on disk (the normal pre-{@code mvn
     * compile} state).
     */
    public static List<CompletionData.ExternalReference> scan(Path classesRoot, String jooqPackage) {
        if (!Files.isDirectory(classesRoot)) {
            return List.of();
        }
        var jooqPrefix = jooqPackage.isEmpty() ? null : jooqPackage + ".";
        var refs = new ArrayList<CompletionData.ExternalReference>();
        try (Stream<Path> walk = Files.walk(classesRoot)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".class"))
                .forEach(p -> {
                    var ref = readIfCandidate(p, jooqPrefix);
                    if (ref != null) refs.add(ref);
                });
        } catch (IOException e) {
            throw new UncheckedIOException("classpath scan failed at " + classesRoot, e);
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
        return new CompletionData.ExternalReference(fqn, fqn, "", methods);
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
            methods.add(new CompletionData.Method(name, returnType, "", List.copyOf(parameters)));
        }
        return List.copyOf(methods);
    }

    /**
     * Reads parameter names off the {@code MethodParameters} attribute when
     * present (i.e. the class was compiled with {@code -parameters}).
     * Returns synthesised {@code arg0}/{@code arg1}/&hellip; placeholders
     * otherwise, mirroring {@link java.lang.reflect.Parameter#getName()}'s
     * behaviour and giving the {@code -parameters}-missing diagnostic
     * (Phase 5c) a detection signal.
     */
    private static List<String> readParameterNames(MethodModel m, int parameterCount) {
        var attrOpt = m.findAttribute(Attributes.methodParameters());
        if (attrOpt.isEmpty()) {
            return synthesisedNames(parameterCount);
        }
        MethodParametersAttribute attr = attrOpt.get();
        var names = new ArrayList<String>(parameterCount);
        var infos = attr.parameters();
        for (int i = 0; i < parameterCount; i++) {
            if (i >= infos.size()) {
                names.add("arg" + i);
                continue;
            }
            var nameOpt = infos.get(i).name();
            names.add(nameOpt.map(n -> n.stringValue()).orElse("arg" + i));
        }
        return List.copyOf(names);
    }

    private static List<String> synthesisedNames(int n) {
        var names = new ArrayList<String>(n);
        for (int i = 0; i < n; i++) names.add("arg" + i);
        return List.copyOf(names);
    }

    private static String displayName(ClassDesc desc) {
        // ClassDesc.displayName returns the simple Java type name:
        // "String" for java/lang/String, "int" for I, "List" for
        // java/util/List, "int[]" for [I.
        return desc.displayName();
    }
}
