package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

/**
 * A field annotated with {@code @multitableReference}.
 *
 * <p>This directive is not supported in record-based output. The
 * {@link no.sikt.graphitron.record.GraphitronSchemaValidator} reports an error for every field
 * classified here.
 */
public record MultitableReferenceField(
    String name,
    SourceLocation location
) implements ChildField {}
