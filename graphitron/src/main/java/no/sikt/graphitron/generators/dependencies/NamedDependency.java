package no.sikt.graphitron.generators.dependencies;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.TypeName;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.asMethodCall;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.declare;
import static no.sikt.graphitron.generators.codebuilding.VariableNames.TRANSFORMER_NAME;
import static no.sikt.graphitron.generators.mapping.TransformerClassGenerator.METHOD_CONTEXT_NAME;

/**
 * An abstract dependency on a class somewhere in the codebase.
 */
abstract public class NamedDependency implements Dependency, Comparable<Dependency> {
    private final String name, path;
    private final ClassName typeName;

    public NamedDependency(ClassName className) {
        typeName = className;
        this.name = typeName.simpleName();
        this.path = typeName.packageName();
    }

    /**
     * @return The name of this dependency.
     */
    public String getName() {
        return name;
    }

    /**
     * @return The location of this dependency.
     */
    public String getPath() {
        return path;
    }

    /**
     * @return The javapoet TypeName for this dependency.
     */
    public TypeName getTypeName() {
        return typeName;
    }

    @Override
    abstract public FieldSpec getSpec();

    @Override
    public CodeBlock getDeclarationCode() {
        return declare(getName(), CodeBlock.of("new $T($L)", getTypeName(), asMethodCall(TRANSFORMER_NAME, METHOD_CONTEXT_NAME)));
    }

    @Override
    public int compareTo(@NotNull Dependency o) {
        if (o instanceof ContextDependency) {
            return 1;
        }
        var nDep = (NamedDependency) o;
        return (getName() + getPath()).compareTo(nDep.getName() + nDep.getPath());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NamedDependency)) return false;
        NamedDependency that = (NamedDependency) o;
        return getName().equals(that.getName()) && Objects.equals(getPath(), that.getPath());
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, path);
    }
}
