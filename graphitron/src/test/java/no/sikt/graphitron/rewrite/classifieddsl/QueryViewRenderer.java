package no.sikt.graphitron.rewrite.classifieddsl;

import graphql.language.AstPrinter;
import graphql.language.Comment;
import graphql.language.Definition;
import graphql.language.Description;
import graphql.language.Directive;
import graphql.language.Document;
import graphql.language.EnumTypeDefinition;
import graphql.language.Field;
import graphql.language.FieldDefinition;
import graphql.language.FragmentDefinition;
import graphql.language.FragmentSpread;
import graphql.language.InlineFragment;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InterfaceTypeDefinition;
import graphql.language.Node;
import graphql.language.ObjectTypeDefinition;
import graphql.language.OperationDefinition;
import graphql.language.ScalarTypeDefinition;
import graphql.language.Selection;
import graphql.language.SelectionSet;
import graphql.language.TypeDefinition;
import graphql.language.UnionTypeDefinition;
import graphql.parser.Parser;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLFieldsContainer;
import graphql.schema.GraphQLInputObjectField;
import graphql.schema.GraphQLInputObjectType;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLNamedType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLScalarType;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLUnionType;
import graphql.schema.idl.ScalarInfo;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.rewrite.GraphitronSchemaBuilder;
import no.sikt.graphitron.rewrite.TestSchemaHelper;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The query-as-view renderer. Prose embeds a GraphQL <em>query</em> (or a fragment {@code on Type})
 * naming the coordinates it wants to show; the renderer resolves that selection against the
 * assembled corpus schema and regenerates minimal SDL for the touched closure. One mechanism does
 * three jobs at once:
 *
 * <ol>
 *   <li><b>Import what's relevant</b>: the selection set is the projection, reusing GraphQL's own
 *       selection mechanism instead of bespoke include-tags.</li>
 *   <li><b>Strip the test directives</b>: regeneration emits only real schema, so {@code @classified}
 *       / {@code @classifiedType} are never printed, while real Graphitron directives survive.</li>
 *   <li><b>Bound the snippet</b>: each touched field container is pruned to exactly the selected
 *       fields, so a sibling field or type the selection did not name is not dragged in.</li>
 * </ol>
 *
 * <p>The selection-and-prune technique follows {@code no.sikt.fs.app.util.GraphQLSubsetter}
 * (the <em>graphql-scissors</em> library), but where scissors strips <em>all</em> directives and
 * re-assembles through {@code makeExecutableSchema} + {@code SchemaPrinter}, this renderer prints
 * the pruned <em>AST</em> directly ({@link AstPrinter}). That keeps real directives without forcing
 * the whole directive-definition and enum vocabulary into the pruned subset, and prints the
 * <em>authored</em> SDL from the parsed registry (so {@code @asConnection} renders as written
 * rather than as the generated {@code *Connection} wrapper the schema transform produces).
 *
 * <p><strong>Selection forms.</strong> A document may carry an operation ({@code query} /
 * {@code mutation}) or stand as a bare {@code fragment F on Type { ... }}; the latter is the
 * <em>type-display</em> form, naming an output type directly without routing a query through a
 * field that happens to return it (so types no query reaches still render).
 *
 * <p><strong>Closure.</strong> The projection is the selection's reachability, with two expansions
 * so the excerpt does not reference types it never shows (the closure-honesty rule): a kept field's
 * argument types are emitted in full, recursing through nested input objects, and a union or
 * interface reached by a kept field (or named by a fragment {@code on}) is emitted so a polymorphic
 * excerpt shows its declaration. A scalar or enum the <em>fixture</em> declares is emitted for the
 * same reason: it is leaf vocabulary, but a leaf the excerpt names and never shows is exactly what
 * closure honesty forbids, and for a scalar the declaration carries the {@code @scalarType} binding
 * that makes the type mean anything. Spec built-ins and the prelude's own enums are not the
 * fixture's to show, so they stay out; so do the generated {@code @asConnection} wrappers, where the
 * authored field that produces them prints instead.
 *
 * <p><strong>Descriptions.</strong> The projection query is the per-example place to say <em>why</em>
 * a coordinate exists, which the shared description-free corpus fixture cannot. A {@code # ...}
 * comment line above a selected coordinate renders as that coordinate's SDL {@code Description}:
 * above a field it describes the field, above {@code ... on T} or a top-level
 * {@code fragment f on T} it describes type {@code T}; multiple comment lines join into a
 * block-string description. Comments carry field prose because {@code Field} is not a
 * {@code DescribedNode} in any graphql-java version; see {@link #descriptionOf(Node)}. A projection
 * with no comments renders unchanged.
 */
public final class QueryViewRenderer {

    private QueryViewRenderer() {}

    /**
     * The test-only directive names to strip from rendered excerpts, derived from the parsed
     * {@link CorpusDocuments#prelude()}'s directive definitions (the
     * {@link no.sikt.graphitron.rewrite.schema.DeclaredDirectives} derivation shape) rather
     * than hand-maintained, so a new corpus directive cannot leak into the published triggers
     * page by construction.
     */
    private static final Set<String> INTERNAL_DIRECTIVES = Set.copyOf(
        new graphql.schema.idl.SchemaParser().parse(CorpusDocuments.prelude())
            .getDirectiveDefinitions().keySet());

    /**
     * The type names the prelude declares, derived the same way as {@link #INTERNAL_DIRECTIVES}.
     * The leaf expansion below emits what the <em>fixture</em> declares; the prelude's assertion
     * vocabulary is not part of any example and would be noise on the page.
     */
    private static final Set<String> PRELUDE_TYPES = Set.copyOf(
        new graphql.schema.idl.SchemaParser().parse(CorpusDocuments.prelude()).types().keySet());

    /**
     * The output-field coordinates the {@code selection} touches, parent type to field names.
     *
     * <p>Exposed because the outcome block beside a rendered example must speak about exactly the
     * coordinates the example shows. Deriving that set a second way, from the rendered SDL or from
     * the fixture, would let the two halves of one example disagree about what the example is.
     */
    public static Map<String, Set<String>> touchedCoordinates(String fixtureSdl, String selection) {
        String full = CorpusDocuments.prelude() + "\n" + fixtureSdl;
        TypeDefinitionRegistry registry = TestSchemaHelper.parseRegistryWithPrelude(full);
        GraphQLSchema schema = GraphitronSchemaBuilder.buildBundle(registry, TestConfiguration.testContext()).assembled();

        Document doc = new Parser().parseDocument(selection);
        Touched touched = new Touched();
        new Walk(schema, indexFragments(doc), touched).fromDocument(doc);
        return Map.copyOf(touched.fieldsByParent);
    }

    /** Renders the SDL closure the {@code selection} (a query/mutation or fragment document) touches over {@code fixtureSdl}. */
    public static String render(String fixtureSdl, String selection) {
        String full = CorpusDocuments.prelude() + "\n" + fixtureSdl;
        TypeDefinitionRegistry registry = TestSchemaHelper.parseRegistryWithPrelude(full);
        GraphQLSchema schema = GraphitronSchemaBuilder.buildBundle(registry, TestConfiguration.testContext()).assembled();

        Document doc = new Parser().parseDocument(selection);
        Touched touched = new Touched();
        new Walk(schema, indexFragments(doc), touched).fromDocument(doc);

        var sb = new StringBuilder();
        // 1. Output field containers, pruned to the selected fields.
        for (var entry : touched.fieldsByParent.entrySet()) {
            TypeDefinition<?> def = merged(registry, entry.getKey());
            if (def != null) {
                append(sb, prune(def, entry.getValue(), touched));
            }
        }
        // 2. Abstract output types referenced but not field-selected (unions; interfaces reached only via fragments).
        for (String name : touched.abstractTypes) {
            if (!touched.fieldsByParent.containsKey(name)) {
                TypeDefinition<?> def = merged(registry, name);
                if (def != null) {
                    append(sb, stripInternalDirectives(def, touched));
                }
            }
        }
        // 3. Input-object closure reached from the kept fields' arguments.
        for (String name : touched.inputTypes) {
            TypeDefinition<?> def = merged(registry, name);
            if (def != null) {
                append(sb, stripInternalDirectives(def, touched));
            }
        }
        // 4. Leaf types the fixture declares and the excerpt names (closure honesty).
        for (String name : touched.leafTypes) {
            if (PRELUDE_TYPES.contains(name) || ScalarInfo.isGraphqlSpecifiedScalar(name)) {
                continue;
            }
            TypeDefinition<?> def = merged(registry, name);
            if (def != null) {
                append(sb, stripInternalDirectives(def, touched));
            }
        }
        return sb.toString().strip();
    }

    private static void append(StringBuilder sb, TypeDefinition<?> def) {
        String printed = AstPrinter.printAst(def).strip();
        if (!printed.isEmpty()) sb.append(printed).append("\n\n");
    }

    private static Map<String, FragmentDefinition> indexFragments(Document doc) {
        Map<String, FragmentDefinition> fragments = new HashMap<>();
        for (Definition<?> def : doc.getDefinitions()) {
            if (def instanceof FragmentDefinition frag) {
                fragments.put(frag.getName(), frag);
            }
        }
        return fragments;
    }

    /**
     * The coordinates a selection touches: pruned field containers, the abstract/input closures to
     * emit whole, and the comment-authored description prose. Field comments land under
     * {@link #fieldDescriptions} keyed by {@code (parent, field)}; type comments land under
     * {@link #typeDescriptions} keyed by type name. The emit loop reads these back and stamps them
     * on as SDL descriptions.
     */
    private static final class Touched {
        final Map<String, Set<String>> fieldsByParent = new LinkedHashMap<>();
        final Set<String> abstractTypes = new LinkedHashSet<>();
        final Set<String> inputTypes = new LinkedHashSet<>();
        final Set<String> leafTypes = new LinkedHashSet<>();
        final Map<String, String> typeDescriptions = new LinkedHashMap<>();
        final Map<String, Map<String, String>> fieldDescriptions = new LinkedHashMap<>();
    }

    /** Walks a parsed selection against the assembled schema, recording the {@link Touched} closure. */
    private static final class Walk {
        private final GraphQLSchema schema;
        private final Map<String, FragmentDefinition> fragments;
        private final Touched out;
        private final Set<String> inputSeen = new HashSet<>();

        Walk(GraphQLSchema schema, Map<String, FragmentDefinition> fragments, Touched out) {
            this.schema = schema;
            this.fragments = fragments;
            this.out = out;
        }

        void fromDocument(Document doc) {
            for (Definition<?> def : doc.getDefinitions()) {
                if (def instanceof OperationDefinition op) {
                    walk(op.getSelectionSet(), rootType(op));
                } else if (def instanceof FragmentDefinition frag) {
                    String typeName = frag.getTypeCondition().getName();
                    recordTypeDescription(typeName, frag);
                    walk(frag.getSelectionSet(), schema.getType(typeName));
                }
            }
        }

        private GraphQLType rootType(OperationDefinition op) {
            return switch (op.getOperation()) {
                case MUTATION -> schema.getMutationType();
                case SUBSCRIPTION -> schema.getSubscriptionType();
                case QUERY -> schema.getQueryType();
            };
        }

        private void walk(SelectionSet selectionSet, GraphQLType parent) {
            if (selectionSet == null || parent == null) {
                return;
            }
            if (parent instanceof GraphQLUnionType union) {
                out.abstractTypes.add(union.getName());
            }
            for (Selection<?> selection : selectionSet.getSelections()) {
                switch (selection) {
                    case Field field -> visitField(field, parent);
                    case InlineFragment inline -> {
                        String typeName = inline.getTypeCondition().getName();
                        recordTypeDescription(typeName, inline);
                        walk(inline.getSelectionSet(), schema.getType(typeName));
                    }
                    case FragmentSpread spread -> {
                        FragmentDefinition frag = fragments.get(spread.getName());
                        if (frag != null) {
                            walk(frag.getSelectionSet(), schema.getType(frag.getTypeCondition().getName()));
                        }
                    }
                    default -> { /* directives-only / introspection selections carry no coordinate */ }
                }
            }
        }

        private void visitField(Field field, GraphQLType parent) {
            if (!(parent instanceof GraphQLFieldsContainer container)) {
                return;
            }
            GraphQLFieldDefinition fieldDef = container.getFieldDefinition(field.getName());
            if (fieldDef == null) {
                return;
            }
            out.fieldsByParent.computeIfAbsent(container.getName(), k -> new LinkedHashSet<>()).add(field.getName());
            String description = descriptionOf(field);
            if (description != null) {
                out.fieldDescriptions
                    .computeIfAbsent(container.getName(), k -> new LinkedHashMap<>())
                    .put(field.getName(), description);
            }
            for (GraphQLArgument arg : fieldDef.getArguments()) {
                collectInputClosure(GraphQLTypeUtil.unwrapAll(arg.getType()));
            }
            GraphQLType target = GraphQLTypeUtil.unwrapAll(fieldDef.getType());
            if (target instanceof GraphQLUnionType || target instanceof GraphQLInterfaceType) {
                out.abstractTypes.add(((GraphQLNamedType) target).getName());
            }
            recordLeaf(target);
            walk(field.getSelectionSet(), target);
        }

        private void collectInputClosure(GraphQLType type) {
            recordLeaf(type);
            if (type instanceof GraphQLInputObjectType input && inputSeen.add(input.getName())) {
                out.inputTypes.add(input.getName());
                for (GraphQLInputObjectField inputField : input.getFieldDefinitions()) {
                    collectInputClosure(GraphQLTypeUtil.unwrapAll(inputField.getType()));
                }
            }
        }

        /**
         * Records a scalar or enum the excerpt names. Whether it is the fixture's to show is the
         * emit loop's call: this only says the excerpt reached it.
         */
        private void recordLeaf(GraphQLType type) {
            if (type instanceof GraphQLScalarType || type instanceof GraphQLEnumType) {
                out.leafTypes.add(((GraphQLNamedType) type).getName());
            }
        }

        private void recordTypeDescription(String typeName, Node<?> carrier) {
            String description = descriptionOf(carrier);
            if (description != null) {
                out.typeDescriptions.put(typeName, description);
            }
        }
    }

    /**
     * The type named {@code name}, with its {@code extend} blocks folded into it.
     *
     * <p>graphql-java keeps a type's extensions beside its definition rather than in it. A fixture
     * contributes its roots with {@code extend type Query}, because the base schema in
     * {@link CorpusDocuments#prelude()} declares the root once for every fixture, so reading the
     * definition alone would render the base schema's {@code Query} and none of the fields the
     * example is about. Folding here also keeps {@code extend} out of the rendered block: the page
     * shows the reader the schema they would write, not how the corpus assembles it.
     */
    private static TypeDefinition<?> merged(TypeDefinitionRegistry registry, String name) {
        TypeDefinition<?> def = registry.getTypeOrNull(name);
        if (def == null) return null;
        return switch (def) {
            case ObjectTypeDefinition o -> {
                List<FieldDefinition> extra = registry.objectTypeExtensions()
                    .getOrDefault(name, List.of()).stream()
                    .flatMap(e -> e.getFieldDefinitions().stream())
                    .toList();
                yield extra.isEmpty() ? o : o.transform(b -> b.fieldDefinitions(
                    Stream.concat(o.getFieldDefinitions().stream(), extra.stream()).toList()));
            }
            case InterfaceTypeDefinition i -> {
                List<FieldDefinition> extra = registry.interfaceTypeExtensions()
                    .getOrDefault(name, List.of()).stream()
                    .flatMap(e -> e.getFieldDefinitions().stream())
                    .toList();
                yield extra.isEmpty() ? i : i.transform(b -> b.definitions(
                    Stream.concat(i.getFieldDefinitions().stream(), extra.stream()).toList()));
            }
            default -> def;
        };
    }

    /** Keeps only the touched fields of {@code def} and strips the internal directives from what remains. */
    private static TypeDefinition<?> prune(TypeDefinition<?> def, Set<String> keep, Touched touched) {
        String typeDescription = touched.typeDescriptions.get(def.getName());
        return switch (def) {
            case ObjectTypeDefinition o -> o.transform(b -> {
                b.fieldDefinitions(keptFields(o.getName(), o.getFieldDefinitions(), keep, touched))
                    .directives(realDirectives(o.getDirectives()));
                applyDescription(b::description, typeDescription);
            });
            case InterfaceTypeDefinition i -> i.transform(b -> {
                b.definitions(keptFields(i.getName(), i.getFieldDefinitions(), keep, touched))
                    .directives(realDirectives(i.getDirectives()));
                applyDescription(b::description, typeDescription);
            });
            default -> stripInternalDirectives(def, touched);
        };
    }

    /** Emits a type whole (all fields kept), stripping only the internal test directives at every level. */
    private static TypeDefinition<?> stripInternalDirectives(TypeDefinition<?> def, Touched touched) {
        String typeDescription = touched.typeDescriptions.get(def.getName());
        return switch (def) {
            case ObjectTypeDefinition o -> o.transform(b -> {
                b.directives(realDirectives(o.getDirectives()));
                applyDescription(b::description, typeDescription);
            });
            case InterfaceTypeDefinition i -> i.transform(b -> {
                b.directives(realDirectives(i.getDirectives()));
                applyDescription(b::description, typeDescription);
            });
            case UnionTypeDefinition u -> u.transform(b -> {
                b.directives(realDirectives(u.getDirectives()));
                applyDescription(b::description, typeDescription);
            });
            case EnumTypeDefinition e -> e.transform(b -> {
                b.directives(realDirectives(e.getDirectives()));
                applyDescription(b::description, typeDescription);
            });
            case ScalarTypeDefinition s -> s.transform(b -> {
                b.directives(realDirectives(s.getDirectives()));
                applyDescription(b::description, typeDescription);
            });
            case InputObjectTypeDefinition io -> io.transform(b -> {
                b.directives(realDirectives(io.getDirectives()))
                    .inputValueDefinitions(io.getInputValueDefinitions().stream()
                        .map(iv -> iv.transform(vb -> vb.directives(realDirectives(iv.getDirectives()))))
                        .toList());
                applyDescription(b::description, typeDescription);
            });
            default -> def;
        };
    }

    private static List<FieldDefinition> keptFields(String parentName, List<FieldDefinition> fields, Set<String> keep, Touched touched) {
        Map<String, String> descriptions = touched.fieldDescriptions.getOrDefault(parentName, Map.of());
        return fields.stream()
            .filter(f -> keep.contains(f.getName()))
            .map(f -> f.transform(b -> {
                b.directives(realDirectives(f.getDirectives()));
                applyDescription(b::description, descriptions.get(f.getName()));
            }))
            .toList();
    }

    private static List<Directive> realDirectives(List<Directive> directives) {
        return directives.stream().filter(d -> !INTERNAL_DIRECTIVES.contains(d.getName())).toList();
    }

    /**
     * The single description-source seam: every description text comes from {@code # ...} comments
     * on the selection AST. Native executable {@code getDescription()} reads (available in
     * graphql-java releases past the pinned 25.0) fold in here without the output side changing;
     * {@code Field} prose stays comment-sourced regardless, since {@code Field} is never a
     * {@code DescribedNode}.
     */
    private static String descriptionOf(Node<?> node) {
        List<Comment> comments = node.getComments();
        if (comments == null || comments.isEmpty()) {
            return null;
        }
        String joined = comments.stream()
            .map(comment -> comment.getContent().strip())
            .collect(Collectors.joining("\n"));
        return joined.isEmpty() ? null : joined;
    }

    /** Stamps a recorded description onto a builder, as a block string when the text spans lines. */
    private static void applyDescription(Consumer<Description> setter, String text) {
        if (text != null) {
            setter.accept(new Description(text, null, text.contains("\n")));
        }
    }
}
