package no.sikt.graphitron.rewrite.model;

/**
 * A resolved reference to a developer-supplied static method that produces a batched child
 * {@code @service} field's key record out of a class-backed parent: the call-site signature is
 * exactly {@code (ParentBackingClass) -> ElementRecord}, where the element record is the class the
 * service signature's {@code Sources} parameter names.
 *
 * <p>The static twin of {@link AccessorRef}: the accessor route reads the key record off an
 * instance method the parent's backing class happens to expose, this one off a static method the
 * author declared with {@code @sourceRow}. Both carry the same facts, and the emitted extraction
 * differs only in the call expression, so the components mirror {@code AccessorRef}'s and add the
 * producer's own declaring class.
 *
 * <p>Distinct from {@link LifterRef}, which is the {@code @sourceRow} reference on a {@code @table}
 * child and keeps its single documented meaning (the lifter returns a {@code RowN} tuple). That ref
 * carries no cast target because {@code GeneratorUtils.backingClassOf} recovers one from the result
 * type; the service path has no such input at its emit seat, so the cast target is resolved at the
 * classifier boundary and carried here.
 *
 * <p>Every class is named as a <em>canonical</em> name rather than a binary one, and as a string
 * rather than an emit-library type: the model states facts the renderer turns into emit vocabulary
 * ({@code ClassName.bestGuess} at the emit seat), and that helper splits on {@code .} only, so a
 * nested class reached through its binary {@code $} name would render as an identifier javac
 * rejects. The classifier resolves the names off the reflected classes, so they are canonical by
 * construction and never re-derived downstream.
 *
 * <p>{@code declaringClass} is the class holding the static method and {@code methodName} its
 * simple name. {@code parentBackingClass} is the cast target for {@code env.getSource()} at the
 * call site, and {@code elementClass} the key record class the method returns.
 */
public record StaticProducerRef(
    String declaringClass,
    String methodName,
    String parentBackingClass,
    String elementClass
) {}
