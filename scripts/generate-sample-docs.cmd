@echo off
rem Creates the sample HRM documents in the folder "sample-docs".
rem Run it from the project folder:   scripts\generate-sample-docs.cmd
rem The texts of the policies are in scripts\sample-content\*.txt. Change a text and run this file again.
rem Needs Java 21 and has to download nothing: it uses the libraries of the project (PDFBox and Apache POI).

call mvnw.cmd -q dependency:build-classpath -Dmdep.outputFile=target\classpath.txt || exit /b 1
set /p CP=<target\classpath.txt
java -cp "%CP%" scripts\GenerateSampleDocs.java sample-docs
