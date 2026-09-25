package no.sikt.graphitron.model.capture.sdl;

import no.sikt.graphitron.model.schema.SiteRef;
import graphql.language.AstPrinter;
import graphql.language.Description;
import graphql.language.Directive;
import no.sikt.graphitron.model.catalog.SchemaCoordinateSyntax;
import graphql.language.DirectiveDefinition;
import graphql.language.EnumTypeDefinition;
import graphql.language.EnumValueDefinition;
import graphql.language.FieldDefinition;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.InterfaceTypeDefinition;
import graphql.language.ListType;
import graphql.language.Node;
import graphql.language.NonNullType;
import graphql.language.ObjectTypeDefinition;
import graphql.language.OperationTypeDefinition;
import graphql.language.ScalarTypeDefinition;
import graphql.language.SourceLocation;
import graphql.language.Type;
import graphql.language.TypeDefinition;
import graphql.language.TypeName;
import graphql.language.UnionTypeDefinition;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.model.capture.graphitron.GraphitronFactCapture;
import no.sikt.graphitron.model.grammar.NodeDeclaration;
import no.sikt.graphitron.model.schema.SchemaLoader;
import no.sikt.graphitron.model.schema.input.SchemaInput;
import no.sikt.graphitron.model.schema.input.SchemaInputAttribution;
import no.sikt.graphitron.model.schema.input.SchemaSource;
import no.sikt.graphitron.model.schema.input.TagLinkSynthesiser;
import no.sikt.graphitron.model.sink.FactSink;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_LOCATION;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_APPLICATION;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_APPLICATION_ARG;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ENUM_VALUE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHQL_IMPLEMENTS_INTERFACE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_UNION_MEMBER;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ROOT_OPERATION;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_DECLARATION;
import static no.sikt.graphitron.model.Tables.STORE_SOURCE;

/**
 * The SDL capture load: one walk over the {@link TypeDefinitionRegistry}, filling the
 * {@code graphql_} family and the as-written half of {@code graphitron_} beside it. Two
 * vocabularies over one document rather than two passes over one corpus: most of what the walk
 * writes is in the document's own words and the rest is in graphitron's, and a relation whose rows
 * are a function of one document has this walk as its owner either way.
 *
 * <p>What it does not write is the resolved half, the relations {@link GraphitronFactCapture}'s
 * stages produce by joining an entry against the catalog or against each other. Those could not run
 * inside a walk at all: a callback sees one directive and no store, which is fatal to a resolution
 * and irrelevant to a relation that joins nothing. The line between the two halves is where the
 * rows come from, not which vocabulary names them.
 *
 * <p>The {@code graphql_} family is a total transcription of the document. Every declaration, every
 * directive definition, and every directive application is a row, graphitron's own namespace
 * included; nothing is withheld on the grounds that an emitter will later strip it, because that is
 * a question about {@code source_name} and belongs where the emitting happens. An application that
 * also carries meaning gets a second, decoded row rather than moving families, which is why
 * federation's {@code @key} needs no special case.
 *
 * <p>The registry, not the assembled schema, is the source, and that choice carries the load's
 * character. The registry validates nothing: it retains undeclared directives, unknown argument
 * names, wrong-typed literals, missing required arguments, and duplicate declarations without
 * error. Capture is therefore <em>tolerant by construction</em> and never throws on author input;
 * a duplicate element loses nothing, both occurrences being rows of the entry stratum already,
 * keyed where each was written. An argument's literal is transcribed exactly
 * as {@code AstPrinter} renders it, for a reader that wants the authored text; the decode beside it
 * reads the parsed value rather than that rendering, and what it makes of a literal that does not
 * fit its declared shape is its own quarantine.
 *
 * <p>Capture is also <b>type-local</b>: every row's content is a function of its own type's
 * declaration sites and nothing else. Nothing here reads across types and no verdict is computed
 * during a file's walk, which is what keeps a single file the unit of an incremental refresh.
 * That is a review rule on this code, not something a test can catch after the fact.
 *
 * @deprecated the traversal the decode rides on; it goes when the decode reads the
     *     entry stratum instead of a registry.
 */
