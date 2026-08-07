package no.sikt.graphitron.rewrite.capture;

import graphql.language.Argument;
import graphql.language.BooleanValue;
import graphql.language.Directive;
import graphql.language.ObjectTypeDefinition;
import graphql.language.StringValue;
import graphql.language.TypeDefinition;
import graphql.language.Value;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.rewrite.NodeDeclaration;
import no.sikt.graphitron.rewrite.schema.federation.FederationSpec;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE_DIRECTIVE_SYNTHESIS;

/**
 * Macro expansion inside the capture walk: the rows an expansion contributes, written through the
 * same doors an authored row goes through, plus the provenance that says an expansion put them
 * there.
 *
 * <p>Capture reads the registry <em>before</em> the pipeline's synthesis rewrites, so an expansion
 * that only ran as a rewrite would leave the store describing a schema no consumer sees. Running it
 * here instead keeps the store's picture effective rather than authored, and keeps the authored
 * picture recoverable as the anti-join against the provenance relations. While a rewrite
 * implementation of the same rule is still live for the legacy pipeline, the two are pinned to each
 * other by the agreement suite rather than by one calling the other; they run at different stages
 * over different representations, and a shared caller would invert the pipeline's ordering.
 *
 * <p>Nothing here rejects. A macro whose precondition does not hold contributes no rows, exactly as
 * the rest of capture declines to throw on author input.
 */
final class MacroCapture {

    private static final String FEDERATION_KEY = "key";
    private static final String KEY_FIELDS_ARG = "fields";
    private static final String KEY_RESOLVABLE_ARG = "resolvable";
    private static final String ID_FIELD = "id";
    private static final String MACRO_FEDERATION_KEY = "FEDERATION_KEY";

    private final FactSink sink;
    private final TypeDefinitionRegistry registry;
    private final NodeDeclaration nodes;
    private final SdlFactCapture sdl;

    MacroCapture(FactSink sink, TypeDefinitionRegistry registry, NodeDeclaration nodes, SdlFactCapture sdl) {
        this.sink = sink;
        this.registry = registry;
        this.nodes = nodes;
        this.sdl = sdl;
    }

    void expand(Map<String, SdlFactCapture.SiteRef> baseSites,
                Map<String, SdlFactCapture.ElementOrdinals> ordinals) {
        expandFederationKeys(baseSites, ordinals);
    }

    /**
     * Federation's node-entity rule: a node type without an {@code @key(fields: "id")} of its own
     * gets one, because federation needs the entity declaration visible in the emitted SDL and a
     * node carries a globally-unique id by definition.
     *
     * <p>The synthesized application is an ordinary application. It transcribes into
     * {@code graphql_type_directive} so the round trip re-emits it, decodes into
     * {@code graphitron_federation_key} so a consumer reads it like an authored key, and is marked
     * only by its provenance row. Its position is the type's declaration site: there is no authored
     * application to point at, and the declaration is what an author would edit to change the
     * outcome.
     */
    private void expandFederationKeys(Map<String, SdlFactCapture.SiteRef> baseSites,
                                      Map<String, SdlFactCapture.ElementOrdinals> ordinals) {
        if (!federationLinked()) {
            return;
        }
        for (TypeDefinition<?> definition : registry.types().values()) {
            if (!(definition instanceof ObjectTypeDefinition object)
                || !nodes.isNodeType(object)
                || hasIdKey(object)) {
                continue;
            }
            SdlFactCapture.SiteRef site = baseSites.get(object.getName());
            if (site == null) {
                // The type's declaration quarantined as a duplicate, so there is no site to hang
                // the application off. The detection is the story; adding a dangling row is not.
                continue;
            }
            int ordinal = ordinals
                .computeIfAbsent(object.getName(), ignored -> new SdlFactCapture.ElementOrdinals())
                .nextTypeDirective(FEDERATION_KEY);
            sdl.captureTypeDirective(site, idKeyDirective(), ordinal, site.location());

            var row = sink.dsl().newRecord(GRAPHITRON_TYPE_DIRECTIVE_SYNTHESIS);
            row.setTypeName(object.getName());
            row.setDirectiveName(FEDERATION_KEY);
            row.setOrdinal(ordinal);
            row.setMacro(MACRO_FEDERATION_KEY);
            sink.add(row);
        }
    }

    /** Whether any schema-level {@code @link} names the federation spec, at any version. */
    private boolean federationLinked() {
        return Stream.concat(
                registry.schemaDefinition().map(schema -> schema.getDirectives("link").stream())
                    .orElseGet(Stream::empty),
                registry.getSchemaExtensionDefinitions().stream()
                    .flatMap(extension -> extension.getDirectives("link").stream()))
            .anyMatch(MacroCapture::isFederationLink);
    }

    private static boolean isFederationLink(Directive directive) {
        Argument url = directive.getArgument("url");
        return url != null
            && url.getValue() instanceof StringValue value
            && value.getValue() != null
            && value.getValue().startsWith(FederationSpec.SPEC_PREFIX);
    }

    /**
     * Whether the type already declares the id key, in which case synthesis stands down and an
     * explicit {@code resolvable: false} keeps the type out of {@code _Entity}. Compound and
     * other-field keys do not count: they are additional alternatives, not the id contract. A field
     * set capture cannot read decodes to nothing, which is how a malformed {@code fields:} argument
     * reaches its detection instead of suppressing synthesis on the strength of a parse failure.
     */
    private static boolean hasIdKey(ObjectTypeDefinition object) {
        for (Directive directive : object.getDirectives(FEDERATION_KEY)) {
            Argument fields = directive.getArgument(KEY_FIELDS_ARG);
            if (fields == null || !(fields.getValue() instanceof StringValue value)) {
                continue;
            }
            if (List.of(ID_FIELD).equals(FieldSetGrammar.paths(value.getValue()))) {
                return true;
            }
        }
        return false;
    }

    private static Directive idKeyDirective() {
        return Directive.newDirective()
            .name(FEDERATION_KEY)
            .argument(Argument.newArgument(KEY_FIELDS_ARG, (Value<?>) new StringValue(ID_FIELD)).build())
            .argument(Argument.newArgument(KEY_RESOLVABLE_ARG, (Value<?>) new BooleanValue(true)).build())
            .build();
    }
}
