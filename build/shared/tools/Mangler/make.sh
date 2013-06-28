#!/bin/sh

# The uecide.jar file may be buried inside the .app file on Mac OS X.
PDE=`find ../.. -name uecide.jar`

javac -target 1.5 \
  -cp "../../lib/core.jar:$PDE" \
  -d bin \
  src/Mangler.java

cd bin && zip -r ../tool/mangler.jar * && cd ..
