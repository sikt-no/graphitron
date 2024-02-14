package no.fellesstudentsystem.graphitron.definitions.fields;

import graphql.language.EnumTypeDefinition;
import graphql.language.EnumValueDefinition;
import no.fellesstudentsystem.graphitron.definitions.objects.EnumDefinition;
import no.fellesstudentsystem.graphitron.definitions.objects.ObjectDefinition;
import no.fellesstudentsystem.graphitron.mappings.TableReflection;
import no.fellesstudentsystem.graphitron.schema.ProcessedSchema;
import org.jooq.SortField;

import java.util.List;
import java.util.Optional;
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

    public Optional<String> getIndexName() {
        return Optional.ofNullable(indexName);
    }

    public List<ObjectField> getSchemaFields(ProcessedSchema processedSchema, ObjectDefinition referenceField) {
        var index = TableReflection.getIndex(referenceField.getTable().getMappingName(), indexName).orElseThrow();

        return index.getFields()
                .stream()
                .map(SortField::getName)
                .map(it ->
                        processedSchema.getFieldAvailableOnTable(referenceField.getName(), it)
                                .orElseThrow(() -> new IllegalArgumentException(
                                        String.format("OrderByField '%s' refers to index '%s' on field '%s' but this field is not accessible from the schema type '%s'",
                                                getName(), indexName, it, referenceField.getName()))))
                .collect(Collectors.toList());
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
}
