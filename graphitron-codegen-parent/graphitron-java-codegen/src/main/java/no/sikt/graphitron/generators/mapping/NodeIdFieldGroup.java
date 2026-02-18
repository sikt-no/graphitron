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
    private boolean isListed;

    public NodeIdFieldGroup(String targetFieldName, Class<? extends UpdatableRecordImpl<?>> jooqRecordClass) {
        this.targetFieldName = targetFieldName;
        this.jooqRecordClass = jooqRecordClass;
        this.fields = new ArrayList<>();
    }

    public void addField(GenerationField field) {
        if (fields.isEmpty()) {
            isListed = field.isIterableWrapped();
        } else if (field.isIterableWrapped() != isListed) {
            throw new IllegalArgumentException(
                "Cannot mix listed and singular @nodeId fields targeting the same record field '"
                + targetFieldName + "'");
        }
        fields.add(field);
    }

    public boolean isListed() {
        return isListed;
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
}