package no.sikt.graphitron.rewrite.capture;

import graphql.language.AstPrinter;
import graphql.language.Description;
import graphql.language.Directive;
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
import no.sikt.graphitron.rewrite.NodeDeclaration;
import no.sikt.graphitron.rewrite.schema.RewriteSchemaLoader;
import no.sikt.graphitron.rewrite.schema.input.SchemaInput;
import no.sikt.graphitron.rewrite.schema.input.SchemaInputAttribution;
import no.sikt.graphitron.rewrite.schema.input.SchemaSource;
import no.sikt.graphitron.rewrite.schema.input.TagLinkSynthesiser;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT_DIRECTIVE_ARG;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.STORE_SOURCE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_LOCATION;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DUPLICATE_DECLARATION;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ENUM_VALUE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ENUM_VALUE_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ENUM_VALUE_DIRECTIVE_ARG;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD_DIRECTIVE_ARG;
import static no.sikt.graphitron.model.Tables.GRAPHQL_IMPLEMENTS;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ROOT_OPERATION;
import static no.sikt.graphitron.model.Tables.GRAPHQL_SCHEMA_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_SCHEMA_DIRECTIVE_ARG;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_DECLARATION;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_DIRECTIVE_ARG;
import static no.sikt.graphitron.model.Tables.GRAPHQL_UNION_MEMBER;

