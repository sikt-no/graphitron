package no.sikt.graphitron.model.schema;

import graphql.language.AstPrinter;
import graphql.language.AstTransformer;
import graphql.language.AbstractDescribedNode;
import graphql.language.Argument;
import graphql.language.Description;
import graphql.language.DirectiveDefinition;
import graphql.language.Directive;
import graphql.language.FieldDefinition;
import graphql.language.DirectivesContainer;
import graphql.language.EnumTypeDefinition;
import graphql.language.EnumTypeExtensionDefinition;
import graphql.language.EnumValueDefinition;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputObjectTypeExtensionDefinition;
import graphql.language.InterfaceTypeDefinition;
import graphql.language.InterfaceTypeExtensionDefinition;
import graphql.language.NamedNode;
import graphql.language.Node;
import graphql.language.NodeTraverser;
import graphql.language.NodeVisitorStub;
import graphql.language.ScalarTypeDefinition;
import graphql.language.UnionTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.language.ObjectTypeExtensionDefinition;
import graphql.language.SDLDefinition;
import graphql.language.SourceLocation;
import graphql.language.StringValue;
import graphql.language.Type;
import graphql.language.TypeDefinition;
import graphql.language.TypeName;
import graphql.parser.Parser;
import graphql.schema.idl.ScalarInfo;
import graphql.schema.idl.TypeDefinitionRegistry;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;
import graphql.util.TreeTransformerUtil;
import no.sikt.graphitron.model.diagnostics.BuildWarning;
import no.sikt.graphitron.model.diagnostics.Rejection;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import no.sikt.graphitron.model.diagnostics.ValidationFailedException;
import no.sikt.graphitron.model.lint.LintRule;
import no.sikt.graphitron.model.read.StoreHandle;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ENUM_VALUE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ENUM_VALUE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_CARRIER_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_INHERITED_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_MINTED_COINAGE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DIRECTIVE_APPLICATION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DIRECTIVE_APPLICATION_ARG;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE_MINTED;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.val;

/**
 * The registry the generator emits, derived from the one capture transcribed plus what the store
 * says was synthesised on top of it.
 *
 * <p>The document an author writes and the schema graphitron emits are not the same document, and
 * the difference is entirely synthesis. {@code AttributedRegistry.of} marks the line in its own
 * body: everything above it is a loading rewrite that capture sees, and everything below is
 * synthesis that capture does not. Two things live below that line, the federation key synthesis
 * and the {@code @asConnection} expansion, and the store already states both as rows. So the
 * emitted registry is the transcribed one with those rows applied, and this is where that happens.
 *
 * <p>The point of deriving it here rather than in the generator is that the schema and the store
 * then come from the same facts. An expansion implemented twice is two readings of one rule with
 * nothing saying they agree, which is what {@code the emitted anchoring}'s own javadoc records about the
 * split it inherited; an expansion read out of the rows the expansion wrote cannot disagree with
 * them.
 *
 * <h3>What it reads, and why the anchors rather than the minted relations</h3>
 *
 * <p>It reads {@code graphitron_type}, {@code graphitron_field} and {@code graphitron_argument},
 * which are the resolved population: the minted rows already reconciled against the transcription,
 * the author winning wherever the mint fills a name. Reading the minted sets directly and
 * re-applying that rule here would put it in two places, which is the defect this class
 * exists to remove rather than to reproduce. A coordinate several applications disagree about is
 * {@code graphitron_minted_conflict}'s and the anchors deliberately hold no row for it, so a
 * contested coordinate reaches this patch as an absence and needs no arm.
 *
 * <p>Directive applications a macro adds, a federation key among them, come from
 * {@code graphitron_directive_application}'s minted rows, and the {@code @tag} a file's entry
 * configures from its configured ones. Only those are applied, the authored applications already
 * being in the registry this patch started from. Where a file's entry configures a note, the part
 * relations carry the description the anchor minted from it, and that is the one emitted.
 *
 * <h3>Additive and type-replacing, never wholesale</h3>
 *
 * <p>A node that already exists is patched in place rather than rebuilt from its rows, and the
 * reason is fidelity rather than economy. The anchors carry what rendering needs and not what only
 * the transcription can assert, applied directives among the things they do not carry, so a field
 * rebuilt from its anchor row would silently lose every directive its author wrote on it. So an
 * existing field keeps its node and gets its type expression replaced where the anchor disagrees,
 * which is what an {@code @asConnection} rewrite is at this grain, and an existing field gains only
 * the arguments the anchor has and it lacks. Only a type the registry does not have at all is built
 * from rows, and nothing is lost there because a minted element has no authored detail to lose: no
 * expansion applies a directive.
 *
 * <p>Nothing is removed. The store holding no row for something the registry declares would be a
 * capture defect rather than a deletion to perform, and stating it as a difference is a gate's job.
 *
 * <p>The elements are patched on object types only. Every row of {@code graphitron_type_minted} is
 * an {@code OBJECT}, because the macros mint nothing else, so an input object, enum, union,
 * interface or scalar reaches the emitted registry as the author wrote it, with only the
 * configured applications and notes added.
 *
 * <h3>Which registry it starts from</h3>
 *
 * <p>The composition capture assembled, before any synthesis: what the store says was synthesised
 * is applied to it here, and a registry already through {@code KeyNodeSynthesiser} would get every
 * key a second time, which the patch refuses. The registry is not modified; the patch is applied to
 * a copy.
 */
public final class EmittedRegistry {

    /** The one kind the macros mint, and so the only kind here. */
    private static final String OBJECT = "OBJECT";

    private static final String TAG_DIRECTIVE = "tag";

    /** The origins of the applications no document states, which are the ones this patch applies. */
    private static final String CONFIGURED = "CONFIGURED";
    private static final String MINTED = "MINTED";
    private static final String SHAREABLE_DIRECTIVE = "shareable";

    /**
     * The one definition of the directive that publishes a node type's wire id, written once and
     * parsed where it is added: federation-jvm supplies {@code @key}'s definition and knows nothing
     * of this one. It must not be declared in graphitron's own {@code directives.graphqls}, whose
     * directives {@link #derive} strips from the output.
     */
    private static final String NODE_TYPE_DIRECTIVE_SDL = "directive @nodeType(typeId: String!) on OBJECT";
    private static final String NODE_TYPE_DIRECTIVE = "nodeType";

