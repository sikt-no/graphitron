package no.sikt.graphitron.definitions.interfaces;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.definitions.keys.EntityKeySet;
import no.sikt.graphitron.definitions.mapping.JOOQMapping;
import no.sikt.graphql.directives.GenerationDirective;

import java.util.List;

/**
 * Specifies that this Java object represents a GraphQL object.
 */
public interface RecordObjectSpecification<T extends GenerationField> extends ObjectSpecification<T>, GenerationTarget {
    /**
     * @return Table objects which holds table names.
     */
    JOOQMapping getTable();

    /**
     * @return Does this object have the "{@link GenerationDirective#TABLE table}" directive
     * which implies a connection to a database table?
     */
    boolean hasTable();

    /**
     * @return The reference for a record class for this input type.
     */
    Class<?> getRecordReference();

    /**
     * @return The reference name for a record class for this input type.
     */
    String getRecordReferenceName();

    /**
     * @return The {@link ClassName} for the record that corresponds to this type.
     */
    ClassName getRecordClassName();

    /**
     * @return Does this input type have a record java class attached?
     */
    boolean hasJavaRecordReference();

    /**
     * @return Does this input type have a record class attached?
     */
    boolean hasRecordReference();

    /**
     * @return The {@link ClassName} for this object when it is considered the source of a mapping.
     */
    ClassName asSourceClassName(boolean toRecord);

    /**
     * @return The {@link ClassName} for this object when it is considered the target of a mapping.
     */
    ClassName asTargetClassName(boolean toRecord);

    /**
     * @return The name of this object once it is transformed into a record.
     */
    String asRecordName();

    /**
     * @return Is this type an entity according to the Apollo Federation specification?
     */
    boolean isEntity();

    /**
     * @return Apollo federation entity keys for this type.
     */
    EntityKeySet getEntityKeys();

    public boolean hasNodeDirective();

    public String getTypeId();

    public boolean hasCustomKeyColumns();

    public List<String> getKeyColumns();
}
