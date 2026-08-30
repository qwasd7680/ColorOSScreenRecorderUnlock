#!/bin/sh

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

if [ -n "$JAVA_HOME" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD=java
fi

exec "$JAVACMD" -Xmx64m -Xms64m \
    -Dorg.gradle.appname=gradlew \
    -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" \
    org.gradle.wrapper.GradleWrapperMain "$@"
