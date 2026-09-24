# Upgrade Progress: commons-jxpath (20260924080250)

- **Started**: 2026-09-24 10:40 (local time)
- **Plan Location**: `.github/modernize/java-upgrade/20260924080250/plan.md`
- **Total Steps**: 6

## Step Details

- **Step 1: Setup Environment**
  - **Status**: ? Completed
  - **Changes Made**:
    - No project changes; environment already complete
    - Confirmed JDK 11, JDK 21 and Maven 3.9.16 installed
  - **Review Code Changes**:
    - Sufficiency: ? All required changes present (none required)
    - Necessity: ? All changes necessary (no files modified)
      - Functional Behavior: ? Preserved
      - Security Controls: ? Preserved
  - **Verification**:
    - Command: `#appmod-list-jdks` / `#appmod-list-mavens`
    - JDK: C:\dev\jdk-11 (baseline), C:\dev\jdk-21 (target)
    - Build tool: C:\dev\apache-maven\bin\mvn (Maven 3.9.16)
    - Result: ? SUCCESS ? no installation needed
    - Notes: No Maven wrapper in the project; system Maven 3.9.16 is JDK 21 compatible
  - **Deferred Work**: None
  - **Commit**: N/A - no changes to commit

- **Step 2: Setup Baseline**
  - **Status**: ? Completed
  - **Changes Made**:
    - No project changes; baseline build and test run recorded
    - Archived baseline `target/osgi/MANIFEST.MF` for later OSGi diff
  - **Review Code Changes**:
    - Sufficiency: ? All required changes present (none required)
    - Necessity: ? All changes necessary (no files modified)
      - Functional Behavior: ? Preserved
      - Security Controls: ? Preserved
  - **Verification**:
    - Command: `mvn clean test`
    - JDK: C:\dev\jdk-11 (11.0.16)
    - Build tool: C:\dev\apache-maven\bin\mvn (3.9.16)
    - Result: ? BUILD SUCCESS | Tests: 398/398 passed (0 failures, 0 errors, 0 skipped)
    - Notes: Acceptance criterion for the upgrade = 398/398 tests passing. Baseline OSGi manifest archived to `%TEMP%\jxpath-baseline\MANIFEST-baseline.MF`; it contains a broken `Require-Capability: osgi.ee;filter:=` (empty filter) produced by bnd 2.3.0, which does not know Java 11.
  - **Deferred Work**: None
  - **Commit**: N/A - no changes to commit

- **Step 3: Pin JDK-21-capable compiler and test plugins (still building on JDK 11)**
  - **Status**: ? Completed
  - **Changes Made**:
    - pom.xml: pinned maven-compiler-plugin 3.3 (inherited) ? 3.14.0
    - pom.xml: pinned maven-surefire-plugin 2.18.1 (inherited) ? 3.5.4
    - Kept the existing surefire `**/*Test.java` include configuration
  - **Review Code Changes**:
    - Sufficiency: ? All required changes present
    - Necessity: ? All changes necessary
      - Functional Behavior: ? Preserved ? identical test set (398) executed, language level unchanged at 11
      - Security Controls: ? Preserved ? no security-relevant configuration touched
  - **Verification**:
    - Command: `mvn clean test`
    - JDK: C:\dev\jdk-11 (11.0.16)
    - Build tool: C:\dev\apache-maven\bin\mvn (3.9.16)
    - Result: ? BUILD SUCCESS | Tests: 398/398 passed (matches baseline)
    - Notes: Surefire 3.5.4 correctly auto-selected the surefire-junit3 provider; the inherited empty `<jvm/>` parameter caused no problem
  - **Deferred Work**: felix maven-bundle-plugin still at inherited 2.5.3 ? upgraded in step 4 together with the JDK switch (plugin 6.x needs a JDK 17+ build JVM)
  - **Commit**: 6c70e71 - Step 3: Pin JDK-21-capable compiler and test plugins - Compile: SUCCESS, Tests: 398/398 passed