    private EmittedRegistry() {}

    /**
     * The emitted registry, and every minted type whose inherited tags the carriers' disagreement
     * narrowed, in type-name order.
     */
    public record Emitted(TypeDefinitionRegistry registry, List<TagNarrowing> narrowings) {
        public Emitted {
            Objects.requireNonNull(registry, "registry");
            narrowings = List.copyOf(narrowings);
        }
    }

    /**
     * One field whose expansion minted a type, as the registry holds it: the {@code @tag} names
     * applied to it in order, whether it is {@code @shareable}, and where it was written.
     */
    public record Carrier(String coordinate, List<String> tags, boolean shareable,
                          SourceLocation location) {
        public Carrier {
            Objects.requireNonNull(coordinate, "coordinate");
            tags = List.copyOf(tags);
        }
    }

    /**
     * A minted type that carries fewer tags than its carriers do between them: {@code kept} is the
     * tags every carrier carries, which the type was given, and {@code dropped} the rest, which
     * some carrier carries and the type was not given. {@code carriers} are all of the type's
     * carriers, in coinage order.
     */
    public record TagNarrowing(String typeName, List<String> kept, List<String> dropped,
                               List<Carrier> carriers) {
        public TagNarrowing {
            Objects.requireNonNull(typeName, "typeName");
            kept = List.copyOf(kept);
            dropped = List.copyOf(dropped);
            carriers = List.copyOf(carriers);
        }

        /**
         * The {@link LintRule#SHARED_TYPE_TAGS_NARROWED} finding for this narrowing, located at
         * the first carrier carrying a tag the type was not given.
         */
        public BuildWarning.LintFinding finding() {
            var dropping = carriers.stream()
                .filter(c -> c.tags().stream().anyMatch(dropped::contains))
                .toList();
            var location = dropping.isEmpty() ? null : dropping.getFirst().location();
            String message = "Generated type '" + typeName + "' carries "
                + (kept.isEmpty()
                    ? "no @tag, since no tag is on every field it was generated for"
                    : "only " + tagList(kept) + ", the tags on every field it was generated for")
                + ", and not " + tagList(dropped) + ", which only some of those fields carry ("
                + carriers.stream()
                    .map(c -> c.coordinate() + (c.tags().isEmpty() ? " untagged" : " " + tagList(c.tags())))
                    .collect(Collectors.joining(", "))
                + "). A contract built by excluding tags keeps '" + typeName + "' wherever it keeps"
                + " one of those fields, which is what it wants; disable this rule ("
                + LintRule.SHARED_TYPE_TAGS_NARROWED.id() + ") if that is how your contracts are"
                + " built. A contract built by including tags drops '" + typeName + "' unless it"
                + " carries one of them; declare the type in your schema with the tags it should"
                + " have, and graphitron uses it as written.";
            return BuildWarning.LintFinding.of(message, location, LintRule.SHARED_TYPE_TAGS_NARROWED);
        }

        private static String tagList(List<String> tags) {
            return tags.stream().map(t -> "@tag(name: \"" + t + "\")")
                .collect(Collectors.joining(", "));
        }
    }

    /**
     * The emitted registry for {@code store}'s graph, derived from {@code transcribed}, and what
     * deriving it narrowed. Capture's entry point: the composition it assembled is pre-synthesis by
     * construction.
     *
     * @param transcribed the registry capture wrote its facts from, which is the pre-synthesis one
     * @param store       the graph's own partition of the fact store
     */
    public static Emitted derive(TypeDefinitionRegistry transcribed, StoreHandle store) {
        Objects.requireNonNull(transcribed, "transcribed");
        Objects.requireNonNull(store, "store");

        // Replayed through the reduce rather than TypeDefinitionRegistry.merge, which rebuilds the
        // parse order kind by kind.
        var patched = SchemaLoader.merge(List.of(transcribed)).registry();
        var replacements = new ArrayList<Replacement>();
        for (var type : emittedTypes(store)) {
            if (patched.getTypeOrNull(type.typeName()) instanceof ObjectTypeDefinition object) {
                patchDeclarationSites(object,
                    patched.objectTypeExtensions().getOrDefault(type.typeName(), List.of()),
                    type.fields(), replacements);
            } else if (patched.getTypeOrNull(type.typeName()) == null) {
                patched.add(objectType(type));
            }
        }
        apply(patched, replacements);
        var nodeTypes = mintedNodeTypes(store);
        refuseAuthoredNodeType(patched, nodeTypes);
        applyUnwritten(patched, store);
        defineNodeType(patched, nodeTypes);
        unpublish(patched, store);
        return new Emitted(patched, narrowings(store));
    }

    // ---------------------------------------------------------------------------------------
    // The rows
    // ---------------------------------------------------------------------------------------

    /** One emitted object type, with the fields it carries. */
    private record TypeRow(String typeName, String description, List<FieldRow> fields) {}

    /** One emitted field, at the type expression the generator reads, with its arguments. */
    private record FieldRow(String fieldName, String typeSdl, String description,
                            List<ArgumentRow> arguments) {}

    /** One emitted field argument. */
    private record ArgumentRow(String argumentName, String typeSdl, String defaultValueSdl,
                               String description) {}

