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

This is a **warning, not an error** — it does not fail the build on Gradle 8.13.
It originates from the Android Gradle Plugin / KSP, not from this project's
scripts. To see the source:

```sh
./gradlew :app:assembleDebug --warning-mode all
```

Safe to ignore until a future AGP upgrade. Do not switch to Gradle 9.0 yet:
AGP 8.13 targets Gradle 8.x.

## jvmTarget deprecation

Resolved: `kotlinOptions { jvmTarget = "17" }` replaced with the top-level
`kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }` DSL block.
