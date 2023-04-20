package no.fellesstudentsystem.graphitron.generators.dependencies;

import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import org.jetbrains.annotations.NotNull;

import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.DSL_CONTEXT;
import static no.fellesstudentsystem.graphitron.mappings.JavaPoetClassName.INJECT;

public class ContextDependency implements Dependency, Comparable<Dependency> {
    private static final ContextDependency instance = new ContextDependency();

    private ContextDependency() {}

    public static ContextDependency getInstance(){
        return instance;
    }

    @Override
    public FieldSpec getSpec() {
        return FieldSpec.builder(DSL_CONTEXT.className, CONTEXT_NAME).addAnnotation(INJECT.className).build();
    }

    @Override
    public CodeBlock getDeclarationCode() {
        return CodeBlock.of("var $L = new $T()", CONTEXT_NAME, DSL_CONTEXT.className);
    }

    @Override
    public int compareTo(@NotNull Dependency o) {
        if (o instanceof ContextDependency) {
            return 0;
        }
        if (o instanceof NamedDependency) {
            return -1;
        }
        return 1;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        return obj instanceof ContextDependency;
    }
}
