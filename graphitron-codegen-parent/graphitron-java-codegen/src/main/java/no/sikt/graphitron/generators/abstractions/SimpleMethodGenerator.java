package no.sikt.graphitron.generators.abstractions;

import no.sikt.graphitron.generators.dependencies.Dependency;
import no.sikt.graphitron.javapoet.MethodSpec;

import java.util.List;
import java.util.Map;

/**
 * An abstract generator that contains simple methods that are independent of the GraphQL schema.
 * <p>
 * Subclasses should override either {@link #generate()} for single method generation,
 * or {@link #generateAll()} for multiple method generation.
 */
abstract public class SimpleMethodGenerator implements MethodGenerator {
    @Override
    public List<MethodSpec> generateAll() {
        return List.of(generate());
    }

    @Override
    public Map<String, List<Dependency>> getDependencyMap() {
        return Map.of();
    }

    /**
     * Generates a single method. Override this for simple single-method generators.
     * <p>
     * If your generator produces multiple methods, override {@link #generateAll()} instead.
     *
     * @return The complete javapoet {@link MethodSpec} based on the provided target.
     */
    public MethodSpec generate() {
        throw new UnsupportedOperationException("Override either generate() or generateAll()");
    }
}
