package no.fellesstudentsystem.graphitron.generators.dependencies;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;

public interface Dependency {
    String CONTEXT_NAME = "ctx";

    FieldSpec getSpec();

    CodeBlock getDeclarationCode();
}
