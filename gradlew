#!/bin/sh
APP_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ -n "$JAVA_HOME" ]; then
    READER_JAVA="$JAVA_HOME/bin/java"
else
    READER_JAVA=java
fi
exec "$READER_JAVA" $JAVA_OPTS $GRADLE_OPTS -classpath "$APP_DIR/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
