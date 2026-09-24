# Java Upgrade Result

> **Executive Summary**\
> This report documents the successful upgrade of Apache Commons JXPath (`com.seeburger.as:commons-jxpath`)
> from Java 11 to Java 21 LTS. The upgrade moves the library onto a runtime with security support into the
> 2030s, unblocks consumption by Java 21 based applications, and removes three long-standing build-tool
> blockers (a 2015-era compiler plugin, a Surefire release whose forked booter never supported JDK 9+, and a
> bnd 2.3 based OSGi manifest generator that cannot read Java 21 class files). No production or test source
> code had to be changed ? the compatibility scan found no use of internal JDK APIs, no `setAccessible` on
> platform types, and no default-charset-sensitive code. All 398 unit tests pass on JDK 21, exactly matching
> the pre-upgrade baseline, and the three HIGH-severity CVEs found in `commons-beanutils` were fixed along
> the way.

## 1. Upgrade Improvements

The project now compiles and runs on Java 21 (LTS, supported into the 2030s) instead of Java 11, whose free
public support window has already closed. Because the language level is enforced with
`maven.compiler.release` rather than plain `source`/`target`, the build now also verifies that only Java 21
platform APIs are referenced. As a side effect of replacing the ancient bnd toolchain, the published OSGi
bundle finally carries a valid execution-environment requirement ? the previous artifact shipped a
syntactically empty `Require-Capability: osgi.ee;filter:=` header.

| Area | Before | After | Improvement |
| ---- | ------ | ----- | ----------- |
| JDK / language level | Java 11 (`source`/`target`) | Java 21 LTS (`release=21`) | Long-term support, modern language & JVM features, API-level compile verification |
| maven-compiler-plugin | 3.3 (inherited from commons-parent 39) | 3.14.0 (pinned) | JDK 9+ support, `<release>` support |
| maven-surefire-plugin | 2.18.1 (inherited) | 3.5.4 (pinned) | Forked test JVM works on JDK 9+; maintained release line |
| org.apache.felix:maven-bundle-plugin | 2.5.3 / bnd 2.3 (inherited) | 6.2.0 / bnd 7.4 (pinned) | Can analyse Java 21 class files; emits a correct `osgi.ee` capability |
| OSGi `Require-Capability` | `osgi.ee;filter:=` (empty, invalid) | `osgi.ee;filter:="(&(osgi.ee=JavaSE)(version=21))"` | Bundle now declares a resolvable execution environment |
| commons-beanutils | 1.8.2 | 1.11.0 | Fixes 3 HIGH CVEs; hardened `PropertyUtilsBean` by default |

### Key Benefits

**Performance & Security**

- Java 21 LTS receives security patches for years to come, whereas Java 11 public updates have ended.
- Modern garbage collectors (G1 improvements, ZGC/Generational ZGC) and JIT optimisations are available to consumers without any code change.
- Three HIGH-severity vulnerabilities (CVE-2014-0114, CVE-2019-10086, CVE-2025-48734) were eliminated by upgrading `commons-beanutils`; version 1.11.0 additionally blocks `class`/`declaredClass` classloader traversal by default.
- The post-upgrade CVE re-scan reports zero known vulnerabilities across the direct dependency set.

**Developer Productivity**

- The library can now be developed and consumed with a current JDK, removing the need to keep a Java 11 toolchain around.
- Surefire 3.5.4 produces modern, readable test reports and no longer risks the "forked VM terminated" failures typical of 2.x on new JDKs.
- Eclipse project metadata (`.classpath`) was aligned to `JavaSE-21`, so the IDE no longer disagrees with the Maven build.
- `maven.compiler.release` makes accidental use of newer-than-target APIs a compile error instead of a runtime surprise.

**Future-Ready Foundation**

- JXPath can now be embedded in Java 17/21-only runtimes and OSGi containers that require a declared `osgi.ee` capability.
- The Java 21 baseline is a prerequisite for adopting virtual threads, records, pattern matching and sealed types in future refactorings.
- Pinning the three critical build plugins locally decouples the project from the frozen `commons-parent:39` plugin set, making further JDK jumps (e.g. Java 25) a small, well-understood change.

## 2. Build and Validation

### Build Validation

