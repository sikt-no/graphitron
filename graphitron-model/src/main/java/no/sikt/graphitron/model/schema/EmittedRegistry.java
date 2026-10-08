package no.sikt.graphitron.model.schema;

import graphql.language.Argument;
import graphql.language.BooleanValue;
import graphql.language.Description;
import graphql.language.Directive;
import graphql.language.FieldDefinition;
import graphql.language.ImplementingTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.language.ObjectTypeExtensionDefinition;
import graphql.language.SDLDefinition;
import graphql.language.SourceLocation;
import graphql.language.StringValue;
import graphql.language.Type;
import graphql.language.TypeDefinition;
import graphql.parser.Parser;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.model.diagnostics.BuildWarning;
import no.sikt.graphitron.model.lint.LintRule;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.model.schema.federation.FederationKeyFieldsParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_MINTED_COINAGE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_SYNTHESIZED_FEDERATION_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE_MINTED;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.selectOne;

/**
 * The registry the generator emits, derived from the one capture transcribed plus what the store
 * says was synthesised on top of it.
 *
 * <p>The document an author writes and the schema graphitron emits are not the same document, and
 * the difference is entirely synthesis. {@code AttributedRegistry.load} marks the line in its own
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
 * with {@code graphitron_minted_*.precedence} applied. Reading the minted relations directly and
 * re-applying precedence here would put that rule in two places, which is the defect this class
 * exists to remove rather than to reproduce. A coordinate several applications disagree about is
 * {@code graphitron_minted_conflict}'s and the anchors deliberately hold no row for it, so a
 * contested coordinate reaches this patch as an absence and needs no arm.
 *
 * <p>Federation keys come from {@code graphitron_synthesized_federation_key}, the derivation itself,
 * whose own comment says the relation is its own provenance. That is the whole of how a derived
 * application is told from an authored one here: by which relation it was read from. The composed
 * {@code intent_federation_key} beside it is for a reader wanting every key the emitted schema
 * carries, which this is not, the authored applications already being in the registry this patch
 * started from.
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
 * <p>Object types only. The {@code CHECK} on {@code graphitron_minted_type.kind} admits
 * {@code OBJECT} and nothing else, because the macros mint nothing else, so an input object, enum,
 * union, interface or scalar reaches the emitted registry exactly as the author wrote it and this
 * class does not visit one.
 *
 * <h3>Which registry it starts from</h3>
 *
 * <p>The pre-synthesis one, and this class takes it off the {@link AttributedRegistry} itself
 * rather than trusting a caller to pick the handle. The other handle has already been through
 * {@code KeyNodeSynthesiser}, so starting from it applies every synthesised key a second time; the
 * choice belongs with the class that states the contract, and production and tests reach the
 * derivation through one input shape.
 *
 * <p>The registry is not modified; the patch is applied to a copy, so a caller holding the
 * read-only pre-synthesis snapshot keeps it.
 */
public final class EmittedRegistry {

    /** The one kind {@code graphitron_minted_type.kind}'s CHECK admits, and so the only kind here. */
    private static final String OBJECT = "OBJECT";

    private static final String TAG_DIRECTIVE = "tag";
    private static final String TAG_NAME_ARG = "name";
    private static final String SHAREABLE_DIRECTIVE = "shareable";
    private static final String KEY_DIRECTIVE = "key";
    private static final String KEY_FIELDS_ARG = "fields";
    private static final String KEY_RESOLVABLE_ARG = "resolvable";

    private EmittedRegistry() {}

    /**
     * The emitted registry for {@code store}'s graph, derived from {@code attributed}'s
     * pre-synthesis registry.
     *
     * @param attributed the run's registry; a caller with no synthesis to account for wraps its
     *                   own with {@link AttributedRegistry#AttributedRegistry(TypeDefinitionRegistry, java.util.Set)},
     *                   whose two handles are one object
     * @param store      the graph's own partition of the fact store
     */
    public static TypeDefinitionRegistry of(AttributedRegistry attributed, StoreHandle store) {
        return derive(attributed, store).registry();
    }

