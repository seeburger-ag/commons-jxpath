# Upgrade Plan: commons-jxpath (20260924080250)

- **Generated**: 2026-09-24 10:35 (local time)
- **HEAD Branch**: 1.4-seeburger
- **HEAD Commit ID**: b0cc4483ee19d265eb644d0a8577f16a3571f3b0

## Available Tools

**JDKs**

- JDK 11.0.16: `C:\dev\jdk-11` (current project JDK, used by step 2 baseline and step 3)
- JDK 21.0.2: `C:\dev\jdk-21` (target JDK, used by steps 4-6; also the JAVA_HOME/PATH default)

**Build Tools**

- Maven 3.9.16: `C:\dev\apache-maven\bin\mvn` (supports JDK 21; no upgrade needed)
- No Maven Wrapper (`mvnw`) present in the project ? the system Maven is used.

## Guidelines

- Upgrade the Java runtime/language level of `commons-jxpath` to Java 21 (LTS).
- Keep changes minimal: only what is required to build, test and package on JDK 21.
- Do not migrate `javax.servlet` ? `jakarta.servlet`; the servlet/JSP APIs are `provided`/`optional`
  integration points of this library and are out of scope for a JDK-only upgrade.
- Keep the artifact's OSGi metadata and manifest entries intact.

> Note: You can add any specific guidelines or constraints for the upgrade process here if needed, bullet points are preferred.

## Options

- Working branch: appmod/java-upgrade-20260924080250
- Run tests before and after the upgrade: true

## Upgrade Goals

- **Java**: 11 ? **21** (LTS)

## Technology Stack

| Technology/Dependency                     | Current | Min Compatible | Why Incompatible                                                                                                              |
| ----------------------------------------- | ------- | -------------- | ----------------------------------------------------------------------------------------------------------------------------- |
| Java                                      | 11      | 21             | User requested                                                                                                                  |
| Maven                                     | 3.9.16  | 3.9.x          | Already compatible with JDK 21                                                                                                  |
| commons-parent (parent POM)               | 39      | 39             | Kept; only individual plugin versions are overridden locally (see Derived Upgrades)                                             |
| maven-compiler-plugin (inherited)         | 3.3     | 3.13.0+        | 3.3 predates the `<release>` option (added 3.6.0) and predates JDK 9+ support in plexus-compiler                                |
| maven-surefire-plugin (inherited)         | 2.18.1  | 3.0.0+         | 2.x forked booter is not supported on JDK 9+; unreliable/failing on JDK 21                                                      |
| maven-bundle-plugin (felix, inherited) ?? | 2.5.3   | 6.0.0+         | Bundles bnd 2.x which cannot parse class-file major version 65 (Java 21); runs at `process-classes` so it blocks every build |
| maven-jar-plugin (inherited)              | 2.6     | 2.6            | Works on JDK 21; only runs at `package`                                                                                         |
| maven-enforcer-plugin (inherited)         | 1.3.1   | 1.3.1          | Only rule is `requireMavenVersion 3.0.0`; unaffected by the JDK                                                                 |
| maven-antrun-plugin (inherited)           | 1.8     | 1.8            | Only performs a `copy` of LICENSE/NOTICE; expected to work on JDK 21                                                            |
| buildnumber-maven-plugin (inherited)      | 1.3     | 1.3            | Pure Java SCM lookup; unaffected                                                                                                |
| maven-remote-resources-plugin (inherited) | 1.5     | 1.5            | Velocity-based resource bundle; expected to work, monitored (see Risks)                                                         |
| junit                                     | 3.8.1   | 3.8.1          | Still supported by the `surefire-junit3` provider in Surefire 3.x                                                               |
| commons-beanutils (optional)              | 1.8.2   | 1.8.2          | JDK-neutral; version reviewed in the CVE step                                                                                   |
| jdom (optional)                           | 1.0     | 1.0            | JDK-neutral; DOM/JDOM model support                                                                                             |
| commons-logging (managed, runtime)        | 1.1.1   | 1.1.1          | JDK-neutral                                                                                                                     |
| javax.servlet:servlet-api (provided/opt.) | 2.4     | 2.4            | Compile-time only optional integration; not affected by JDK 21                                                                  |
| javax.servlet:jsp-api (provided/opt.)     | 2.0     | 2.0            | Compile-time only optional integration; not affected by JDK 21                                                                  |
| com.mockrunner (test)                     | 0.4     | 0.4            | Test-only mocks; old XML stack already excluded in the POM                                                                      |

## Derived Upgrades

