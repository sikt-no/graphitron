package no.sikt.graphitron.rewrite.catalog;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.reflect.AccessFlag;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Walks the consumer's compiled class output directory and enumerates the
 * public top-level classes the LSP can offer for {@code @service(class:)} /
 * {@code @condition(class:)} / {@code @record(record: {className:})}
 * completion. Reads {@code .class} bytes via the JDK 25 stdlib
 * {@link java.lang.classfile} API; no external dependency.
 *
 * <p>Filters: public access, top-level (no {@code $} in the simple name),
 * not synthetic, not {@code module-info} / {@code package-info}, and not
 * under the jOOQ-generated package (those are referenced through
 * {@code @table} / {@code @reference}, not {@code @service}). The filter
 * is generous on purpose: enums and interfaces are still candidates,
 * because consumers do reference them as {@code @record} class names and
 * as service-method-bearing interfaces. Picking the wrong one is a
 * one-keystroke fix; missing a valid one in the list is a worse failure.
 */
public final class ClasspathScanner {

    private ClasspathScanner() {}

    /**
     * Returns class FQNs in deterministic walk order. Empty when
     * {@code classesRoot} does not exist on disk; this is the normal case
     * for fresh checkouts before the first {@code mvn compile}.
     */
    public static List<String> scan(Path classesRoot, String jooqPackage) {
        if (!Files.isDirectory(classesRoot)) {
            return List.of();
        }
        var jooqPrefix = jooqPackage.isEmpty() ? null : jooqPackage + ".";
        var fqns = new ArrayList<String>();
        try (Stream<Path> walk = Files.walk(classesRoot)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".class"))
                .forEach(p -> {
                    String fqn = readFqnIfCandidate(p, jooqPrefix);
                    if (fqn != null) fqns.add(fqn);
                });
        } catch (IOException e) {
            throw new UncheckedIOException("classpath scan failed at " + classesRoot, e);
        }
        return List.copyOf(fqns);
    }

    private static String readFqnIfCandidate(Path classFile, String jooqPrefix) {
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
        return fqn;
    }
}
