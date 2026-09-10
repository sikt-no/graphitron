package no.sikt.graphitron.model.diagnostics;

import java.util.List;

/**
 * Where a decoding {@code @nodeId} instruction sits, in components rather than as a rendered
 * coordinate string. The key both halves of the decode-coverage rule meet on: the store's census of
 * authored instructions is read into these, and the classification walk mints these as it decides
 * what it did with each one, so an instruction the generator neither carried out nor refused is the
 * set difference between the two and nothing else.
 *
 * <p><b>Components, never a composed string.</b> The store renders a use site into a single
 * {@code use_site} column, and a Java-side string built to match it is two spellings of one value
 * that agree until one of them changes. A join miss there does not read as a join miss: it reads as
 * a dropped instruction and fails a build that should pass. So every component travels as its own
 * field, read off the relations that state it ({@code intent_input_occurrence_path} and its
 * ordinal-keyed step child on the store side, the walk's own descent on the other) and compared as
 * a record value.
 *
 * <p><b>Keyed by use, not by definition.</b> One input field carrying one directive is as many
 * coordinates as there are use sites consuming it, which is the grain the instruction census is
 * already at. A key at the definition would let one install cover every use site of the same input
 * field, and the use site where nothing was installed would vanish into the join instead of being
 * reported: a silent miss of exactly the class this coordinate exists to make visible.
 *
 * <p>Only the two decoding sites are expressible. An output field's {@code @nodeId} encodes rather
 * than decodes, and its coverage is a separate question nothing here asks.
 */
public sealed interface NodeIdDecodeCoordinate {

    /**
     * The use site's owning type: the object type declaring the field whose argument carries the
     * instruction, or that declares the field the containing input surface descends from.
     */
    String rootTypeName();

    /** The use site's field within {@link #rootTypeName()}. */
    String rootFieldName();

    /** The argument the instruction is carried on, or descends from. */
    String rootArgumentName();

    /**
     * An instruction on a field argument. The definition and the use site are the same coordinate
     * here, an argument being declared exactly where it is consumed, which is why this arm carries
     * no descent.
     */
    record Argument(String rootTypeName, String rootFieldName, String rootArgumentName)
            implements NodeIdDecodeCoordinate {}

    /**
     * An instruction on an input field, reached by descending from {@link #rootArgumentName()}
     * through input-object-typed fields. {@code descent} is that path decomposed, outermost first,
     * with the instructed input field itself as the last step; it is what makes two use sites of one
     * input type two coordinates.
     */
    record InputField(String rootTypeName, String rootFieldName, String rootArgumentName,
                      List<Step> descent) implements NodeIdDecodeCoordinate {

        public InputField {
            descent = List.copyOf(descent);
        }

        /** The input field this coordinate names: the descent's last step. */
        public Step leaf() {
            return descent.getLast();
        }
    }

    /** One input-field step of a descent: the input type it is declared on, and its own name. */
    record Step(String containerTypeName, String fieldName) {}

    /**
     * The coordinate a {@link ValidationError} attaches to, in the {@code Type.field} grain that
     * carrier takes. Deliberately lossier than this value: it names the use site's field, which is
     * where an author looks, while the message beside it says which instruction under that field is
     * meant.
     */
    default String errorCoordinate() {
        return rootTypeName() + "." + rootFieldName();
    }

    /** How an author would spell this coordinate back: the use site, with the descent under it. */
    default String describe() {
        var out = new StringBuilder(rootTypeName()).append('.').append(rootFieldName())
            .append('(').append(rootArgumentName()).append(')');
        if (this instanceof InputField f) {
            for (Step step : f.descent()) {
                out.append('/').append(step.fieldName());
            }
        }
        return out.toString();
    }
}