    /**
     * The emitted object types, each carrying its fields and each field its arguments.
     *
     * <p>One row of this answer is one object type the generator emits, and the children hang off it
     * on their own keys rather than being fetched flat and regrouped in memory. That is not only
     * fewer statements: a flat read has to be put back together by something, and the only key
     * available for the arguments was the field's coordinate, which this class would have had to
     * spell for itself. A second spelling of a coordinate is how two of them come to disagree, and
     * nesting removes the question rather than answering it carefully.
     *
     * <p>Order is the anchors' own {@code ordinal} at both levels: document order on an authored
     * element and the order the macro wrote them on a minted one. So a type built from these rows
     * carries the order the store says the generator emits rather than one this class invents.
     *
     * <p>Object types only, and stated once here rather than in each pass. The macros mint nothing
     * but {@code OBJECT}s, so every other kind reaches the emitted registry exactly as its author
     * wrote it.
     */
    private static List<TypeRow> emittedTypes(StoreHandle store) {
        return store.dsl()
            .select(GRAPHITRON_TYPE.TYPE_NAME, GRAPHITRON_TYPE.DESCRIPTION,
                multiset(
                    select(GRAPHITRON_FIELD.FIELD_NAME, GRAPHITRON_FIELD.TYPE_SDL,
                        GRAPHITRON_FIELD.DESCRIPTION,
                        multiset(
                            select(GRAPHITRON_ARGUMENT.ARGUMENT_NAME, GRAPHITRON_ARGUMENT.TYPE_SDL,
                                GRAPHITRON_ARGUMENT.DEFAULT_VALUE_SDL,
                                GRAPHITRON_ARGUMENT.DESCRIPTION)
                                .from(GRAPHITRON_ARGUMENT)
                                .where(GRAPHITRON_ARGUMENT.GRAPH_NAME.eq(GRAPHITRON_FIELD.GRAPH_NAME))
                                .and(GRAPHITRON_ARGUMENT.TYPE_NAME.eq(GRAPHITRON_FIELD.TYPE_NAME))
                                .and(GRAPHITRON_ARGUMENT.FIELD_NAME.eq(GRAPHITRON_FIELD.FIELD_NAME))
                                .orderBy(GRAPHITRON_ARGUMENT.ORDINAL))
                            .convertFrom(r -> r.map(a -> new ArgumentRow(
                                a.value1(), a.value2(), a.value3(), a.value4()))))
                        .from(GRAPHITRON_FIELD)
                        .where(GRAPHITRON_FIELD.GRAPH_NAME.eq(GRAPHITRON_TYPE.GRAPH_NAME))
                        .and(GRAPHITRON_FIELD.TYPE_NAME.eq(GRAPHITRON_TYPE.TYPE_NAME))
                        .orderBy(GRAPHITRON_FIELD.ORDINAL))
                    .convertFrom(r -> r.map(f -> new FieldRow(
                        f.value1(), f.value2(), f.value3(), f.value4()))))
            .from(GRAPHITRON_TYPE)
            .where(GRAPHITRON_TYPE.GRAPH_NAME.eq(store.graphName()))
            .and(GRAPHITRON_TYPE.KIND.eq(OBJECT))
            .orderBy(GRAPHITRON_TYPE.TYPE_NAME)
            .fetch(r -> new TypeRow(r.value1(), r.value2(), r.value3()));
    }

    // ---------------------------------------------------------------------------------------
    // Minting
    // ---------------------------------------------------------------------------------------

    /**
     * One object type the registry does not have at all: the macro's own machinery, the connection,
     * its edge and the shared page info, none of which any author wrote. Built from rows rather
     * than patched, and nothing is lost by that because a minted element has no authored detail to
     * lose: no expansion applies a directive.
     */
    private static ObjectTypeDefinition objectType(TypeRow row) {
        return ObjectTypeDefinition.newObjectTypeDefinition()
            .name(row.typeName())
            .description(description(row.description()))
            .fieldDefinitions(row.fields().stream().map(EmittedRegistry::fieldDefinition).toList())
            .build();
    }

    private static FieldDefinition fieldDefinition(FieldRow row) {
        return FieldDefinition.newFieldDefinition()
            .name(row.fieldName())
            .type(type(row.typeSdl()))
            .description(description(row.description()))
            .inputValueDefinitions(row.arguments().stream()
                .map(EmittedRegistry::inputValue).toList())
            .build();
    }

    private static InputValueDefinition inputValue(ArgumentRow row) {
        var builder = InputValueDefinition.newInputValueDefinition()
            .name(row.argumentName())
            .type(type(row.typeSdl()))
            .description(description(row.description()));
        if (row.defaultValueSdl() != null) {
            builder.defaultValue(Parser.parseValue(row.defaultValueSdl()));
        }
        return builder.build();
    }

    // ---------------------------------------------------------------------------------------
    // Patching what is already there
    // ---------------------------------------------------------------------------------------

    /** Swaps each patched node for the one it replaces, after the whole traversal has decided. */
    private static void apply(TypeDefinitionRegistry patched, List<Replacement> replacements) {
        for (var replacement : replacements) {
            patched.remove(replacement.old());
            patched.add(replacement.replacement());
        }
    }

    /**
     * Brings one object type the registry already holds up to what the store says the generator
     * emits: a field whose type expression the expansion rewrote is retyped, a field the expansion
     * added is added, and an argument it appended is appended. Everything else on the node is left
     * exactly as the author wrote it.
     *
     * <h4>A type is its definition and its extensions, and the anchors are not</h4>
     *
     * <p>{@code graphitron_field} is merged across declaration sites, numbering a field's ordinal
     * "exactly as graphql_field numbers it", so one row stands for a field whichever site declared
     * it. A registry does not merge: {@code extend type Query} is a separate node, and its fields
     * are not on the base definition. So a patch that decides presence by looking only at the base
     * declares every extension's field a second time, and assembly rejects the document with
     * {@code TypeExtensionFieldRedefinitionError}. Presence is therefore asked of every site that
     * declares the type, and each row is routed to the site declaring its field.
     *
     * <p>A field no site declares is new, and it lands on the base definition. That is the right
     * home rather than an arbitrary one: what mints a field onto an authored type is an expansion,
     * which is a property of the type rather than of any one document that contributed to it.
     */
    private static void patchDeclarationSites(ObjectTypeDefinition base,
                                              List<? extends ObjectTypeDefinition> extensions,
                                              List<FieldRow> rows,
                                              List<Replacement> replacements) {
        var sites = new ArrayList<ObjectTypeDefinition>();
        sites.add(base);
        sites.addAll(extensions);

        var siteOf = new LinkedHashMap<String, ObjectTypeDefinition>();
        for (var site : sites) {
            for (var field : site.getFieldDefinitions()) {
                siteOf.putIfAbsent(field.getName(), site);
            }
        }

        var perSite = new LinkedHashMap<ObjectTypeDefinition, List<FieldRow>>();
        var minted = new ArrayList<FieldRow>();
        for (var row : rows) {
            var site = siteOf.get(row.fieldName());
            if (site == null) {
                minted.add(row);
            } else {
                perSite.computeIfAbsent(site, s -> new ArrayList<>()).add(row);
            }
        }

        for (var site : sites) {
            var owned = perSite.getOrDefault(site, List.of());
            var added = site == base ? minted : List.<FieldRow>of();
            var rebuilt = patchFields(site, owned, added);
            if (rebuilt != null) {
                replacements.add(new Replacement(site, rebuilt));
            }
        }
    }

