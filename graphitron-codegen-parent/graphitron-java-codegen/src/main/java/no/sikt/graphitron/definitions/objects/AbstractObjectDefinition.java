package no.sikt.graphitron.definitions.objects;

import no.sikt.graphitron.javapoet.ClassName;
import graphql.language.TypeDefinition;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.helpers.ClassReference;
import no.sikt.graphitron.definitions.interfaces.FieldSpecification;
import no.sikt.graphitron.definitions.interfaces.ObjectSpecification;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.apache.commons.lang3.StringUtils.capitalize;

/**
 * A generalized implementation of {@link ObjectSpecification}.
 * Contains functionality that is common between the different kinds of GraphQL objects.
 */
public abstract class AbstractObjectDefinition<T extends TypeDefinition<T>, U extends FieldSpecification> implements ObjectSpecification<U> {
    private final String name;
    private final ClassReference graphClass;
    private final LinkedHashMap<String, U> fieldsByName;
    private final T objectDefinition;

    public AbstractObjectDefinition(T objectDefinition) {
        this.objectDefinition = objectDefinition;
        name = objectDefinition.getName();
        graphClass = new ClassReference(capitalize(name), GeneratorConfig.generatedModelsPackage());
        fieldsByName = createFields(objectDefinition).stream().collect(Collectors.toMap(FieldSpecification::getName, Function.identity(), (x, y) -> y, LinkedHashMap::new));
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Class<?> getClassReference() {
        return graphClass.getReferenceClass();
    }

    @Override
    public ClassName getGraphClassName() {
        return graphClass.getClassName();
    }

    protected abstract List<U> createFields(T objectDefinition);

    @Override
    public List<U> getFields() {
        return new ArrayList<>(fieldsByName.values());
    }

    @Override
    public U getFieldByName(String name) {
        return fieldsByName.get(name);
    }

    /**
     * @return Does this object contain this field?
     */
    public boolean hasField(String name) {
        return fieldsByName.containsKey(name);
    }

    public T getObjectDefinition() {
        return objectDefinition;
    }

    @Override
    public boolean isOperationRoot() {
        return false;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AbstractObjectDefinition)) return false;
        AbstractObjectDefinition<?, ?> that = (AbstractObjectDefinition<?, ?>) o;
        return Objects.equals(getName(), that.getName());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getName());
    }
}
