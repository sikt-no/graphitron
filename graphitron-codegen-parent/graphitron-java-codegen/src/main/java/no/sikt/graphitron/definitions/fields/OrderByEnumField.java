package no.sikt.graphitron.definitions.fields;

import graphql.language.EnumTypeDefinition;
import graphql.language.EnumValueDefinition;
import no.sikt.graphitron.definitions.objects.EnumDefinition;
import no.sikt.graphitron.validation.ValidationHandler;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static no.sikt.graphql.directives.DirectiveHelpers.getOptionalDirectiveArgumentString;
import static no.sikt.graphql.directives.GenerationDirective.INDEX;
import static no.sikt.graphql.directives.GenerationDirectiveParam.NAME;

/**
 * An order by field enum value within an {@link EnumDefinition}.
 */
public class OrderByEnumField extends AbstractField<EnumValueDefinition> {

    private final String indexName;

    public OrderByEnumField(EnumValueDefinition field, String container) {
        super(field, container);
        var DirectiveArg = getOptionalDirectiveArgumentString(field, INDEX, NAME);
        if (DirectiveArg.isPresent()){
            indexName = DirectiveArg.get();
        } else {
            indexName = null;
            ValidationHandler.addFormatedErrorMessage("Expected enum field '%s' of '%s' to have an '@%s(%s: ...)' directive, but no such directive was set", field.getName(), container, INDEX.getName(), NAME.getName());
        }
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

    @Override
    public boolean hasNodeID() {
        return false;
    }

    @Override
    public String getNodeIdTypeName() {
        return null;
    }
}
