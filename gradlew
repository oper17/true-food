#!/bin/sh

export JAVA_HOME="/storage/internal_new/project/jdk17"

GRADLE_BIN="/tmp/gradle-8.7/gradle-8.7/bin/gradle"

if [ ! -x "$JAVA_HOME/bin/java" ]; then
    echo "Java 17 not found at $JAVA_HOME"
    exit 1
fi

if [ ! -x "$GRADLE_BIN" ]; then
    echo "Gradle 8.7 not found at $GRADLE_BIN"
    exit 1
fi

exec "$GRADLE_BIN" "$@"