| Field      | Value |
| ---------- | ----- |
| Status     | ? Success |
| Compiler   | Java 21.0.2 (`C:\dev\jdk-21`), `maven.compiler.release=21` |
| Build Tool | Maven 3.9.16 (`C:\dev\apache-maven\bin\mvn`) ? no wrapper in this project |
| Result     | `mvn clean package` succeeded; all main and test sources compiled with no errors. Emitted class files are at class-file major version 65 (Java 21) and the packaged jar reports `X-Compile-Source-JDK: 21` / `X-Compile-Target-JDK: 21`. |

### Test Validation

| Field          | Value |
| -------------- | ----- |
| Status         | ? Success |
| Total Tests    | 398 |
| Passed         | 398 (100%) |
| Failed         | 0 (0 failures, 0 errors, 0 skipped) |
| Test Framework | JUnit 3.8.1 executed through the `surefire-junit3` provider of maven-surefire-plugin 3.5.4 |

Baseline comparison: the pre-upgrade run on JDK 11 also produced 398/398 passing tests, so the upgrade
introduced no regressions. Code coverage measured with JaCoCo 0.8.13 on JDK 21: **line 76.7 %**
(7363/9596), instruction 75.3 %, branch 67.5 %.

The individual test list is omitted because the suite contains far more than 20 test cases; the full
per-class breakdown is available in `target/surefire-reports`.

---

## 3. Limitations

None. All planned changes were applied, all tests pass, and no temporary workarounds, `--add-opens`
flags or TODO markers were left behind.

Two observations were deliberately treated as out of scope rather than as limitations, because they do
not affect the Java 21 goal and are tracked as next steps below:

- **Deprecated-for-removal APIs still compile on Java 21.** `Class.newInstance()` (7 call sites in `src/main/java`) and boxed-primitive constructors such as `new Integer(...)` (test sources only) produce deprecation warnings but are fully functional in Java 21. Changing them is a behaviour-neutral refactoring unrelated to the runtime upgrade.
- **`javax.servlet` / `javax.servlet.jsp` were not migrated to Jakarta.** These are `provided` + `optional` integration points of the `org.apache.commons.jxpath.servlet` package. They are unaffected by the JDK version, and migrating them would be a breaking API change for existing consumers.

---

## 4. Recommended next steps

I. **Validate the OSGi bundle in the target container.** The `Require-Capability` header changed from an empty (invalid) filter to `(&(osgi.ee=JavaSE)(version=21))`. Deploy the snapshot into the consuming runtime and confirm the bundle resolves ? the framework must now provide a JavaSE 21 execution environment.

II. **Verify downstream consumers can run on Java 21.** The artifact's byte code is now class-file major version 65 and can no longer be loaded by a Java 11/17 JVM. Confirm every consumer of `com.seeburger.as:commons-jxpath` has been moved to a Java 21 runtime before releasing.

III. **Smoke-test the hardened `commons-beanutils` 1.11.0 behaviour.** Version 1.11.0 enables the suppressing `BeanIntrospector` by default, so `PropertyUtilsBean` no longer exposes the `class` / `declaredClass` properties. The JXPath test suite passes unchanged, but applications that intentionally resolved such paths must be reviewed.

IV. **Clean up deprecated-for-removal APIs.** Replace the 7 `Class.newInstance()` call sites with `clazz.getDeclaredConstructor().newInstance()` and remove boxed-primitive constructors from the tests, so a future JDK that actually removes them cannot break the build.

V. **Consider modernising the remaining inherited plugins.** `maven-antrun-plugin 1.8`, `maven-remote-resources-plugin 1.5`, `buildnumber-maven-plugin 1.3` and `maven-enforcer-plugin 1.3.1` still come from `commons-parent:39`. They all executed correctly on JDK 21 during this upgrade, but pinning current versions (or moving to a newer `commons-parent`) would reduce future risk.

VI. **Adopt Java 21 language features where they add value.** Records, pattern matching for `switch`/`instanceof` and enhanced `Collection` APIs can simplify parts of the XPath compiler and the model packages.

---

## 5. Additional details

<details>
<summary>Click to expand for upgrade details</summary>

### Project Details

| Field                 | Value                            |
| --------------------- | -------------------------------- |
| Session ID            | 20260924080250                   |
| Upgrade executed by   | r.neubauer                       |
| Upgrade performed by  | GitHub Copilot                   |
| Project path          | C:\Users\r.neubauer\git\commons-jxpath |
| Repository            | seeburger-ag/commons-jxpath      |
| Build tool (before)   | Maven 3.9.16                     |
| Build tool (after)    | Maven 3.9.16 (unchanged)         |
| Files modified        | 2 project files (`pom.xml`, `.classpath`) + 2 generated session documents |
| Lines added / removed | +48 / -17 (project files only)   |
| Branch created        | appmod/java-upgrade-20260924080250 |
| Base branch / commit  | 1.4-seeburger @ b0cc4483ee19d265eb644d0a8577f16a3571f3b0 |

