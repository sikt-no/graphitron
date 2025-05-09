package no.sikt.graphitron.generators.dependencies;

import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.FieldSpec;

/**
 * General interface for dependency handling in generated classes.
 */
public interface Dependency {
    /**
     * @return Javapoet FieldSpec for a dependency of this type.
     */
    FieldSpec getSpec();

    /**
     * @return CodeBlock that declares a dependency of this type.
     */
    CodeBlock getDeclarationCode();
}
