package no.sikt.graphitron.rewrite;

import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInputObjectField;

import java.util.LinkedHashMap;
import java.util.Map;

import static no.sikt.graphitron.rewrite.BuildContext.ARG_NAME;
import static no.sikt.graphitron.rewrite.BuildContext.DIR_FIELD;
import static no.sikt.graphitron.rewrite.BuildContext.argString;

/**
 * Pre-resolved binding map used by {@link ServiceCatalog#reflectServiceMethod} and
 * {@link ServiceCatalog#reflectTableMethod} to bind reflected method parameters to their
 * GraphQL counterparts.
 *
 * <p>Keys are Java parameter names; values are the GraphQL argument (or input-field) names that
 * should bind to them. Identity entries ({@code key.equals(value)}) cover the no-override case;
 * override entries ({@code key != value}) name a Java parameter that differs from the GraphQL
 * argument's own name (R41: {@code @field(name:)} on a {@code @service} / {@code @tableMethod}
 * argument site).
 *
 * <p><b>Why two factory families.</b> {@code @field(name:)} on a filter argument site already
 * has column-binding semantics (the column to bind for the auto-equality predicate, exercised
 * by tests like {@code ARG_CONDITION_ADDITIVE}). The Java-parameter-override axis only applies
 * where there is no column-binding interpretation: {@code @service} / {@code @tableMethod}
 * argument sites, which feed the value directly to a developer method.
 *
 * <p>Concretely:
 * <ul>
 *   <li>{@link #forField} reads {@code @field(name:)} as a Java-parameter override and detects
 *       collisions. Use at {@code @service} / {@code @tableMethod} field-level call sites.</li>
 *   <li>{@code identityFor*} factories build identity-only maps and ignore {@code @field(name:)}.
 *       Use at {@code @condition} call sites (field-level, argument-level, input-field-level)
 *       where the directive's column-binding meaning at the same site already takes precedence.
 *       The typed factory name makes the per-axis decision visible at the call site.</li>
 *   <li>{@link #empty} for path-step {@code @condition} resolution where the method takes no
 *       arg-bound parameters.</li>
 * </ul>
 *
 * <p>{@link #forField} returns a {@link Result} carrying either an {@link Result.Ok} or a
 * {@link Result.Collision} (two arguments of the same field whose effective Java targets
 * collide). {@code identityFor*} factories cannot collide and return {@link ArgBindingMap}
 * directly.
 *
 * <p>The post-reflection typo guard inside {@link ServiceCatalog} only fires for explicit
 * override entries ({@code key != value}); identity entries fall through to the existing
 * per-parameter mismatch error, so the {@code identityFor*} path stays compatible with the
 * pre-R41 behaviour.
 */
record ArgBindingMap(Map<String, String> byJavaName) {

    /** Result of a multi-argument resolution that may detect a collision. */
    sealed interface Result {
        record Ok(ArgBindingMap map) implements Result {}
        record Collision(String message) implements Result {}
    }

    private static final ArgBindingMap EMPTY = new ArgBindingMap(Map.of());

    /** No bindings — used by path-step {@code @condition} resolution where the method takes no args. */
    static ArgBindingMap empty() {
        return EMPTY;
    }

    /**
     * Reads {@code @field(name:)} overrides from each argument of {@code fieldDef}. Two arguments
     * whose effective Java targets collide produce a {@link Result.Collision} naming both
     * arguments and the contested target.
     *
     * <p>Use only at {@code @service} / {@code @tableMethod} field-level call sites; on filter
     * arguments {@code @field(name:)} has column-binding semantics, not Java-parameter override.
     */
    static Result forField(GraphQLFieldDefinition fieldDef) {
        var map = new LinkedHashMap<String, String>();
        for (var arg : fieldDef.getArguments()) {
            String argName = arg.getName();
            String javaTarget = argString(arg, DIR_FIELD, ARG_NAME).orElse(argName);
            String existingArg = map.put(javaTarget, argName);
            if (existingArg != null) {
                return new Result.Collision(
                    "arguments '" + existingArg + "' and '" + argName
                    + "' both target Java parameter '" + javaTarget
                    + "' — adjust @field(name:) so each Java parameter is targeted by at most one argument");
            }
        }
        return new Result.Ok(new ArgBindingMap(Map.copyOf(map)));
    }

    /**
     * Identity binding for every argument of {@code fieldDef} — ignores {@code @field(name:)}.
     * Use at field-level {@code @condition} call sites where {@code @field(name:)} on the
     * arguments carries column-binding semantics (auto-equality), not Java-parameter override.
     */
    static ArgBindingMap identityForField(GraphQLFieldDefinition fieldDef) {
        var map = new LinkedHashMap<String, String>();
        for (var arg : fieldDef.getArguments()) {
            map.put(arg.getName(), arg.getName());
        }
        return new ArgBindingMap(Map.copyOf(map));
    }

    /**
     * Identity binding for one argument — ignores {@code @field(name:)}. Use at argument-level
     * {@code @condition} call sites.
     */
    static ArgBindingMap identityForSingleArg(GraphQLArgument arg) {
        return new ArgBindingMap(Map.of(arg.getName(), arg.getName()));
    }

    /**
     * Identity binding for one input field — ignores {@code @field(name:)}. Use at
     * input-field-level {@code @condition} call sites.
     */
    static ArgBindingMap identityForSingleInputField(GraphQLInputObjectField field) {
        return new ArgBindingMap(Map.of(field.getName(), field.getName()));
    }
}