    /**
     * The emitted registry together with what deriving it narrowed, for a caller that reports on
     * it. {@link #of} is this with the narrowings dropped.
     *
     * @param attributed the run's registry, as {@link #of} takes it
     * @param store      the graph's own partition of the fact store
     */
    public static Emitted derive(AttributedRegistry attributed, StoreHandle store) {
        Objects.requireNonNull(attributed, "attributed");
        return derive(attributed.preSynthesisRegistry(), store);
    }

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
     * The emitted registry for {@code store}'s graph, derived from {@code transcribed}.
     *
     * @param transcribed the registry capture wrote its facts from, which is the pre-synthesis one
     * @param store       the graph's own partition of the fact store
     */
    private static Emitted derive(TypeDefinitionRegistry transcribed, StoreHandle store) {
        Objects.requireNonNull(transcribed, "transcribed");
        Objects.requireNonNull(store, "store");

        var patched = new TypeDefinitionRegistry().merge(transcribed);
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
        var narrowings = applyInheritedFederationDirectives(patched, store);
        applySynthesisedKeys(patched, store);
        return new Emitted(patched, narrowings);
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
     * <p>Object types only, and stated once here rather than in each pass. The {@code CHECK} on
     * {@code graphitron_minted_type.kind} admits {@code OBJECT} and nothing else, the macros minting
     * nothing else, so every other kind reaches the emitted registry exactly as its author wrote it.
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
    // Federation tags and keys
    // ---------------------------------------------------------------------------------------

    /**
     * The federation directives a minted type inherits from the fields that coined it, its
     * carriers: the {@code @tag}s every carrier carries, and {@code @shareable} when any carrier
     * is. Returns each type the tag rule narrowed, for the build to report.
     *
     * <h4>Tags: the intersection over the carriers</h4>
     *
     * <p>For a minted type {@code T} with carriers {@code C(T)}, {@code T} carries
     * {@code ⋂ { tags(c) : c ∈ C(T) }}. That is Apollo's contract rule, that a tag on a type also
     * belongs on every field returning it, read over the carriers: a minted Connection is returned
     * only by its carriers, its Edge only by the Connection's {@code edges}, and {@code PageInfo}
     * and the facet types only by fields on minted types whose tags are their carriers'. So a
     * contract that excludes one carrier's tag keeps a shared type for every carrier it keeps, and
     * a type with one carrier takes that carrier's tags exactly. The cost falls on a contract that
     * includes by tag, which drops a type its carriers disagree about; the narrowing returned here
     * is what tells that author to declare the type themselves. Ordered by the first carrier's tag
     * order, so emission is deterministic.
     *
     * <p>{@code @shareable} is a composition requirement rather than a contract filter, so it is
     * the union instead: a type two subgraphs may both resolve for any one carrier is shareable.
     * The two are folded here together and kept apart in the arithmetic, since intersecting
     * {@code @shareable} would drop it whenever carriers disagree.
     *
     * <h4>Only minted types, and where the directives are read from</h4>
     *
     * <p>Only a type the store says was minted inherits. Coinage is written for every carrier
     * whether or not its mint wins, so a {@code PageInfo} the author declared has coinage rows
     * like any other, and stamping their directives on it would publish a type the author wrote
     * as something they did not write. Whether the mint stood down is
     * {@code graphitron_type_minted}'s to say, which is where the author-wins rule already lives,
     * so the coinage is joined to it rather than checked against the registry here: a second
     * statement of precedence, and one whose answer would depend on which registry was passed.
     *
     * <p>The store names the coining coordinates; the directives themselves are read off the
     * registry being patched. That is not a shortcut around the store, and the alternative was
     * tried. A tag reaches an element two ways: an author writes it, or a schema input carries one
     * and the tag applier stamps it on everything that input declared. Only the first is captured:
     * the applier's tags reach the store's assembly but no relation transcribes them, so a
     * store-sourced inheritance silently drops the second and a federated build configuring
     * {@code <schemaInput tag>} emits synthesised types a gateway can no longer filter. The
     * registry has both by the time this runs, because the applier has already rewritten it.
     * {@code @shareable} is read from the same place so that the one fold has one source.
     *
     * <p>The store owing those tags is a real gap and it is not this method's to close. It is a
     * relation transcribing them, which is a fact arriving earlier rather than a reader
     * compensating; the intersection above is then a relational division over (type, carrier,
     * tag).
     */
    private static List<TagNarrowing> applyInheritedFederationDirectives(
            TypeDefinitionRegistry patched, StoreHandle store) {
        var m = GRAPHITRON_MINTED_COINAGE;
        // Which application coined each minted name, asked of the relation that states it. The
        // arms are not enumerated here: a mint arm added to the schema is one this fold already
        // reads. The whole fold goes when the emitted population carries its own applied
        // directives; see the method's note.
        var minted = GRAPHITRON_TYPE_MINTED;
        var coined = store.dsl()
            .select(m.TYPE_NAME, m.COORDINATE)
            .from(m)
            .where(m.GRAPH_NAME.eq(store.graphName()))
            .andExists(selectOne().from(minted)
                .where(minted.GRAPH_NAME.eq(m.GRAPH_NAME))
                .and(minted.TYPE_NAME.eq(m.TYPE_NAME)))
            .orderBy(m.TYPE_NAME, m.COORDINATE)
            .fetch();

        var byType = new LinkedHashMap<String, List<Carrier>>();
        for (var row : coined) {
            byType.computeIfAbsent(row.get(m.TYPE_NAME), ignored -> new ArrayList<>())
                .add(carrierAt(patched, row.get(m.COORDINATE)));
        }

        var replacements = new ArrayList<Replacement>();
        var narrowings = new ArrayList<TagNarrowing>();
        byType.forEach((typeName, carriers) -> {
            if (!(patched.getTypeOrNull(typeName) instanceof ObjectTypeDefinition object)) {
                return;
            }
            var kept = new LinkedHashSet<>(carriers.getFirst().tags());
            var union = new LinkedHashSet<String>();
            for (var carrier : carriers) {
                kept.retainAll(carrier.tags());
                union.addAll(carrier.tags());
            }
            union.removeAll(kept);
            if (!union.isEmpty()) {
                narrowings.add(new TagNarrowing(typeName, List.copyOf(kept), List.copyOf(union),
                    carriers));
            }
            boolean shareable = carriers.stream().anyMatch(Carrier::shareable)
                && object.getDirectives(SHAREABLE_DIRECTIVE).isEmpty();
            if (kept.isEmpty() && !shareable) {
                return;
            }
            var directives = new ArrayList<>(object.getDirectives());
            if (shareable) {
                directives.add(Directive.newDirective().name(SHAREABLE_DIRECTIVE).build());
            }
            kept.forEach(tag -> directives.add(tagDirective(tag)));
            replacements.add(new Replacement(object,
                object.transform(b -> b.directives(directives))));
        });
        apply(patched, replacements);
        return narrowings;
    }

    /**
     * The carrier at one field coordinate, as the registry holds it.
     *
     * <p>A coining coordinate is a field, every expansion being a rewrite of one, so this resolves
     * {@code Type.field} against the declaration sites of that type. Extensions are searched
     * beside the base definition: a carrier an extension declared is as much a carrier as one the
     * base did. Interface sites are searched as well as object ones, the carrier relation not
     * asking which kind declared the field.
     *
     * <p>A coordinate no site declares is a defect in this lookup and ends the run. Coinage is
     * written from fields that exist, and under the intersection a carrier read as untagged
     * strips every tag from the type it coined, which is not a failure to have quietly.
     */
    private static Carrier carrierAt(TypeDefinitionRegistry patched, String coordinate) {
        int dot = coordinate.indexOf('.');
        String typeName = dot < 0 ? coordinate : coordinate.substring(0, dot);
        String fieldName = dot < 0 ? "" : coordinate.substring(dot + 1);
        var sites = new ArrayList<ImplementingTypeDefinition<?>>();
        if (patched.getTypeOrNull(typeName) instanceof ImplementingTypeDefinition<?> base) {
            sites.add(base);
        }
        sites.addAll(patched.objectTypeExtensions().getOrDefault(typeName, List.of()));
        sites.addAll(patched.interfaceTypeExtensions().getOrDefault(typeName, List.of()));

        FieldDefinition found = null;
        var names = new ArrayList<String>();
        boolean shareable = false;
        for (var site : sites) {
            for (var field : site.getFieldDefinitions()) {
                if (!field.getName().equals(fieldName)) {
                    continue;
                }
                if (found == null) {
                    found = field;
                }
                shareable |= !field.getDirectives(SHAREABLE_DIRECTIVE).isEmpty();
                for (var directive : field.getDirectives(TAG_DIRECTIVE)) {
                    var argument = directive.getArgument(TAG_NAME_ARG);
                    if (argument != null && argument.getValue() instanceof StringValue value
                            && !names.contains(value.getValue())) {
                        names.add(value.getValue());
                    }
                }
            }
        }
        if (found == null) {
            throw new IllegalStateException("The store names '" + coordinate + "' as the field"
                + " that coined a generated type, and no declaration of that field is in the"
                + " registry being patched. This is a defect in graphitron, not in the schema:"
                + " coinage is written from fields that exist.");
        }
        return new Carrier(coordinate, names, shareable, found.getSourceLocation());
    }

    /** One {@code @tag} application, with the name the relation holds. */
    private static Directive tagDirective(String tagName) {
        return Directive.newDirective()
            .name(TAG_DIRECTIVE)
            .argument(Argument.newArgument(TAG_NAME_ARG, new StringValue(tagName)).build())
            .build();
    }

    /**
     * Applies the {@code @key} applications the rule derived and no author wrote.
     *
     * <p>Read from {@code graphitron_synthesized_federation_key}, which is the derivation itself. Its
     * own comment puts it exactly: the relation is its own provenance, which is what lets a
     * synthesized application leave the transcription families entirely. So a reader wanting the
     * derived applications names that relation and gets them, and needs no test to tell derived
     * from authored.
     *
     * <p>Deliberately not {@code intent_federation_key} with the authored arm filtered out. That
     * view is the composition of both, for a reader that wants every key the emitted schema
     * carries; this patch is not one, the authored applications already being in the registry it
     * started from. Filtering the composition would also have to discriminate on the null ordinal
     * the union puts on its derived arm, which is a statement about document position rather than
     * about provenance, and reading it as provenance would work only for as long as those two facts
     * happen to coincide.
     *
     * <p>The relation's third condition is that no authored key states the id contract, so its
     * rows are disjoint from the registry's applications by construction, provided the registry is
     * the pre-synthesis one. This method enforces that rather than trusting it: a type already
     * carrying a key with the row's field set means synthesis was applied twice, which is a
     * generator defect, and it ends the run instead of being skipped, a skip hiding the wrong input
     * rather than reporting it.
     */
    private static void applySynthesisedKeys(TypeDefinitionRegistry patched, StoreHandle store) {
        var t = GRAPHITRON_SYNTHESIZED_FEDERATION_KEY;
        var derived = store.dsl()
            .select(t.TYPE_NAME, t.FIELDS_SDL, t.RESOLVABLE)
            .from(t)
            .where(t.GRAPH_NAME.eq(store.graphName()))
            .orderBy(t.TYPE_NAME)
            .fetch();
        var replacements = new ArrayList<Replacement>();
        for (var row : derived) {
            if (!(patched.getTypeOrNull(row.get(t.TYPE_NAME))
                    instanceof ObjectTypeDefinition object)) {
                continue;
            }
            if (carriesKey(object, row.get(t.FIELDS_SDL))) {
                throw new IllegalStateException("Type '" + object.getName() + "' already carries"
                    + " the @key(fields: \"" + row.get(t.FIELDS_SDL) + "\") the store synthesises"
                    + " for it. This is a defect in graphitron, not in the schema: key synthesis was"
                    + " applied twice, the emitted registry having been derived from a registry"
                    + " that had already been through it.");
            }
            var directives = new ArrayList<>(object.getDirectives());
            directives.add(keyDirective(row.get(t.FIELDS_SDL), row.get(t.RESOLVABLE)));
            replacements.add(new Replacement(object,
                object.transform(b -> b.directives(directives))));
        }
        for (var replacement : replacements) {
            patched.remove(replacement.old());
            patched.add(replacement.replacement());
        }
    }

    /**
     * Whether {@code object} already carries a {@code @key} whose {@code fields:} decodes to the
     * same field set as {@code fieldsSdl}, decoded the way {@code KeyNodeSynthesiser} decides it.
     * A {@code fields:} that does not decode states no field set and so matches nothing.
     */
    private static boolean carriesKey(ObjectTypeDefinition object, String fieldsSdl) {
        var wanted = FederationKeyFieldsParser.parse(fieldsSdl);
        for (var directive : object.getDirectives(KEY_DIRECTIVE)) {
            var argument = directive.getArgument(KEY_FIELDS_ARG);
            if (argument == null || !(argument.getValue() instanceof StringValue value)) {
                continue;
            }
            try {
                if (FederationKeyFieldsParser.parse(value.getValue()).equals(wanted)) {
                    return true;
                }
            } catch (FederationKeyFieldsParser.ParseException ignored) {
                // A malformed fields: argument is the classifier's to report; it is no key here.
            }
        }
        return false;
    }

    /**
     * Both arguments come off the row rather than being restated here. The relation's own comment
     * says the rule's constants live in it "rather than in a comment each composing reader re-mints
     * from", so a {@code true} written at this site would be that re-minting. {@code resolvable}
     * unboxes: the relation states it is always true, and a null would be the relation failing its
     * own contract, which should end the run rather than quietly emit a key without it.
     */
    private static Directive keyDirective(String fieldsSdl, boolean resolvable) {
        return Directive.newDirective()
            .name(KEY_DIRECTIVE)
            .argument(Argument.newArgument(KEY_FIELDS_ARG, new StringValue(fieldsSdl)).build())
            .argument(Argument.newArgument(KEY_RESOLVABLE_ARG, new BooleanValue(resolvable)).build())
            .build();
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
