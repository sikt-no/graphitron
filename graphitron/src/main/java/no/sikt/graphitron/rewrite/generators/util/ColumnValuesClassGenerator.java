package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.AnnotationSpec;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.FieldSpec;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.javapoet.TypeVariableName;
import no.sikt.graphitron.javapoet.WildcardTypeName;

import javax.lang.model.element.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

/**
 * Generates the {@code ColumnValues} utility class, emitted once per code-generation run: the one
 * decode-side answer to "is this value a value of that column, and if so which one".
 *
 * <p>Every entry point runs the value on the <em>column trip</em>, read off the column's
 * {@code DataType} and its converter (jOOQ's identity converter when the column has none, so there
 * is one emitted shape): the user value {@code type.convert(input)}, or the input parsed as the
 * parse class {@code exact} is handed for a converter's user type, the database value
 * {@code converter.to(user)}, and the canonical value {@code converter.from(database)};
 * {@code fromDatabase} starts the trip at the database value. The trip
 * fails when a step gives {@code null} or throws {@code IllegalArgumentException} or
 * {@code DataTypeException}; any other exception is a fault in the consumer's converter and
 * propagates. Following the value through the converter is what catches a converter-backed column,
 * where {@code convert} alone passes a user-type input through untouched and the value would only
 * fail at bind time.
 *
 * <ul>
 *   <li>{@code exact(type, parseClass, text)} is the inverse of {@code NodeIdEncoder.encode}, for
 *       server-minted text: the trip succeeds, the canonical value's {@code toString()} is the
 *       text, and the value fits the column's declared precision. It refuses a wrapped number, a
 *       padded or reformatted one, and any text the encoder never writes. A non-null
 *       {@code parseClass} is the converter's user type, and the trip's first step then parses the
 *       text as that type rather than through {@code convert}, which on a converted type parses as
 *       the database type and can drop what the user type's text carries (see
 *       {@link no.sikt.graphitron.render.SlotParse}).</li>
 *   <li>{@code coerce(type, value)} is the documented wire coercion for authored values (a tenant
 *       argument, a federation {@code @key} field): the trip succeeds, a value that reads as a
 *       number lands on that number or its integral part (so {@code "1.5"} binds as {@code 1} on an
 *       integer column but a number that wraps is refused), and the value fits the declared
 *       precision.</li>
 *   <li>{@code folded(type, parseClass, text)} names what {@code exact} cannot: for a slot over a
 *       converter's user type, the value the trip came back as when the converter did not give the
 *       parsed value back ({@code from(to(parsed))} is not {@code parsed}), else {@code null}. It
 *       compares values, not text, so a padded or reformatted number is no fold, and it answers
 *       {@code null} without a parse class, since a consumer's own class need not have value
 *       equality. The node-id decode asks it only after {@code exact} refused a slot, to tell an
 *       operator that a key column's converter loses what the column holds.</li>
 *   <li>{@code fromDatabase(type, value)} reads a value written as the column's <em>database</em>
 *       value, as a connection cursor carries it: the trip's first step parses the input as the
 *       database type ({@code type.getSQLDataType().convert(input)}) and the canonical value is
 *       {@code converter.from} of that, which must fit the declared precision. Parsing through the
 *       converted {@code convert} instead would answer {@code null} for text the user type does not
 *       share with the database type, and hand an input that is already an instance of the user
 *       type back untouched rather than converting it.</li>
 * </ul>
 *
 * <p>Each returns the canonical value or {@code null}; none throws for a value that is not a
 * value of the column, so whether that is a skipped slot or a client error stays with the caller.
 */
public class ColumnValuesClassGenerator {

    public static final String CLASS_NAME = "ColumnValues";

    private static final ClassName DATA_TYPE           = ClassName.get("org.jooq", "DataType");
    private static final ClassName CONVERTER           = ClassName.get("org.jooq", "Converter");
    private static final ClassName DATA_TYPE_EXCEPTION = ClassName.get("org.jooq.exception", "DataTypeException");
    private static final ClassName DSL                 = ClassName.get("org.jooq.impl", "DSL");
    private static final ClassName BIG_DECIMAL         = ClassName.get(BigDecimal.class);
    private static final ClassName BIG_INTEGER         = ClassName.get(BigInteger.class);
    private static final ClassName ROUNDING_MODE       = ClassName.get(RoundingMode.class);
    private static final ClassName TRIP                = ClassName.get("", "Trip");