| Derived change                                        | Justification                                                                                                                                          |
| ----------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------ |
| maven-compiler-plugin 3.3 ? 3.14.0 (local override)   | Java 21 ? compiler plugin 3.11+ recommended; also unlocks `<maven.compiler.release>`, which the POM comment already anticipates                        |
| maven-surefire-plugin 2.18.1 ? 3.5.4 (local override) | Java 21 ? Surefire 3.x required; 2.x forked booter is unsupported on JDK 9+                                                                           |
| maven-bundle-plugin 2.5.3 ? 6.2.0 (local override)    | Java 21 class files (major 65) must be analysable by bnd; bnd ? 7 (plugin 6.x) supports them. Plugin 6.x itself requires a JDK 17+ build JVM ? therefore it is upgraded in the same step that switches the build to JDK 21 |
| `maven.compiler.release=21` added                     | Java 21 target; `release` guarantees compilation against the Java 21 API signatures instead of only emitting 21 byte code                               |
| `.classpath` JRE container JavaSE-11 ? JavaSE-21      | IDE (Eclipse/m2e) metadata must match the new language level, otherwise the IDE reports phantom errors                                                  |

No Kotlin, Spring Boot, Gradle or CI/CD pipeline files exist in this project, so no derived upgrades apply for those.

## Impact Analysis

### Dependency Changes

| File    | Dependency                              | Current | Action | Target | Reason                                                                                                        |
| ------- | --------------------------------------- | ------- | ------ | ------ | ------------------------------------------------------------------------------------------------------------- |
| pom.xml | `maven.compiler.source`                 | 11      | upgrade | 21     | Java 21 language level; also feeds the `X-Compile-Source-JDK` manifest entry from commons-parent              |
| pom.xml | `maven.compiler.target`                 | 11      | upgrade | 21     | Java 21 byte code; also feeds the `X-Compile-Target-JDK` manifest entry from commons-parent                   |
| pom.xml | `maven.compiler.release`                | (unset) | add     | 21     | Compile against the Java 21 API; possible once the compiler plugin is ? 3.6.0                                |
| pom.xml | `org.apache.maven.plugins:maven-compiler-plugin` | 3.3 (inherited) | add (pin) | 3.14.0 | JDK 21 support and `<release>` support                                                                 |
| pom.xml | `org.apache.maven.plugins:maven-surefire-plugin` | 2.18.1 (inherited) | add (pin) | 3.5.4 | Surefire 2.x cannot fork a JDK 9+ JVM reliably; 3.x keeps the `surefire-junit3` provider for JUnit 3.8.1 |
| pom.xml | `org.apache.felix:maven-bundle-plugin`  | 2.5.3 (inherited) | add (pin) | 6.2.0 | bnd 2.x aborts on class-file major version 65 (Java 21) during the `manifest` goal at `process-classes` |

Note: the parent POM (`commons-parent:39`) is intentionally **not** upgraded. Only the three plugin
versions that actually block JDK 21 are overridden locally ? this is the minimal change set and avoids
the large, unrelated behaviour changes of a parent POM jump.

### Source Code Changes

No source code changes are required for the JDK 11 ? 21 upgrade. The compatibility scan found:

| Pattern searched                                                        | Result in `src/main/java` + `src/test/java`                                                                             |
| ----------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------ |
| `sun.misc.*`, `sun.reflect.*`, `jdk.internal.*` imports                 | None                                                                                                                       |
| `setAccessible(...)` on JDK-internal types                              | None                                                                                                                       |
| `SecurityManager`, `AccessController.doPrivileged`                      | None                                                                                                                       |
| `Thread.stop`, `Object.finalize()`, `java.applet.*`                     | None                                                                                                                       |
| `javax.xml.bind.*`, `javax.activation.*` (JDK-removed modules)          | None                                                                                                                       |
| Default-charset sensitive APIs (`new FileReader`, `getBytes()`, `Charset.defaultCharset`) | None ? relevant because the default `file.encoding` became UTF-8 in JDK 18                             |
| `Class.newInstance()` (deprecated for removal)                          | Present in 7 files (`JXPathIntrospector`, `ValueUtils`, `BasicTypeConverter`, `DocumentContainer`, `JXPathContextReferenceImpl`, `JXPathContextFactory`) ? **still available in Java 21**, compiles with a deprecation warning. No change required. |
| `new Integer(...)` / boxed-primitive constructors (deprecated for removal) | Present in test sources only ? **still available in Java 21**, compiles with a deprecation warning. No change required. |
| `javax.servlet.*` / `javax.servlet.jsp.*` imports                       | Present in the `org.apache.commons.jxpath.servlet` package ? unaffected by the JDK upgrade, out of scope per Guidelines |

