package no.sikt.graphitron.rewrite.selection;

import java.util.List;

/**
 * One {@code key: dotted.path} entry parsed from a comma-separated list.
 *
 * <p>The lexer accepts dots inside name tokens, so {@code input.kvotesporsmalId} arrives as a
 * single name; {@link GraphQLSelectionParser#parseEntries(String)} splits on {@code .} to
 * produce the segment list. {@code segments.get(0)} is the head; subsequent entries are tail
 * segments.
 *
 * <p>Both {@code @experimental_constructType(selection: ...)} (R69) and {@code argMapping}
 * path expressions (R84) consume this shape: R69 binds the head + tail to a column reference;
 * R84 walks the head + tail through the GraphQL input-type tree at the directive's scope.
 */
public record ParsedEntry(String key, List<String> segments) {}
