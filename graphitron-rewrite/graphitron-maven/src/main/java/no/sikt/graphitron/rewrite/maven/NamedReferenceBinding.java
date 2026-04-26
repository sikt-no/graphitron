package no.sikt.graphitron.rewrite.maven;

/**
 * POM XML binding for a single {@code <namedReference>} entry.
 * Collapses into a {@code Map<String, String>} entry on {@link no.sikt.graphitron.rewrite.RewriteContext}.
 */
public class NamedReferenceBinding {
    /** The name used to look up the reference in generated code. */
    String name;
    /** The fully qualified Java class name the reference resolves to. */
    String className;
}
