package no.sikt.graphitron.generators.abstractions;

import com.palantir.javapoet.MethodSpec;
import no.sikt.graphitron.generators.dependencies.Dependency;

import java.util.List;
import java.util.Set;

/**
 * A method generator generates a set of methods for any purpose.
 */
public interface MethodGenerator {
    /**
     * @return List of complete javapoet {@link MethodSpec} that can be generated for this object.
     */
    List<MethodSpec> generateAll();

    /**
     * @return A set containing all dependencies necessary for these generated methods.
     */
    Set<Dependency> getDependencySet();
}
