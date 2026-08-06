# Build troubleshooting notes

## "unexpected jvm signature V" during :app:kspDebugKotlin

**Cause:** a known KSP2 bug (Kotlin 2.0+) processing Room DAO `suspend` functions
that return `Unit`. Fixed in Room 2.7.0+.

**Fix applied:** Room bumped 2.6.1 → 2.8.4 in `gradle/libs.versions.toml`, and
`fallbackToDestructiveMigration()` updated to `fallbackToDestructiveMigration(dropAllTables = true)`
(the old signature is deprecated in Room 2.7+).

**If the error persists after this change**, confirm the build actually picked
up Room 2.8.4:

```sh
./gradlew :app:dependencies --configuration debugRuntimeClasspath | grep androidx.room
```

Every `androidx.room:*` line should show `2.8.4`. If it still shows `2.6.1`,
the change wasn't committed/pulled, or a Gradle cache is stale — clear it:

```sh
./gradlew --stop
rm -rf ~/.gradle/caches/build-cache-1
./gradlew clean :app:assembleDebug
```

## "Deprecated Gradle features ... incompatible with Gradle 9.0"

Historical note: on the old Gradle 8.13 / AGP 8.13 toolchain this appeared as a
warning (not an error) originating from AGP / KSP, not from this project's
scripts. The project has since migrated to **Gradle 9.6.1 and AGP 9.3.1**, so
this warning no longer applies. To inspect any remaining deprecation warnings on
the current toolchain:

```sh
./gradlew :app:assembleDebug --warning-mode all
```

## jvmTarget deprecation

Resolved: `kotlinOptions { jvmTarget = "17" }` replaced with the top-level
`kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }` DSL block.
