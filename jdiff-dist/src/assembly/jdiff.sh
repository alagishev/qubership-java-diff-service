#!/bin/sh
exec java -jar "$(dirname "$0")/jdiff.jar" "$@"
