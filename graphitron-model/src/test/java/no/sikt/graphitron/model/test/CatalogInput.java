package no.sikt.graphitron.model.test;

import no.sikt.graphitron.model.config.ClasspathEntry;
import no.sikt.graphitron.model.config.RunContext;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Which jOOQ catalog a fixture's capture reads: the package it was generated into and the loader
 * it is read through. What a run's configuration and classpath say, so a fixture states the same
 * two things a run does rather than handing capture a catalog it built itself.
 *
 * <p>A run's catalog is also an entry of its classpath, one the run declares: the capture reads
 * the catalog in the entry its loader finds it in, and only where the graph's own classpath names
 * that entry, an inherited one holding no catalog of the graph's. {@link #declaredOn} says it for
 * a fixture handing the capture a classpath.
 */
public record CatalogInput(String jooqPackage, ClassLoader loader) {

    public CatalogInput {
        Objects.requireNonNull(jooqPackage, "jooqPackage");
    }

    /** The catalog a run context configures, read through its codegen loader. */
    public static CatalogInput of(RunContext ctx) {
        return new CatalogInput(ctx.jooqPackage(), ctx.codegenLoader());
    }

    /** A package on the test's own classpath. */
    public static CatalogInput of(String jooqPackage) {
        return new CatalogInput(jooqPackage, Thread.currentThread().getContextClassLoader());
    }

    /**
     * {@code classpath} with the entry this catalog is loaded from declared: added where the
     * classpath does not name it, and taken as declared where it names it as inherited. A fixture
     * whose loader finds no catalog gets {@code classpath} back, there being no entry to declare,
     * and so does one handed no classpath, which classifies nothing and reads the catalog from
     * where its loader finds it.
     */
    public List<ClasspathEntry> declaredOn(List<ClasspathEntry> classpath) {
        Path root = classpath.isEmpty() ? null : rootOf();
        if (root == null) {
            return classpath;
        }
        var declared = new ArrayList<ClasspathEntry>(classpath.size() + 1);
        boolean named = false;
        for (ClasspathEntry entry : classpath) {
            if (entry.path().toAbsolutePath().normalize().equals(root)) {
                named = true;
                declared.add(entry.origin() != ClasspathEntry.Origin.TRANSITIVE ? entry
                    : new ClasspathEntry(entry.path(), ClasspathEntry.Origin.DECLARED,
                        entry.coordinate(), entry.suppliedStamp(), entry.kind(), entry.module()));
            } else {
                declared.add(entry);
            }
        }
        if (!named) {
            declared.add(new ClasspathEntry(root, ClasspathEntry.Origin.DECLARED, null, null));
        }
        return List.copyOf(declared);
    }

    /** {@link #declaredOn} over a nullable catalog: none declares nothing. */
    public static List<ClasspathEntry> declaredOn(CatalogInput catalog, List<ClasspathEntry> classpath) {
        return catalog == null ? classpath : catalog.declaredOn(classpath);
    }

    /** Where the loader finds the catalog's {@code DefaultCatalog} class; null where it does not. */
    private Path rootOf() {
        String resource = jooqPackage.replace('.', '/') + "/DefaultCatalog.class";
        var url = loader.getResource(resource);
        if (url == null) {
            return null;
        }
        try {
            Path root;
            if ("jar".equals(url.getProtocol())) {
                String spec = url.getPath();
                root = Path.of(java.net.URI.create(spec.substring(0, spec.indexOf("!/"))));
            } else if ("file".equals(url.getProtocol())) {
                root = Path.of(url.toURI());
                for (int i = 0; i < resource.split("/").length; i++) {
                    root = root.getParent();
                }
            } else {
                return null;
            }
            return root.toAbsolutePath().normalize();
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }
}
