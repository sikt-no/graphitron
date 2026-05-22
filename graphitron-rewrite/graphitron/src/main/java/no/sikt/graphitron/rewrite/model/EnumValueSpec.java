package no.sikt.graphitron.rewrite.model;

import graphql.schema.GraphQLEnumValueDefinition;

/**
 * Pre-resolved per-value carrier on {@link GraphitronType.EnumType}. The classifier walks the
 * GraphQL enum once at build time and lifts {@code @field(name:)} into {@link #runtimeValue}; both
 * the schema emitter ({@code EnumTypeGenerator}) and the filter-axis resolver
 * ({@code EnumMappingResolver}) read the resolved string off this record instead of re-evaluating
 * the directive at consumer time.
 *
 * <ul>
 *   <li>{@link #sdlName} — the GraphQL Name lex-rule identifier; what graphql-java's parser matches
 *       on input and what serialization emits on output.</li>
 *   <li>{@link #runtimeValue} — the directive-supplied runtime / DB / upstream string; falls back
 *       to {@link #sdlName} when {@code @field(name:)} is absent.</li>
 *   <li>{@link #description} / {@link #deprecationReason} — pre-extracted, {@code null} when
 *       absent (the emitter checks for null to decide whether to emit the corresponding builder
 *       call).</li>
 *   <li>{@link #source} — the raw {@link GraphQLEnumValueDefinition} retained for
 *       {@code AppliedDirectiveEmitter.applicationsFor(value)} at value-level applied-directive
 *       emission. Consolidating the applied-directive emission against this record is a separate
 *       concern.</li>
 * </ul>
 */
public record EnumValueSpec(
    String sdlName,
    String runtimeValue,
    String description,
    String deprecationReason,
    GraphQLEnumValueDefinition source
) {}