    /**
     * The patched site, or {@code null} where the store and this site already agree. {@code owned}
     * are the rows for fields this site declares and {@code added} the ones no site does.
     */
    private static ObjectTypeDefinition patchFields(ObjectTypeDefinition site,
                                                    List<FieldRow> owned, List<FieldRow> added) {
        var byName = new LinkedHashMap<String, FieldDefinition>();
        for (var field : site.getFieldDefinitions()) {
            byName.put(field.getName(), field);
        }
        boolean changed = false;
        for (var row : owned) {
            var existing = byName.get(row.fieldName());
            var patchedField = patchField(existing, row);
            if (patchedField != existing) {
                byName.put(row.fieldName(), patchedField);
                changed = true;
            }
        }
        for (var row : added) {
            byName.put(row.fieldName(), fieldDefinition(row));
            changed = true;
        }
        if (!changed) return null;
        var ordered = List.copyOf(byName.values());
        return site instanceof ObjectTypeExtensionDefinition extension
            ? extension.transformExtension(b -> b.fieldDefinitions(ordered))
            : site.transform(b -> b.fieldDefinitions(ordered));
    }

    /** The patched field, or {@code existing} itself where nothing about it changed. */
    private static FieldDefinition patchField(FieldDefinition existing, FieldRow row) {
        boolean retype = !printed(existing.getType()).equals(printed(type(row.typeSdl())));
        var appended = absentArguments(existing, row.arguments());
        if (!retype && appended.isEmpty()) return existing;

        var inputValues = new ArrayList<>(existing.getInputValueDefinitions());
        inputValues.addAll(appended);
        return existing.transform(b -> {
            if (retype) b.type(type(row.typeSdl()));
            b.inputValueDefinitions(inputValues);
        });
    }

    /**
     * The arguments the store holds for this field and the node does not. Appended rather than
     * merged in the anchor's order, because an authored argument keeps its own node and the macro
     * appends after every authored one anyway, which the anchor's ordinal already states.
     */
    private static List<InputValueDefinition> absentArguments(FieldDefinition existing,
                                                              List<ArgumentRow> rows) {
        Set<String> present = existing.getInputValueDefinitions().stream()
            .map(InputValueDefinition::getName)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        return rows.stream()
            .filter(row -> !present.contains(row.argumentName()))
            .map(EmittedRegistry::inputValue)
            .toList();
    }

    private record Replacement(SDLDefinition<?> old, SDLDefinition<?> replacement) {}

    // ---------------------------------------------------------------------------------------
    // What is not published
    // ---------------------------------------------------------------------------------------

    /**
     * Takes out of the emitted schema what is not published, so a printer of it filters nothing.
     *
     * <p>Graphitron's own directives go, definitions and applications alike: they are read while
     * generating and have no meaning in the schema a consumer serves. Which they are is a fact the
     * store holds, the directives the bundled vocabulary defines.
     *
     * <p>Then the support types those directives take go, unless a type outside the support set
     * references one, directly or through a support type it keeps. Only the published tier,
     * {@link DirectiveSupportTypes#published()}, may be referenced that way; a reference to a
     * strictly internal one is an author error the classifier reports, and the type is kept rather
     * than dropped so the emitted schema still assembles while that error is said.
     */
    private static void unpublish(TypeDefinitionRegistry patched, StoreHandle store) {
        var d = GRAPHQL_DIRECTIVE;
        Set<String> own = Set.copyOf(store.dsl().select(d.DIRECTIVE_NAME).from(d)
            .where(d.GRAPH_NAME.eq(store.graphName()))
            .and(d.SOURCE_NAME.eq(SchemaLoader.DIRECTIVES_SOURCE_NAME))
            .fetch(d.DIRECTIVE_NAME));
        own.forEach(name -> patched.getDirectiveDefinition(name).ifPresent(patched::remove));
        var replacements = new ArrayList<Replacement>();
        for (var site : sites(patched)) {
            var stripped = (SDLDefinition<?>) new AstTransformer().transform(site, new NodeVisitorStub() {
                // graphql-java's visitor signature names the raw Node.
                @SuppressWarnings("rawtypes")
                @Override
                public TraversalControl visitDirective(Directive node, TraverserContext<Node> context) {
                    return own.contains(node.getName())
                        ? TreeTransformerUtil.deleteNode(context) : TraversalControl.CONTINUE;
                }
            });
            if (stripped != site) {
                replacements.add(new Replacement(site, stripped));
            }
        }
        apply(patched, replacements);

        var referenced = new HashSet<String>();
        sites(patched).stream()
            .filter(site -> !DirectiveSupportTypes.isSupportType(((NamedNode<?>) site).getName()))
            .forEach(site -> referenced.addAll(typeNames(site)));
        for (boolean grew = true; grew; ) {
            grew = false;
            for (var site : sites(patched)) {
                String name = ((NamedNode<?>) site).getName();
                if (DirectiveSupportTypes.isSupportType(name) && referenced.contains(name)) {
                    grew |= referenced.addAll(typeNames(site));
                }
            }
        }
        sites(patched).stream()
            .filter(site -> {
                String name = ((NamedNode<?>) site).getName();
                return DirectiveSupportTypes.isSupportType(name) && !referenced.contains(name);
            })
            .toList()
            .forEach(patched::remove);
    }

    /** Every type definition and type extension the registry holds. */
    private static List<SDLDefinition<?>> sites(TypeDefinitionRegistry registry) {
        var sites = new ArrayList<SDLDefinition<?>>();
        registry.types().values().forEach(type -> sites.add((SDLDefinition<?>) type));
        registry.scalars().values().stream()
            .filter(scalar -> !ScalarInfo.isGraphqlSpecifiedScalar(scalar.getName()))
            .forEach(sites::add);
        Stream.<Map<String, ? extends List<? extends SDLDefinition<?>>>>of(
                registry.objectTypeExtensions(), registry.interfaceTypeExtensions(),
                registry.unionTypeExtensions(), registry.enumTypeExtensions(),
                registry.scalarTypeExtensions(), registry.inputObjectTypeExtensions())
            .flatMap(extensions -> extensions.values().stream())
            .forEach(sites::addAll);
        return sites;
    }

