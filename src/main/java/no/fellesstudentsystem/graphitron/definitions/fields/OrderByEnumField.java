package no.fellesstudentsystem.graphitron.definitions.fields;

import graphql.language.EnumTypeDefinition;
import graphql.language.EnumValueDefinition;
import no.fellesstudentsystem.graphitron.definitions.objects.EnumDefinition;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.mappings.TableReflection;
import no.fellesstudentsystem.graphql.schema.ProcessedSchema;
import org.jooq.SortField;

import java.util.*;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.graphql.directives.DirectiveHelpers.getOptionalDirectiveArgumentString;
import static no.fellesstudentsystem.graphql.directives.GenerationDirective.INDEX;
import static no.fellesstudentsystem.graphql.directives.GenerationDirectiveParam.NAME;

/**
 * An order by field enum value within an {@link EnumDefinition}.
 */
public class OrderByEnumField extends AbstractField<EnumValueDefinition> {

    private final String indexName;

    public OrderByEnumField(EnumValueDefinition field, String container) {
        super(field, container);
        indexName = getOptionalDirectiveArgumentString(field, INDEX, NAME)
                .orElseThrow(() -> new IllegalArgumentException(
                        String.format("Expected enum field '%s' of '%s' to have an '@%s(name : ...)' directive, but no such directive was found", field.getName(), container, INDEX.getName())));
    }

    /**
     * @return List of instances based on an instance of {@link EnumTypeDefinition}.
     */
    public static List<OrderByEnumField> from(EnumTypeDefinition e, String container) {
        return e.getEnumValueDefinitions()
                .stream()
                .map(field -> new OrderByEnumField(field, container))
                .collect(Collectors.toList());
    }

    public Optional<String> getIndexName() {
        return Optional.ofNullable(indexName);
    }

    /**
     * Validates that the index referred to by this orderBy field is accessible from the given schema node.
     *
     * @param processedSchema  The processed schema.
     * @param referenceNode   The reference schema node.
     */
    public void validateThatReferredIndexIsAccessibleFromNode(ProcessedSchema processedSchema, ObjectDefinition referenceNode) {
        var index = TableReflection.getIndex(referenceNode.getTable().getMappingName(), indexName).orElseThrow();

        index.getFields()
                .stream()
                .map(SortField::getName)
                .forEach(it ->
                        getFieldWithPath(processedSchema, referenceNode, it, new ArrayList<>())
                                .orElseThrow(() -> new IllegalArgumentException(
                                        String.format("OrderByField '%s' refers to index '%s' on field '%s' but this field is not accessible from the schema type '%s'",
                                                getName(), indexName, it, referenceNode.getName()))));
    }

    private Optional<FieldWithPath> getFieldWithPath(ProcessedSchema processedSchema, ObjectDefinition referenceNode, String fieldName, ArrayList<String> path) {

        for (ObjectField field : referenceNode.getFields()) {

            if (field.getUpperCaseName().equalsIgnoreCase(fieldName)) {
                return Optional.of(new FieldWithPath(field, path));
            }
            var nestedObject =  processedSchema.getObject(field);

            if (nestedObject != null) {
                var pathCopy = new ArrayList<>(path);
                pathCopy.add(field.getName());
                var fieldWithPath = getFieldWithPath(processedSchema, nestedObject, fieldName, pathCopy);

                if (fieldWithPath.isPresent()) {
                    return fieldWithPath;
                }
            }
        }
        return Optional.empty();
    }

    private static class FieldWithPath {
        ObjectField objectField;
        List<String> path;

        public FieldWithPath(ObjectField objectField, List<String> path) {
            this.objectField = objectField;
            this.path = path;
        }
    }
}
