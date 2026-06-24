# turbovec Android AAR

This directory contains an Android library module that packages turbovec as an
`arm64-v8a` AAR. The public Java API loads a Rust JNI shared library and exposes
the core positional `TurboQuantIndex` operations for Android apps.

## What is packaged

- Java API: `com.turbovec.android.TurboVecIndex`
- Native library: `libturbovec_jni.so`
- ABI: `arm64-v8a` only
- Minimum Android API: 23

## Prerequisites

Install these on the build machine:

1. JDK 17 or newer.
2. Android SDK plus Android NDK.
3. Gradle compatible with Android Gradle Plugin 9.2.1.
4. Rust toolchain with the Android arm64 target:

```bash
rustup target add aarch64-linux-android
```

If Gradle cannot locate the NDK automatically, set one of:

```bash
export ANDROID_NDK_HOME=/path/to/android-ndk
export ANDROID_NDK_ROOT=/path/to/android-ndk
```

## Build the AAR

From this directory:

```bash
gradle :library:assembleRelease
```

The release AAR is written to:

```text
turbovec-android/library/build/outputs/aar/library-release.aar
```

During the build, Gradle runs:

```bash
cargo build --release --target aarch64-linux-android
```

and copies the generated `libturbovec_jni.so` into the AAR under
`jni/arm64-v8a/`.

## Use from an Android app

Copy the AAR into your app project, for example:

```text
app/libs/library-release.aar
```

Then add it to the app module:

```kotlin
dependencies {
    implementation(files("libs/library-release.aar"))
}
```

The consuming app must support `arm64-v8a`:

```kotlin
android {
    defaultConfig {
        ndk {
            abiFilters += "arm64-v8a"
        }
    }
}
```

## Java example

```java
import com.turbovec.android.TurboVecIndex;

float[] vectors = new float[1536 * 1000];
float[] queries = new float[1536 * 2];

try (TurboVecIndex index = new TurboVecIndex(1536, 4)) {
    index.add(vectors, 1536);
    index.prepare();

    TurboVecIndex.SearchResult result = index.search(queries, 10);
    float[] firstQueryScores = result.scoresForQuery(0);
    long[] firstQuerySlots = result.indicesForQuery(0);

    index.write(context.getFilesDir() + "/index.tv");
}
```

The Java API also accepts signed int8 vectors (`byte[]`) and IEEE-754 binary16
vectors encoded as raw half-float bits (`short[]`):

```java
byte[] int8Vectors = new byte[1536 * 1000];
byte[] int8Queries = new byte[1536 * 2];

short[] fp16Vectors = new short[1536 * 1000]; // raw half-float bits
short[] fp16Queries = new short[1536 * 2];

try (TurboVecIndex index = new TurboVecIndex(1536, 4)) {
    index.add(int8Vectors, 1536);
    TurboVecIndex.SearchResult int8Result = index.search(int8Queries, 10);

    index.addFloat16(fp16Vectors, 1536);
    TurboVecIndex.SearchResult fp16Result = index.searchFloat16(fp16Queries, 10);
}
```

Load a persisted index:

```java
try (TurboVecIndex index = TurboVecIndex.load(context.getFilesDir() + "/index.tv")) {
    TurboVecIndex.SearchResult result = index.search(queries, 10);
}
```

Search with a slot mask:

```java
boolean[] mask = new boolean[index.size()];
mask[42] = true;
TurboVecIndex.SearchResult filtered = index.search(queries, 10, mask);
```

`SearchResult.k()` is the effective per-query result count. It can be smaller
than the requested `k` when a mask restricts the eligible slot count.

## Notes

- The AAR exposes positional slot ids. If you need stable external ids, maintain
  an app-side id table or extend the JNI layer to wrap turbovec's `IdMapIndex`.
- `TurboVecIndex` owns native memory. Always call `close()` or use
  try-with-resources.
- Input arrays are flat row-major `float[]`, signed int8 `byte[]`, or raw FP16
  bit-pattern `short[]` buffers. Vector dimensions must be a positive multiple
  of 8, matching the Rust core requirements.
