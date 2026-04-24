package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.ConditionFilter;

/**
 * Builder-internal capture of a {@code @condition} directive applied to a single GraphQL argument.
 *
 * <p>Produced during argument classification and attached to the relevant
 * {@link ArgumentRef} variant (scalar, table-input, or plain-input). Never appears on field
 * records — the projection step reads {@link ArgConditionRef} and appends the carried
 * {@link ConditionFilter} to the field's {@code filters} list, honouring the four-state
 * projection table in {@code docs/argument-resolution.md}.
 *
 * <p>{@code override} is the directive's {@code override} flag at the argument level: when
 * {@code true}, the implicit column condition for this argument is replaced by the arg's
 * condition method; when {@code false}, the implicit condition and the arg method are AND-ed
 * together.
 */
public record ArgConditionRef(ConditionFilter filter, boolean override) {}
