package no.sikt.graphitron.model.schema;

import graphql.language.Argument;
import graphql.language.BooleanValue;
import graphql.language.Description;
import graphql.language.Directive;
import graphql.language.FieldDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.language.ObjectTypeExtensionDefinition;
import graphql.language.SDLDefinition;
import graphql.language.StringValue;
import graphql.language.Type;
import graphql.language.TypeDefinition;
import graphql.parser.Parser;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.sikt.graphitron.model.read.StoreHandle;

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
import static no.sikt.graphitron.model.Tables.GRAPHITRON_SYNTHESIZED_FEDERATION_KEY;
import static org.jooq.impl.DSL.multiset;
import static org.jooq.impl.DSL.select;

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
 * nothing saying they agree, which is what {@code MacroCapture}'s own javadoc records about the
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
 * <p>The registry handed in is not modified; the patch is applied to a copy, so a caller holding
 * the read-only pre-synthesis snapshot keeps it.
 */
public final class EmittedRegistry {

    /** The one kind {@code graphitron_minted_type.kind}'s CHECK admits, and so the only kind here. */
    private static final String OBJECT = "OBJECT";

    private static final String KEY_DIRECTIVE = "key";
    private static final String KEY_FIELDS_ARG = "fields";
    private static final String KEY_RESOLVABLE_ARG = "resolvable";

    private EmittedRegistry() {}

    /**
     * The emitted registry for {@code store}'s graph, derived from {@code transcribed}.
     *
     * @param transcribed the registry capture wrote its facts from, which is the pre-synthesis one
     * @param store       the graph's own partition of the fact store
     */
    public static TypeDefinitionRegistry of(TypeDefinitionRegistry transcribed, StoreHandle store) {
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
        applySynthesisedKeys(patched, store);
        return patched;
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
    // Federation keys
    // ---------------------------------------------------------------------------------------

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
     * <p>Nothing here checks whether the type already carries the key. The relation's third
     * condition is that no authored key states the id contract, so its rows are disjoint from the
     * authored applications by construction rather than by a check this method repeats.
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