### Code Changes

1. **`pom.xml`**
   - **Changes:** Raised the language level to Java 21 and pinned the three build plugins that block JDK 21.
   - **Details:**
     - `maven.compiler.source` / `maven.compiler.target`: `11` ? `21`; added `maven.compiler.release=21` as the authoritative setting. `source`/`target` are kept in sync because `commons-parent:39` feeds them into the `X-Compile-Source-JDK` / `X-Compile-Target-JDK` manifest entries.
     - Added `org.apache.maven.plugins:maven-compiler-plugin:3.14.0` (was `3.3`, inherited).
     - Added `org.apache.maven.plugins:maven-surefire-plugin:3.5.4` (was `2.18.1`, inherited), keeping the existing `**/*Test.java` include.
     - Added `org.apache.felix:maven-bundle-plugin:6.2.0` (was `2.5.3`, inherited).
     - `commons.osgi.import`: `*;resolution:=optional` ? `!java.*,*;resolution:=optional`, so bnd 7 does not add `java.*` entries to `Import-Package` that the previous manifest did not contain.
     - `commons-beanutils`: `1.8.2` ? `1.11.0`, annotated as a CVE-driven pin.
     - Replaced the now-obsolete "Java 11 baseline" comment.

2. **`.classpath`**
   - **Changes:** Aligned the Eclipse JRE container with the new build language level.
   - **Before:** `org.eclipse.jdt.launching.JRE_CONTAINER/...StandardVMType/JavaSE-11`
   - **After:** `org.eclipse.jdt.launching.JRE_CONTAINER/...StandardVMType/JavaSE-21`

3. **No source code changes**
   - The compatibility scan across all 232 Java files found no `sun.misc.*` / `jdk.internal.*` imports, no `setAccessible` on JDK types, no `SecurityManager` / `AccessController` usage, no JDK-removed modules (JAXB, activation) and no default-charset-sensitive APIs ? so nothing in `src/main/java` or `src/test/java` required modification.

4. **No CI/CD changes**
   - The repository contains no Dockerfile, workflow, pipeline or Jenkinsfile with a hardcoded JDK version.

All changes are committed to `appmod/java-upgrade-20260924080250` (commits `6c70e71`, `48b8847`, `56a6e90`, `07063286`) and are ready for review.

### Automated tasks

- Compatibility scan of 232 Java sources, build files, configuration and documentation
- Baseline build and test run on JDK 11 (398/398 passing) for regression comparison
- Build plugin upgrades (compiler, surefire, felix bundle plugin)
- Java language level migration to 21 (`release`, `source`, `target`)
- OSGi manifest regeneration plus byte-for-byte comparison against the pre-upgrade manifest
- IDE metadata alignment (`.classpath`)
- Dependency CVE scan, automated fix and re-scan verification
- Test coverage measurement with JaCoCo
- Per-step verification and commits via version control

### Potential Issues

#### CVEs

**Scan Status**: ? All CVEs resolved

**Scanned**: 7 direct/managed dependencies | **Found**: 3 | **Auto-fixed**: 3 | **Remaining**: 0

| Severity | CVE ID         | Dependency                            | Before | After  | Status   |
| -------- | -------------- | ------------------------------------- | ------ | ------ | -------- |
| High     | CVE-2014-0114  | commons-beanutils:commons-beanutils   | 1.8.2  | 1.11.0 | ? Fixed |
| High     | CVE-2019-10086 | commons-beanutils:commons-beanutils   | 1.8.2  | 1.11.0 | ? Fixed |
| High     | CVE-2025-48734 | commons-beanutils:commons-beanutils   | 1.8.2  | 1.11.0 | ? Fixed |

No CVEs were reported for `jdom:jdom:1.0`, `junit:junit:3.8.1`, `javax.servlet:servlet-api:2.4`,
`javax.servlet:jsp-api:2.0`, `com.mockrunner:mockrunner-jdk1.3-j2ee1.3:0.4` or the managed
`commons-logging:commons-logging:1.1.1`.

</details>

