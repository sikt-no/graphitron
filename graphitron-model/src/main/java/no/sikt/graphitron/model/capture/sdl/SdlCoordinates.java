package no.sikt.graphitron.model.capture.sdl;

import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ENUM_VALUE_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD_ELEMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_ELEMENT;
import no.sikt.graphitron.model.sink.FactSink;

import no.sikt.graphitron.model.catalog.SchemaCoordinateSyntax;

/**
 * Writes the SDL element anchors, and owns the first-wins claim on each of them.
 *
 * <p>One place, because the anchor row and the won claim are the same fact: a coordinate exists
 * exactly when some declaration site first names it, and the anchor is what every other SDL
 * relation, every directive decode and every coordinate-keyed derivation hangs off. A caller that
 * took the claim without writing the anchor would leave the attribute row it goes on to write with
 * no parent, so the two are not separable steps a call site could get half right. Both producers
 * that name coordinates take their claims through here: the SDL walk, and the macro expansion that
 * mints shapes the author never wrote.
 *
 * <p>Each anchor is written with its {@code graphql_element} row, the supertype the four share,
 * and both take the same string from one call to {@link SchemaCoordinateSyntax}. Written together for
 * the reason the anchor and the claim are: a caller that took one without the other would leave a
 * row with no parent. One rendering rather than two, so the foreign key between them has nothing
 * to adjudicate and is there to refuse an anchor nobody wrote a coordinate for.
 *
 * <p>Each method returns {@code false} when the coordinate was already claimed, which is the signal
 * the caller quarantines the losing declaration on.
 */
final class SdlCoordinates {

    private final FactSink sink;

    SdlCoordinates(FactSink sink) {
        this.sink = sink;
    }

    boolean claimType(String typeName) {
        if (!sink.claim(GRAPHQL_TYPE_ELEMENT, typeName)) {
            return false;
        }
        String coordinate = SchemaCoordinateSyntax.ofType(typeName);
        anchor(coordinate, "NAMED_TYPE");
        var record = sink.dsl().newRecord(GRAPHQL_TYPE_ELEMENT);
        record.setTypeName(typeName);
        record.setCoordinate(coordinate);
        sink.add(record);
        return true;
    }

    boolean claimOutputField(String typeName, String fieldName) {
        return claimField(typeName, fieldName, "FIELD");
    }

    boolean claimInputField(String typeName, String fieldName) {
        return claimField(typeName, fieldName, "INPUT_FIELD");
    }

    /**
     * The two share an anchor relation and a coordinate form and differ only in the element kind,
     * which is the specification's own split: a field and an input field are different schema
     * elements written the same way, told apart by the kind of the type declaring them. The walk
     * knows which body it is in, so the kind is settled here rather than left to a join every
     * reader would have to remember.
     */
    private boolean claimField(String typeName, String fieldName, String elementKind) {
        if (!sink.claim(GRAPHQL_FIELD_ELEMENT, typeName, fieldName)) {
            return false;
        }
        String coordinate = SchemaCoordinateSyntax.ofField(typeName, fieldName);
        anchor(coordinate, elementKind);
        var record = sink.dsl().newRecord(GRAPHQL_FIELD_ELEMENT);
        record.setTypeName(typeName);
        record.setFieldName(fieldName);
        record.setCoordinate(coordinate);
        sink.add(record);
        return true;
    }

    boolean claimArgument(String typeName, String fieldName, String argumentName) {
        if (!sink.claim(GRAPHQL_ARGUMENT_ELEMENT, typeName, fieldName, argumentName)) {
            return false;
        }
        String coordinate = SchemaCoordinateSyntax.ofArgument(typeName, fieldName, argumentName);
        anchor(coordinate, "FIELD_ARGUMENT");
        var record = sink.dsl().newRecord(GRAPHQL_ARGUMENT_ELEMENT);
        record.setTypeName(typeName);
        record.setFieldName(fieldName);
        record.setArgumentName(argumentName);
        record.setCoordinate(coordinate);
        sink.add(record);
        return true;
    }

    boolean claimEnumValue(String typeName, String valueName) {
        if (!sink.claim(GRAPHQL_ENUM_VALUE_ELEMENT, typeName, valueName)) {
            return false;
        }
        String coordinate = SchemaCoordinateSyntax.ofEnumValue(typeName, valueName);
        anchor(coordinate, "ENUM_VALUE");
        var record = sink.dsl().newRecord(GRAPHQL_ENUM_VALUE_ELEMENT);
        record.setTypeName(typeName);
        record.setValueName(valueName);
        record.setCoordinate(coordinate);
        sink.add(record);
        return true;
    }

    /**
     * The supertype row the anchor about to be written hangs off. Claimed on its own key rather
     * than assumed unclaimed: a type coordinate and an enum value coordinate can spell the same
     * text only across different types, but the claim is what keeps that reasoning out of here.
     */
    private void anchor(String coordinate, String elementKind) {
        if (!sink.claim(GRAPHQL_ELEMENT, coordinate)) {
            return;
        }
        var record = sink.dsl().newRecord(GRAPHQL_ELEMENT);
        record.setCoordinate(coordinate);
        record.setElementKind(elementKind);
        sink.add(record);
    }
}
