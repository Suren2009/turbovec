# turbovec Android AAR

This directory contains an Android library module that packages turbovec as an
`arm64-v8a` AAR. The public Java API loads a Rust JNI shared library and exposes
the core positional `TurboQuantIndex` operations for Android apps.

## What is packaged

- Java API: `com.turbovec.android.TurboVecIndex`
- Native library: `libturbovec_jni.so`
- ABI: `arm64-v8a` only
- Minimum Android API: 23
- **Storage quantization:** TurboQuant 2-, 3-, 4-, or **8-bit** per coordinate

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
./gradlew :library:assembleRelease
```

On Windows:

```bat
gradlew.bat :library:assembleRelease
```

The release AAR is written to:

```text
turbovec-android/library/build/outputs/aar/library-release.aar
```

Copy the freshly built AAR into the checked-in release bundle used by the sample
app:

```bash
cp library/build/outputs/aar/library-release.aar release/library-release.aar
```

During the build, Gradle runs:

```bash
cargo build --release --target aarch64-linux-android
```

with `CARGO_TARGET_DIR` set to `native/target/` so the JNI library lands where
the AAR packaging step expects it. If you previously saw
`IllegalArgumentException: bit_width must be 2, 3, or 4, got 8` when selecting
8-bit storage, rebuild with a clean native target:

```bash
rm -rf native/target
./gradlew clean :library:assembleRelease :app:installDebug
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

## Java example (8-bit storage)

**Storage quantization** (TurboQuant bit width) is set at construction. **8-bit**
stores one byte per coordinate — higher recall than 2/4-bit at the cost of more
memory (e.g. 1536 bytes per 1536-dim vector vs 384 bytes at 2-bit).

```java
import com.turbovec.android.TurboVecIndex;

float[] vectors = new float[1536 * 1000];
float[] queries = new float[1536 * 2];

try (TurboVecIndex index = new TurboVecIndex(1536, 8)) {
    index.add(vectors, 1536);
    index.prepare();

    TurboVecIndex.SearchResult result = index.search(queries, 10);
    float[] firstQueryScores = result.scoresForQuery(0);
    long[] firstQuerySlots = result.indicesForQuery(0);

    index.write(context.getFilesDir() + "/index.tv");
}
```

Other supported storage widths: `new TurboVecIndex(dim, 2)`, `3`, or `4`.

The Java API also accepts signed 8-bit integer coordinates (`byte[]`) and
IEEE-754 binary16 vectors encoded as raw half-float bits (`short[]`). These
describe **input buffer layout**, not storage bit width:

```java
byte[] int8Vectors = new byte[1536 * 1000];
byte[] int8Queries = new byte[1536 * 2];

short[] fp16Vectors = new short[1536 * 1000];
short[] fp16Queries = new short[1536 * 2];

try (TurboVecIndex index = new TurboVecIndex(1536, 8)) {
    index.addInt8(int8Vectors, 1536);
    TurboVecIndex.SearchResult int8Result = index.searchInt8(int8Queries, 10);

    index.addFp16(fp16Vectors, 1536);
    TurboVecIndex.SearchResult fp16Result = index.searchFp16(fp16Queries, 10);
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

## Supported coordinate value types

Two concepts apply when using the Android API:

1. **Storage quantization** — TurboQuant bit width (2, 3, 4, or **8**) set via
   `new TurboVecIndex(dim, bitWidth)`. This controls how vectors are compressed
   inside the index.
2. **Coordinate value type** — buffer layout at add/search time. Use
   `TurboVecIndex.ValueType` (`FP32`, `INT8`, `FP16`) to describe which format
   you are passing.

| Value type | Java type | Add API | Search API |
|------------|-----------|---------|------------|
| FP32 | `float[]` | `add(float[], dim)` | `search(float[], k)` |
| INT8 (8-bit signed) | `byte[]` | `addInt8(byte[], dim)` | `searchInt8(byte[], k)` |
| FP16 (16-bit half) | `short[]` raw half bits | `addFp16(short[], dim)` | `searchFp16(short[], k)` |

`add(byte[], dim)` / `search(byte[], k)` and `addFloat16` / `searchFloat16`
remain as aliases. INT8 and FP16 coordinates are converted to `float32` in
native code before TurboQuant compression, so search results match an equivalent
FP32 index for the same coordinate values.

### INT8 coordinates + 8-bit storage

```java
byte[] int8Vectors = new byte[1536 * 1000];
byte[] int8Queries = new byte[1536 * 2];

try (TurboVecIndex index = new TurboVecIndex(1536, 8)) {
    index.addInt8(int8Vectors, 1536);
    index.prepare();
    TurboVecIndex.SearchResult result = index.searchInt8(int8Queries, 10);
}
```

### FP16 coordinates + 8-bit storage

```java
short[] fp16Vectors = new short[1536 * 1000];
short[] fp16Queries = new short[1536 * 2];

try (TurboVecIndex index = new TurboVecIndex(1536, 8)) {
    index.addFp16(fp16Vectors, 1536);
    index.prepare();
    TurboVecIndex.SearchResult result = index.searchFp16(fp16Queries, 10);
}
```

## Sample application

The `app/` module demonstrates TurboQuant storage at **2-, 3-, 4-, and 8-bit**
(default **8-bit**) plus **FP32, INT8, and FP16** coordinate value types.

The `app/` module depends on the `:library` project directly (not the prebuilt
`release/library-release.aar`) so Java and native code always stay in sync during
development. After `assembleRelease`, copy the AAR into `release/` for distribution:

```bash
./gradlew :library:assembleRelease
cp library/build/outputs/aar/library-release.aar release/library-release.aar
./gradlew :app:assembleDebug
```

Install on a connected device:

```bash
./gradlew :app:installDebug
adb shell am start -n com.turbovec.android.sample/.MainActivity
```

On Windows, use `gradlew.bat` instead of `./gradlew`.

The sample UI defaults to **8-bit** storage. You can switch to 2/3/4-bit, pick a
**coordinate value type** (FP32/INT8/FP16), insert five test vectors (`10, 20,
30, 40, 50` per dimension), then search with query value `15` (nearest to the
`10` and `20` vectors).

## Notes

- The AAR exposes positional slot ids. If you need stable external ids, maintain
  an app-side id table or extend the JNI layer to wrap turbovec's `IdMapIndex`.
- `TurboVecIndex` owns native memory. Always call `close()` or use
  try-with-resources.
- Input arrays are flat row-major `float[]` (FP32), signed `byte[]` (INT8), or
  raw FP16 bit-pattern `short[]` buffers. Vector dimensions must be a positive
  multiple of 8, matching the Rust core requirements.
- `bitWidth` must be one of **2, 3, 4, or 8**.
