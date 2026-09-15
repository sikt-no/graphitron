package no.sikt.graphitron.model.schema;

import graphql.language.Argument;
import graphql.language.BooleanValue;
import graphql.language.Description;
import graphql.language.Directive;
import graphql.language.FieldDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.ObjectTypeDefinition;
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
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TYPE;
import static no.sikt.graphitron.model.Tables.INTENT_SYNTHESIZED_FEDERATION_KEY;

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
 * <p>Federation keys come from {@code intent_synthesized_federation_key}, the derivation itself,
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
        var fields = fieldRows(store);
        var arguments = argumentRows(store);

        mintAbsentTypes(patched, store, fields, arguments);
        patchPresentTypes(patched, fields, arguments);
        applySynthesisedKeys(patched, store);
        return patched;
    }

    // ---------------------------------------------------------------------------------------
    // The rows
    // ---------------------------------------------------------------------------------------

    /** One field of the emitted population, at the type expression the generator reads. */
    private record FieldRow(String typeName, String fieldName, String typeSdl, String description) {}

    /** One field argument of the emitted population. */
    private record ArgumentRow(String typeName, String fieldName, String argumentName,
                               String typeSdl, String defaultValueSdl, String description) {}

    /**
     * Every emitted field, grouped by its type and ordered within it. The order is the anchor's
     * {@code ordinal}, which is document order on an authored field and the order the macro wrote
     * them on a minted one, so a type built from these rows carries the field order the store says
     * the generator emits rather than one this class invents.
     */
    private static Map<String, List<FieldRow>> fieldRows(StoreHandle store) {
        var t = GRAPHITRON_FIELD;
        return store.dsl()
            .select(t.TYPE_NAME, t.FIELD_NAME, t.TYPE_SDL, t.DESCRIPTION)
            .from(t)
            .where(t.GRAPH_NAME.eq(store.graphName()))
            .orderBy(t.TYPE_NAME, t.ORDINAL)
            .fetch(r -> new FieldRow(r.get(t.TYPE_NAME), r.get(t.FIELD_NAME),
                r.get(t.TYPE_SDL), r.get(t.DESCRIPTION)))
            .stream()
            .collect(Collectors.groupingBy(FieldRow::typeName, LinkedHashMap::new,
                Collectors.toList()));
    }

    /** Every emitted field argument, grouped by the coordinate of the field it sits on. */
    private static Map<String, List<ArgumentRow>> argumentRows(StoreHandle store) {
        var t = GRAPHITRON_ARGUMENT;
        return store.dsl()
            .select(t.TYPE_NAME, t.FIELD_NAME, t.ARGUMENT_NAME, t.TYPE_SDL,
                t.DEFAULT_VALUE_SDL, t.DESCRIPTION)
            .from(t)
            .where(t.GRAPH_NAME.eq(store.graphName()))
            .orderBy(t.TYPE_NAME, t.FIELD_NAME, t.ORDINAL)
            .fetch(r -> new ArgumentRow(r.get(t.TYPE_NAME), r.get(t.FIELD_NAME),
                r.get(t.ARGUMENT_NAME), r.get(t.TYPE_SDL),
                r.get(t.DEFAULT_VALUE_SDL), r.get(t.DESCRIPTION)))
            .stream()
            .collect(Collectors.groupingBy(a -> coordinate(a.typeName(), a.fieldName()),
                LinkedHashMap::new, Collectors.toList()));
    }

    // ---------------------------------------------------------------------------------------
    // Minting
    // ---------------------------------------------------------------------------------------

    /**
     * Adds every object type the store holds and the registry does not. These are the macro's own
     * machinery: the connection, its edge and the shared page info, none of which any author wrote.
     */
    private static void mintAbsentTypes(TypeDefinitionRegistry patched, StoreHandle store,
                                        Map<String, List<FieldRow>> fields,
                                        Map<String, List<ArgumentRow>> arguments) {
        var t = GRAPHITRON_TYPE;
        var minted = store.dsl()
            .select(t.TYPE_NAME, t.DESCRIPTION)
            .from(t)
            .where(t.GRAPH_NAME.eq(store.graphName()))
            .and(t.KIND.eq("OBJECT"))
            .orderBy(t.TYPE_NAME)
            .fetch();
        for (var row : minted) {
            String name = row.get(t.TYPE_NAME);
            if (patched.getTypeOrNull(name) != null) continue;
            patched.add(objectType(name, row.get(t.DESCRIPTION),
                fields.getOrDefault(name, List.of()), arguments));
        }
    }

    private static ObjectTypeDefinition objectType(String name, String description,
                                                   List<FieldRow> fields,
                                                   Map<String, List<ArgumentRow>> arguments) {
        return ObjectTypeDefinition.newObjectTypeDefinition()
            .name(name)
            .description(description(description))
            .fieldDefinitions(fields.stream().map(f -> fieldDefinition(f, arguments)).toList())
            .build();
    }

    private static FieldDefinition fieldDefinition(FieldRow row,
                                                   Map<String, List<ArgumentRow>> arguments) {
        return FieldDefinition.newFieldDefinition()
            .name(row.fieldName())
            .type(type(row.typeSdl()))
            .description(description(row.description()))
            .inputValueDefinitions(arguments
                .getOrDefault(coordinate(row.typeName(), row.fieldName()), List.<ArgumentRow>of())
                .stream().map(EmittedRegistry::inputValue).toList())
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

    /**
     * Brings every object type the registry already holds up to what the store says the generator
     * emits: a field whose type expression the expansion rewrote is retyped, a field the expansion
     * added is added, and an argument it appended is appended. Everything else on the node is left
     * exactly as the author wrote it.
     */
    private static void patchPresentTypes(TypeDefinitionRegistry patched,
                                          Map<String, List<FieldRow>> fields,
                                          Map<String, List<ArgumentRow>> arguments) {
        var replacements = new ArrayList<Replacement>();
        for (TypeDefinition<?> definition : patched.types().values()) {
            if (!(definition instanceof ObjectTypeDefinition object)) continue;
            var rows = fields.get(object.getName());
            if (rows == null || rows.isEmpty()) continue;
            var rebuilt = patchFields(object, rows, arguments);
            if (rebuilt != null) {
                replacements.add(new Replacement(object, rebuilt));
            }
        }
        for (var replacement : replacements) {
            patched.remove(replacement.old());
            patched.add(replacement.replacement());
        }
    }

    /** The patched type, or {@code null} where the store and the registry already agree. */
    private static ObjectTypeDefinition patchFields(ObjectTypeDefinition object,
                                                    List<FieldRow> rows,
                                                    Map<String, List<ArgumentRow>> arguments) {
        var byName = new LinkedHashMap<String, FieldDefinition>();
        for (var field : object.getFieldDefinitions()) {
            byName.put(field.getName(), field);
        }
        boolean changed = false;
        for (var row : rows) {
            var existing = byName.get(row.fieldName());
            if (existing == null) {
                byName.put(row.fieldName(), fieldDefinition(row, arguments));
                changed = true;
                continue;
            }
            var patchedField = patchField(existing, row, arguments);
            if (patchedField != existing) {
                byName.put(row.fieldName(), patchedField);
                changed = true;
            }
        }
        if (!changed) return null;
        var ordered = List.copyOf(byName.values());
        return object.transform(b -> b.fieldDefinitions(ordered));
    }

    /** The patched field, or {@code existing} itself where nothing about it changed. */
    private static FieldDefinition patchField(FieldDefinition existing, FieldRow row,
                                              Map<String, List<ArgumentRow>> arguments) {
        boolean retype = !printed(existing.getType()).equals(printed(type(row.typeSdl())));
        var appended = absentArguments(existing, arguments
            .getOrDefault(coordinate(row.typeName(), row.fieldName()), List.of()));
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
     * <p>Read from {@code intent_synthesized_federation_key}, which is the derivation itself. Its
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
        var t = INTENT_SYNTHESIZED_FEDERATION_KEY;
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

    private static String coordinate(String typeName, String fieldName) {
        return typeName + "." + fieldName;
    }
}