    /** The names of the types a definition refers to: its fields', arguments', members' and interfaces'. */
    private static Set<String> typeNames(SDLDefinition<?> site) {
        var names = new HashSet<String>();
        new NodeTraverser().depthFirst(new NodeVisitorStub() {
            // graphql-java's visitor signature names the raw Node.
            @SuppressWarnings("rawtypes")
            @Override
            public TraversalControl visitTypeName(TypeName node, TraverserContext<Node> context) {
                names.add(node.getName());
                return TraversalControl.CONTINUE;
            }
        }, site);
        return names;
    }

    // ---------------------------------------------------------------------------------------
    // Federation tags and keys
    // ---------------------------------------------------------------------------------------

    /**
     * The minted types whose inherited tags the carriers' disagreement narrowed, for the build to
     * report: each carrier's tags beside the ones the type was given.
     *
     * <p>Read off the store rather than worked out again: the type's tags are
     * {@code graphitron_inherited_directive}'s, the rule's own output, and a narrowing is any tag a
     * carrier carries that is not among them.
     */
    private static List<TagNarrowing> narrowings(StoreHandle store) {
        var coinage = GRAPHITRON_MINTED_COINAGE;
        var minted = GRAPHITRON_TYPE_MINTED;
        var anchored = GRAPHITRON_FIELD;
        var field = GRAPHQL_FIELD;
        // The minted carriers first, fenced by OFFSET 0: joined with the fields in one block, H2
        // orders the four relations so that a few hundred rows take seconds on a large schema.
        var carrier = store.dsl()
            .select(coinage.GRAPH_NAME, coinage.TYPE_NAME, coinage.COORDINATE)
            .from(coinage)
            .join(minted).on(minted.GRAPH_NAME.eq(coinage.GRAPH_NAME)
                .and(minted.TYPE_NAME.eq(coinage.TYPE_NAME)))
            .where(coinage.GRAPH_NAME.eq(store.graphName()))
            .offset(inline(0))
            .asTable("carrier");
        var carrierGraph = carrier.field(coinage.GRAPH_NAME);
        var carrierType = carrier.field(coinage.TYPE_NAME);
        var carrierCoordinate = carrier.field(coinage.COORDINATE);
        // The carrier's own position, reached by name: the anchored field spells the coordinate's
        // parts, and the transcription is keyed by them.
        var carriers = store.dsl()
            .select(carrierType, carrierCoordinate, field.SOURCE_NAME, field.SOURCE_LINE,
                field.SOURCE_COLUMN)
            .from(carrier)
            .leftJoin(anchored).on(anchored.GRAPH_NAME.eq(carrierGraph)
                .and(anchored.COORDINATE.eq(carrierCoordinate)))
            .leftJoin(field).on(field.GRAPH_NAME.eq(anchored.GRAPH_NAME)
                .and(field.TYPE_NAME.eq(anchored.TYPE_NAME))
                .and(field.FIELD_NAME.eq(anchored.FIELD_NAME)))
            .orderBy(carrierType, carrierCoordinate)
            .fetch();
        var d = GRAPHITRON_CARRIER_DIRECTIVE;
        var carried = store.dsl()
            .select(d.TYPE_NAME, d.COORDINATE, d.DIRECTIVE_NAME, d.VALUE_SDL)
            .from(d)
            .where(d.GRAPH_NAME.eq(store.graphName()))
            .orderBy(d.TYPE_NAME, d.COORDINATE, d.DIRECTIVE_NAME, d.ORDINAL)
            .fetch();
        var i = GRAPHITRON_INHERITED_DIRECTIVE;
        var inherited = store.dsl()
            .select(i.TYPE_NAME, i.VALUE_SDL)
            .from(i)
            .where(i.GRAPH_NAME.eq(store.graphName()))
            .and(i.DIRECTIVE_NAME.eq(TAG_DIRECTIVE))
            .orderBy(i.TYPE_NAME, i.ORDINAL)
            .fetch();

        var keptByType = new LinkedHashMap<String, List<String>>();
        inherited.forEach(row -> keptByType.computeIfAbsent(row.get(i.TYPE_NAME),
            t -> new ArrayList<>()).add(tagName(row.get(i.VALUE_SDL))));
        var tagsByCarrier = new LinkedHashMap<List<String>, List<String>>();
        var shareableCarriers = new LinkedHashSet<List<String>>();
        for (var row : carried) {
            var key = List.of(row.get(d.TYPE_NAME), row.get(d.COORDINATE));
            if (SHAREABLE_DIRECTIVE.equals(row.get(d.DIRECTIVE_NAME))) {
                shareableCarriers.add(key);
            } else if (row.get(d.VALUE_SDL) != null) {
                var names = tagsByCarrier.computeIfAbsent(key, k -> new ArrayList<>());
                var name = tagName(row.get(d.VALUE_SDL));
                if (!names.contains(name)) {
                    names.add(name);
                }
            }
        }
        var carriersByType = new LinkedHashMap<String, List<Carrier>>();
        for (var row : carriers) {
            var key = List.of(row.get(carrierType), row.get(carrierCoordinate));
            var location = row.get(field.SOURCE_LINE) == null ? null
                : new SourceLocation(row.get(field.SOURCE_LINE), row.get(field.SOURCE_COLUMN),
                    row.get(field.SOURCE_NAME));
            carriersByType.computeIfAbsent(row.get(carrierType), t -> new ArrayList<>())
                .add(new Carrier(row.get(carrierCoordinate), tagsByCarrier.getOrDefault(key, List.of()),
                    shareableCarriers.contains(key), location));
        }
        var narrowings = new ArrayList<TagNarrowing>();
        carriersByType.forEach((typeName, typeCarriers) -> {
            var kept = keptByType.getOrDefault(typeName, List.of());
            var dropped = new LinkedHashSet<String>();
            typeCarriers.forEach(c -> c.tags().stream().filter(t -> !kept.contains(t))
                .forEach(dropped::add));
            if (!dropped.isEmpty()) {
                narrowings.add(new TagNarrowing(typeName, kept, List.copyOf(dropped), typeCarriers));
            }
        });
        return narrowings;
    }

