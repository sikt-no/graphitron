package no.sikt.graphitron.generators.dto;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.objects.ObjectDefinition;
import no.sikt.graphql.naming.GraphQLReservedName;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.LinkedList;
import java.util.List;
import java.util.stream.Stream;

import static no.sikt.graphitron.configuration.GeneratorConfig.generatedModelsPackage;
import static no.sikt.graphql.naming.GraphQLReservedName.*;

public class TypeDTOGenerator extends DTOGenerator {
    private final static LinkedList<String> PAGE_INFO_FIELD_ORDER = new LinkedList<>(
            Stream.of(HAS_PREVIOUS_PAGE_FIELD, HAS_NEXT_PAGE_FIELD, START_CURSOR_FIELD, END_CURSOR_FIELD)
            .map(GraphQLReservedName::getName).toList());
    
    private final static LinkedList<String> CONNECTION_FIELD_ORDER = new LinkedList<>(
            Stream.of(CONNECTION_EDGE_FIELD, CONNECTION_PAGE_INFO_FIELD, CONNECTION_NODES_FIELD, CONNECTION_TOTAL_COUNT)
            .map(GraphQLReservedName::getName).toList());

    public TypeDTOGenerator(ProcessedSchema processedSchema) {
        super(processedSchema);
    }

    protected TypeSpec generate(ObjectDefinition target) {
        var fields = target.getFields();

        // Ensure constructors for PageInfo and Connection type are in the order data fetchers expect
        if (target.getName().equals(CONNECTION_PAGE_INFO_NODE.getName())) {
            fields = sortFields(fields, PAGE_INFO_FIELD_ORDER);
        } else if (target.getName().endsWith(SCHEMA_CONNECTION_SUFFIX.getName())) {
            fields = sortFields(fields, CONNECTION_FIELD_ORDER);
        }

        var classBuilder = getTypeSpecBuilder(target.getName(), fields);

        target.getImplementedInterfaces()
                .forEach(i -> classBuilder.addSuperinterface(ClassName.get(generatedModelsPackage(), i)));

        processedSchema
                .getUnions()
                .values()
                .stream()
                .filter(it -> it.getFieldTypeNames().contains(target.getName()))
                .forEach(it -> classBuilder.addSuperinterface(ClassName.get(generatedModelsPackage(), it.getGraphClassName().simpleName())));

        return classBuilder.build();
    }

    private List<ObjectField> sortFields(List<ObjectField> fields, List<String> sortOrder){
        return fields.stream()
                .sorted((a, b) -> sortOrder.indexOf(a.getName()) > sortOrder.indexOf(b.getName()) ? 1 : -1)
                .toList();
    }

    @Override
    public List<TypeSpec> generateAll() {
        return processedSchema
                .getObjects()
                .values()
                .stream().filter(it ->
                        !it.getName().equals(SCHEMA_QUERY.getName()) && !it.getName().equals(SCHEMA_MUTATION.getName()))
                .map(this::generate)
                .toList();
    }
}
