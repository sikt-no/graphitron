package no.sikt.graphitron.generators.dependencies;

import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;

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
