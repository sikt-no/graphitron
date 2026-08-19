package no.sikt.graphitron.rewrite.capture;

import graphql.language.Directive;
import graphql.language.FieldDefinition;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.SourceLocation;
import graphql.language.StringValue;
import graphql.language.Type;
import graphql.language.TypeName;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.rewrite.model.ConnectionNaming;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_SYNTHESIS;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE_DECLARATION_SYNTHESIS;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_DECLARATION;

/**
 * The {@code @asConnection} expansion inside the capture walk: the rows it contributes, written
 * through the same doors an authored row goes through, plus the provenance that says an expansion
 * put them there.
 *
 * <p>Capture reads the registry <em>before</em> the pipeline's synthesis rewrites, so an expansion
 * that only ran as a rewrite would leave the store describing a schema no consumer sees. Running it
 * here makes the expansion a derivation inside the capture walk: its rows go through capture's own
 * doors, and the provenance relations record what it contributed. What that leaves recoverable
 * splits by kind. For the declaration sites it <em>adds</em>
 * ({@code graphitron_type_declaration_synthesis}) the transcription is the anti-join against the
 * provenance. For the field type it <em>rewrites</em> ({@code graphitron_field_synthesis}) it is
 * not: the expression the field was written with survives only in that relation's own text column,
 * and no anti-join recovers it.
 * While a rewrite
 * implementation of the same rule is still live for the legacy pipeline, the two are pinned to each
 * other by the agreement suite rather than by one calling the other; they run at different stages
 * over different representations, and a shared caller would invert the pipeline's ordering.
 *
 * <p>This is the one expansion that may run here, and the constraint is the reason rather than an
 * accident of what got written: an expansion inside a crawler may read only the corpus that crawler
 * is responsible for. {@code @asConnection} passes, its element type entering as a name that
 * nothing here resolves. Federation's key synthesis does not, since nodehood conjoins the SDL claim
 * with metadata a generated jOOQ class publishes, so that rule is a derivation over the captured
 * facts of both corpora; see the fact model's stratum discipline in
 * {@code docs/architecture/explanation/fact-model.adoc}.
 *
 * <p>Nothing here rejects. A macro whose precondition does not hold contributes no rows, exactly as
 * the rest of capture declines to throw on author input.
 */
final class MacroCapture {

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

    MacroCapture(FactSink sink, TypeDefinitionRegistry registry) {
        this.sink = sink;
        this.registry = registry;
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

    void expand() {
        expandConnections();
    }

    /**
     * The expanded type of an output field, called while the walk writes the field's row. A
     * directive-driven {@code @asConnection} carrier returns the Connection it mints, and the
     * expression the field was written with is recorded in {@code graphitron_field_synthesis} rather
     * than lost, that relation being the only place it survives. Every other field returns its own
     * type unchanged.
     *
     * <p>Only the directive-driven arm rewrites. A carrier whose return type already names a
     * declared Connection is minting nothing and rewriting nothing: those rows are the author's and
     * the walk captured them already.
     */
    Type<?> expandedFieldType(String parentTypeName, FieldDefinition field) {
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
}
