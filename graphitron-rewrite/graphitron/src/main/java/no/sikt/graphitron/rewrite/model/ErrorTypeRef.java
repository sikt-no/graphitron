package no.sikt.graphitron.rewrite.model;

/**
 * A reference to a classified {@link GraphitronType.ErrorType} by GraphQL type name.
 *
 * <p>Used wherever a schema construct points at one or more {@code @error} types — most
 * notably the {@link no.sikt.graphitron.rewrite.model.ChildField.ErrorsField errors-field}
 * shape on a payload, where the list flattens single, union-of-{@code @error}, and
 * interface-of-{@code @error} declarations into one ordered sequence. Resolution to the
 * concrete handler list happens through the schema's type map.
 *
 * <p>{@code typeName} is the simple GraphQL type name (e.g. {@code "FilmNotFoundError"}).
 */
public record ErrorTypeRef(String typeName) {}
