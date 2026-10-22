package no.sikt.graphitron.rewrite.model;

/**
 * What a data field of a payload with an errors field answers when the producer's call fails into
 * the errors list (the {@code ErrorList} arm of the {@code Outcome} transport), or when the
 * producer returned {@code null}: nothing was produced, so the field has nothing to read.
 *
 * <p>A pure function of the field's SDL return wrapper ({@link #of}), so it is never stored: every
 * data-field leaf already carries the wrapper on its {@link ReturnTypeRef}, and the classifier
 * derives it from the same {@code BuildContext.buildWrapper} on the same field definition.
 *
 * <p>The answer is only consulted for a data field of a payload with an errors field. {@link #NONE}
 * on any other field is legal and never read; beside an errors field it is the classifier's
 * {@link no.sikt.graphitron.model.diagnostics.ErrorChannelWalkerError.NonNullableSuccessProjectionField}
 * rejection.
 */
public enum ErrorArmAnswer {
    /** A nullable field answers {@code null}. */
    NULL,
    /** A non-null plain list answers the empty list, since nothing was produced. */
    EMPTY_LIST,
    /**
     * A non-null single value or non-null connection has no empty value to answer, and a
     * {@code null} would bubble up and drop the sibling errors field.
     */
    NONE;

    /**
     * The answer for a field with return wrapper {@code wrapper}: nullable answers {@link #NULL}
     * whatever its shape; a non-null {@link FieldWrapper.List} answers {@link #EMPTY_LIST}; a
     * non-null {@link FieldWrapper.Single} or {@link FieldWrapper.Connection} answers {@link #NONE}.
     */
    public static ErrorArmAnswer of(FieldWrapper wrapper) {
        return switch (wrapper) {
            case FieldWrapper.Single s -> s.nullable() ? NULL : NONE;
            case FieldWrapper.List l -> l.listNullable() ? NULL : EMPTY_LIST;
            case FieldWrapper.Connection c -> c.connectionNullable() ? NULL : NONE;
        };
    }
}
