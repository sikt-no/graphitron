package no.sikt.graphitron.model.test;

import no.sikt.graphitron.model.config.RunContext;

import java.util.Objects;

/**
 * Which jOOQ catalog a fixture's capture reads: the package it was generated into and the loader
 * it is read through. What a run's configuration and classpath say, so a fixture states the same
 * two things a run does rather than handing capture a catalog it built itself.
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
}
