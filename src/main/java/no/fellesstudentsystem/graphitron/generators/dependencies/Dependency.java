package no.fellesstudentsystem.graphitron.generators.dependencies;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;

/**
 * General interface for dependency handling in generated classes.
 */
public interface Dependency {
    String CONTEXT_NAME = "ctx";

    /**
     * @return Javapoet FieldSpec for a dependency of this type.
     */
    FieldSpec getSpec();

    /**
     * @return CodeBlock that declares a dependency of this type.
     */
    CodeBlock getDeclarationCode();
}