### Configuration Changes

| File        | Property/Setting                                        | Current                      | Required Change               | Reason                                                              |
| ----------- | ------------------------------------------------------- | ---------------------------- | ----------------------------- | ------------------------------------------------------------------- |
| pom.xml     | `<properties>` comment above `maven.compiler.source`     | Explains the 3.3 plugin limitation | Rewrite to describe the Java 21 baseline and the pinned plugin versions | The comment becomes stale/incorrect after the compiler plugin is pinned |
| .classpath  | `JRE_CONTAINER/.../JavaSE-11`                            | JavaSE-11                    | JavaSE-21                     | Keep Eclipse/m2e project metadata consistent with the build          |

`src/conf/MANIFEST.MF`, `checkstyle.xml`, `rat.xml` and `conf/findbugs-exclude-filter.xml` contain no
Java-version-specific settings. `build.xml` (legacy Ant build) has no `source=`/`target=` attributes.

### CI/CD Changes

None ? the repository contains no Dockerfile, no GitHub Actions workflow, no Jenkinsfile and no
pipeline definition. (`.github/modernize/appcat/assessment-config.yaml` is tooling metadata, not CI.)

### Risks & Warnings

- **maven-bundle-plugin / bnd upgrade (2.5.3 ? 6.2.0)**: This is the change most likely to alter the
  produced artifact, because bnd 7 regenerates `target/osgi/MANIFEST.MF` (which `maven-jar-plugin`
  embeds into the jar). Expect a new `Require-Capability: osgi.ee=JavaSE 21` header and possibly a
  re-ordered `Import-Package`. **Mitigation**: after the upgrade, diff the generated
  `target/osgi/MANIFEST.MF` against the pre-upgrade one and verify that `Bundle-SymbolicName`,
  `Export-Package` (`org.apache.commons.*;version=?`) and `Import-Package: *;resolution:=optional`
  are still present and unchanged in substance.
- **maven-bundle-plugin 6.x requires a JDK 17+ build JVM**: it therefore cannot be introduced while the
  build still runs on JDK 11. **Mitigation**: the plugin pin and the JDK switch are performed in the
  same step (step 4), and step 3 is verified on JDK 11 with only the compiler/surefire pins.
- **Surefire 2.18.1 ? 3.5.4 with JUnit 3.8.1**: the provider auto-selection changes from
  `surefire-junit3` (2.x) to `surefire-junit3` (3.x). commons-parent also passes an empty `<jvm/>`
  parameter into the surefire configuration. **Mitigation**: the baseline run in step 2 records the
  exact test counts; after the upgrade the same number of tests must be executed (not silently `0`).
  If Surefire reports "No tests to run", override the `<jvm/>` parameter and/or add
  `<forkCount>1</forkCount>` explicitly in the project POM.
- **maven-remote-resources-plugin 1.5 and maven-antrun-plugin 1.8 stay at their inherited versions**:
  both are old (Velocity 1.x / Ant 1.9.x) and run before `test-compile`. They are expected to work on
  JDK 21 but were not independently verified. **Mitigation**: if either fails on JDK 21, pin
  `maven-remote-resources-plugin` to 3.2.0 and `maven-antrun-plugin` to 3.1.0 in the project POM.
- **Reflection-heavy library semantics**: JXPath resolves properties via `java.beans.Introspector`
  and reflective method lookup (`ValueUtils`, `MethodLookupUtils`). Reflective access to *non-exported*
  JDK packages is denied since JDK 17. No such access was found in the sources, and the behaviour is
  covered by the existing test suite. **Mitigation**: the full test suite must pass on JDK 21 in the
  final validation step; any `InaccessibleObjectException` surfacing there must be fixed with
  `MethodHandles.privateLookupIn(...)` rather than with `--add-opens`.
- **Deprecated-for-removal APIs (`Class.newInstance()`, boxed constructors)**: they still compile on
  Java 21 but will be removed in a future release. Out of scope for this upgrade (minimal-change
  guideline); recorded here so that a follow-up task can address them.
- **Known-vulnerable transitive/optional dependencies** (`commons-beanutils 1.8.2`, `jdom 1.0`,
  `commons-logging 1.1.1`, `servlet-api 2.4`): pre-existing, unrelated to the JDK upgrade.
  **Mitigation**: handled explicitly in the CVE Validation & Fix step (step 5).

