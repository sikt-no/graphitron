package no.sikt.graphitron.command;

/**
 * One shared-outer map lift in a glue body: the outer argument name whose coerced
 * {@code Map<?, ?>} is bound once to a named local, referenced by every nested extraction riding
 * that argument. The producer lifts an outer exactly when two or more of the method's retained
 * bindings traverse it, generalising the old per-generator {@code computeLiftedOuters} into the
 * one-local-per-argument convention; the lift is method-grain data, so two predicates sharing an
 * outer can never race to declare the same local.
 */
public record OuterLift(String outerArgName, String localName) {

    public OuterLift {
        if (outerArgName == null || outerArgName.isBlank()) {
            throw new IllegalArgumentException("an outer lift names the shared outer argument");
        }
        if (localName == null || localName.isBlank()) {
            throw new IllegalArgumentException("an outer lift requires a producer-named body local");
        }
    }
}
