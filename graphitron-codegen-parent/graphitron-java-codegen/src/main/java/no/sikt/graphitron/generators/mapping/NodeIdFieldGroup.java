package no.sikt.graphitron.generators.mapping;

import no.sikt.graphitron.definitions.interfaces.GenerationField;
import org.jooq.impl.UpdatableRecordImpl;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a group of @nodeId fields that target the same jOOQ record field
 * in a Java record input type.
 */
public class NodeIdFieldGroup {
    private final String targetFieldName;
    private final Class<? extends UpdatableRecordImpl<?>> jooqRecordClass;
    private final List<GenerationField> fields;

    public NodeIdFieldGroup(String targetFieldName, Class<? extends UpdatableRecordImpl<?>> jooqRecordClass) {
        this.targetFieldName = targetFieldName;
        this.jooqRecordClass = jooqRecordClass;
        this.fields = new ArrayList<>();
    }

    public void addField(GenerationField field) {
        fields.add(field);
    }

    public String getTargetFieldName() {
        return targetFieldName;
    }

    public Class<? extends UpdatableRecordImpl<?>> getJooqRecordClass() {
        return jooqRecordClass;
    }

    public List<GenerationField> getFields() {
        return fields;
    }

    public boolean hasMultipleFields() {
        return fields.size() > 1;
    }
}