package no.sikt.graphitron.rewrite.schema.federation;

import graphql.language.Argument;
import graphql.language.BooleanValue;
import graphql.language.Directive;
import graphql.language.ObjectTypeDefinition;
import graphql.language.SDLDefinition;
import graphql.language.StringValue;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.rewrite.schema.input.FederationLinkApplier;

import java.util.ArrayList;
import java.util.List;

/**
 * Build-time synthesis of {@code @key(fields: "id", resolvable: true)} on every {@code @node}
 * type that does not already carry an explicit {@code @key(fields: "id", ...)} directive.
 *
 * <p>Federation requires the {@code @key} directive to be visible in the SDL so the supergraph
 * composer sees the entity declaration. {@code @node} types are entities by definition (they
 * carry a globally-unique id), so the synthesised directive surfaces them in {@code _Entity}
 * without forcing every consumer to redeclare the obvious.
 *
 * <p>Runs in the {@code loadAttributedRegistry} pipeline after {@link FederationLinkApplier}
 * (so the {@code @key} directive declaration is in scope) and before {@code TagApplier}
 * (which iterates {@code @tag}s; ordering is independent but stable).
 *
 * <h3>Opt-out</h3>
 * <p>A consumer who writes {@code @key(fields: "id", resolvable: false)} on a {@code @node}
 * type keeps it out of {@code _Entity}. The "already-present" check honours this: if any
 * {@code @key(fields: "id", ...)} is already on the type, no synthesis fires, and the
 * classify-time alternative carries {@code resolvable: false} through to the dispatcher.
 *
 * <h3>No-op when federation is not in use</h3>
 * <p>If the schema has no federation {@code @link}, the whole step is skipped: synthesising a
 * {@code @key} without its declaration would fail validation in
 * {@code SchemaGenerator.makeExecutableSchema}.
 */
public final class KeyNodeSynthesiser {

    private static final String NODE_DIRECTIVE_NAME = "node";
    private static final String KEY_DIRECTIVE_NAME = "key";
    private static final String KEY_FIELDS_ARG = "fields";
    private static final String KEY_RESOLVABLE_ARG = "resolvable";
    private static final String ID_FIELD = "id";

    private KeyNodeSynthesiser() {}

    /**
     * Walks {@code registry} and attaches a synthesised
     * {@code @key(fields: "id", resolvable: true)} directive to every {@code @node}
     * {@link ObjectTypeDefinition} that does not already carry an explicit
     * {@code @key(fields: "id", ...)}. No-op when no federation {@code @link} is present.
     */
    public static void apply(TypeDefinitionRegistry registry) {
        if (!FederationLinkApplier.hasFederationLink(registry)) {
            return;
        }
        var replacements = new ArrayList<Replacement>();
        for (var def : registry.types().values()) {
            if (def instanceof ObjectTypeDefinition obj
                && hasNodeDirective(obj)
                && !hasIdKeyDirective(obj)) {
                var synthesised = withKey(obj.getDirectives(), idKeyDirective());
                replacements.add(new Replacement(obj, obj.transform(b -> b.directives(synthesised))));
            }
        }
        for (var r : replacements) {
            registry.remove(r.oldDef());
            registry.add(r.newDef());
        }
    }

    private record Replacement(SDLDefinition<?> oldDef, SDLDefinition<?> newDef) {}

    private static boolean hasNodeDirective(ObjectTypeDefinition obj) {
        return obj.getDirectives().stream().anyMatch(d -> NODE_DIRECTIVE_NAME.equals(d.getName()));
    }

    /**
     * Returns {@code true} when the type already carries a {@code @key(fields: "id", ...)}
     * directive, regardless of {@code resolvable:}. Compound keys ({@code "id sku"}) and
     * other-field keys ({@code "sku"}) do not count: synthesis still fires because federation
     * needs the {@code "id"} alternative present for {@code @node}'s implicit contract, and
     * {@code EntityResolutionBuilder} will record both keys as separate alternatives.
     *
     * <p>The fields-argument string is compared after the {@link FederationKeyFieldsParser}
     * has accepted it; a malformed {@code fields:} string here is reported elsewhere by the
     * classifier when it walks {@code @key} directives, so we treat unparseable input as
     * "not the id key" and let synthesis proceed.
     */
    private static boolean hasIdKeyDirective(ObjectTypeDefinition obj) {
        for (var d : obj.getDirectives()) {
            if (!KEY_DIRECTIVE_NAME.equals(d.getName())) continue;
            var fields = d.getArguments().stream()
                .filter(a -> KEY_FIELDS_ARG.equals(a.getName()))
                .findFirst().orElse(null);
            if (fields == null) continue;
            if (!(fields.getValue() instanceof StringValue sv)) continue;
            try {
                var parsed = FederationKeyFieldsParser.parse(sv.getValue());
                if (parsed.size() == 1 && ID_FIELD.equals(parsed.get(0))) {
                    return true;
                }
            } catch (FederationKeyFieldsParser.ParseException ignored) {
                // Malformed fields: arg; classifier will report the error. Don't synthesise on
                // top of it.
            }
        }
        return false;
    }

    private static List<Directive> withKey(List<Directive> existing, Directive key) {
        var combined = new ArrayList<Directive>(existing.size() + 1);
        combined.addAll(existing);
        combined.add(key);
        return combined;
    }

    private static Directive idKeyDirective() {
        return Directive.newDirective()
            .name(KEY_DIRECTIVE_NAME)
            .argument(Argument.newArgument(KEY_FIELDS_ARG, new StringValue(ID_FIELD)).build())
            .argument(Argument.newArgument(KEY_RESOLVABLE_ARG, new BooleanValue(true)).build())
            .build();
    }
}
