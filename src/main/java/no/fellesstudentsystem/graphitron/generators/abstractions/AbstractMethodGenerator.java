package no.fellesstudentsystem.graphitron.generators.abstractions;

import no.fellesstudentsystem.graphitron.definitions.fields.ObjectField;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.generators.dependencies.Dependency;
import no.fellesstudentsystem.graphitron.schema.ProcessedSchema;

import java.util.HashSet;
import java.util.Set;

abstract public class AbstractMethodGenerator<T extends ObjectField> implements MethodGenerator<T> {
    protected final ObjectDefinition localObject;
    protected final ProcessedSchema processedSchema;
    protected Set<Dependency> dependencySet = new HashSet<>();

    public AbstractMethodGenerator(ObjectDefinition localObject, ProcessedSchema processedSchema) {
        this.localObject = localObject;
        this.processedSchema = processedSchema;
    }

    /**
     * @return The object that this generator is attempting to build methods for.
     */
    public ObjectDefinition getLocalObject() {
        return localObject;
    }

    @Override
    public Set<Dependency> getDependencySet() {
        return dependencySet;
    }
}