@Deprecated
// Acknowledges FactSink's deprecation. This is the decode, which is what the sink is for
// and what it goes with; the suppression is here so the list of them is the list of what
// still writes through it.
@SuppressWarnings("deprecation")
public final class SdlFactCapture {

    /** {@code store_source.source_kind}'s schema-file arm; the classpath arms are the scan's. */
    private static final String SCHEMA_FILE = "SCHEMA_FILE";

    /** The declaration form a site wrote, in the vocabulary the {@code kind} CHECK constraints fix. */
    private static final String OBJECT = "OBJECT";
    private static final String INTERFACE = "INTERFACE";
    private static final String UNION = "UNION";
    private static final String ENUM = "ENUM";
    private static final String INPUT_OBJECT = "INPUT_OBJECT";
    private static final String SCALAR = "SCALAR";

    private final FactSink sink;
    private final TypeDefinitionRegistry registry;

    /** The element anchors, and the first-wins claim on every one of them. */
    private final SdlCoordinates coordinates;





    /** Per type, the ordinals its elements are numbered by as the walk meets them. */
    private final Map<String, ElementOrdinals> ordinalsByType = new LinkedHashMap<>();

    /**
     * The as-written half of {@code graphitron_}, decoded from the applications this walk is
     * holding. It is a second vocabulary over one document rather than a second pass over a corpus:
     * the walk already understands what a graphitron directive means well enough to unpack it, and
     * a relation whose rows are a function of one document has this walk as its owner by the same
     * rule that gives it the transcription.
     */
    private final GraphitronFactCapture decode;

    private SdlFactCapture(FactSink sink, TypeDefinitionRegistry registry) {
        this.sink = sink;
        this.registry = registry;
        this.coordinates = new SdlCoordinates(sink);
        this.decode = GraphitronFactCapture.decodingInto(sink);
    }

    /**
     * Decodes the applications of {@code registry} into the as-written half of
     * {@code graphitron_}, buffering into {@code sink}; the caller flushes.
     *
     * <p>A walk with nothing of its own left to write. Every relation it once held is written by
     * the gatherers that read the documents, and what remains is the traversal the decode rides
     * on: it visits each directive application in the merged corpus and hands it over. It leaves
     * when the decode reads the entry stratum instead of a registry.
     */
    public static void capture(FactSink sink, TypeDefinitionRegistry registry) {
        new SdlFactCapture(sink, registry).run();
    }

    private void run() {
        captureDirectiveDefinitions();
        captureSchema();
        captureTypes();
        writePolyMembers();
    }


    private static <T extends TypeDefinition<?>> void addExtensionSources(
            java.util.Set<String> names, java.util.Map<String, List<T>> extensions) {
        extensions.values().forEach(sites ->
            sites.forEach(site -> addSource(names, site.getSourceLocation())));
    }

    private static void addSource(java.util.Set<String> names, SourceLocation location) {
        if (location != null && location.getSourceName() != null) {
            names.add(location.getSourceName());
        }
    }

    // ---------------------------------------------------------------- directive definitions

    /** The directives the specification gives every schema; see {@link #captureDirectiveDefinitions}. */
    private static final List<String> SPECIFIED_DIRECTIVES =
        List.of("deprecated", "include", "oneOf", "skip", "specifiedBy");

