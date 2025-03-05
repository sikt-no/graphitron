package no.sikt.graphitron.generators.abstractions;

import com.palantir.javapoet.MethodSpec;
import no.sikt.graphitron.generators.dependencies.Dependency;

import java.util.List;
import java.util.Set;

/**
 * An abstract generator that contains simple methods that are independent of the GraphQL schema.
 */
abstract public class SimpleMethodGenerator implements MethodGenerator {
    @Override
    public List<MethodSpec> generateAll() {
        return List.of(generate());
    }

    @Override
    public Set<Dependency> getDependencySet() {
        return Set.of();
    }

    /**
     * @return The complete javapoet {@link MethodSpec} based on the provided target.
     */
    abstract public MethodSpec generate();
}
