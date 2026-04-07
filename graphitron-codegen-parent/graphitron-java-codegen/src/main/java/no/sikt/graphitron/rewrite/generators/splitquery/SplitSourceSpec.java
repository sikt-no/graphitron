package no.sikt.graphitron.rewrite.generators.splitquery;

import java.util.List;

/**
 * All data needed to generate the {@code toSourceRows} method for one {@code @splitQuery}
 * {@link no.sikt.graphitron.rewrite.field.ChildField.TableField}.
 *
 * <p>{@code parentTypeName} is the name of the containing GraphQL type (e.g. {@code "Language"}),
 * used together with {@code fieldName} to form the generated class name
 * (e.g. {@code LanguageFilmsDerivedSource}).
 *
 * <p>{@code fieldName} is the GraphQL field name (e.g. {@code "films"}).
 *
 * <p>{@code parentTableJavaFieldName} is the Java field name for the parent table in the jOOQ
 * {@code Tables} class (e.g. {@code "LANGUAGE"}), used to qualify column references such as
 * {@code LANGUAGE.LANGUAGE_ID}.
 *
 * <p>{@code keyFields} is the ordered list of FK key-column mappings that form the derived source
 * table. These are the parent-side fields of the foreign key that joins the child table back to
 * the parent — the columns to extract from each source record.
 */
public record SplitSourceSpec(
    String parentTypeName,
    String fieldName,
    String parentTableJavaFieldName,
    List<SplitSourceKeyFieldSpec> keyFields
) {}