    /**
     * Records what each directive <em>is</em>, for every definition the registry holds and for the
     * five the specification gives every schema. Graphitron's own bundled definitions are rows too,
     * so an application's directive name always resolves to a definition and reading a repeatable
     * flag or an argument default stays a join. Which definitions an emitter re-declares is a
     * question about their {@code source_name}, answered where the emitting happens.
     */
    private void captureDirectiveDefinitions() {
        // The five the specification gives every schema, which no document declares and the
        // registry therefore does not list. They are claimed because an author can apply them and
        // the anchor writes an existence row for each; graphitron applies one of them itself.
        for (String specified : SPECIFIED_DIRECTIVES) {
            sink.claim(GRAPHQL_DIRECTIVE, specified);
        }
        for (DirectiveDefinition definition : registry.getDirectiveDefinitions().values()) {
            String name = definition.getName();
            if (!sink.claim(GRAPHQL_DIRECTIVE, name)) {
                continue;
            }

            for (var location : definition.getDirectiveLocations()) {
                if (!sink.claim(GRAPHQL_DIRECTIVE_LOCATION, name, location.getName())) {
                    continue;
                }
            }

            int ordinal = 0;
            for (InputValueDefinition argument : definition.getInputValueDefinitions()) {
                String argumentName = argument.getName();
                if (!sink.claim(GRAPHQL_DIRECTIVE_ARGUMENT, name, argumentName)) {
                    continue;
                }
                var wrapping = Wrapping.of(argument.getType());
                // A formal argument of a directive definition is a coordinate the specification
                // spells and graphql_element anchors, so an application written on one is a row
                // like any other. It reached no relation while the applications were kept per
                // site, there being no relation shaped for this one; keying them at the
                // coordinate is what makes the site ordinary rather than absent.
                captureDirectiveArgumentDirectives(name, argumentName, argument.getDirectives());
            }
        }
    }

    /** The applications written on one formal argument of a directive definition. */
    private void captureDirectiveArgumentDirectives(String directiveName, String argumentName,
                                                    List<Directive> directives) {
        var ordinals = new LinkedHashMap<String, Integer>();
        for (Directive directive : directives) {
            int ordinal = ordinals.merge(directive.getName(), 0, (old, ignored) -> old + 1);
            String coordinate =
                SchemaCoordinateSyntax.ofDirectiveArgument(directiveName, argumentName);
            if (!sink.claim(GRAPHQL_DIRECTIVE_APPLICATION, coordinate, directive.getName(),
                    ordinal)) {
                continue;
            }
            for (var argument : directive.getArguments()) {
                if (!sink.claim(GRAPHQL_DIRECTIVE_APPLICATION_ARG, coordinate,
                        directive.getName(), ordinal, argument.getName())) {
                    continue;
                }
            }
        }
    }

    // ---------------------------------------------------------------- the schema definition

    /**
     * Records the root-operation bindings and the schema definition's own directive applications
     * ({@code @link}, the federation opt-in, lives here). The base definition and every schema
     * extension contribute; a re-binding of one operation cannot reach capture, since the registry
     * rejects it at parse. A document with no schema definition binds its roots by the name
     * convention, and those bindings are rows too, with all three position columns null exactly as
     * the relation's comments state: the relation is total over the effective roots, which is what
     * lets the reachability derivation seed from it without re-deriving the convention.
     */
    private void captureSchema() {
        var definitions = new ArrayList<Node<?>>();
        registry.schemaDefinition().ifPresent(definitions::add);
        definitions.addAll(registry.getSchemaExtensionDefinitions());

        var ordinals = new LinkedHashMap<String, Integer>();
        for (Node<?> definition : definitions) {
            List<OperationTypeDefinition> operations = definition instanceof graphql.language.SchemaDefinition schema
                ? schema.getOperationTypeDefinitions()
                : ((graphql.language.SchemaExtensionDefinition) definition).getOperationTypeDefinitions();
            for (OperationTypeDefinition operation : operations) {
                String slot = operation.getName().toUpperCase(Locale.ROOT);
                if (!sink.claim(GRAPHQL_ROOT_OPERATION, slot)) {
                    continue;
                }
            }
            List<Directive> directives = definition instanceof graphql.language.SchemaDefinition schema
                ? schema.getDirectives()
                : ((graphql.language.SchemaExtensionDefinition) definition).getDirectives();
            for (Directive directive : directives) {
                int ordinal = ordinals.merge(directive.getName(), 0, (old, ignored) -> old + 1);
                String coordinate = SchemaCoordinateSyntax.ofSchema();
                if (!sink.claim(GRAPHQL_DIRECTIVE_APPLICATION, coordinate, directive.getName(),
                        ordinal)) {
                    continue;
                }
                for (var argument : directive.getArguments()) {
                    if (!sink.claim(GRAPHQL_DIRECTIVE_APPLICATION_ARG, coordinate,
                            directive.getName(), ordinal, argument.getName())) {
                        continue;
                    }
                }
                decode.captureSchemaDirective(directive, ordinal);
            }
        }
        if (registry.schemaDefinition().isEmpty()) {
            captureConventionRoots();
        }
    }

