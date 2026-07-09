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
  private static final int DIM = 384;
  private static final int NUM_VECTORS = 50000;

  private TurboVecIndex index;
  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final Handler mainHandler = new Handler(Looper.getMainLooper());

  private TextView consoleText;
  private ScrollView consoleScroll;
  private Button btnInsert;
  private Button btnSearch;

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    consoleText = findViewById(R.id.console_text);
    consoleScroll = findViewById(R.id.console_scroll);
    btnInsert = findViewById(R.id.btn_insert);
    btnSearch = findViewById(R.id.btn_search);

    // Hide selection controls since we are running a specific 8-bit benchmark
    RadioGroup quantizationGroup = findViewById(R.id.quantization_group);
    RadioGroup inputTypeGroup = findViewById(R.id.input_type_group);
    if (quantizationGroup != null) quantizationGroup.setVisibility(View.GONE);
    if (inputTypeGroup != null) inputTypeGroup.setVisibility(View.GONE);

    btnInsert.setText("Run 50k Insert Benchmark");
    btnSearch.setText("Run 10x Search Benchmark");

    btnInsert.setOnClickListener(v -> handleInsert());
    btnSearch.setOnClickListener(v -> handleSearch());

    log("System: Application started. TurboVec JNI library loaded.");
    log("Tap 'Run 50k Insert Benchmark' to generate and index 50,000 vectors of 384 dim.");
  }

  private void resetIndex() {
    if (index != null && !index.isClosed()) {
      index.close();
    }
    index = null;
  }

  private void handleInsert() {
    btnInsert.setEnabled(false);
    btnSearch.setEnabled(false);
    log("\n[Benchmark]: Starting 50,000 vector insertion (384 dim, 8-bit quantization)...");

    executor.execute(
        () -> {
          try {
            resetIndex();
            logThreadSafe("Creating TurboQuant index (dim=384, bitWidth=8)...");
            index = new TurboVecIndex(DIM, 8);

            long startTime = System.currentTimeMillis();
            java.util.Random rand = new java.util.Random(42);
            int batchSize = 10000;
            int numBatches = NUM_VECTORS / batchSize;

            for (int b = 0; b < numBatches; b++) {
              logThreadSafe(String.format(Locale.US, "Generating & inserting batch %d/%d (%d vectors)...", b + 1, numBatches, batchSize));
              float[] batchVectors = new float[batchSize * DIM];
              for (int i = 0; i < batchVectors.length; i++) {
                batchVectors[i] = rand.nextFloat() * 2.0f - 1.0f;
              }
              index.add(batchVectors, DIM);
            }

            long endTime = System.currentTimeMillis();
            logThreadSafe(String.format(Locale.US, "Insertion time: %d ms", (endTime - startTime)));

            logThreadSafe("Calling index.prepare() to warm native search caches...");
            long prepStart = System.currentTimeMillis();
            index.prepare();
            long prepEnd = System.currentTimeMillis();
            logThreadSafe(String.format(Locale.US, "Prepare time: %d ms", (prepEnd - prepStart)));

            logThreadSafe(
                String.format(
                    Locale.US,
                    "SUCCESS: Inserted %d vectors at 8-bit. Index size: %d",
                    NUM_VECTORS,
                    index.size()));
          } catch (Throwable t) {
            logThreadSafe("ERROR: " + t.getClass().getSimpleName() + " - " + t.getMessage());
          } finally {
            mainHandler.post(() -> {
              btnInsert.setEnabled(true);
              btnSearch.setEnabled(true);
            });
          }
        });
  }

  private void handleSearch() {
    btnSearch.setEnabled(false);
    btnInsert.setEnabled(false);
    log("\n[Benchmark]: Running 10 similarity search operations (k=10)...");

    executor.execute(
        () -> {
          try {
            if (index == null || index.size() == 0) {
              logThreadSafe("ERROR: Index is empty! Run 'Insert' benchmark first.");
              return;
            }

            int k = 10;
            int numQueries = 10;
            double[] latenciesMs = new double[numQueries];
            double totalLatencyMs = 0.0;

            java.util.Random rand = new java.util.Random(1337);

            for (int q = 0; q < numQueries; q++) {
              float[] query = new float[DIM];
              for (int i = 0; i < DIM; i++) {
                query[i] = rand.nextFloat() * 2.0f - 1.0f;
              }

              long startNano = System.nanoTime();
              TurboVecIndex.SearchResult result = index.search(query, k);
              long endNano = System.nanoTime();

              double latencyMs = (double) (endNano - startNano) / 1_000_000.0;
              latenciesMs[q] = latencyMs;
              totalLatencyMs += latencyMs;

              logThreadSafe(String.format(Locale.US, "Query %d latency: %.3f ms (found top result slot=%d, score=%.4f)", q + 1, latencyMs, result.indices()[0], result.scores()[0]));
            }

            double avgLatencyMs = totalLatencyMs / numQueries;
            logThreadSafe(String.format(Locale.US, "\n====== Latency Summary ======"));
            logThreadSafe(String.format(Locale.US, "Average Search Latency: %.3f ms", avgLatencyMs));
            logThreadSafe(String.format(Locale.US, "============================="));

            // Log to logcat for verification
            android.util.Log.i("TurboVecBenchmark", String.format(Locale.US, "Average search latency for 50k vectors, 384 dim, 8-bit SIMD index: %.3f ms", avgLatencyMs));
          } catch (Throwable t) {
            logThreadSafe("ERROR: " + t.getClass().getSimpleName() + " - " + t.getMessage());
          } finally {
            mainHandler.post(() -> {
              btnSearch.setEnabled(true);
              btnInsert.setEnabled(true);
            });
          }
        });
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
