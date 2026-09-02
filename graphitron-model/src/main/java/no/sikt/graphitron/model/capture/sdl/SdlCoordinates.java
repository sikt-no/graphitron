package no.sikt.graphitron.model.capture.sdl;

import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT_COORDINATE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_COORDINATE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ENUM_VALUE_COORDINATE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD_COORDINATE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_COORDINATE;
import no.sikt.graphitron.model.sink.FactSink;

import no.sikt.graphitron.model.catalog.SchemaCoordinate;

/**
 * Writes the SDL coordinate anchors, and owns the first-wins claim on each of them.
 *
 * <p>One place, because the anchor row and the won claim are the same fact: a coordinate exists
 * exactly when some declaration site first names it, and the anchor is what every other SDL
 * relation, every directive decode and every coordinate-keyed derivation hangs off. A caller that
 * took the claim without writing the anchor would leave the attribute row it goes on to write with
 * no parent, so the two are not separable steps a call site could get half right. Both producers
 * that name coordinates take their claims through here: the SDL walk, and the macro expansion that
 * mints shapes the author never wrote.
 *
 * <p>Each anchor is written with its {@code graphql_coordinate} row, the supertype the four share,
 * and both take the same string from one call to {@link SchemaCoordinate}. Written together for
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
        if (!sink.claim(GRAPHQL_TYPE_COORDINATE, typeName)) {
            return false;
        }
        String coordinate = SchemaCoordinate.ofType(typeName);
        anchor(coordinate, "TYPE");
        var record = sink.dsl().newRecord(GRAPHQL_TYPE_COORDINATE);
        record.setTypeName(typeName);
        record.setCoordinate(coordinate);
        sink.add(record);
        return true;
    }

    boolean claimField(String typeName, String fieldName) {
        if (!sink.claim(GRAPHQL_FIELD_COORDINATE, typeName, fieldName)) {
            return false;
        }
        String coordinate = SchemaCoordinate.ofField(typeName, fieldName);
        anchor(coordinate, "FIELD");
        var record = sink.dsl().newRecord(GRAPHQL_FIELD_COORDINATE);
        record.setTypeName(typeName);
        record.setFieldName(fieldName);
        record.setCoordinate(coordinate);
        sink.add(record);
        return true;
    }

    boolean claimArgument(String typeName, String fieldName, String argumentName) {
        if (!sink.claim(GRAPHQL_ARGUMENT_COORDINATE, typeName, fieldName, argumentName)) {
            return false;
        }
        String coordinate = SchemaCoordinate.ofArgument(typeName, fieldName, argumentName);
        anchor(coordinate, "ARGUMENT");
        var record = sink.dsl().newRecord(GRAPHQL_ARGUMENT_COORDINATE);
        record.setTypeName(typeName);
        record.setFieldName(fieldName);
        record.setArgumentName(argumentName);
        record.setCoordinate(coordinate);
        sink.add(record);
        return true;
    }

    boolean claimEnumValue(String typeName, String valueName) {
        if (!sink.claim(GRAPHQL_ENUM_VALUE_COORDINATE, typeName, valueName)) {
            return false;
        }
        String coordinate = SchemaCoordinate.ofEnumValue(typeName, valueName);
        anchor(coordinate, "ENUM_VALUE");
        var record = sink.dsl().newRecord(GRAPHQL_ENUM_VALUE_COORDINATE);
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
    private void anchor(String coordinate, String kind) {
        if (!sink.claim(GRAPHQL_COORDINATE, coordinate)) {
            return;
        }
        var record = sink.dsl().newRecord(GRAPHQL_COORDINATE);
        record.setCoordinate(coordinate);
        record.setKind(kind);
        sink.add(record);
    }
}
