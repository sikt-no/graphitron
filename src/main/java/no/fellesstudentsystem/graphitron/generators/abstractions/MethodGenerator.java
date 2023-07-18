package no.fellesstudentsystem.graphitron.generators.abstractions;

import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import no.fellesstudentsystem.graphitron.definitions.interfaces.GenerationTarget;
import no.fellesstudentsystem.graphitron.generators.dependencies.Dependency;
import no.fellesstudentsystem.graphql.directives.GenerationDirective;

import java.util.List;
import java.util.Set;

/**
 * A method generator uses a GraphQL object and its fields in order to generate methods for said object.
 * Methods are only generated for referred objects with the
 * "{@link GenerationDirective#TABLE Table}" directive set.
 * @param <T> Field type that this generator operates on.
 */
public interface MethodGenerator<T extends GenerationTarget> {
    /**
     * @return List of complete javapoet {@link MethodSpec} that can be generated for this object.
     */
    List<MethodSpec> generateAll();

    /**
     * @param target A {@link GenerationTarget} object for which a method should be generated for.
     * @return The complete javapoet {@link MethodSpec} based on the provided target.
     */
    MethodSpec generate(T target);

    /**
     * @param methodName The name of the method.
     * @param returnType The return type of the method, as a javapoet {@link TypeName}.
     * @return The default builder for this class' methods, with any common settings applied.
     */
    MethodSpec.Builder getDefaultSpecBuilder(String methodName, TypeName returnType);

    boolean generatesAll();

    /**
     * @return A set containing all dependencies necessary for these generated methods.
     */
    Set<Dependency> getDependencySet();
}
