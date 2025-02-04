package no.sikt.graphitron.generators.dependencies;

import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import org.jetbrains.annotations.NotNull;

import javax.lang.model.element.Modifier;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.declare;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.NODE_ID_HANDLER_NAME;
import static no.sikt.graphitron.mappings.JavaPoetClassName.*;

/**
 * A dependency on DSLContext.
 */
public class IdHandlerDependency implements Dependency, Comparable<Dependency> {
    private static final IdHandlerDependency instance = new IdHandlerDependency();
    private final static CodeBlock declaration = declare(NODE_ID_HANDLER_NAME, NODE_ID_HANDLER.className);

    private IdHandlerDependency() {}

    /**
     * @return Get the singleton DSLContext dependency.
     */
    public static IdHandlerDependency getInstance() {
        return instance;
    }

    @Override
    public FieldSpec getSpec() {
        return FieldSpec.builder(NODE_ID_HANDLER.className, NODE_ID_HANDLER_NAME, Modifier.PRIVATE).addAnnotation(INJECT.className).build();
    }

    @Override
    public CodeBlock getDeclarationCode() {
        return declaration;
    }

    @Override
    public int compareTo(@NotNull Dependency o) {
        if (o instanceof IdHandlerDependency) {
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
        return obj instanceof IdHandlerDependency;
    }
}
