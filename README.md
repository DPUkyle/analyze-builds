# Gradle Export API Parallelism Analyzer

![Build](https://github.com/lptr/analyze-builds/actions/workflows/build.yml/badge.svg)

Queries build scan data from a [Gradle Enterprise](https://gradle.com) server via the [Export API](https://docs.gradle.com/enterprise/export-api/), filters them and summarizes task execution and parallelism data.

Currently the following data is collected:

- distribution of build time spent running 1, 2, 3 etc. tasks concurrently,
- distribution of build time broken down by task type,
- distribution of build time broken down by task path,
- distribution of the `org.gradle.workers.max` setting for the builds encountered.

### Limitations

The tool only processes task executions. It does not include worker API work and artifact transform executions, as build scans contain no information about these work types. For vanilla Java and Kotlin builds this typically shouldn't be a problem. It will distort results when measuring Android builds that rely on both transforms and the worker API.

The resutls only include a breakdown of the _execution phase_ of the Gradle build. They don't include the overhead added by Gradle's configuration phase, nor any IDE overhead. When considering actual developer experience these overheads should be accounted for.

## Minimum Gradle Enterprise version

This tool uses version 2 of the Export API, available since Gradle Enterprise 2021.2.

## Authentication

1. Create a Gradle Enterprise access key for a user with the `Export API` role as described in the [Export API Access Control](https://docs.gradle.com/enterprise/export-api/#access_control) documentation.
2. Set an environment variable locally: `EXPORT_API_ACCESS_KEY` to match the newly created Gradle Enterprise access key. You can also pass it via the `--api-key` parameter.

## Invoking the tool

You can download the latest release from [releases](https://github.com/lptr/analyze-builds/releases). Once extracted, use the shell script or batch file in the `bin` directory to invoke the tool:

```bash
$ bin/analyze-builds \
    --server https://ge.gradle.org \
    --api-key ... \
    --include-project gradle \
    --include-tag LOCAL \
    --exclude-task-type org.gradle.api.tasks.testing.Test
```

Use `--help` to check all the options available.

### Using Gradle to build the tool from source and invoke it

```bash
$ ./gradlew :run --args="..."
```

## Example output

```text
Connecting to GE server at https://ge.gradle.org/
Querying builds since Jan 21, 2022, 1:48:20 PM
Filtering builds by:
 - include projects 'gradle'
 - include tags 'LOCAL'
 - not filtering by requested tasks
Filtering tasks by:
 - exclude task type prefixes 'org.gradle.api.tasks.testing.Test'
Finished querying builds, found 111
Statistics for 8 builds with 162 tasks

Wall-clock time spent running n tasks concurrently:
1: 697098 ms
2: 13226 ms
3: 22307 ms
4: 2896 ms
5: 658 ms

Cumlative build time broken down by task type:
org.gradle.api.DefaultTask: 2 ms
org.gradle.api.internal.runtimeshaded.PackageListGenerator: 5365 ms
org.gradle.api.publish.maven.tasks.GenerateMavenPom: 38 ms
org.gradle.api.publish.maven.tasks.PublishToMavenRepository: 654 ms
org.gradle.api.publish.tasks.GenerateModuleMetadata: 249 ms
...

Cumlative build time broken down by task path:
:antlr:classes: 0 ms
:antlr:parameterNamesIndex: 204 ms
:base-services:createBuildReceipt: 204 ms
:base-services:jar: 1292 ms
:basics:inspectClassesForKotlinIC: 25 ms
...

Max workers:
1: 0 builds
2: 0 builds
3: 0 builds
4: 0 builds
5: 3 builds
6: 0 builds
7: 0 builds
8: 0 builds
9: 0 builds
10: 0 builds
11: 0 builds
12: 0 builds
13: 0 builds
14: 0 builds
15: 0 builds
16: 5 builds
```
