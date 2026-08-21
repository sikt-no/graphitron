package no.sikt.graphitron.sakila.example.app;

/**
 * The one resource key every {@code @QuarkusTest} class in this module locks, so that no two of
 * them are ever in flight at the same time.
 *
 * <p>{@code io.quarkus.test.junit.QuarkusTestExtension} keeps its per-test bookkeeping in static
 * single slots: the class under test, the test instance, the outer-instance deque and the method
 * context type, one of each per JVM. A class start writes them; every later callback reflects the
 * running JUnit method onto whatever the instance slot holds. Two classes in flight means the
 * second start overwrites the first, and the first class's callbacks then look up their own method
 * name on the wrong class and invoke it against the wrong receiver. Sharing the running
 * application across classes is deliberate and works; it is the bookkeeping beside it that is not
 * thread-safe. Parallel {@code @QuarkusTest} execution has never been supported upstream.
 *
 * <p>A single key in {@code READ_WRITE} mode is the narrowest fix available: it makes the
 * {@code @QuarkusTest} classes mutually exclusive with each other and leaves them free to overlap
 * every other class in the module. {@code @Execution(SAME_THREAD)} is the wrong tool and is named
 * here so nobody reaches for it: it pins a class to its parent's thread and says nothing about
 * which other classes run beside it.
 *
 * <p>Carrying the key is build-enforced by
 * {@code no.sikt.graphitron.rewrite.test.internal.QuarkusTestLockEnforcementTest}.
 */
public final class QuarkusTestLock {

    /** Annotate a {@code @QuarkusTest} class {@code @ResourceLock(QuarkusTestLock.KEY)}. */
    public static final String KEY = "quarkus-test-deployment";

    private QuarkusTestLock() {
    }
}
