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
 * <p>Two consumers share this shape: {@code @experimental_constructType(selection: ...)} binds
 * the head + tail to a column reference; {@code argMapping} dot-path expressions walk the head
 * + tail through the GraphQL input-type tree at the directive's scope.
 */
public record ParsedEntry(String key, List<String> segments) {}
