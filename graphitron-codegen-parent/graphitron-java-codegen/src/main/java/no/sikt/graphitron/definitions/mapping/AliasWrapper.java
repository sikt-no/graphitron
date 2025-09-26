package no.sikt.graphitron.definitions.mapping;

import no.sikt.graphitron.definitions.fields.ObjectField;
import no.sikt.graphitron.definitions.helpers.ServiceWrapper;
import no.sikt.graphitron.definitions.interfaces.GenerationField;
import no.sikt.graphitron.generators.context.InputParser;
import no.sikt.graphql.schema.ProcessedSchema;

import java.util.List;
import java.util.Objects;

public class AliasWrapper {
    private final Alias alias;
    private ServiceWrapper tableMethod;
    private List<String> inputNames;
    private ObjectField referenceObjectField;

    public AliasWrapper(Alias alias, GenerationField referenceObjectField, boolean isTargetTable, ProcessedSchema processedSchema) {
        this.alias = alias;
        this.tableMethod = referenceObjectField.hasTableMethodDirective() && isTargetTable ? referenceObjectField.getExternalMethod() : null;
        this.inputNames = new InputParser((ObjectField) referenceObjectField, processedSchema).getMethodInputNames();
        this.referenceObjectField = (ObjectField) referenceObjectField;
    }

    public AliasWrapper(Alias alias, GenerationField referenceObjectField, ProcessedSchema processedSchema) {
        this.alias = alias;
        this.tableMethod = referenceObjectField.hasTableMethodDirective() ? referenceObjectField.getExternalMethod() : null;
        this.inputNames = new InputParser((ObjectField) referenceObjectField, processedSchema).getMethodInputNames();
        this.referenceObjectField = (ObjectField) referenceObjectField;
    }

    public AliasWrapper(Alias alias) {
        this.alias = alias;
    }

    public boolean hasTableMethod() {
        return tableMethod != null;
    }

    public Alias getAlias() {
        return alias;
    }
    public ServiceWrapper getTableMethod() {
        return tableMethod;
    }
    public List<String> getInputNames() {
        return inputNames;
    }
    public ObjectField getReferenceObjectField() {
        return referenceObjectField;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o instanceof Alias) {
            return o.equals(this.getAlias());
        }
        if (o instanceof AliasWrapper) {
            return ((AliasWrapper) o).getAlias().equals(this.getAlias());
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.getAlias().getMappingName());
    }
}
