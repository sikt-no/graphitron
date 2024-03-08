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

    public OrderByEnumField(EnumTypeDefinition enumTypeDefinition, EnumValueDefinition field) {
        super(field);
        indexName = getOptionalDirectiveArgumentString(field, INDEX, NAME)
                .orElseThrow(() -> new IllegalArgumentException(
                        String.format("Expected enum field '%s' of '%s' to have an '@index(name : ...)' directive, but no such directive was found", field.getName(), enumTypeDefinition.getName())));
    }

    /**
     * @return List of instances based on an instance of {@link EnumTypeDefinition}.
     */
    public static List<OrderByEnumField> from(EnumTypeDefinition e) {
        return e.getEnumValueDefinitions()
                .stream()
                .map(field -> new OrderByEnumField(e, field))
                .collect(Collectors.toList());
    }

    public Optional<String> getIndexName() {
        return Optional.ofNullable(indexName);
    }

    /**
     * Fetches schema fields with their respective paths for all fields in the index identified by the class/instance variable 'indexName'.
     *
     * @param processedSchema  The processed schema.
     * @param referenceNode   The reference schema node.
     * @return  A mapping of object fields to their respective paths for all fields in the index. The paths are relative to `referenceNode`.
     * i.e., the path is empty for index fields directly accessible from `referenceNode`.
     */
    public Map<ObjectField, List<String>> getSchemaFieldsWithPathForIndex(ProcessedSchema processedSchema, ObjectDefinition referenceNode) {
        var index = TableReflection.getIndex(referenceNode.getTable().getMappingName(), indexName).orElseThrow();

        return index.getFields()
                .stream()
                .map(SortField::getName)
                .map(it ->
                        getFieldWithPath(processedSchema, referenceNode, it, new ArrayList<>())
                                .orElseThrow(() -> new IllegalArgumentException(
                                        String.format("OrderByField '%s' refers to index '%s' on field '%s' but this field is not accessible from the schema type '%s'",
                                                getName(), indexName, it, referenceNode.getName()))))
                .collect(Collectors.toMap(it -> it.objectField, it -> it.path, (x, y) -> y, LinkedHashMap::new));
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