/**
 * The SDL capture load: one walk over the {@link TypeDefinitionRegistry} filling the {@code graphql_}
 * family, and, through {@link GraphitronFactCapture}, the {@code graphitron_} relations decoded from
 * the graphitron and federation directive inventory.
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
 * a duplicate element quarantines in {@code graphql_duplicate_declaration} and an undecodable
 * literal in {@code graphitron_undecoded_argument}, both rendered and located, so a detection has its
 * row and no authored text is lost.
 *
 * <p>Capture is also <b>type-local</b>: every row's content is a function of its own type's
 * declaration sites and nothing else. Nothing here reads across types and no verdict is computed
 * during a file's walk, which is what keeps a single file the unit of an incremental refresh.
 * That is a review rule on this code, not something a test can catch after the fact.
 */
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
    private final GraphitronFactCapture decode;

    /**
     * Each type's running element ordinals. Type-wide rather than per-site because a repeatable type
     * directive applied once on a base declaration and once on an extension has to number 0 and 1
     * instead of colliding at 0, so the counter has to outlive the site that opened it.
     */
    private final Map<String, ElementOrdinals> ordinalsByType = new LinkedHashMap<>();

    private final MacroCapture macros;

    private final ClasspathSources sources;

    /**
     * The run's inputs keyed on their canonical source name, so the stamp decision below reads the
     * arm the producer decided instead of asking the filesystem what the producer already knew.
     * Rebuilt from the run's input list at the capture site rather than ferried down from the load:
     * {@link SchemaInputAttribution#build} is pure over that list, so the rebuilt map is the same
     * map by construction.
     */
    private final Map<String, SchemaInput> attribution;

    /**
     * The sources this run read that contributed no declaration, so the walk cannot find them: the
     * ones the parser refused. Kept apart from {@link #attribution} because that map is the run's
     * configured inputs, while these are the subset the run actually opened and was refused, which
     * is what the source census records.
     */
    private final Set<String> refusedSources;

    private SdlFactCapture(FactSink sink, TypeDefinitionRegistry registry,
                           ClasspathSources sources, Map<String, SchemaInput> attribution,
                           Set<String> refusedSources) {
        this.sink = sink;
        this.registry = registry;
        this.decode = new GraphitronFactCapture(sink);
        this.macros = new MacroCapture(sink, registry);
        this.sources = sources;
        this.attribution = attribution;
        this.refusedSources = refusedSources;
    }

    /** Runs the walk, buffering into {@code sink}; the caller flushes. */
    static void capture(FactSink sink, TypeDefinitionRegistry registry,
                        ClasspathSources sources, Map<String, SchemaInput> attribution) {
        capture(sink, registry, sources, attribution, Set.of());
    }

    /**
     * {@link #capture(FactSink, TypeDefinitionRegistry, ClasspathSources, Map)}
     * plus the sources the parser refused, which the walk has no other way to learn about: a
     * refused source contributes no declaration, so nothing in the registry points back at it.
     */
    static void capture(FactSink sink, TypeDefinitionRegistry registry,
                        ClasspathSources sources, Map<String, SchemaInput> attribution,
                        Set<String> refusedSources) {
        new SdlFactCapture(sink, registry, sources, attribution, refusedSources).run();
    }

    private void run() {
        captureDirectiveDefinitions();
        captureSchema();
        captureTypes();
        macros.expand();
        captureSources();
    }

    /**
     * The schema files this walk read, so every SDL row is reachable from the source that produced
     * it. Collected from the top-level definitions rather than from every element: an element sits
     * lexically inside the site that declares it, so the sites cover the set, and a macro's
     * synthesized site inherits its carrier's file and is already among them.
     *
     * <p>Stamped, for every source the run's inputs declare as a file. The walk is handed source
     * <em>names</em> by graphql-java rather than the {@link SchemaInput} that produced them, so the
     * arm is recovered through {@link #stampTarget} and switched on exhaustively; stamping costs one
     * file re-read per schema file at capture time, and that price was weighed against the reader it
     * buys: a currency check that re-hashes a cold graph's schema files against the working tree
     * without building its module. The residue stays unstamped exactly as the null-while-loading
     * discipline allows: a programmatic caller's bare label has nothing to hash, and the two
     * generator-injected names have no input behind them at all.
     *
     * <p>Runs last because it is a summary of the walk, and no SDL relation declares a foreign key
     * into it: a schema-level row can carry a null source name, and the fact-schema convention puts
     * a FOREIGN KEY only where the walk writes the child while standing on the parent. Reachability
     * is what the partition rule asks for, and these rows give it.
     */
    private void captureSources() {
        var names = new java.util.LinkedHashSet<String>();
        registry.schemaDefinition().ifPresent(schema -> addSource(names, schema.getSourceLocation()));
        registry.getSchemaExtensionDefinitions()
            .forEach(extension -> addSource(names, extension.getSourceLocation()));
        registry.getDirectiveDefinitions().values()
            .forEach(definition -> addSource(names, definition.getSourceLocation()));
        registry.types().values().forEach(definition -> addSource(names, definition.getSourceLocation()));
        registry.scalars().values().forEach(definition -> addSource(names, definition.getSourceLocation()));
        addExtensionSources(names, registry.objectTypeExtensions());
        addExtensionSources(names, registry.interfaceTypeExtensions());
        addExtensionSources(names, registry.unionTypeExtensions());
        addExtensionSources(names, registry.enumTypeExtensions());
        addExtensionSources(names, registry.scalarTypeExtensions());
        addExtensionSources(names, registry.inputObjectTypeExtensions());
        // A refused source is one this run read, so the census owes it a row even though it
        // declared nothing for the walk to find it by. Without this the store contradicts itself
        // on the rows the verdict families introduce: one family recording that the read refused a
        // source, the other that the graph has no such source. It also decides whether the
        // currency check covers the file the author is most likely to edit next.
        names.addAll(refusedSources);

        for (String name : names) {
            GraphSourceMembership.note(sink, name);
            if (!sink.claim(STORE_SOURCE, name)) {
                continue;
            }
            ClasspathSources.upsert(sink.dsl(), name, SCHEMA_FILE);
            stampTarget(name).ifPresent(sources::noteRegularFile);
        }
    }

    /**
     * The file behind a source name, recovered from the run's inputs rather than probed for. A
     * label has no file, and neither do the two source names the generator injects itself: the
     * bundled {@link RewriteSchemaLoader#DIRECTIVES_SOURCE_NAME} and the {@code @link} extension
     * {@link TagLinkSynthesiser#SYNTHESISED_SOURCE_NAME} stamps when a binding carries a tag. That
     * pair is the whole miss set, and it is named here rather than absorbed, because a lookup that
     * tolerated one unknown name would tolerate a genuine gap between the inputs and what the
     * parser handed back.
     */
    private java.util.Optional<Path> stampTarget(String name) {
        SchemaInput input = attribution.get(name);
        if (input == null) {
            if (RewriteSchemaLoader.DIRECTIVES_SOURCE_NAME.equals(name)
                || TagLinkSynthesiser.SYNTHESISED_SOURCE_NAME.equals(name)) {
                return java.util.Optional.empty();
            }
            throw new IllegalStateException("source '" + name + "' came back from the parser but no "
                + "schema input declared it, and it is neither of the two names the generator injects "
                + "itself. Capture cannot decide whether to stamp it.");
        }
        return switch (input.source()) {
            case SchemaSource.File file -> java.util.Optional.of(file.path());
            case SchemaSource.Named ignored -> java.util.Optional.empty();
        };
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

    /**
     * Records what each directive <em>is</em>, for every definition the registry holds. Graphitron's
     * own bundled definitions are rows too, so an application's directive name always resolves to a
     * definition and reading a repeatable flag or an argument default stays a join. Which
     * definitions an emitter re-declares is a question about their {@code source_name}, answered
     * where the emitting happens.
     */
    private void captureDirectiveDefinitions() {
        for (DirectiveDefinition definition : registry.getDirectiveDefinitions().values()) {
            String name = definition.getName();
            if (!sink.claim(GRAPHQL_DIRECTIVE, name)) {
                quarantine("TYPE", "@" + name, definition);
                continue;
            }
            var record = sink.dsl().newRecord(GRAPHQL_DIRECTIVE);
            record.setDirectiveName(name);
            record.setRepeatable(definition.isRepeatable());
            record.setDescription(descriptionOf(definition.getDescription()));
            setPosition(definition.getSourceLocation(),
                record::setSourceName, record::setSourceLine, record::setSourceColumn);
            sink.add(record);

            for (var location : definition.getDirectiveLocations()) {
                if (!sink.claim(GRAPHQL_DIRECTIVE_LOCATION, name, location.getName())) {
                    quarantine("DIRECTIVE_LOCATION", "@" + name + " on " + location.getName(), location);
                    continue;
                }
                var row = sink.dsl().newRecord(GRAPHQL_DIRECTIVE_LOCATION);
                row.setDirectiveName(name);
                row.setLocation(location.getName());
                sink.add(row);
            }

            int ordinal = 0;
            for (InputValueDefinition argument : definition.getInputValueDefinitions()) {
                String argumentName = argument.getName();
                if (!sink.claim(GRAPHQL_DIRECTIVE_ARGUMENT, name, argumentName)) {
                    quarantine("DIRECTIVE_ARGUMENT", "@" + name + "(" + argumentName + ":)", argument);
                    continue;
                }
                var row = sink.dsl().newRecord(GRAPHQL_DIRECTIVE_ARGUMENT);
                row.setDirectiveName(name);
                row.setArgumentName(argumentName);
                row.setOrdinal(ordinal++);
                var wrapping = Wrapping.of(argument.getType());
                row.setTypeSdl(wrapping.typeSdl());
                row.setNamedType(wrapping.namedType());
                row.setNonNull(wrapping.nonNull());
                row.setIsList(wrapping.isList());
                row.setItemNonNull(wrapping.itemNonNull());
                row.setDefaultValueSdl(renderOrNull(argument.getDefaultValue()));
                row.setDescription(descriptionOf(argument.getDescription()));
                setPosition(argument.getSourceLocation(),
                    row::setSourceName, row::setSourceLine, row::setSourceColumn);
                sink.add(row);
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
                    quarantine("TYPE", slot, operation);
                    continue;
                }
                var record = sink.dsl().newRecord(GRAPHQL_ROOT_OPERATION);
                record.setOperation(slot);
                record.setTypeName(operation.getTypeName().getName());
                setPosition(operation.getSourceLocation(),
                    record::setSourceName, record::setSourceLine, record::setSourceColumn);
                sink.add(record);
            }
            List<Directive> directives = definition instanceof graphql.language.SchemaDefinition schema
                ? schema.getDirectives()
                : ((graphql.language.SchemaExtensionDefinition) definition).getDirectives();
            for (Directive directive : directives) {
                int ordinal = ordinals.merge(directive.getName(), 0, (old, ignored) -> old + 1);
                decode.captureSchemaDirective(directive, ordinal);
                if (!sink.claim(GRAPHQL_SCHEMA_DIRECTIVE, directive.getName(), ordinal)) {
                    quarantine("DIRECTIVE_APPLICATION", "@" + directive.getName(), directive);
                    continue;
                }
                var record = sink.dsl().newRecord(GRAPHQL_SCHEMA_DIRECTIVE);
                record.setDirectiveName(directive.getName());
                record.setOrdinal(ordinal);
                setPosition(directive.getSourceLocation(),
                    record::setSourceName, record::setSourceLine, record::setSourceColumn);
                sink.add(record);
                for (var argument : directive.getArguments()) {
                    if (!sink.claim(GRAPHQL_SCHEMA_DIRECTIVE_ARG,
                            directive.getName(), ordinal, argument.getName())) {
                        continue;
                    }
                    var row = sink.dsl().newRecord(GRAPHQL_SCHEMA_DIRECTIVE_ARG);
                    row.setDirectiveName(directive.getName());
                    row.setOrdinal(ordinal);
                    row.setDirectiveArgumentName(argument.getName());
                    row.setValueSdl(AstPrinter.printAstCompact(argument.getValue()));
                    sink.add(row);
                }
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
            var record = sink.dsl().newRecord(GRAPHQL_ROOT_OPERATION);
            record.setOperation(operation);
            record.setTypeName(typeName);
            sink.add(record);
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

            var typeRecord = sink.dsl().newRecord(GRAPHQL_TYPE);
            typeRecord.setTypeName(typeName);
            typeRecord.setKind(first.kind());
            typeRecord.setDescription(first.extension() ? null : descriptionOf(first.description()));
            sink.claim(GRAPHQL_TYPE, typeName);
            sink.add(typeRecord);

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
        int argument;
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
            quarantine("TYPE", typeName, site.definition());
            return;
        }
        var record = sink.dsl().newRecord(GRAPHQL_TYPE_DECLARATION);
        record.setTypeName(typeName);
        record.setSourceName(location.getSourceName());
        record.setSourceLine(location.getLine());
        record.setSourceColumn(location.getColumn());
        record.setMergeOrdinal(mergeOrdinal);
        record.setIsExtension(site.extension());
        record.setKind(site.kind());
        sink.add(record);

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

    /** The declaration site an element hangs off: the monomorphic contributed-by reference. */
    record SiteRef(String typeName, SourceLocation location) {}

    private void captureImplements(SiteRef site, List<?> interfaces) {
        // graphql-java declares these lists over the raw Type; an implements entry and a union
        // member are always a bare TypeName, so the element type is narrowed here instead.
        for (Object element : interfaces) {
            TypeName type = (TypeName) element;
            String name = type.getName();
            if (!sink.claim(GRAPHQL_IMPLEMENTS, site.typeName(), name)) {
                quarantine("IMPLEMENTS", site.typeName() + " implements " + name, type);
                continue;
            }
            var record = sink.dsl().newRecord(GRAPHQL_IMPLEMENTS);
            record.setTypeName(site.typeName());
            record.setInterfaceName(name);
            record.setDeclarationLine(site.location().getLine());
            record.setDeclarationColumn(site.location().getColumn());
            record.setSourceName(site.location().getSourceName());
            setOwnPosition(type.getSourceLocation(), record::setSourceLine, record::setSourceColumn);
            sink.add(record);
        }
    }

    private void captureUnionMembers(SiteRef site, List<?> members, ElementOrdinals ordinals) {
        for (Object element : members) {
            TypeName type = (TypeName) element;
            String name = type.getName();
            if (!sink.claim(GRAPHQL_UNION_MEMBER, site.typeName(), name)) {
                quarantine("UNION_MEMBER", site.typeName() + " = " + name, type);
                continue;
            }
            var record = sink.dsl().newRecord(GRAPHQL_UNION_MEMBER);
            record.setUnionName(site.typeName());
            record.setMemberTypeName(name);
            record.setOrdinal(ordinals.unionMember++);
            record.setDeclarationLine(site.location().getLine());
            record.setDeclarationColumn(site.location().getColumn());
            record.setSourceName(site.location().getSourceName());
            setOwnPosition(type.getSourceLocation(), record::setSourceLine, record::setSourceColumn);
            sink.add(record);
        }
    }

    private void captureEnumValues(SiteRef site, List<EnumValueDefinition> values, ElementOrdinals ordinals) {
        for (EnumValueDefinition value : values) {
            String name = value.getName();
            if (!sink.claim(GRAPHQL_ENUM_VALUE, site.typeName(), name)) {
                quarantine("ENUM_VALUE", site.typeName() + "." + name, value);
                continue;
            }
            var record = sink.dsl().newRecord(GRAPHQL_ENUM_VALUE);
            record.setTypeName(site.typeName());
            record.setValueName(name);
            record.setOrdinal(ordinals.enumValue++);
            record.setDeclarationLine(site.location().getLine());
            record.setDeclarationColumn(site.location().getColumn());
            record.setDescription(descriptionOf(value.getDescription()));
            record.setSourceName(site.location().getSourceName());
            setOwnPosition(value.getSourceLocation(), record::setSourceLine, record::setSourceColumn);
            sink.add(record);
            captureEnumValueDirectives(site.typeName(), name, value.getDirectives());
        }
    }

    private void captureFields(SiteRef site, List<FieldDefinition> fields, ElementOrdinals ordinals) {
        for (FieldDefinition field : fields) {
            String name = field.getName();
            if (!sink.claim(GRAPHQL_FIELD, site.typeName(), name)) {
                quarantine("FIELD", site.typeName() + "." + name, field);
                continue;
            }
            var record = sink.dsl().newRecord(GRAPHQL_FIELD);
            record.setTypeName(site.typeName());
            record.setFieldName(name);
            record.setOrdinal(ordinals.field++);
            record.setDeclarationLine(site.location().getLine());
            record.setDeclarationColumn(site.location().getColumn());
            // The expansion's result, not the expression the field was written with: a macro that
            // rewrites a field's type expression records the written form in its own provenance
            // relation, which is the only place that form survives.
            var wrapping = Wrapping.of(macros.expandedFieldType(site.typeName(), field));
            record.setTypeSdl(wrapping.typeSdl());
            record.setNamedType(wrapping.namedType());
            record.setNonNull(wrapping.nonNull());
            record.setIsList(wrapping.isList());
            record.setItemNonNull(wrapping.itemNonNull());
            record.setDescription(descriptionOf(field.getDescription()));
            record.setSourceName(site.location().getSourceName());
            setOwnPosition(field.getSourceLocation(), record::setSourceLine, record::setSourceColumn);
            sink.add(record);

            captureFieldDirectives(site.typeName(), name, field.getDirectives());
            captureArguments(site.typeName(), name, field.getInputValueDefinitions(), ordinals);
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
            if (!sink.claim(GRAPHQL_FIELD, site.typeName(), name)) {
                quarantine("FIELD", site.typeName() + "." + name, field);
                continue;
            }
            var record = sink.dsl().newRecord(GRAPHQL_FIELD);
            record.setTypeName(site.typeName());
            record.setFieldName(name);
            record.setOrdinal(ordinals.field++);
            record.setDeclarationLine(site.location().getLine());
            record.setDeclarationColumn(site.location().getColumn());
            var wrapping = Wrapping.of(field.getType());
            record.setTypeSdl(wrapping.typeSdl());
            record.setNamedType(wrapping.namedType());
            record.setNonNull(wrapping.nonNull());
            record.setIsList(wrapping.isList());
            record.setItemNonNull(wrapping.itemNonNull());
            record.setDefaultValueSdl(renderOrNull(field.getDefaultValue()));
            record.setDescription(descriptionOf(field.getDescription()));
            record.setSourceName(site.location().getSourceName());
            setOwnPosition(field.getSourceLocation(), record::setSourceLine, record::setSourceColumn);
            sink.add(record);

            captureFieldDirectives(site.typeName(), name, field.getDirectives());
        }
    }

    private void captureArguments(String typeName, String fieldName,
                                  List<InputValueDefinition> arguments, ElementOrdinals ordinals) {
        for (InputValueDefinition argument : arguments) {
            String name = argument.getName();
            if (!sink.claim(GRAPHQL_ARGUMENT, typeName, fieldName, name)) {
                quarantine("ARGUMENT", typeName + "." + fieldName + "(" + name + ":)", argument);
                continue;
            }
            var record = sink.dsl().newRecord(GRAPHQL_ARGUMENT);
            record.setTypeName(typeName);
            record.setFieldName(fieldName);
            record.setArgumentName(name);
            record.setOrdinal(ordinals.argument++);
            var wrapping = Wrapping.of(argument.getType());
            record.setTypeSdl(wrapping.typeSdl());
            record.setNamedType(wrapping.namedType());
            record.setNonNull(wrapping.nonNull());
            record.setIsList(wrapping.isList());
            record.setItemNonNull(wrapping.itemNonNull());
            record.setDefaultValueSdl(renderOrNull(argument.getDefaultValue()));
            record.setDescription(descriptionOf(argument.getDescription()));
            setPosition(argument.getSourceLocation(),
                record::setSourceName, record::setSourceLine, record::setSourceColumn);
            sink.add(record);

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
        decode.captureTypeDirective(site, directive, ordinal);
        if (!sink.claim(GRAPHQL_TYPE_DIRECTIVE, site.typeName(), directive.getName(), ordinal)) {
            quarantine("DIRECTIVE_APPLICATION", site.typeName() + " @" + directive.getName(), directive);
            return;
        }
        var record = sink.dsl().newRecord(GRAPHQL_TYPE_DIRECTIVE);
        record.setTypeName(site.typeName());
        record.setDirectiveName(directive.getName());
        record.setOrdinal(ordinal);
        record.setDeclarationLine(site.location().getLine());
        record.setDeclarationColumn(site.location().getColumn());
        record.setSourceName(site.location().getSourceName());
        setOwnPosition(directive.getSourceLocation(), record::setSourceLine, record::setSourceColumn);
        sink.add(record);
        for (var argument : directive.getArguments()) {
            if (!sink.claim(GRAPHQL_TYPE_DIRECTIVE_ARG,
                    site.typeName(), directive.getName(), ordinal, argument.getName())) {
                continue;
            }
            var row = sink.dsl().newRecord(GRAPHQL_TYPE_DIRECTIVE_ARG);
            row.setTypeName(site.typeName());
            row.setDirectiveName(directive.getName());
            row.setOrdinal(ordinal);
            row.setDirectiveArgumentName(argument.getName());
            row.setValueSdl(AstPrinter.printAstCompact(argument.getValue()));
            sink.add(row);
        }
    }

    private void captureFieldDirectives(String typeName, String fieldName, List<Directive> directives) {
        var ordinals = new LinkedHashMap<String, Integer>();
        for (Directive directive : directives) {
            int ordinal = ordinals.merge(directive.getName(), 0, (old, ignored) -> old + 1);
            decode.captureFieldDirective(typeName, fieldName, directive, ordinal);
            if (!sink.claim(GRAPHQL_FIELD_DIRECTIVE, typeName, fieldName, directive.getName(), ordinal)) {
                quarantine("DIRECTIVE_APPLICATION",
                    typeName + "." + fieldName + " @" + directive.getName(), directive);
                continue;
            }
            var record = sink.dsl().newRecord(GRAPHQL_FIELD_DIRECTIVE);
            record.setTypeName(typeName);
            record.setFieldName(fieldName);
            record.setDirectiveName(directive.getName());
            record.setOrdinal(ordinal);
            setPosition(directive.getSourceLocation(),
                record::setSourceName, record::setSourceLine, record::setSourceColumn);
            sink.add(record);
            for (var argument : directive.getArguments()) {
                if (!sink.claim(GRAPHQL_FIELD_DIRECTIVE_ARG,
                        typeName, fieldName, directive.getName(), ordinal, argument.getName())) {
                    continue;
                }
                var row = sink.dsl().newRecord(GRAPHQL_FIELD_DIRECTIVE_ARG);
                row.setTypeName(typeName);
                row.setFieldName(fieldName);
                row.setDirectiveName(directive.getName());
                row.setOrdinal(ordinal);
                row.setDirectiveArgumentName(argument.getName());
                row.setValueSdl(AstPrinter.printAstCompact(argument.getValue()));
                sink.add(row);
            }
        }
    }

    private void captureArgumentDirectives(String typeName, String fieldName, String argumentName,
                                           List<Directive> directives) {
        var ordinals = new LinkedHashMap<String, Integer>();
        for (Directive directive : directives) {
            int ordinal = ordinals.merge(directive.getName(), 0, (old, ignored) -> old + 1);
            decode.captureArgumentDirective(typeName, fieldName, argumentName, directive, ordinal);
            if (!sink.claim(GRAPHQL_ARGUMENT_DIRECTIVE,
                    typeName, fieldName, argumentName, directive.getName(), ordinal)) {
                quarantine("DIRECTIVE_APPLICATION",
                    typeName + "." + fieldName + "(" + argumentName + ":) @" + directive.getName(), directive);
                continue;
            }
            var record = sink.dsl().newRecord(GRAPHQL_ARGUMENT_DIRECTIVE);
            record.setTypeName(typeName);
            record.setFieldName(fieldName);
            record.setArgumentName(argumentName);
            record.setDirectiveName(directive.getName());
            record.setOrdinal(ordinal);
            setPosition(directive.getSourceLocation(),
                record::setSourceName, record::setSourceLine, record::setSourceColumn);
            sink.add(record);
            for (var argument : directive.getArguments()) {
                if (!sink.claim(GRAPHQL_ARGUMENT_DIRECTIVE_ARG, typeName, fieldName, argumentName,
                        directive.getName(), ordinal, argument.getName())) {
                    continue;
                }
                var row = sink.dsl().newRecord(GRAPHQL_ARGUMENT_DIRECTIVE_ARG);
                row.setTypeName(typeName);
                row.setFieldName(fieldName);
                row.setArgumentName(argumentName);
                row.setDirectiveName(directive.getName());
                row.setOrdinal(ordinal);
                row.setDirectiveArgumentName(argument.getName());
                row.setValueSdl(AstPrinter.printAstCompact(argument.getValue()));
                sink.add(row);
            }
        }
    }

    private void captureEnumValueDirectives(String typeName, String valueName, List<Directive> directives) {
        var ordinals = new LinkedHashMap<String, Integer>();
        for (Directive directive : directives) {
            int ordinal = ordinals.merge(directive.getName(), 0, (old, ignored) -> old + 1);
            decode.captureEnumValueDirective(typeName, valueName, directive, ordinal);
            if (!sink.claim(GRAPHQL_ENUM_VALUE_DIRECTIVE, typeName, valueName, directive.getName(), ordinal)) {
                quarantine("DIRECTIVE_APPLICATION",
                    typeName + "." + valueName + " @" + directive.getName(), directive);
                continue;
            }
            var record = sink.dsl().newRecord(GRAPHQL_ENUM_VALUE_DIRECTIVE);
            record.setTypeName(typeName);
            record.setValueName(valueName);
            record.setDirectiveName(directive.getName());
            record.setOrdinal(ordinal);
            setPosition(directive.getSourceLocation(),
                record::setSourceName, record::setSourceLine, record::setSourceColumn);
            sink.add(record);
            for (var argument : directive.getArguments()) {
                if (!sink.claim(GRAPHQL_ENUM_VALUE_DIRECTIVE_ARG,
                        typeName, valueName, directive.getName(), ordinal, argument.getName())) {
                    continue;
                }
                var row = sink.dsl().newRecord(GRAPHQL_ENUM_VALUE_DIRECTIVE_ARG);
                row.setTypeName(typeName);
                row.setValueName(valueName);
                row.setDirectiveName(directive.getName());
                row.setOrdinal(ordinal);
                row.setDirectiveArgumentName(argument.getName());
                row.setValueSdl(AstPrinter.printAstCompact(argument.getValue()));
                sink.add(row);
            }
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

    /**
     * Records a losing occurrence of an element-level natural key. The registry retains these
     * without error, so the key is author-reachable; first-wins keeps the earlier occurrence and
     * the later one lands here, rendered and located, for the duplicate-declaration detection.
     */
    private void quarantine(String elementKind, String coordinate, Node<?> node) {
        SourceLocation location = node.getSourceLocation();
        if (location == null || location.getSourceName() == null) {
            return;
        }
        if (!sink.claim(GRAPHQL_DUPLICATE_DECLARATION,
                location.getSourceName(), location.getLine(), location.getColumn())) {
            return;
        }
        var record = sink.dsl().newRecord(GRAPHQL_DUPLICATE_DECLARATION);
        record.setSourceName(location.getSourceName());
        record.setSourceLine(location.getLine());
        record.setSourceColumn(location.getColumn());
        record.setElementKind(elementKind);
        record.setCoordinate(coordinate);
        record.setValueSdl(AstPrinter.printAstCompact(node));
        sink.add(record);
    }

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
    static void setPosition(SourceLocation location, java.util.function.Consumer<String> name,
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
    static void setOwnPosition(SourceLocation location, java.util.function.Consumer<Integer> line,
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
