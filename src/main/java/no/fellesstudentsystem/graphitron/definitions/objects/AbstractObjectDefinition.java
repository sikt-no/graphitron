package no.fellesstudentsystem.graphitron.definitions.objects;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.TypeName;
import graphql.language.DirectivesContainer;
import graphql.language.NamedNode;
import graphql.language.TypeDefinition;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import no.fellesstudentsystem.graphitron.definitions.fields.AbstractField;
import no.fellesstudentsystem.graphitron.definitions.interfaces.ObjectSpecification;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.apache.commons.lang3.StringUtils.capitalize;

/**
 * A generalized implementation of {@link ObjectSpecification}.
 * Contains functionality that is common between the different kinds of GraphQL objects.
 */
public abstract class AbstractObjectDefinition<T extends TypeDefinition<T>, F extends NamedNode<F> & DirectivesContainer<F>, U extends AbstractField<F>> implements ObjectSpecification {
    private final String name;
    private final TypeName graphClassName;
    private final LinkedHashMap<String, U> fieldsByName;

    public AbstractObjectDefinition(T objectDefinition) {
        name = objectDefinition.getName();
        graphClassName = ClassName.get(GeneratorConfig.generatedModelsPackage(), capitalize(name));
        fieldsByName = createFields(objectDefinition).stream().collect(Collectors.toMap(AbstractField::getName, Function.identity(), (x, y) -> y, LinkedHashMap::new));
    }

    public String getName() {
        return name;
    }

    public TypeName getGraphClassName() {
        return graphClassName;
    }

    protected abstract List<U> createFields(T objectDefinition);

    /**
     * @return The fields contained within this object.
     */
    public List<U> getFields() {
        return new ArrayList<>(fieldsByName.values());
    }

    /**
     * @return The field with this name. Null if it does not exist.
     */
    public U getFieldByName(String name) {
        return fieldsByName.get(name);
    }

    /**
     * @return Does this object contain this field?
     */
    public boolean hasField(String name) {
        return fieldsByName.containsKey(name);
    }
}