    public static List<TypeSpec> generate() {
        var t = TypeVariableName.get("T");
        var d = TypeVariableName.get("D");
        TypeName dataTypeOfT = ParameterizedTypeName.get(DATA_TYPE, t);
        TypeName dataTypeWildcard = ParameterizedTypeName.get(DATA_TYPE, WildcardTypeName.subtypeOf(ClassName.OBJECT));
        TypeName tripOfT = ParameterizedTypeName.get(TRIP, t);
        TypeName tripWildcard = ParameterizedTypeName.get(TRIP, WildcardTypeName.subtypeOf(ClassName.OBJECT));
        TypeName classWildcard = ParameterizedTypeName.get(ClassName.get(Class.class),
            WildcardTypeName.subtypeOf(ClassName.OBJECT));

        var exact = MethodSpec.methodBuilder("exact")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addTypeVariable(t)
            .returns(t)
            .addParameter(dataTypeOfT, "type")
            .addParameter(classWildcard, "parseClass")
            .addParameter(String.class, "text")
            .addJavadoc("The value server-minted {@code text} names in a column of {@code type}, or {@code null}\n"
                + "when it names none: the text must be exactly what {@code NodeIdEncoder.encode} writes for\n"
                + "that value, and the value must fit the column. A non-null {@code parseClass} is the\n"
                + "converter's user type, which the text is parsed as before the converter runs on it.\n")
            .addStatement("if (text == null) return null")
            .addStatement("$T trip = trip(type, parseClass, text)", tripOfT)
            .addStatement("if (trip == null || !trip.canonical.toString().equals(text)) return null")
            .addStatement("return fitsPrecision(type, numericValue(trip)) ? trip.canonical : null")
            .build();

        var coerce = MethodSpec.methodBuilder("coerce")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addTypeVariable(t)
            .returns(t)
            .addParameter(dataTypeOfT, "type")
            .addParameter(Object.class, "value")
            .addJavadoc("The value an authored {@code value} binds as in a column of {@code type}, or {@code null}\n"
                + "when it binds as none: the column's own coercion, except that a number which would\n"
                + "wrap, or which does not fit the column's precision, is refused.\n")
            .addStatement("if (value == null) return null")
            .addStatement("$T trip = trip(type, null, value)", tripOfT)
            .addStatement("if (trip == null) return null")
            .addStatement("$T number = numericValue(trip)", BIG_DECIMAL)
            .addStatement("$T input = readNumber(value)", BIG_DECIMAL)
            .beginControlFlow("if (number != null && input != null && number.compareTo(input) != 0\n"
                + "&& number.compareTo(input.setScale(0, $T.DOWN)) != 0)", ROUNDING_MODE)
            .addComment("The column holds neither the number the client wrote nor its integral part: a wrap.")
            .addStatement("return null")
            .endControlFlow()
            .addStatement("return fitsPrecision(type, number) ? trip.canonical : null")
            .build();

        var folded = MethodSpec.methodBuilder("folded")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addTypeVariable(t)
            .returns(t)
            .addParameter(dataTypeOfT, "type")
            .addParameter(classWildcard, "parseClass")
            .addParameter(String.class, "text")
            .addJavadoc("What the column's converter gives back for {@code text} parsed as its user type\n"
                + "{@code parseClass}, when that is not the parsed value; {@code null} when the converter gives\n"
                + "the value back, the text does not parse, or there is no parse class.\n")
            .addStatement("if (text == null || parseClass == null) return null")
            .addStatement("$T trip = trip(type, parseClass, text)", tripOfT)
            .addStatement("return trip == null || $T.deepEquals(trip.parsed, trip.canonical) ? null : trip.canonical",
                Objects.class)
            .build();

        var fromDatabase = MethodSpec.methodBuilder("fromDatabase")
            .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
            .addTypeVariable(t)
            .returns(t)
            .addParameter(dataTypeOfT, "type")
            .addParameter(Object.class, "value")
            .addJavadoc("The value {@code value}, read as the column's database value, names in a column of\n"
                + "{@code type}, or {@code null} when it names none: parsed as the database type, taken through\n"
                + "the converter, and fitting the column's precision.\n")
            .addStatement("if (value == null) return null")
            .beginControlFlow("try")
            .addStatement("Object database = type.getSQLDataType().convert(value)")
            .addStatement("if (database == null) return null")
            .addStatement("$T canonical = fromDatabaseValue(type.getConverter(), database)", t)
            .addStatement("if (canonical == null) return null")
            .addStatement("return fitsPrecision(type, numericValue(new $T<>(canonical, canonical, database)))"
                + " ? canonical : null", TRIP)
            .nextControlFlow("catch ($T | $T e)", IllegalArgumentException.class, DATA_TYPE_EXCEPTION)
            .addStatement("return null")
            .endControlFlow()
            .build();

        // Generic over the converter's database type, as roundTrip is, so handing the parsed database
        // value to from() type-checks in Java 17 against the wildcard getConverter() returns.
        var fromDatabaseValue = MethodSpec.methodBuilder("fromDatabaseValue")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .addTypeVariable(d)
            .addTypeVariable(t)
            .returns(t)
            .addParameter(ParameterizedTypeName.get(CONVERTER, d, t), "converter")
            .addParameter(Object.class, "database")
            .addStatement("return converter.from(converter.fromType().cast(database))")
            .build();

        var trip = MethodSpec.methodBuilder("trip")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .addTypeVariable(t)
            .returns(tripOfT)
            .addParameter(dataTypeOfT, "type")
            .addParameter(classWildcard, "parseClass")
            .addParameter(Object.class, "input")
            .beginControlFlow("try")
            .addStatement("$T user = parseClass == null ? type.convert(input)\n"
                + ": type.getType().cast($T.val(input, parseClass).getValue())", t, DSL)
            .addStatement("return user == null ? null : roundTrip(type.getConverter(), user)")
            .nextControlFlow("catch ($T | $T e)", IllegalArgumentException.class, DATA_TYPE_EXCEPTION)
            .addStatement("return null")
            .endControlFlow()
            .build();

        // Generic over the converter's database type so the to-and-back trip type-checks in Java 17
        // against the wildcard getConverter() returns.
        var roundTrip = MethodSpec.methodBuilder("roundTrip")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .addTypeVariable(d)
            .addTypeVariable(t)
            .returns(tripOfT)
            .addParameter(ParameterizedTypeName.get(CONVERTER, d, t), "converter")
            .addParameter(t, "user")
            .addStatement("$T database = converter.to(user)", d)
            .addStatement("if (database == null) return null")
            .addStatement("$T canonical = converter.from(database)", t)
            .addStatement("return canonical == null ? null : new $T<>(user, canonical, database)", TRIP)
            .build();

        var numericValue = MethodSpec.methodBuilder("numericValue")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(BIG_DECIMAL)
            .addParameter(tripWildcard, "trip")
            .addCode("// The canonical value when it is a number, else the database value: a converter over a\n"
                + "// numeric column exposes its number on whichever side holds one.\n")
            .addStatement("if (trip.canonical instanceof $T n) return toBigDecimal(n)", Number.class)
            .addStatement("if (trip.database instanceof $T n) return toBigDecimal(n)", Number.class)
            .addStatement("return null")
            .build();

        var readNumber = MethodSpec.methodBuilder("readNumber")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(BIG_DECIMAL)
            .addParameter(Object.class, "value")
            .addStatement("if (value instanceof $T n) return toBigDecimal(n)", Number.class)
            .beginControlFlow("if (value instanceof $T s)", String.class)
            .addComment("Trimmed as jOOQ trims before it parses, so a padded number keeps its coercion.")
            .beginControlFlow("try")
            .addStatement("return new $T(s.trim())", BIG_DECIMAL)
            .nextControlFlow("catch ($T e)", NumberFormatException.class)
            .addStatement("return null")
            .endControlFlow()
            .endControlFlow()
            .addStatement("return null")
            .build();

        var toBigDecimal = MethodSpec.methodBuilder("toBigDecimal")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(BIG_DECIMAL)
            .addParameter(Number.class, "n")
            .addStatement("if (n instanceof $T b) return b", BIG_DECIMAL)
            .addStatement("if (n instanceof $T b) return new $T(b)", BIG_INTEGER, BIG_DECIMAL)
            .beginControlFlow("if (n instanceof $T || n instanceof $T || n instanceof $T || n instanceof $T)",
                Long.class, Integer.class, Short.class, Byte.class)
            .addStatement("return $T.valueOf(n.longValue())", BIG_DECIMAL)
            .endControlFlow()
            .beginControlFlow("try")
            .addStatement("return new $T(n.toString())", BIG_DECIMAL)
            .nextControlFlow("catch ($T e)", NumberFormatException.class)
            .addComment("NaN, an infinity, or a Number subtype with no decimal spelling: no number to check.")
            .addStatement("return null")
            .endControlFlow()
            .build();

        var fitsPrecision = MethodSpec.methodBuilder("fitsPrecision")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(boolean.class)
            .addParameter(dataTypeWildcard, "type")
            .addParameter(BIG_DECIMAL, "number")
            .addCode("// Only a numeric column's precision bounds a number; a temporal type's precision counts\n"
                + "// fractional seconds.\n")
            .beginControlFlow("if (number == null || !type.isNumeric() || !type.hasPrecision() || type.precision() <= 0"
                + " || number.signum() == 0)")
            .addStatement("return true")
            .endControlFlow()
            .addStatement("int scale = type.hasScale() ? type.scale() : 0")
            .addStatement("$T stripped = number.stripTrailingZeros()", BIG_DECIMAL)
            .addStatement("int integerDigits = $T.max(0, stripped.precision() - stripped.scale())", Math.class)
            .addStatement("int fractionDigits = $T.max(0, stripped.scale())", Math.class)
            .addStatement("return integerDigits <= type.precision() - scale && fractionDigits <= scale")
            .build();

        var tripClass = TypeSpec.classBuilder("Trip")
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .addTypeVariable(t)
            .addJavadoc("One successful column trip: the user value it started from, the database value that went\n"
                + "to, and the canonical user value that came back.\n")
            .addField(FieldSpec.builder(t, "parsed", Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(t, "canonical", Modifier.PRIVATE, Modifier.FINAL).build())
            .addField(FieldSpec.builder(Object.class, "database", Modifier.PRIVATE, Modifier.FINAL).build())
            .addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PRIVATE)
                .addParameter(t, "parsed")
                .addParameter(t, "canonical")
                .addParameter(Object.class, "database")
                .addStatement("this.parsed = parsed")
                .addStatement("this.canonical = canonical")
                .addStatement("this.database = database")
                .build())
            .build();

        // DataType.convert(Object) is deprecated for removal in jOOQ 3.20 with no public
        // Object-to-T successor; the same suppression NodeIdEncoder carries. This class holds the
        // one decode-side call to it.
        var suppressRemoval = AnnotationSpec.builder(SuppressWarnings.class)
            .addMember("value", "{$S, $S}", "deprecation", "removal")
            .build();
        var spec = TypeSpec.classBuilder(CLASS_NAME)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addAnnotation(suppressRemoval)
            .addJavadoc("Whether a decoded value is a value of its column, and which one. Static,\n"
                + "non-extendable; see {@code ColumnValuesClassGenerator} for the contract.\n")
            .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build())
            .addMethod(exact)
            .addMethod(coerce)
            .addMethod(folded)
            .addMethod(fromDatabase)
            .addMethod(fromDatabaseValue)
            .addMethod(trip)
            .addMethod(roundTrip)
            .addMethod(numericValue)
            .addMethod(readNumber)
            .addMethod(toBigDecimal)
            .addMethod(fitsPrecision)
            .addType(tripClass)
            .build();
        return List.of(spec);
    }
}
