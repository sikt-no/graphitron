package no.sikt.graphitron.rewrite.capture;

import graphql.language.Argument;
import graphql.language.BooleanValue;
import graphql.language.Directive;
import graphql.language.FieldDefinition;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.ObjectTypeDefinition;
import graphql.language.SourceLocation;
import graphql.language.StringValue;
import graphql.language.Type;
import graphql.language.TypeDefinition;
import graphql.language.TypeName;
import graphql.language.Value;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.rewrite.NodeDeclaration;
import no.sikt.graphitron.rewrite.model.ConnectionNaming;
import no.sikt.graphitron.rewrite.schema.federation.FederationSpec;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_SYNTHESIS;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE_DECLARATION_SYNTHESIS;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE_DIRECTIVE_SYNTHESIS;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_DECLARATION;

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
    private static final String MACRO_CONNECTION = "CONNECTION";
    private static final String AS_CONNECTION = "asConnection";
    private static final String ARG_CONNECTION_NAME = "connectionName";
    private static final String CONNECTION_SUFFIX = "Connection";
    private static final String EDGE_SUFFIX = "Edge";
    private static final String PAGE_INFO = "PageInfo";
    private static final String OBJECT = "OBJECT";

    /** The Relay shapes' descriptions, matching what the assembled-schema synthesis emits. */
    private static final String DESC_CONNECTION = "A connection to a list of items.";
    private static final String DESC_EDGES = "A list of edges.";
    private static final String DESC_NODES = "A list of nodes.";
    private static final String DESC_PAGE_INFO_FIELD = "Information to aid in pagination.";
    private static final String DESC_TOTAL_COUNT = "Identifies the total count of items in the connection.";
    private static final String DESC_EDGE = "An edge in a connection.";
    private static final String DESC_CURSOR = "A cursor for use in pagination.";
    private static final String DESC_NODE = "The item at the end of the edge.";
    private static final String DESC_PAGE_INFO = "Information about pagination in a connection.";
    private static final String DESC_HAS_NEXT_PAGE = "When paginating forwards, are there more items?";
    private static final String DESC_HAS_PREVIOUS_PAGE = "When paginating backwards, are there more items?";
    private static final String DESC_START_CURSOR = "When paginating backwards, the cursor to continue.";
    private static final String DESC_END_CURSOR = "When paginating forwards, the cursor to continue.";

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

    /** Carriers found during the walk, minted after it so a type's own rows are never interleaved. */
    private final List<Carrier> carriers = new ArrayList<>();
    private final Set<String> minted = new LinkedHashSet<>();
    private int pageInfoSites;

    /**
     * One directive-driven {@code @asConnection} carrier: everything the mint needs, read from the
     * carrier field alone. The element type is a name, not a resolved type, which is what keeps the
     * expansion type-local; nothing here reads the type it names.
     */
    private record Carrier(String parentTypeName, String fieldName, String connectionName,
                           String edgeName, String elementTypeName, boolean itemNullable,
                           SourceLocation position) {}

    void expand(Map<String, SdlFactCapture.SiteRef> baseSites,
                Map<String, SdlFactCapture.ElementOrdinals> ordinals) {
        expandConnections();
        expandFederationKeys(baseSites, ordinals);
    }

    /**
     * The effective type of an output field, called while the walk writes the field's row. A
     * directive-driven {@code @asConnection} carrier returns the Connection it mints; the authored
     * expression it replaces is recorded here rather than lost, so the authored picture stays the
     * anti-join against the provenance relations. Every other field returns its own type unchanged.
     *
     * <p>Only the directive-driven arm rewrites. A carrier whose return type already names a
     * declared Connection is minting nothing and rewriting nothing: those rows are the author's and
     * the walk captured them already.
     */
    Type<?> effectiveFieldType(String parentTypeName, FieldDefinition field) {
        var applications = field.getDirectives(AS_CONNECTION);
        if (applications.isEmpty()) {
            return field.getType();
        }
        Directive directive = applications.get(0);
        Type<?> unwrapped = field.getType() instanceof NonNullType outer ? outer.getType() : field.getType();
        if (!(unwrapped instanceof ListType list)) {
            // @asConnection on something that is not a bare list. The misuse is a detection, and
            // the field keeps the type its author wrote.
            return field.getType();
        }
        boolean itemNullable = !(list.getType() instanceof NonNullType);
        Type<?> element = itemNullable ? list.getType() : ((NonNullType) list.getType()).getType();
        if (!(element instanceof TypeName elementName)) {
            return field.getType();
        }
        String connectionName = connectionName(parentTypeName, field, directive);
        carriers.add(new Carrier(parentTypeName, field.getName(), connectionName,
            connectionName.replace(CONNECTION_SUFFIX, EDGE_SUFFIX), elementName.getName(), itemNullable,
            position(directive, field)));

        var row = sink.dsl().newRecord(GRAPHITRON_FIELD_SYNTHESIS);
        row.setTypeName(parentTypeName);
        row.setFieldName(field.getName());
        row.setMacro(MACRO_CONNECTION);
        row.setAuthoredTypeSdl(SdlFactCapture.Wrapping.of(field.getType()).typeSdl());
        sink.add(row);

        return new TypeName(connectionName);
    }

    /** The deprecated {@code connectionName:} override wins; otherwise the derived name. */
    private static String connectionName(String parentTypeName, FieldDefinition field, Directive directive) {
        var override = directive.getArgument(ARG_CONNECTION_NAME);
        if (override != null && override.getValue() instanceof StringValue value
                && value.getValue() != null && !value.getValue().isEmpty()) {
            return value.getValue();
        }
        return ConnectionNaming.defaultConnectionName(parentTypeName, field.getName());
    }

    /**
     * Mints the Relay machinery each carrier implies: a Connection and an Edge per carrier, and one
     * shared PageInfo. A minted type enters as an ordinary declaration site at the causing
     * application's position, and its fields hang off that site through the ordinary declaration
     * reference, so nothing downstream needs to know a macro was involved to read them.
     *
     * <p>A name a carrier would mint but an author already declared is left to the author: capture
     * is first-wins and the collision is the author's to resolve, so the site claim simply loses and
     * the walk's rows stand. The primary key stays a capture-bug detector, never an author-triggerable
     * throw.
     */
    private void expandConnections() {
        for (Carrier carrier : carriers) {
            mintConnection(carrier);
            mintEdge(carrier);
            mintPageInfo(carrier);
        }
    }

    private void mintConnection(Carrier carrier) {
        if (!mintType(carrier.connectionName(), DESC_CONNECTION, carrier, 0, false)) {
            return;
        }
        var fields = new MintedFields(carrier.connectionName(), carrier.position());
        fields.add("edges", DESC_EDGES,
            nonNull(list(nonNull(new TypeName(carrier.edgeName())))));
        fields.add("nodes", DESC_NODES, nonNull(list(item(carrier))));
        fields.add("pageInfo", DESC_PAGE_INFO_FIELD, nonNull(new TypeName(PAGE_INFO)));
        // Nullable like the connection's other aggregate: a skipped count degrades to null rather
        // than bubbling a failure through the connection.
        fields.add("totalCount", DESC_TOTAL_COUNT, new TypeName("Int"));
    }

    private void mintEdge(Carrier carrier) {
        if (!mintType(carrier.edgeName(), DESC_EDGE, carrier, 0, false)) {
            return;
        }
        var fields = new MintedFields(carrier.edgeName(), carrier.position());
        fields.add("cursor", DESC_CURSOR, nonNull(new TypeName("String")));
        fields.add("node", DESC_NODE, item(carrier));
    }

    /**
     * PageInfo is shared machinery, so the first carrier defines it and every later carrier adds an
     * empty extension site at its own position. The site count is the carrier multiplicity, which is
     * what lets an incremental refresh refcount the shared type instead of guessing when it is
     * orphaned.
     */
    private void mintPageInfo(Carrier carrier) {
        int mergeOrdinal = pageInfoSites;
        boolean extension = mergeOrdinal > 0;
        if (!mintType(PAGE_INFO, extension ? null : DESC_PAGE_INFO, carrier, mergeOrdinal, extension)) {
            return;
        }
        pageInfoSites++;
        if (extension) {
            return;
        }
        var fields = new MintedFields(PAGE_INFO, carrier.position());
        fields.add("hasNextPage", DESC_HAS_NEXT_PAGE, nonNull(new TypeName("Boolean")));
        fields.add("hasPreviousPage", DESC_HAS_PREVIOUS_PAGE, nonNull(new TypeName("Boolean")));
        fields.add("startCursor", DESC_START_CURSOR, new TypeName("String"));
        fields.add("endCursor", DESC_END_CURSOR, new TypeName("String"));
    }

    /**
     * Writes the existence row (first site only), the declaration site, and its provenance. Returns
     * false when the site was already claimed, which is how an author-declared name wins.
     */
    private boolean mintType(String typeName, String description, Carrier carrier,
                             int mergeOrdinal, boolean extension) {
        if (registry.types().containsKey(typeName) || registry.scalars().containsKey(typeName)) {
            // The author declared the name this carrier would mint. Their declaration is already
            // captured, and adding machinery fields to it would silently merge two types nobody
            // asked to merge; the misuse is a detection, so the mint simply stands down.
            return false;
        }
        SourceLocation at = carrier.position();
        if (!sink.claim(GRAPHQL_TYPE_DECLARATION, typeName,
                at.getSourceName(), at.getLine(), at.getColumn())) {
            return false;
        }
        if (minted.add(typeName) && sink.claim(GRAPHQL_TYPE, typeName)) {
            var type = sink.dsl().newRecord(GRAPHQL_TYPE);
            type.setTypeName(typeName);
            type.setKind(OBJECT);
            type.setDescription(description);
            sink.add(type);
        }
        var declaration = sink.dsl().newRecord(GRAPHQL_TYPE_DECLARATION);
        declaration.setTypeName(typeName);
        declaration.setSourceName(at.getSourceName());
        declaration.setSourceLine(at.getLine());
        declaration.setSourceColumn(at.getColumn());
        declaration.setMergeOrdinal(mergeOrdinal);
        declaration.setIsExtension(extension);
        declaration.setKind(OBJECT);
        sink.add(declaration);

        var provenance = sink.dsl().newRecord(GRAPHITRON_TYPE_DECLARATION_SYNTHESIS);
        provenance.setTypeName(typeName);
        provenance.setSourceName(at.getSourceName());
        provenance.setSourceLine(at.getLine());
        provenance.setSourceColumn(at.getColumn());
        provenance.setMacro(MACRO_CONNECTION);
        provenance.setCarrierTypeName(carrier.parentTypeName());
        provenance.setCarrierFieldName(carrier.fieldName());
        sink.add(provenance);
        return true;
    }

    /** Writes a minted type's fields against the site the mint just contributed. */
    private final class MintedFields {
        private final String typeName;
        private final SourceLocation at;
        private int ordinal;

        MintedFields(String typeName, SourceLocation at) {
            this.typeName = typeName;
            this.at = at;
        }

        void add(String fieldName, String description, Type<?> type) {
            if (!sink.claim(GRAPHQL_FIELD, typeName, fieldName)) {
                return;
            }
            var wrapping = SdlFactCapture.Wrapping.of(type);
            var record = sink.dsl().newRecord(GRAPHQL_FIELD);
            record.setTypeName(typeName);
            record.setFieldName(fieldName);
            record.setOrdinal(ordinal++);
            record.setDeclarationLine(at.getLine());
            record.setDeclarationColumn(at.getColumn());
            record.setSourceName(at.getSourceName());
            record.setSourceLine(at.getLine());
            record.setSourceColumn(at.getColumn());
            record.setTypeSdl(wrapping.typeSdl());
            record.setNamedType(wrapping.namedType());
            record.setNonNull(wrapping.nonNull());
            record.setIsList(wrapping.isList());
            record.setItemNonNull(wrapping.itemNonNull());
            record.setDescription(description);
            sink.add(record);
        }
    }

    /** The element reference a Connection's {@code nodes} and an Edge's {@code node} share. */
    private static Type<?> item(Carrier carrier) {
        TypeName element = new TypeName(carrier.elementTypeName());
        return carrier.itemNullable() ? element : nonNull(element);
    }

    private static NonNullType nonNull(Type<?> type) {
        return NonNullType.newNonNullType(type).build();
    }

    private static ListType list(Type<?> type) {
        return ListType.newListType(type).build();
    }

    /**
     * Where a synthesized row points. The causing application if it is located, the carrier field
     * otherwise; both are lines the author can edit, which is the whole point of inheriting a
     * position rather than inventing one.
     */
    private static SourceLocation position(Directive directive, FieldDefinition field) {
        SourceLocation own = directive.getSourceLocation();
        return own != null && own.getSourceName() != null ? own : field.getSourceLocation();
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
            if (List.of(List.of(ID_FIELD)).equals(FieldSetGrammar.paths(value.getValue()))) {
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
