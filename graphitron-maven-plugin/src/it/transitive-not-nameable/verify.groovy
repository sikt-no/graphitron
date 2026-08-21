// The execution-visible pin on the nameability rule, against real Maven resolution: a schema
// naming a class that is reachable only through a transitive dependency fails the build with the
// declared-dependency reason, naming the coordinate that carries the class. The enforcer and the
// census read the same classified list, so this failure also witnesses that the classification
// judged org.jooq:jooq transitive, which is what keeps the census from opening its jar.
def buildLog = new File(basedir, "build.log").text

assert buildLog.contains("org.jooq.impl.DSL") :
    "The rejection must name the class as the author wrote it"
assert buildLog.contains("org.jooq:jooq") :
    "The rejection must name the coordinate that carries the class; that is what the transitive " +
    "probe exists for"
assert buildLog.contains("does not declare") :
    "The reason is the declared-dependency rule, not a class-not-found"
assert buildLog.contains("Declare org.jooq:jooq as a dependency") :
    "The message must state the one-<dependency>-block migration"

// The failure is the generate that caused it, not the consumer's later compile: nothing may be
// emitted for a schema the rule rejects.
def wiring = new File(basedir, "target/generated-sources/graphitron/no/sikt/it/generated/Graphitron.java")
assert !wiring.exists() :
    "A rejected schema must not generate"