## Upgrade Steps

- Step 1: Setup Environment
  - **Rationale**: JDK 21 and a JDK-21-capable Maven must be present before any build is attempted.
  - **Changes to Make**: None in the project. Verify JDK 11 (`C:\dev\jdk-11`), JDK 21 (`C:\dev\jdk-21`)
    and Maven 3.9.16 (`C:\dev\apache-maven`) are available; nothing needs to be installed.
  - **Verification**: `#appmod-list-jdks` / `#appmod-list-mavens`; JDK: n/a. Expected: JDK 11, JDK 21 and Maven 3.9.16 reported as available.

- Step 2: Setup Baseline
  - **Rationale**: Establish the pre-upgrade compile and test pass rate, which forms the acceptance
    criteria for the final validation.
  - **Changes to Make**: None. Record the produced `target/osgi/MANIFEST.MF` for the later OSGi diff.
  - **Verification**: Command `mvn clean test-compile -q` then `mvn clean test`; JDK: `C:\dev\jdk-11`;
    Build tool: `C:\dev\apache-maven\bin\mvn`. Expected: documented SUCCESS/FAILURE plus exact
    tests-run / failures / errors / skipped counts.

- Step 3: Pin JDK-21-capable compiler and test plugins (still building on JDK 11)
  - **Rationale**: Isolate the plugin-infrastructure upgrade from the language-level change. Both
    plugins run fine on a JDK 11 build JVM, so a regression here is unambiguously attributable to the
    plugin upgrade and not to Java 21.
  - **Changes to Make**: Apply the `maven-compiler-plugin` ? 3.14.0 and `maven-surefire-plugin` ? 3.5.4
    rows from *Dependency Changes*. Keep `maven.compiler.source/target` at 11 for this step.
  - **Verification**: Command `mvn clean test-compile -q` and `mvn clean test`; JDK: `C:\dev\jdk-11`;
    Build tool: `C:\dev\apache-maven\bin\mvn`. Expected: compilation SUCCESS and the same test counts
    as the step 2 baseline.

- Step 4: Switch the build to Java 21
  - **Rationale**: The actual upgrade goal. The felix bundle plugin must be upgraded in the same step,
    because bnd 2.x cannot read Java 21 class files while bnd 7 needs a JDK 17+ build JVM ? the two
    changes are only valid together.
  - **Changes to Make**: Apply the remaining *Dependency Changes* (`maven.compiler.source/target` ? 21,
    add `maven.compiler.release` = 21, pin `org.apache.felix:maven-bundle-plugin` ? 6.2.0) and all
    *Configuration Changes* (refresh the stale `<properties>` comment, `.classpath` ? JavaSE-21).
  - **Verification**: Command `mvn clean test-compile -q`; JDK: `C:\dev\jdk-21`; Build tool:
    `C:\dev\apache-maven\bin\mvn`. Expected: compilation of main and test sources SUCCESS on JDK 21;
    `target/classes` class files at major version 65; `target/osgi/MANIFEST.MF` regenerated and diffed
    against the baseline per *Risks & Warnings*.

- Step 5: CVE Validation & Fix
  - **Rationale**: Ensure the upgraded dependency set carries no known vulnerabilities.
  - **Changes to Make**: Extract direct dependencies (`mvn dependency:list -DexcludeTransitive=true`),
    scan with `#appmod-validate-cves-for-java`, then upgrade the affected dependency versions in
    `pom.xml`. Re-scan to confirm resolution and record any CVE without an available fix.
  - **Verification**: Command `mvn clean test-compile -q` after the fixes plus a CVE re-scan;
    JDK: `C:\dev\jdk-21`; Build tool: `C:\dev\apache-maven\bin\mvn`. Expected: build SUCCESS and no
    remaining fixable CVEs.

- Step 6: Final Validation
  - **Rationale**: Prove that the Java 21 upgrade goal is met and that no regression was introduced.
  - **Changes to Make**: Resolve every remaining TODO/temporary workaround introduced in steps 3-5;
    fix all test failures iteratively until the pass rate is 100% (or at least equal to the step 2
    baseline).
  - **Verification**: Command `mvn clean test` and `mvn clean package`; JDK: `C:\dev\jdk-21`;
    Build tool: `C:\dev\apache-maven\bin\mvn`. Expected: BUILD SUCCESS, test pass rate ? baseline,
    the packaged jar manifest showing `X-Compile-Source-JDK: 21` / `X-Compile-Target-JDK: 21`, and a
    valid OSGi manifest.