- **Step 4: Switch the build to Java 21**
  - **Status**: ? Completed
  - **Changes Made**:
    - pom.xml: `maven.compiler.release/source/target` 11 ? 21
    - pom.xml: pinned org.apache.felix:maven-bundle-plugin 2.5.3 ? 6.2.0
    - pom.xml: `commons.osgi.import` now excludes `java.*` from Import-Package
    - .classpath: Eclipse JRE container JavaSE-11 ? JavaSE-21
    - No source code changes required
  - **Review Code Changes**:
    - Sufficiency: ? All required changes present
    - Necessity: ? All changes necessary
      - Functional Behavior: ? Preserved ? 398/398 tests pass; OSGi manifest diffed against baseline: `Bundle-SymbolicName`, `Export-Package`, `Import-Package` and `Include-Resource` are identical. `Require-Capability: osgi.ee` changed from an empty filter (bnd 2.3 did not know Java 11) to `(&(osgi.ee=JavaSE)(version=21))`, which is the correct and intended result of the upgrade.
      - Security Controls: ? Preserved ? no security-relevant code, configuration or dependency scope was changed
  - **Verification**:
    - Command: `mvn clean test-compile` then `mvn clean test`
    - JDK: C:\dev\jdk-21 (21.0.2)
    - Build tool: C:\dev\apache-maven\bin\mvn (3.9.16)
    - Result: ? BUILD SUCCESS | Tests: 398/398 passed | bytecode class-file major version 65 (Java 21)
    - Notes: The inherited maven-antrun-plugin 1.8, maven-remote-resources-plugin 1.5, buildnumber-maven-plugin 1.3 and maven-enforcer-plugin 1.3.1 all executed without errors on JDK 21, so the contingency pins listed in the plan's Risks section were not needed.
  - **Deferred Work**: None
  - **Commit**: 48b8847 - Step 4: Switch the build to Java 21 - Compile: SUCCESS, Tests: 398/398 passed

- **Step 5: CVE Validation & Fix**
  - **Status**: ? Completed
  - **Changes Made**:
    - pom.xml: commons-beanutils 1.8.2 ? 1.11.0 (3 HIGH CVEs)
    - Added a comment marking the version as a CVE-driven pin
  - **Review Code Changes**:
    - Sufficiency: ? All required changes present ? re-scan reports no remaining CVEs
    - Necessity: ? All changes necessary
      - Functional Behavior: ? Preserved ? 398/398 tests pass, including all DynaBean/LazyDynaBean tests
      - Security Controls: ? Strengthened ? commons-beanutils 1.11.0 enables the suppressing `BeanIntrospector` by default, blocking `class`/`declaredClass` classloader access through `PropertyUtilsBean`
  - **Verification**:
    - Command: `mvn dependency:list -DexcludeTransitive=true`, `#appmod-validate-cves-for-java`, `mvn clean test`
    - JDK: C:\dev\jdk-21 (21.0.2)
    - Build tool: C:\dev\apache-maven\bin\mvn (3.9.16)
    - Result: ? BUILD SUCCESS | Tests: 398/398 passed | CVE re-scan: 0 remaining
    - Notes: Scanned direct dependencies plus the managed `commons-logging:1.1.1`. Fixed: CVE-2014-0114, CVE-2019-10086, CVE-2025-48734. No CVEs reported for jdom 1.0, junit 3.8.1, servlet-api 2.4, jsp-api 2.0, mockrunner 0.4 or commons-logging 1.1.1.
  - **Deferred Work**: None
  - **Commit**: 56a6e90 - Step 5: CVE Validation & Fix - Compile: SUCCESS, Tests: 398/398 passed

- **Step 6: Final Validation**
  - **Status**: ? Completed
  - **Changes Made**:
    - No further code changes needed; all goals already met
    - Verified packaged jar manifest and OSGi metadata
    - Collected JaCoCo coverage metrics
  - **Review Code Changes**:
    - Sufficiency: ? All required changes present ? Java 21 goal met, no TODOs or deferred work left
    - Necessity: ? All changes necessary ? no `--add-opens`/`--add-exports` workarounds were introduced
      - Functional Behavior: ? Preserved ? 398/398 tests pass, identical to the JDK 11 baseline
      - Security Controls: ? Preserved and strengthened by the commons-beanutils upgrade
  - **Verification**:
    - Command: `mvn clean package` plus `mvn org.jacoco:jacoco-maven-plugin:0.8.13:prepare-agent test ...:report`
    - JDK: C:\dev\jdk-21 (21.0.2)
    - Build tool: C:\dev\apache-maven\bin\mvn (3.9.16)
    - Result: ? BUILD SUCCESS | Tests: 398/398 passed (100%) | jar manifest `X-Compile-Source-JDK: 21`, `X-Compile-Target-JDK: 21`, `Require-Capability: osgi.ee;filter:="(&(osgi.ee=JavaSE)(version=21))"`
    - Notes: Coverage ? line 76.7% (7363/9596), instruction 75.3%, branch 67.5%. JaCoCo was invoked from the command line only; the POM was deliberately not modified.
  - **Deferred Work**: None
  - **Commit**: (recorded below - final progress commit)

---

## Notes

- Working branch `appmod/java-upgrade-20260924080250` created from `1.4-seeburger` @ `b0cc4483ee19d265eb644d0a8577f16a3571f3b0`.
- Pre-existing uncommitted working-tree changes (`.classpath`, `.project`, `dump2.txt`) were carried over onto the working branch.







