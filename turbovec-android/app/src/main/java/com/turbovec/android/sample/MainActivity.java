package com.turbovec.android.sample;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.turbovec.android.TurboVecIndex;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends AppCompatActivity {
  private static final int DIM = 16;
  private static final int NUM_VECTORS = 5;
  private static final float[] VECTOR_VALUES = {10.0f, 20.0f, 30.0f, 40.0f, 50.0f};
  private static final float QUERY_VALUE = 15.0f;

  private TurboVecIndex index;
  private TurboVecIndex.ValueType activeValueType = TurboVecIndex.ValueType.FP32;
  private int activeBitWidth = 8;

  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final Handler mainHandler = new Handler(Looper.getMainLooper());

  private TextView consoleText;
  private ScrollView consoleScroll;
  private Button btnInsert;
  private Button btnSearch;
  private RadioGroup quantizationGroup;
  private RadioGroup inputTypeGroup;

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    consoleText = findViewById(R.id.console_text);
    consoleScroll = findViewById(R.id.console_scroll);
    btnInsert = findViewById(R.id.btn_insert);
    btnSearch = findViewById(R.id.btn_search);
    quantizationGroup = findViewById(R.id.quantization_group);
    inputTypeGroup = findViewById(R.id.input_type_group);

    btnInsert.setOnClickListener(v -> handleInsert());
    btnSearch.setOnClickListener(v -> handleSearch());
    quantizationGroup.setOnCheckedChangeListener(
        (group, checkedId) -> handleQuantizationChanged(checkedId));
    inputTypeGroup.setOnCheckedChangeListener((group, checkedId) -> handleValueTypeChanged(checkedId));

    log("System: Application started. Native library loaded.");
    log("Pick storage bit width (2/3/4/8) and coordinate value type (FP32/INT8/FP16).");
    activeBitWidth = resolveActiveBitWidth();
  }

  private void handleQuantizationChanged(int checkedId) {
    int selected = resolveBitWidth(checkedId);
    if (selected == activeBitWidth) {
      return;
    }
    activeBitWidth = selected;
    resetIndex();
    log(
        "\n[Quantization]: Switched to "
            + selected
            + "-bit storage. Index cleared — insert vectors again.");
  }

  private void handleValueTypeChanged(int checkedId) {
    TurboVecIndex.ValueType selected = resolveValueType(checkedId);
    if (selected == activeValueType) {
      return;
    }
    activeValueType = selected;
    resetIndex();
    log("\n[ValueType]: Switched to " + valueTypeLabel(selected) + ". Index cleared — insert again.");
  }

  private int resolveActiveBitWidth() {
    int checkedId = quantizationGroup.getCheckedRadioButtonId();
    return resolveBitWidth(checkedId);
  }

  private int resolveBitWidth(int checkedId) {
    if (checkedId == R.id.radio_2bit) {
      return 2;
    }
    if (checkedId == R.id.radio_3bit) {
      return 3;
    }
    if (checkedId == R.id.radio_8bit) {
      return 8;
    }
    if (checkedId == R.id.radio_4bit) {
      return 4;
    }
    if (checkedId == View.NO_ID) {
      return activeBitWidth;
    }
    return 4;
  }

  private TurboVecIndex.ValueType resolveValueType(int checkedId) {
    if (checkedId == R.id.radio_int8) {
      return TurboVecIndex.ValueType.INT8;
    }
    if (checkedId == R.id.radio_fp16) {
      return TurboVecIndex.ValueType.FP16;
    }
    return TurboVecIndex.ValueType.FP32;
  }

  private static String valueTypeLabel(TurboVecIndex.ValueType type) {
    switch (type) {
      case INT8:
        return "INT8 (8-bit signed byte[])";
      case FP16:
        return "FP16 (16-bit half short[])";
      case FP32:
      default:
        return "FP32 (32-bit float[])";
    }
  }

  private void resetIndex() {
    if (index != null && !index.isClosed()) {
      index.close();
    }
    index = null;
  }

  private void handleInsert() {
    btnInsert.setEnabled(false);
    log(
        "\n[Insert]: Starting vector insertion ("
            + activeBitWidth
            + "-bit, "
            + valueTypeLabel(activeValueType)
            + ")...");

    executor.execute(
        () -> {
          try {
            resetIndex();
            activeBitWidth = resolveActiveBitWidth();
            logThreadSafe(
                String.format(
                    Locale.US,
                    "Creating TurboQuant index (dim=%d, bitWidth=%d)...",
                    DIM,
                    activeBitWidth));
            index = new TurboVecIndex(DIM, activeBitWidth);

            switch (activeValueType) {
              case FP32:
                insertFp32();
                break;
              case INT8:
                insertInt8();
                break;
              case FP16:
                insertFp16();
                break;
              default:
                throw new IllegalStateException("Unsupported value type: " + activeValueType);
            }

            logThreadSafe("Calling index.prepare() to warm native search caches...");
            index.prepare();

            logThreadSafe(
                String.format(
                    Locale.US,
                    "SUCCESS: Inserted %d vectors at %d-bit. Index size: %d",
                    NUM_VECTORS,
                    activeBitWidth,
                    index.size()));
          } catch (Throwable t) {
            logThreadSafe("ERROR: " + t.getClass().getSimpleName() + " - " + t.getMessage());
          } finally {
            mainHandler.post(() -> btnInsert.setEnabled(true));
          }
        });
  }

  private void insertFp32() {
    float[] vectors = buildFloatVectors();
    logThreadSafe(String.format(Locale.US, "add(float[%d], dim=%d)", vectors.length, DIM));
    index.add(vectors, DIM);
  }

  private void insertInt8() {
    byte[] vectors = buildInt8Vectors();
    logThreadSafe(String.format(Locale.US, "addInt8(byte[%d], dim=%d)", vectors.length, DIM));
    index.addInt8(vectors, DIM);
  }

  private void insertFp16() {
    short[] vectors = buildFp16Vectors();
    logThreadSafe(String.format(Locale.US, "addFp16(short[%d], dim=%d)", vectors.length, DIM));
    index.addFp16(vectors, DIM);
  }

  private void handleSearch() {
    btnSearch.setEnabled(false);
    log(
        "\n[Search]: Starting similarity search ("
            + activeBitWidth
            + "-bit, "
            + valueTypeLabel(activeValueType)
            + ")...");

    executor.execute(
        () -> {
          try {
            if (index == null || index.size() == 0) {
              logThreadSafe("ERROR: Index is empty! Insert vectors first.");
              return;
            }

            int k = 3;
            TurboVecIndex.SearchResult result;

            switch (activeValueType) {
              case FP32:
                result = searchFp32(k);
                break;
              case INT8:
                result = searchInt8(k);
                break;
              case FP16:
                result = searchFp16(k);
                break;
              default:
                throw new IllegalStateException("Unsupported value type: " + activeValueType);
            }

            logSearchResults(result, k);
          } catch (Throwable t) {
            logThreadSafe("ERROR: " + t.getClass().getSimpleName() + " - " + t.getMessage());
          } finally {
            mainHandler.post(() -> btnSearch.setEnabled(true));
          }
        });
  }

  private TurboVecIndex.SearchResult searchFp32(int k) {
    float[] query = buildFloatQuery();
    logThreadSafe(
        String.format(Locale.US, "Query FP32 value=%.1f across dim=%d, k=%d", QUERY_VALUE, DIM, k));
    return index.search(query, k);
  }

  private TurboVecIndex.SearchResult searchInt8(int k) {
    byte[] query = buildInt8Query();
    logThreadSafe(
        String.format(Locale.US, "Query INT8 value=%d across dim=%d, k=%d", (int) QUERY_VALUE, DIM, k));
    return index.searchInt8(query, k);
  }

  private TurboVecIndex.SearchResult searchFp16(int k) {
    short[] query = buildFp16Query();
    logThreadSafe(
        String.format(
            Locale.US,
            "Query FP16 bits=0x%04X (%.1f) across dim=%d, k=%d",
            query[0] & 0xFFFF,
            halfBitsToFloat(query[0]),
            DIM,
            k));
    return index.searchFp16(query, k);
  }

  private void logSearchResults(TurboVecIndex.SearchResult result, int requestedK) {
    int effectiveK = result.k();
    long[] indices = result.indices();
    float[] scores = result.scores();

    logThreadSafe(
        String.format(
            Locale.US,
            "Search completed (%d-bit). queryCount=%d, effective k=%d (requested %d)",
            activeBitWidth,
            result.queryCount(),
            effectiveK,
            requestedK));

    for (int i = 0; i < effectiveK; i++) {
      logThreadSafe(
          String.format(
              Locale.US,
              "  #%d -> slot=%d, score=%.6f (vector value %.1f)",
              i + 1,
              indices[i],
              scores[i],
              VECTOR_VALUES[(int) indices[i]]));
    }
  }

  private static float[] buildFloatVectors() {
    float[] vectors = new float[NUM_VECTORS * DIM];
    for (int i = 0; i < NUM_VECTORS; i++) {
      float value = VECTOR_VALUES[i];
      for (int d = 0; d < DIM; d++) {
        vectors[i * DIM + d] = value;
      }
    }
    return vectors;
  }

  private static byte[] buildInt8Vectors() {
    byte[] vectors = new byte[NUM_VECTORS * DIM];
    for (int i = 0; i < NUM_VECTORS; i++) {
      byte value = (byte) VECTOR_VALUES[i];
      for (int d = 0; d < DIM; d++) {
        vectors[i * DIM + d] = value;
      }
    }
    return vectors;
  }

  private static short[] buildFp16Vectors() {
    short[] vectors = new short[NUM_VECTORS * DIM];
    for (int i = 0; i < NUM_VECTORS; i++) {
      short bits = floatToHalfBits(VECTOR_VALUES[i]);
      for (int d = 0; d < DIM; d++) {
        vectors[i * DIM + d] = bits;
      }
    }
    return vectors;
  }

  private static float[] buildFloatQuery() {
    float[] query = new float[DIM];
    for (int d = 0; d < DIM; d++) {
      query[d] = QUERY_VALUE;
    }
    return query;
  }

  private static byte[] buildInt8Query() {
    byte[] query = new byte[DIM];
    byte value = (byte) QUERY_VALUE;
    for (int d = 0; d < DIM; d++) {
      query[d] = value;
    }
    return query;
  }

  private static short[] buildFp16Query() {
    short[] query = new short[DIM];
    short bits = floatToHalfBits(QUERY_VALUE);
    for (int d = 0; d < DIM; d++) {
      query[d] = bits;
    }
    return query;
  }

  private static short floatToHalfBits(float value) {
    int bits = Float.floatToRawIntBits(value);
    int sign = (bits >>> 16) & 0x8000;
    int exp = ((bits >>> 23) & 0xff) - 127 + 15;
    int mantissa = bits & 0x7fffff;

    if (exp <= 0) {
      if (exp < -10) {
        return (short) sign;
      }
      mantissa |= 0x800000;
      int shift = 14 - exp;
      mantissa >>>= shift;
      return (short) (sign | mantissa);
    }

    if (exp >= 31) {
      return (short) (sign | 0x7c00);
    }

    return (short) (sign | (exp << 10) | (mantissa >>> 13));
  }

  private static float halfBitsToFloat(short halfBits) {
    int bits = halfBits & 0xffff;
    int sign = (bits & 0x8000) << 16;
    int exp = (bits >>> 10) & 0x1f;
    int mantissa = bits & 0x3ff;

    if (exp == 0) {
      if (mantissa == 0) {
        return Float.intBitsToFloat(sign);
      }
      while ((mantissa & 0x400) == 0) {
        mantissa <<= 1;
        exp--;
      }
      exp++;
      mantissa &= 0x3ff;
    } else if (exp == 31) {
      return Float.intBitsToFloat(sign | 0x7f800000 | (mantissa << 13));
    }

    int f32Exp = (exp - 15 + 127) << 23;
    return Float.intBitsToFloat(sign | f32Exp | (mantissa << 13));
  }

  private void log(String message) {
    consoleText.append(message + "\n");
    consoleScroll.post(() -> consoleScroll.fullScroll(View.FOCUS_DOWN));
  }

  private void logThreadSafe(final String message) {
    mainHandler.post(() -> log(message));
  }

  @Override
  protected void onDestroy() {
    executor.shutdownNow();
    resetIndex();
    super.onDestroy();
  }
}