    /**
     * The name-convention arm of {@link #captureSchema}: with no schema definition, an object
     * type named for an operation is that operation's root. Runs after the explicit bindings (a
     * schema extension may bind an operation even without a base definition, and the claim guard
     * keeps the spelled binding); the row's positions are null because no SDL line spells the
     * binding.
     */
    private void captureConventionRoots() {
        for (String operation : List.of("QUERY", "MUTATION", "SUBSCRIPTION")) {
            String typeName = switch (operation) {
                case "QUERY" -> "Query";
                case "MUTATION" -> "Mutation";
                default -> "Subscription";
            };
            if (registry.getTypeOrNull(typeName, ObjectTypeDefinition.class) == null) {
                continue;
            }
            if (!sink.claim(GRAPHQL_ROOT_OPERATION, operation)) {
                continue;
            }
        }
    }

    // ---------------------------------------------------------------- types and their elements

    /**
     * Walks every named type once. A type's declaration sites are its base definition (merge
     * ordinal 0) followed by its extensions in document order, and the elements each site
     * contributes are captured while standing on that site, which is what makes every declared
     * foreign key structural and the walk order-free.
     */
    private void captureTypes() {
        for (Map.Entry<String, List<Site>> entry : sitesByType().entrySet()) {
            String typeName = entry.getKey();
            List<Site> sites = entry.getValue();
            Site first = sites.get(0);

            coordinates.claimType(typeName);

            var elements = ordinalsByType.computeIfAbsent(typeName, ignored -> new ElementOrdinals());
            for (int mergeOrdinal = 0; mergeOrdinal < sites.size(); mergeOrdinal++) {
                Site site = sites.get(mergeOrdinal);
                if (site.location() == null) {
                    // An engine-provided element no SDL line declares (a built-in scalar). It has
                    // an existence row and no declaration site, so its members have nowhere to
                    // hang; the built-ins declare none anyway.
                    continue;
                }
                captureSite(typeName, site, mergeOrdinal, elements);
            }
        }
    }

    /** Per-type running ordinals; declaration order across sites is the merge order. */
    static final class ElementOrdinals {
        int field;
        int enumValue;
        int unionMember;
        /**
         * Last-used application ordinal per type-level directive name. Type-wide rather than
         * per-site because the key it feeds is, so a repeatable directive applied once on the base
         * and once on an extension numbers 0 and 1 instead of colliding at 0.
         */
        final Map<String, Integer> typeDirective = new LinkedHashMap<>();

        int nextTypeDirective(String name) {
            return typeDirective.merge(name, 0, (old, ignored) -> old + 1);
        }
    }

    private void captureSite(String typeName, Site site, int mergeOrdinal, ElementOrdinals ordinals) {
        SourceLocation location = site.location();
        if (!sink.claim(GRAPHQL_TYPE_DECLARATION, typeName,
                location.getSourceName(), location.getLine(), location.getColumn())) {
            return;
        }

        var siteRef = new SiteRef(typeName, location);
        captureTypeDirectives(siteRef, site.definition().getDirectives(), ordinals);

        switch (site.definition()) {
            case ObjectTypeDefinition object -> {
                captureImplements(siteRef, object.getImplements());
                captureFields(siteRef, object.getFieldDefinitions(), ordinals);
            }
            case InterfaceTypeDefinition iface -> {
                captureImplements(siteRef, iface.getImplements());
                captureFields(siteRef, iface.getFieldDefinitions(), ordinals);
            }
            case InputObjectTypeDefinition input ->
                captureInputFields(siteRef, input.getInputValueDefinitions(), ordinals);
            case EnumTypeDefinition enumType ->
                captureEnumValues(siteRef, enumType.getEnumValueDefinitions(), ordinals);
            case UnionTypeDefinition union ->
                captureUnionMembers(siteRef, union.getMemberTypes(), ordinals);
            case ScalarTypeDefinition ignored -> { /* scalars declare no members */ }
            default -> throw new IllegalStateException(
                "unexpected type definition at capture: " + site.definition().getClass());
        }
    }

