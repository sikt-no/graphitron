package no.sikt.graphitron.definitions.objects;

import no.sikt.graphitron.definitions.interfaces.GenerationTarget;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.definitions.fields.OperationField;
import no.sikt.graphitron.definitions.helpers.ClassReference;
import no.sikt.graphitron.definitions.interfaces.FieldSpecification;
import no.sikt.graphitron.definitions.interfaces.ObjectSpecification;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import static no.sikt.graphql.naming.GraphQLReservedName.*;
import static org.apache.commons.lang3.StringUtils.capitalize;

/**
 * Represents the top schema object in GraphQL.
 */
public class SchemaDefinition implements ObjectSpecification<OperationField>, GenerationTarget {
    private final static String name = SCHEMA.getName();
    private final LinkedHashMap<String, OperationField> fieldsByName;
    private final ClassReference graphClass;
    private final OperationField query;
    private final OperationField mutation;
    // private final OperationField subscription;

    public SchemaDefinition(graphql.language.SchemaDefinition definition) {
        graphClass = new ClassReference(capitalize(name), GeneratorConfig.generatedModelsPackage());
        fieldsByName = definition
                .getOperationTypeDefinitions()
                .stream()
                .map(OperationField::new)
                .collect(Collectors.toMap(FieldSpecification::getName, Function.identity(), (x, y) -> y, LinkedHashMap::new));
        query = fieldsByName.values().stream().filter(it -> it.getName().equals(OPERATION_QUERY.getName())).findFirst().orElse(null);
        mutation = fieldsByName.values().stream().filter(it -> it.getName().equals(OPERATION_MUTATION.getName())).findFirst().orElse(null);
        // subscription = fieldsByName.values().stream().filter(it -> it.getName().equals(OPERATION_SUBSCRIPTION.getName())).findFirst().orElse(null);
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

    @Override
    public List<OperationField> getFields() {
        return new ArrayList<>(fieldsByName.values());
    }

    @Override
    public OperationField getFieldByName(String name) {
        return fieldsByName.get(name);
    }

    /**
     * @return Is this object an operation node? That should be either the Query or the Mutation type.
     */
    @Override
    public boolean isOperationRoot() {
        return false;
    }

    public OperationField getQuery() {
        return query;
    }

    public OperationField getMutation() {
        return mutation;
    }

    @Override
    public boolean isGenerated() {
        return true;
    }

    @Override
    public boolean isGeneratedWithResolver() {
        return false;
    }

    @Override
    public boolean isExplicitlyNotGenerated() {
        return false;
    }
}