    /** The name a {@code @tag}'s {@code name:} argument states, from its SDL. */
    private static String tagName(String valueSdl) {
        return Parser.parseValue(valueSdl) instanceof StringValue value ? value.getValue() : valueSdl;
    }

    /**
     * Applies what no document states to the elements the documents do: the directive applications
     * the configuration adds and the ones a macro mints, and the description the anchors carry where
     * it is not the one the document wrote. Each element's site is rewritten once, with all of it.
     */
    private static void applyUnwritten(TypeDefinitionRegistry patched, StoreHandle store) {
        var directives = directives(store);
        var parts = parts(store);
        var coordinates = new TreeSet<String>(directives.keySet());
        parts.forEach((coordinate, part) -> {
            if (part.redescribed()) {
                coordinates.add(coordinate);
            }
        });
        for (var coordinate : coordinates) {
            var part = parts.get(coordinate);
            if (part == null) {
                throw new IllegalStateException("The store applies something at '" + coordinate
                    + "', which no part relation holds. This is a defect in graphitron, not in the"
                    + " schema.");
            }
            var site = ElementSite.of(patched, part, coordinate);
            patched.remove(site.definition());
            patched.add(site.with(directives.getOrDefault(coordinate, List.of()),
                part.redescribed() ? part.description() : null));
        }
    }

    /**
     * One emitted element as its part relation states it: the type, the member and the argument
     * its coordinate names, its description, and whether that description is other than the one
     * its document wrote, which it is where the configuration appended a note.
     */
    private record Part(String typeName, String member, String argument, String description,
                        boolean redescribed) {}

    /**
     * Every emitted element by coordinate, read from the four part relations so the coordinate's
     * parts are the store's columns rather than a spelling taken apart here. An element no document
     * declared is never redescribed, there being nothing written to differ from.
     */
    private static Map<String, Part> parts(StoreHandle store) {
        var graph = store.graphName();
        var dsl = store.dsl();
        var t = GRAPHITRON_TYPE;
        var gt = GRAPHQL_TYPE;
        var f = GRAPHITRON_FIELD;
        var gf = GRAPHQL_FIELD;
        var a = GRAPHITRON_ARGUMENT;
        var ga = GRAPHQL_ARGUMENT;
        var v = GRAPHITRON_ENUM_VALUE;
        var gv = GRAPHQL_ENUM_VALUE;
        var rows = dsl
            .select(t.COORDINATE, t.TYPE_NAME, inline((String) null), inline((String) null),
                t.DESCRIPTION, field(gt.TYPE_NAME.isNotNull().and(t.DESCRIPTION.isDistinctFrom(gt.DESCRIPTION))))
            .from(t)
            .leftJoin(gt).on(gt.GRAPH_NAME.eq(t.GRAPH_NAME).and(gt.TYPE_NAME.eq(t.TYPE_NAME)))
            .where(t.GRAPH_NAME.eq(graph))
            .unionAll(dsl
                .select(f.COORDINATE, f.TYPE_NAME, f.FIELD_NAME, inline((String) null),
                    f.DESCRIPTION, field(gf.TYPE_NAME.isNotNull().and(f.DESCRIPTION.isDistinctFrom(gf.DESCRIPTION))))
                .from(f)
                .leftJoin(gf).on(gf.GRAPH_NAME.eq(f.GRAPH_NAME).and(gf.TYPE_NAME.eq(f.TYPE_NAME))
                    .and(gf.FIELD_NAME.eq(f.FIELD_NAME)))
                .where(f.GRAPH_NAME.eq(graph)))
            .unionAll(dsl
                .select(a.COORDINATE, a.TYPE_NAME, a.FIELD_NAME, a.ARGUMENT_NAME,
                    a.DESCRIPTION, field(ga.TYPE_NAME.isNotNull().and(a.DESCRIPTION.isDistinctFrom(ga.DESCRIPTION))))
                .from(a)
                .leftJoin(ga).on(ga.GRAPH_NAME.eq(a.GRAPH_NAME).and(ga.TYPE_NAME.eq(a.TYPE_NAME))
                    .and(ga.FIELD_NAME.eq(a.FIELD_NAME)).and(ga.ARGUMENT_NAME.eq(a.ARGUMENT_NAME)))
                .where(a.GRAPH_NAME.eq(graph)))
            .unionAll(dsl
                .select(v.COORDINATE, v.TYPE_NAME, v.VALUE_NAME, inline((String) null),
                    v.DESCRIPTION, field(gv.TYPE_NAME.isNotNull().and(v.DESCRIPTION.isDistinctFrom(gv.DESCRIPTION))))
                .from(v)
                .leftJoin(gv).on(gv.GRAPH_NAME.eq(v.GRAPH_NAME).and(gv.TYPE_NAME.eq(v.TYPE_NAME))
                    .and(gv.VALUE_NAME.eq(v.VALUE_NAME)))
                .where(v.GRAPH_NAME.eq(graph)))
            .fetch();
        var parts = new LinkedHashMap<String, Part>();
        for (var row : rows) {
            parts.put(row.value1(), new Part(row.value2(), row.value3(), row.value4(), row.value5(),
                Boolean.TRUE.equals(row.value6())));
        }
        return parts;
    }

