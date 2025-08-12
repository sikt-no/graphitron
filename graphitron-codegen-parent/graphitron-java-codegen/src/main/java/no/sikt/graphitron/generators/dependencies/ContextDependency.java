package no.sikt.graphitron.generators.dependencies;

import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.FieldSpec;
import org.jetbrains.annotations.NotNull;

import static no.sikt.graphitron.generators.codebuilding.VariableNames.CONTEXT_NAME;
import static no.sikt.graphitron.mappings.JavaPoetClassName.DSL_CONTEXT;
import static no.sikt.graphitron.mappings.JavaPoetClassName.INJECT;

/**
 * A dependency on DSLContext.
 */
public class ContextDependency implements Dependency, Comparable<Dependency> {
    private static final ContextDependency instance = new ContextDependency();
    private final static CodeBlock declaration = CodeBlock.declareNew(CONTEXT_NAME, DSL_CONTEXT.className);

    private ContextDependency() {}

    /**
     * @return Get the singleton DSLContext dependency.
     */
    public static ContextDependency getInstance() {
        return instance;
    }

    @Override
    public FieldSpec getSpec() {
        return FieldSpec.builder(DSL_CONTEXT.className, CONTEXT_NAME).addAnnotation(INJECT.className).build();
    }

    @Override
    public CodeBlock getDeclarationCode() {
        return declaration;
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
