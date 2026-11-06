#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
if command -v javac >/dev/null 2>&1; then
    javac -d "$out" app/src/main/java/com/suj/zhuanluji/ModelArchiveVerifier.java tests/ModelArchiveVerifierTest.java
else
    java -m jdk.compiler/com.sun.tools.javac.Main -d "$out" app/src/main/java/com/suj/zhuanluji/ModelArchiveVerifier.java tests/ModelArchiveVerifierTest.java
fi
java -cp "$out" com.suj.zhuanluji.ModelArchiveVerifierTest