    /**
     * The directive applications no document states, by coordinate: the ones the configuration adds
     * and the ones a macro mints.
     *
     * <p>Read from {@code graphitron_directive_application}'s {@code CONFIGURED} and {@code MINTED}
     * rows, the registry this patches already holding the authored ones, and rendered from their
     * arguments as the rule stated them, so nothing here knows what any of them means. Arguments
     * come in name order, which for every application of this kind today is the definition's order.
     * Each lands after what its element already carries.
     *
     * <p>An element already carrying the same application means the rule was applied to a registry
     * that had already been through it, which is a generator defect and ends the run instead of
     * being skipped.
     */
    private static Map<String, List<Directive>> directives(StoreHandle store) {
        var d = GRAPHITRON_DIRECTIVE_APPLICATION;
        var a = GRAPHITRON_DIRECTIVE_APPLICATION_ARG;
        var rows = store.dsl()
            .select(d.COORDINATE, d.DIRECTIVE_NAME, d.ORDINAL, a.DIRECTIVE_ARGUMENT_NAME, a.VALUE_SDL)
            .from(d)
            .leftJoin(a).on(a.GRAPH_NAME.eq(d.GRAPH_NAME).and(a.COORDINATE.eq(d.COORDINATE))
                .and(a.DIRECTIVE_NAME.eq(d.DIRECTIVE_NAME)).and(a.ORDINAL.eq(d.ORDINAL)))
            .where(d.GRAPH_NAME.eq(store.graphName()))
            .and(d.ORIGIN.in(CONFIGURED, MINTED))
            .orderBy(d.COORDINATE, d.DIRECTIVE_NAME, d.ORDINAL, a.DIRECTIVE_ARGUMENT_NAME)
            .fetch();
        var applications = new LinkedHashMap<List<Object>, Directive.Builder>();
        var coordinates = new LinkedHashMap<List<Object>, String>();
        for (var row : rows) {
            var key = List.<Object>of(row.get(d.COORDINATE), row.get(d.DIRECTIVE_NAME),
                row.get(d.ORDINAL));
            var builder = applications.computeIfAbsent(key,
                k -> Directive.newDirective().name(row.get(d.DIRECTIVE_NAME)));
            coordinates.putIfAbsent(key, row.get(d.COORDINATE));
            if (row.get(a.DIRECTIVE_ARGUMENT_NAME) != null) {
                builder.argument(Argument.newArgument(row.get(a.DIRECTIVE_ARGUMENT_NAME),
                    Parser.parseValue(row.get(a.VALUE_SDL))).build());
            }
        }
        var byCoordinate = new LinkedHashMap<String, List<Directive>>();
        applications.forEach((key, builder) ->
            byCoordinate.computeIfAbsent(coordinates.get(key), c -> new ArrayList<>())
                .add(builder.build()));
        return byCoordinate;
    }

    /**
     * The definition that declares the element at a coordinate: the type's own for a type, and for
     * a member or an argument whichever of the type's definition and extensions declares the
     * member. A registry does not merge a type's sites, so what is applied lands on the one its
     * element is written in.
     */
    private record ElementSite(SDLDefinition<?> definition, String coordinate, String member,
                               String argument) {

        static ElementSite of(TypeDefinitionRegistry registry, Part part, String coordinate) {
            String typeName = part.typeName();
            String member = part.member();
            String argument = part.argument();
            var base = registry.getTypeOrNull(typeName);
            if (base != null && (member == null || declares(base, member))) {
                return new ElementSite(base, coordinate, member, argument);
            }
            var extensions = new ArrayList<SDLDefinition<?>>();
            extensions.addAll(registry.objectTypeExtensions().getOrDefault(typeName, List.of()));
            extensions.addAll(registry.interfaceTypeExtensions().getOrDefault(typeName, List.of()));
            extensions.addAll(registry.inputObjectTypeExtensions().getOrDefault(typeName, List.of()));
            extensions.addAll(registry.enumTypeExtensions().getOrDefault(typeName, List.of()));
            for (var extension : extensions) {
                if (member != null && declares(extension, member)) {
                    return new ElementSite(extension, coordinate, member, argument);
                }
            }
            throw new IllegalStateException("The store applies something at '" + coordinate
                + "', which the emitted registry does not hold. This is a defect in graphitron,"
                + " not in the schema.");
        }

        private static boolean declares(SDLDefinition<?> site, String member) {
            return switch (site) {
                case ObjectTypeDefinition o -> o.getFieldDefinitions().stream()
                    .anyMatch(f -> f.getName().equals(member));
                case InterfaceTypeDefinition i -> i.getFieldDefinitions().stream()
                    .anyMatch(f -> f.getName().equals(member));
                case InputObjectTypeDefinition i -> i.getInputValueDefinitions().stream()
                    .anyMatch(v -> v.getName().equals(member));
                case EnumTypeDefinition e -> e.getEnumValueDefinitions().stream()
                    .anyMatch(v -> v.getName().equals(member));
                default -> false;
            };
        }

        /**
         * The site with {@code added} applied to its element and its description replaced by
         * {@code description} where that is not null; an extension stays one.
         */
        SDLDefinition<?> with(List<Directive> added, String description) {
            var edit = new Edit(coordinate, added, description);
            if (member == null) {
                return switch (definition) {
                    case ObjectTypeDefinition o -> o.transform(b -> b.directives(edit.directives(o)).description(edit.description(o)));
                    case InterfaceTypeDefinition i -> i.transform(b -> b.directives(edit.directives(i)).description(edit.description(i)));
                    case UnionTypeDefinition u -> u.transform(b -> b.directives(edit.directives(u)).description(edit.description(u)));
                    case EnumTypeDefinition e -> e.transform(b -> b.directives(edit.directives(e)).description(edit.description(e)));
                    case InputObjectTypeDefinition i -> i.transform(b -> b.directives(edit.directives(i)).description(edit.description(i)));
                    case ScalarTypeDefinition sc -> sc.transform(b -> b.directives(edit.directives(sc)).description(edit.description(sc)));
                    default -> throw new IllegalStateException("Nothing lands on " + definition);
                };
            }
            // Extensions before their base types: transform() on an extension builds the base type,
            // which would turn the extension into a second definition.
            return switch (definition) {
                case ObjectTypeExtensionDefinition o ->
                    o.transformExtension(b -> b.fieldDefinitions(fields(o.getFieldDefinitions(), edit)));
                case ObjectTypeDefinition o ->
                    o.transform(b -> b.fieldDefinitions(fields(o.getFieldDefinitions(), edit)));
                case InterfaceTypeExtensionDefinition i ->
                    i.transformExtension(b -> b.definitions(fields(i.getFieldDefinitions(), edit)));
                case InterfaceTypeDefinition i ->
                    i.transform(b -> b.definitions(fields(i.getFieldDefinitions(), edit)));
                case InputObjectTypeExtensionDefinition i -> i.transformExtension(
                    b -> b.inputValueDefinitions(inputValues(i.getInputValueDefinitions(), member, edit)));
                case InputObjectTypeDefinition i -> i.transform(
                    b -> b.inputValueDefinitions(inputValues(i.getInputValueDefinitions(), member, edit)));
                case EnumTypeExtensionDefinition e -> e.transformExtension(
                    b -> b.enumValueDefinitions(enumValues(e.getEnumValueDefinitions(), edit)));
                case EnumTypeDefinition e -> e.transform(
                    b -> b.enumValueDefinitions(enumValues(e.getEnumValueDefinitions(), edit)));
                default -> throw new IllegalStateException("No member lands on " + definition);
            };
        }

        private List<FieldDefinition> fields(List<FieldDefinition> fields, Edit edit) {
            return fields.stream().map(f -> !f.getName().equals(member) ? f
                : argument == null
                    ? f.transform(b -> b.directives(edit.directives(f)).description(edit.description(f)))
                    : f.transform(b -> b.inputValueDefinitions(
                        inputValues(f.getInputValueDefinitions(), argument, edit))))
                .toList();
        }

        private List<InputValueDefinition> inputValues(List<InputValueDefinition> values,
                                                       String name, Edit edit) {
            return values.stream().map(v -> !v.getName().equals(name) ? v
                : v.transform(b -> b.directives(edit.directives(v)).description(edit.description(v))))
                .toList();
        }

        private List<EnumValueDefinition> enumValues(List<EnumValueDefinition> values, Edit edit) {
            return values.stream().map(v -> !v.getName().equals(member) ? v
                : v.transform(b -> b.directives(edit.directives(v)).description(edit.description(v))))
                .toList();
        }

        /** What is applied to the one element, against what it already carries. */
        private record Edit(String coordinate, List<Directive> added, String description) {

            List<Directive> directives(DirectivesContainer<?> element) {
                var directives = new ArrayList<>(element.getDirectives());
                for (var directive : added) {
                    var printed = AstPrinter.printAst(directive);
                    if (directives.stream().anyMatch(held -> AstPrinter.printAst(held).equals(printed))) {
                        throw new IllegalStateException("'" + coordinate + "' already carries the " + printed
                            + " the store applies to it. This is a defect in graphitron, not in the"
                            + " schema: the emitted registry was derived from a registry that had"
                            + " already been through it.");
                    }
                    directives.add(directive);
                }
                return directives;
            }

            /** The element's description, replaced where the anchors state another. */
            Description description(AbstractDescribedNode<?> element) {
                return description == null ? element.getDescription()
                    : EmittedRegistry.description(description);
            }
        }
    }

