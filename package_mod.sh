#!/bin/sh
# Builds the jar and packages a release: LemonLib.zip
# The file list lives in packageMod (build.gradle.kts).
cd "$(dirname "$0")" && sh ./gradlew packageMod