    /**
     * One membership the document spelled, held until every site has been read.
     *
     * <p>Buffered rather than written where it is found, because one of the two arms cannot state
     * its own order yet: a union lists its members in one place and numbers them as the walk passes,
     * while an interface's implementors are declared apart from it and apart from each other, so the
     * first of them is not known until the last site is read. The claim and the quarantine stay at
     * the site, which is where a duplicate is a fact about a document and where the offending node
     * is in hand; only the row waits.
     *
     * @param position the union arm's authored ordinal, null on the interface arm, which
     *        {@link #writePolyMembers()} settles
     */
    private record PolyMemberSite(String containerKind, String containerName, String memberTypeName,
                                  Integer position, String declaredOn, SourceLocation declaration,
                                  SourceLocation own) {}

    private final List<PolyMemberSite> polyMembers = new ArrayList<>();

    private void captureImplements(SiteRef site, List<?> interfaces) {
        // graphql-java declares these lists over the raw Type; an implements entry and a union
        // member are always a bare TypeName, so the element type is narrowed here instead.
        for (Object element : interfaces) {
            TypeName type = (TypeName) element;
            String name = type.getName();
            // Keyed type-first, which is the relation's own key now that the two arms are two
            // relations: one interface named twice by one type is the duplicate to catch.
            if (!sink.claim(GRAPHQL_IMPLEMENTS_INTERFACE, site.typeName(), name)) {
                continue;
            }
            polyMembers.add(new PolyMemberSite("INTERFACE", name, site.typeName(), null,
                site.typeName(), site.location(), type.getSourceLocation()));
        }
    }

    private void captureUnionMembers(SiteRef site, List<?> members, ElementOrdinals ordinals) {
        for (Object element : members) {
            TypeName type = (TypeName) element;
            String name = type.getName();
            if (!sink.claim(GRAPHQL_UNION_MEMBER, site.typeName(), name)) {
                continue;
            }
            polyMembers.add(new PolyMemberSite("UNION", site.typeName(), name,
                ordinals.unionMember++, site.typeName(), site.location(),
                type.getSourceLocation()));
        }
    }

