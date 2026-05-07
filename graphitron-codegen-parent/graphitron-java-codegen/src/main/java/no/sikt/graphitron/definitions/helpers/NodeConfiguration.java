package no.sikt.graphitron.definitions.helpers;

import graphql.language.TypeDefinition;
import no.sikt.graphitron.definitions.mapping.JOOQMapping;
import no.sikt.graphitron.definitions.mapping.MethodMapping;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphql.directives.GenerationDirective;
import no.sikt.graphql.directives.GenerationDirectiveParam;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.tableFieldsBlock;
import static no.sikt.graphitron.generators.codebuilding.FormatCodeBlocks.tableFieldsWithStaticTableInstanceBlock;
import static no.sikt.graphitron.mappings.TableReflection.*;
import static no.sikt.graphql.directives.DirectiveHelpers.getOptionalDirectiveArgumentString;
import static no.sikt.graphql.directives.DirectiveHelpers.getOptionalDirectiveArgumentStringList;

/**
 * Configuration for a globally identifiable GraphQL object, derived from the {@code @node} and {@code @table} directives.
 *
 * @param typeId              from {@code @node(typeId)}. Defaults to the GraphQL type name when omitted.
 * @param targetTable         the {@link JOOQMapping} for the table associated via {@code @table}.
 *                            Should match the table of the {@link no.sikt.graphitron.definitions.objects.RecordObjectDefinition} this NodeConfiguration belongs to.
 * @param keyColumnsJavaNames from {@code @node(keyColumns)}. Falls back to the table's primary key columns when omitted.
 * @param hasCustomTypeId     whether the {@code @node} directive explicitly sets the {@code typeId} parameter.
 */
public record NodeConfiguration(String typeId, JOOQMapping targetTable, List<String> keyColumnsJavaNames, boolean hasCustomTypeId) {
    public NodeConfiguration(TypeDefinition<?> objectDefinition, JOOQMapping table) {
        this(
                resolveTypeId(objectDefinition),
                table,
                resolveKeyColumns(objectDefinition, table),
                getOptionalDirectiveArgumentString(objectDefinition, GenerationDirective.NODE, GenerationDirectiveParam.TYPE_ID).isPresent()
        );
    }

    private static String resolveTypeId(TypeDefinition<?> objectDefinition) {
        return getOptionalDirectiveArgumentString(objectDefinition, GenerationDirective.NODE, GenerationDirectiveParam.TYPE_ID)
                .orElse(objectDefinition.getName());
    }

    private static List<String> resolveKeyColumns(TypeDefinition<?> objectDefinition, JOOQMapping table) {
        String javaTableName = Optional.ofNullable(table).map(MethodMapping::getName).orElse(null);

        var keyColumns = getOptionalDirectiveArgumentStringList(objectDefinition, GenerationDirective.NODE, GenerationDirectiveParam.KEY_COLUMNS)
                .stream()
                .map(columnName -> getJavaFieldName(javaTableName, columnName).orElse(columnName))
                .toList();

        if (keyColumns.isEmpty()) {
            keyColumns = getPrimaryKeyForTable(javaTableName)
                    .stream()
                    .map(it -> getJavaFieldNamesForKey(javaTableName, it))
                    .flatMap(Collection::stream)
                    .toList();
        }
        return keyColumns;
    }

    /**
     * @return CodeBlock with comma-separated node ID fields using a static table instance.
     * Example: {@code Film.FILM.FILM_ID, Film.FILM.TITLE}
     */
    public CodeBlock nodeIdFieldsWithStaticTableInstanceBlock() {
        return tableFieldsWithStaticTableInstanceBlock(targetTable().getName(), keyColumnsJavaNames());
    }

    /**
     * @return CodeBlock with comma-separated node ID fields referencing the static table field directly.
     * Example: {@code FILM.FILM_ID, FILM.TITLE}
     */
    public CodeBlock nodeIdFieldsWithStaticFieldBlock() {
        return tableFieldsBlock(CodeBlock.of("$N", targetTable().getName()), keyColumnsJavaNames());
    }

    /**
     * @return CodeBlock with comma-separated node ID fields using a table alias variable.
     * Example: {@code _a_film.FILM_ID, _a_film.TITLE}
     */
    public CodeBlock nodeIdFieldsWithTableVariableBlock(String tableVariable) {
        return tableFieldsBlock(CodeBlock.of("$N", tableVariable), keyColumnsJavaNames());
    }
}
