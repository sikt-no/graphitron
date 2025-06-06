package no.sikt.graphitron.definitions.interfaces;

import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.definitions.mapping.MethodMapping;
import no.sikt.graphql.directives.GenerationDirective;

/**
 * Specifies that this Java object represents a GraphQL field.
 */
public interface FieldSpecification {
    /**
     * @return Is this field an ID?
     */
    boolean isID();

    /**
     * @return Does this field have the @nodeId directive?
     */
    boolean hasNodeID();

    /**
     * @return The type name configured in the @nodeId directive
     */
    String getNodeIdTypeName();

    /**
     * @return The name of the object as specified in the schema.
     */
    String getName();

    /**
     * @return The name of the field's underlying data type.
     */
    String getTypeName();

    /**
     * @return {@link no.sikt.graphitron.javapoet.TypeName} for this field's type.
     */
    TypeName getTypeClass();

    /**
     * @return The name of the field's container schema type.
     */
    String getContainerTypeName();

    /**
     * @return Is this field wrapped in a list?
     */
    boolean isIterableWrapped();

    /**
     * @return Schema-side method name mappings based on the GraphQL equivalent of this field.
     */
    MethodMapping getMappingFromSchemaName();

    /**
     * @return Is this field optional/nullable?
     */
    boolean isNullable();

    /**
     * @return Is this field required/non-nullable?
     */
    boolean isNonNullable();

    /**
     * @return The database equivalent name of this field. Defaults to field name.
     */
    String getUpperCaseName();

    /**
     * @return Does this field use the {@link GenerationDirective#FIELD} directive?
     */
    boolean hasSetFieldOverride();

    /**
     * @return DB-side method name mappings based on the database equivalent of this field.
     */
    MethodMapping getMappingFromFieldOverride();

    /**
     * @return Is this object field a Query or Mutation root field?
     */
    boolean isRootField();
}
