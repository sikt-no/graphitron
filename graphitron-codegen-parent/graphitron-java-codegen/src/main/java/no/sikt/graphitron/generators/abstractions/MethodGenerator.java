package no.sikt.graphitron.generators.abstractions;

import no.sikt.graphitron.generators.dependencies.Dependency;
import no.sikt.graphitron.javapoet.MethodSpec;

import java.util.List;
import java.util.Map;

/**
 * A method generator generates a set of methods for any purpose.
 */
public interface MethodGenerator {
    /**
     * @return List of complete javapoet {@link MethodSpec} that can be generated for this object.
     */
    List<MethodSpec> generateAll();

    /**
     * @return A set containing all dependencies necessary for these generated methods. Key is the method name where the dependency is needed.
     */
    Map<String, List<Dependency>> getDependencyMap();
}
