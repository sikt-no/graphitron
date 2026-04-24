// Verifies the missing-schema-inputs IT: the build must fail with a clear
// "matched no files" message rather than an NPE or cryptic stack trace.
def buildLog = new File(basedir, "build.log")
def log = buildLog.text

assert log.contains("matched no files") :
    "Expected 'matched no files' error in build.log but got:\n${log.readLines().findAll { it.contains('ERROR') || it.contains('FATAL') }.join('\n')}"

assert log.contains("nonexistent/**/*.graphqls") :
    "Expected the pattern name in the error message but got:\n${log}"