    /**
     * The node types the anchor mints {@code @nodeType} on, which is every node type of a graph that
     * composes the directive and none of any other. The applications themselves are
     * {@link #directives}'s, rendered like any minted one; what this and the two methods
     * below add is the directive's definition and the refusal of an author who wrote it.
     */
    private static List<String> mintedNodeTypes(StoreHandle store) {
        var d = GRAPHITRON_DIRECTIVE_APPLICATION;
        return store.dsl().select(d.COORDINATE).from(d)
            .where(d.GRAPH_NAME.eq(store.graphName()))
            .and(d.ORIGIN.eq(MINTED))
            .and(d.DIRECTIVE_NAME.eq(NODE_TYPE_DIRECTIVE))
            .orderBy(d.COORDINATE)
            .fetch(d.COORDINATE);
    }

    /**
     * Refuses a {@code @nodeType} the author wrote, a declaration of it or an application on a node
     * type, in a graph that composes it. Graphitron owns the value, and two sources for one fact
     * would disagree, so it is the author's error, raised before assembly turns it into a failure
     * reported as a defect in the generator.
     */
    private static void refuseAuthoredNodeType(TypeDefinitionRegistry patched, List<String> nodeTypes) {
        if (nodeTypes.isEmpty()) {
            return;
        }
        var declared = patched.getDirectiveDefinition(NODE_TYPE_DIRECTIVE);
        if (declared.isPresent()) {
            throw authoredNodeType("directive @" + NODE_TYPE_DIRECTIVE + " is declared here, but"
                + " graphitron declares it for a schema that composes it",
                declared.get().getSourceLocation());
        }
        for (var coordinate : nodeTypes) {
            if (!(patched.getTypeOrNull(coordinate) instanceof ObjectTypeDefinition object)) {
                continue;
            }
            var sites = new ArrayList<ObjectTypeDefinition>();
            sites.add(object);
            sites.addAll(patched.objectTypeExtensions().getOrDefault(coordinate, List.of()));
            for (var site : sites) {
                var written = site.getDirectives(NODE_TYPE_DIRECTIVE);
                if (!written.isEmpty()) {
                    throw authoredNodeType("@" + NODE_TYPE_DIRECTIVE + " is written on type '"
                        + coordinate + "', but graphitron applies it to every node type of a schema"
                        + " that composes it, with the id it encodes into ids",
                        written.get(0).getSourceLocation());
                }
            }
        }
    }

    /** Adds the directive's definition once, when anything was minted that uses it. */
    private static void defineNodeType(TypeDefinitionRegistry patched, List<String> nodeTypes) {
        if (nodeTypes.isEmpty()) {
            return;
        }
        patched.add(Parser.parse(NODE_TYPE_DIRECTIVE_SDL)
            .getFirstDefinitionOfType(DirectiveDefinition.class).orElseThrow());
    }

    private static ValidationFailedException authoredNodeType(String message, SourceLocation location) {
        return new ValidationFailedException(List.of(new ValidationError(null,
            Rejection.invalidSchema(message + ". Remove it: composing @" + NODE_TYPE_DIRECTIVE
                + " is the only opt-in."), location)));
    }

    // ---------------------------------------------------------------------------------------
    // Values
    // ---------------------------------------------------------------------------------------

    /**
     * The written type expression, parsed. Taken from {@code type_sdl} rather than rebuilt from the
     * decomposed wrapper columns beside it: those describe one list level, so they agree about
     * {@code [[Film]]} and {@code [Film]}, and a reconstruction owes the expression exactly.
     */
    private static Type<?> type(String typeSdl) {
        return Parser.parseType(typeSdl);
    }

    private static Description description(String text) {
        return text == null ? null : new Description(text, null, text.contains("\n"));
    }

    private static String printed(Type<?> type) {
        return graphql.language.AstPrinter.printAst(type);
    }

}
