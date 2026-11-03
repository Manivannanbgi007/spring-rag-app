#!/bin/sh
# Creates the sample HRM documents in the folder "sample-docs".
# Run it from the project folder:   sh scripts/generate-sample-docs.sh
# The texts of the policies are in scripts/sample-content/*.txt. Change a text and run this file again.
# Needs Java 21. It uses the libraries of the project (PDFBox and Apache POI), nothing is downloaded separately.
set -e
./mvnw -q dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
java -cp "$(cat target/classpath.txt)" scripts/GenerateSampleDocs.java sample-docs
