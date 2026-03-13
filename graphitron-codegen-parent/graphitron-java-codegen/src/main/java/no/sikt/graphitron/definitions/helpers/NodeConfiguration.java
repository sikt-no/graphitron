package no.sikt.graphitron.definitions.helpers;

import java.util.LinkedList;

/**
 * Configuration for a globally identifiable GraphQL object, derived from the {@code @node} and {@code @table} directives.
 *
 * @param typeId              from {@code @node(typeId)}. Defaults to the GraphQL type name when omitted.
 * @param javaTableName       the jOOQ Java field name for the table associated via {@code @table}.
 *                            Should match the table of the {@link no.sikt.graphitron.definitions.objects.RecordObjectDefinition} this NodeConfiguration belongs to. Included here for convenience.
 * @param keyColumnsJavaNames from {@code @node(keyColumns)}. Falls back to the table's primary key columns when omitted.
 * @param hasCustomTypeId     whether the {@code @node} directive explicitly sets the {@code typeId} parameter.
 */
public record NodeConfiguration(String typeId, String javaTableName, LinkedList<String> keyColumnsJavaNames, boolean hasCustomTypeId) {
}
