// org.jooq:jooq at another version under <plugin><dependencies> is mediated over graphitron's own,
// leaving one jOOQ in the realm that is not the one graphitron was compiled against. Refused on the
// version arm, which is what pins the compiled-against constant javac inlines into graphitron.
def log = new File(basedir, "build.log").text

assert log.contains("graphitron was compiled against org.jooq:jooq:") :
    "Expected the realm check's version refusal in build.log but got:\n${log}"
assert log.contains("holds org.jooq:jooq:3.19.99-it (declared under <plugin><dependencies> for graphitron-maven-plugin) in its place, which reports jOOQ 3.19.99") :
    "Expected the mediated jar, its entry and its runtime version to be named but got:\n${log}"
assert !new File(basedir, "target/graphitron-model").exists() :
    "The store must not have been opened: the realm check runs before it"
