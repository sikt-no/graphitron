package no.sikt.graphitron.rewrite.field;

/**
 * A parsed {@code @defaultOrder} directive: a normalised {@link OrderSpec} combined with a sort
 * direction.
 *
 * <p>{@code direction} is {@code "ASC"} or {@code "DESC"} (the directive's default is {@code "ASC"}).
 */
public record DefaultOrderSpec(OrderSpec spec, String direction) {}