    /**
     * Writes every membership, settling the interface arm's order first.
     *
     * <p>Source order is what the column means, so the sort is over the coordinate each row already
     * carries: the declaring site's file, then its line and column, then the implementing type's
     * name as the tie-break no two rows of one container reach. This is the pass the schema used to
     * do at read time with a window over the whole partition, which no correlated probe can prune
     * and which therefore ran once per driving row per reader.
     *
     * <p>The two arms do not agree on where the order starts, the union numbering from zero and this
     * pass from one. That is inherited and is preserved deliberately: no consumer reads the absolute
     * value, but the mapping-constant fingerprint digests a list in this order, so rebasing is a
     * change to emitted names rather than a tidy-up.
     */
    private void writePolyMembers() {
        var byContainer = new LinkedHashMap<String, List<PolyMemberSite>>();
        for (PolyMemberSite member : polyMembers) {
            if (member.position() == null) {
                byContainer.computeIfAbsent(member.containerName(), k -> new ArrayList<>()).add(member);
            }
        }
        var settled = new HashMap<PolyMemberSite, Integer>();
        for (List<PolyMemberSite> members : byContainer.values()) {
            members.sort(Comparator
                .comparing((PolyMemberSite m) -> m.declaration().getSourceName(),
                    Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparingInt(m -> m.declaration().getLine())
                .thenComparingInt(m -> m.declaration().getColumn())
                .thenComparing(PolyMemberSite::memberTypeName));
            for (int i = 0; i < members.size(); i++) {
                settled.put(members.get(i), i + 1);
            }
        }
        // Two relations now, and the arm decides which: a union declares who it admits and a type
        // declares which interfaces it answers to, so the declaring end differs and with it the key.
        // graphql_poly_member is the view over both, for readers that take either.
        for (PolyMemberSite member : polyMembers) {
            int position = member.position() != null ? member.position() : settled.get(member);
            if ("UNION".equals(member.containerKind())) {
            } else {
            }
        }
    }

    private void captureEnumValues(SiteRef site, List<EnumValueDefinition> values, ElementOrdinals ordinals) {
        for (EnumValueDefinition value : values) {
            coordinates.claimEnumValue(site.typeName(), value.getName());
        }
    }

    private void captureFields(SiteRef site, List<FieldDefinition> fields, ElementOrdinals ordinals) {
        for (FieldDefinition field : fields) {
            String name = field.getName();
            if (!coordinates.claimOutputField(site.typeName(), name)) {
                continue;
            }
            // The expression the author wrote, and never an expansion's replacement for it. What
            // a macro rewrites a carrier to is a row of the graphitron family's own, so this
            // relation stays a transcription and both readings survive.
            var wrapping = Wrapping.of(field.getType());

            captureFieldDirectives(site.typeName(), name, field.getDirectives(), false);
            captureArguments(site.typeName(), name, field.getInputValueDefinitions());
        }
    }

    /**
     * Input-object fields share {@code graphql_field} with output fields: the coordinate is the
     * same and the owning type's kind is a join away, so the SDL location kind of an application
     * on one falls out of a join rather than a second table. The default value is the one column
     * only this side fills.
     */
    private void captureInputFields(SiteRef site, List<InputValueDefinition> fields, ElementOrdinals ordinals) {
        for (InputValueDefinition field : fields) {
            String name = field.getName();
            if (!coordinates.claimInputField(site.typeName(), name)) {
                continue;
            }
            var wrapping = Wrapping.of(field.getType());

            captureFieldDirectives(site.typeName(), name, field.getDirectives(), true);
        }
    }

    /**
     * A field's arguments, numbered within that field, which is the grain
     * {@code graphql_argument.ordinal} states and the grain its readers use: both of them window
     * by field before ordering on it.
     *
     * <p>A counter local to the call and not one of {@link ElementOrdinals}'s, because an argument
     * belongs to a field and not to the type. Those counters are per type so a family's numbering
     * survives the site boundary, a type declared across several extensions numbering its fields,
     * enum values and union members in one sequence. An argument needs no such thing: the field
     * carrying it is claimed once, so every argument it has arrives in this one call, and spending
     * the type's counter on them numbered a second field's first argument after the first field's
     * last.
     */
    private void captureArguments(String typeName, String fieldName,
                                  List<InputValueDefinition> arguments) {
        int ordinal = 0;
        for (InputValueDefinition argument : arguments) {
            String name = argument.getName();
            if (!coordinates.claimArgument(typeName, fieldName, name)) {
                continue;
            }
            var wrapping = Wrapping.of(argument.getType());

            captureArgumentDirectives(typeName, fieldName, name, argument.getDirectives());
        }
    }

    // ---------------------------------------------------------------- directive applications

    private void captureTypeDirectives(SiteRef site, List<Directive> directives, ElementOrdinals ordinals) {
        for (Directive directive : directives) {
            captureTypeDirective(site, directive, ordinals.nextTypeDirective(directive.getName()));
        }
    }

    /** One authored type-level application, at the position the author wrote it. */
    private void captureTypeDirective(SiteRef site, Directive directive, int ordinal) {
        String coordinate = SchemaCoordinateSyntax.ofType(site.typeName());
        if (!sink.claim(GRAPHQL_DIRECTIVE_APPLICATION, coordinate, directive.getName(), ordinal)) {
            return;
        }
        for (var argument : directive.getArguments()) {
            if (!sink.claim(GRAPHQL_DIRECTIVE_APPLICATION_ARG,
                    coordinate, directive.getName(), ordinal, argument.getName())) {
                continue;
            }
        }
        decode.captureTypeDirective(site, directive, ordinal);
    }

    private void captureFieldDirectives(String typeName, String fieldName,
                                        List<Directive> directives, boolean inputField) {
        var ordinals = new LinkedHashMap<String, Integer>();
        for (Directive directive : directives) {
            int ordinal = ordinals.merge(directive.getName(), 0, (old, ignored) -> old + 1);
            String coordinate = SchemaCoordinateSyntax.ofField(typeName, fieldName);
            if (!sink.claim(GRAPHQL_DIRECTIVE_APPLICATION, coordinate, directive.getName(),
                    ordinal)) {
                continue;
            }
            for (var argument : directive.getArguments()) {
                if (!sink.claim(GRAPHQL_DIRECTIVE_APPLICATION_ARG,
                        coordinate, directive.getName(), ordinal, argument.getName())) {
                    continue;
                }
            }
            decode.captureFieldDirective(typeName, fieldName, directive, ordinal, inputField);
        }
    }

    private void captureArgumentDirectives(String typeName, String fieldName, String argumentName,
                                           List<Directive> directives) {
        var ordinals = new LinkedHashMap<String, Integer>();
        for (Directive directive : directives) {
            int ordinal = ordinals.merge(directive.getName(), 0, (old, ignored) -> old + 1);
            String coordinate =
                SchemaCoordinateSyntax.ofArgument(typeName, fieldName, argumentName);
            if (!sink.claim(GRAPHQL_DIRECTIVE_APPLICATION, coordinate, directive.getName(),
                    ordinal)) {
                continue;
            }
            for (var argument : directive.getArguments()) {
                if (!sink.claim(GRAPHQL_DIRECTIVE_APPLICATION_ARG, coordinate,
                        directive.getName(), ordinal, argument.getName())) {
                    continue;
                }
            }
            decode.captureArgumentDirective(typeName, fieldName, argumentName, directive, ordinal);
        }
    }

    // ---------------------------------------------------------------- site assembly

    /** One declaration site: a base definition or an extension, with the form it wrote. */
    private record Site(TypeDefinition<?> definition, String kind, boolean extension) {
        SourceLocation location() {
            SourceLocation location = definition.getSourceLocation();
            return location == null || location.getSourceName() == null ? null : location;
        }

        Description description() {
            return definition instanceof graphql.language.DescribedNode<?> described
                ? described.getDescription()
                : null;
        }
    }

    /**
     * Every named type in the registry paired with its declaration sites, base first and
     * extensions in document order. The registry keeps definitions and extensions in separate
     * per-kind maps because assembly patches them differently; the store's element families each
     * need one monomorphic contributed-by reference, which only a unified site relation gives
     * them, so the two maps transcribe into the one relation here.
     */
    private Map<String, List<Site>> sitesByType() {
        var bases = new LinkedHashMap<String, Site>();
        for (TypeDefinition<?> definition : registry.types().values()) {
            bases.put(definition.getName(), new Site(definition, kindOf(definition), false));
        }
        for (ScalarTypeDefinition definition : registry.scalars().values()) {
            bases.put(definition.getName(), new Site(definition, SCALAR, false));
        }

        var extensions = new LinkedHashMap<String, List<Site>>();
        collectExtensions(registry.objectTypeExtensions(), OBJECT, extensions);
        collectExtensions(registry.interfaceTypeExtensions(), INTERFACE, extensions);
        collectExtensions(registry.unionTypeExtensions(), UNION, extensions);
        collectExtensions(registry.enumTypeExtensions(), ENUM, extensions);
        collectExtensions(registry.inputObjectTypeExtensions(), INPUT_OBJECT, extensions);
        collectExtensions(registry.scalarTypeExtensions(), SCALAR, extensions);

        var names = new LinkedHashSet<String>(bases.keySet());
        names.addAll(extensions.keySet());

        var sites = new LinkedHashMap<String, List<Site>>();
        for (String name : names) {
            var forType = new ArrayList<Site>();
            Site base = bases.get(name);
            if (base != null) {
                forType.add(base);
            }
            forType.addAll(extensions.getOrDefault(name, List.of()));
            // A base-less extension chain is an author error a detection reports, not a reason
            // capture cannot run: the first extension simply holds merge ordinal 0.
            sites.put(name, forType);
        }
        return sites;
    }

    private static <T extends TypeDefinition<?>> void collectExtensions(
        Map<String, List<T>> source, String kind, Map<String, List<Site>> into
    ) {
        source.forEach((name, definitions) -> definitions.forEach(definition ->
            into.computeIfAbsent(name, k -> new ArrayList<>()).add(new Site(definition, kind, true))));
    }

    private static String kindOf(TypeDefinition<?> definition) {
        return switch (definition) {
            case ObjectTypeDefinition ignored -> OBJECT;
            case InterfaceTypeDefinition ignored -> INTERFACE;
            case UnionTypeDefinition ignored -> UNION;
            case EnumTypeDefinition ignored -> ENUM;
            case InputObjectTypeDefinition ignored -> INPUT_OBJECT;
            case ScalarTypeDefinition ignored -> SCALAR;
            default -> throw new IllegalStateException(
                "unexpected type definition at capture: " + definition.getClass());
        };
    }

    // ---------------------------------------------------------------- shared helpers

    static String descriptionOf(Description description) {
        if (description == null) {
            return null;
        }
        String content = description.getContent();
        return content == null || content.isEmpty() ? null : content;
    }

    static String renderOrNull(Node<?> node) {
        return node == null ? null : AstPrinter.printAstCompact(node);
    }

    /** Writes a three-column position group, leaving all three NULL when the node carries none. */
    public static void setPosition(SourceLocation location, java.util.function.Consumer<String> name,
                            java.util.function.Consumer<Integer> line,
                            java.util.function.Consumer<Integer> column) {
        if (location == null || location.getSourceName() == null) {
            return;
        }
        name.accept(location.getSourceName());
        line.accept(location.getLine());
        column.accept(location.getColumn());
    }

    /**
     * Writes the line and column of a position whose {@code source_name} column is already spoken
     * for by the site key. The two always name the same file: an element sits lexically inside
     * the site that declares it.
     */
    public static void setOwnPosition(SourceLocation location, java.util.function.Consumer<Integer> line,
                               java.util.function.Consumer<Integer> column) {
        if (location == null) {
            return;
        }
        line.accept(location.getLine());
        column.accept(location.getColumn());
    }

    /**
     * The type-expression decode the capture-time rule admits: {@code typeSdl} is the literal the
     * author wrote and the three booleans describe the wrapping. Deeper nesting keeps a faithful
     * {@code typeSdl} while the decode describes the outermost list and the innermost item;
     * whether the generator accepts such a shape is a detection's business, not capture's.
     */
    record Wrapping(String typeSdl, String namedType, boolean nonNull, boolean isList, Boolean itemNonNull) {

        static Wrapping of(Type<?> type) {
            boolean nonNull = type instanceof NonNullType;
            Type<?> outer = nonNull ? ((NonNullType) type).getType() : type;
            boolean isList = outer instanceof ListType;
            Boolean itemNonNull = null;
            if (isList) {
                Type<?> item = ((ListType) outer).getType();
                while (true) {
                    if (item instanceof NonNullType wrapped && wrapped.getType() instanceof ListType inner) {
                        item = inner;
                    } else if (item instanceof ListType inner) {
                        item = inner.getType();
                    } else {
                        break;
                    }
                }
                itemNonNull = item instanceof NonNullType;
            }
            return new Wrapping(AstPrinter.printAstCompact(type), namedTypeOf(type), nonNull, isList, itemNonNull);
        }

        private static String namedTypeOf(Type<?> type) {
            Type<?> current = type;
            while (true) {
                switch (current) {
                    case TypeName named -> {
                        return named.getName();
                    }
                    case NonNullType wrapped -> current = wrapped.getType();
                    case ListType list -> current = list.getType();
                    default -> throw new IllegalStateException(
                        "unexpected type node at capture: " + current.getClass());
                }
            }
        }
    }

}
