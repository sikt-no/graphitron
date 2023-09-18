package no.fellesstudentsystem.graphitron.generators.dependencies;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

import static org.apache.commons.lang3.StringUtils.uncapitalize;

/**
 * An abstract dependency on a class somewhere in the codebase.
 */
abstract public class NamedDependency implements Dependency, Comparable<Dependency> {
    private final String name, path;
    private final ClassName className;

    public NamedDependency(String name, String path) {
        this.name = name;
        this.path = path;
        className = ClassName.get(path, name);
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
     * @return The javapoet ClassName for this dependency.
     */
    public ClassName getClassName() {
        return className;
    }

    @Override
    abstract public FieldSpec getSpec();

    @Override
    public CodeBlock getDeclarationCode() {
        return CodeBlock.of("var $L = new $T($N)", uncapitalize(getName()), getClassName(), CONTEXT_NAME);
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
