#!/usr/bin/env bash
# Compiles and runs the pipeline against fixtures/corpus-a.jsonl.
# Needs a JDK (17+; 21 also fine) and nothing else - no network, no database, no Gradle.
set -euo pipefail
cd "$(dirname "$0")"

echo "==> compiling (core pipeline only; Mongo files need the driver, so they are"
echo "    excluded here and covered by ./gradlew test with docker compose mongo running)"
rm -rf build/selfcheck && mkdir -p build/selfcheck
javac -d build/selfcheck $(find src/main/java -name '*.java' | grep -v 'store/MongoDocumentStore.java' | grep -v 'store/DocStoreBench.java' | grep -v 'store/BenchExplain.java')

echo
echo "==> running"
java -cp build/selfcheck in.simplifymoney.ledgersync.SelfCheck "$@"
