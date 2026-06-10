package com.turbovec.android.sample;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.turbovec.android.TurboVecIndex;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends AppCompatActivity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private TurboVecIndex index;
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

        btnInsert.setOnClickListener(v -> handleInsert());
        btnSearch.setOnClickListener(v -> handleSearch());

        log("System: Application started. SDK libraries loaded successfully.");
    }

    private void handleInsert() {
        btnInsert.setEnabled(false);
        log("\n[Insert]: Starting vector insertion...");

        executor.execute(() -> {
            try {
                if (index == null) {
                    logThreadSafe("Creating index with dimension = 16, quantization = 4-bit...");
                    index = new TurboVecIndex(16, 4);
                }

                int numVectors = 5;
                int dim = 16;
                float[] vectors = new float[numVectors * dim];

                // Generate 5 distinct vectors:
                // Vector 0: all values = 0.1
                // Vector 1: all values = 0.2
                // Vector 2: all values = 0.3
                // Vector 3: all values = 0.4
                // Vector 4: all values = 0.5
                for (int i = 0; i < numVectors; i++) {
                    float val = (i + 1) * 0.1f;
                    for (int d = 0; d < dim; d++) {
                        vectors[i * dim + d] = val;
                    }
                }

                logThreadSafe(String.format(Locale.US, "Adding %d vectors flat row-major...", numVectors));
                index.add(vectors, dim);

                logThreadSafe("Calling index.prepare() to build native index structures...");
                index.prepare();

                int currentSize = index.size();
                logThreadSafe(String.format(Locale.US, "SUCCESS: Inserted %d vectors. Current Index Size: %d", numVectors, currentSize));

            } catch (Throwable t) {
                logThreadSafe("ERROR: " + t.getClass().getSimpleName() + " - " + t.getMessage());
            } finally {
                mainHandler.post(() -> btnInsert.setEnabled(true));
            }
        });
    }

    private void handleSearch() {
        btnSearch.setEnabled(false);
        log("\n[Search]: Starting similarity search...");

        executor.execute(() -> {
            try {
                if (index == null || index.size() == 0) {
                    logThreadSafe("ERROR: Index is empty! Please click 'Insert Vectors' first.");
                    return;
                }

                int dim = 16;
                // Query vector: halfway between vector 1 (0.2) and vector 2 (0.3) -> all values = 0.25f
                float[] query = new float[dim];
                for (int d = 0; d < dim; d++) {
                    query[d] = 0.25f;
                }

                int k = 3;
                logThreadSafe(String.format(Locale.US, "Query vector: [0.25, 0.25, ..., 0.25] (dim = %d)", dim));
                logThreadSafe(String.format(Locale.US, "Running search with k = %d...", k));

                TurboVecIndex.SearchResult result = index.search(query, k);

                int queryCount = result.queryCount();
                int effectiveK = result.k();
                float[] scores = result.scores();
                long[] indices = result.indices();

                logThreadSafe(String.format(Locale.US, "Search completed. queryCount: %d, effective k: %d", queryCount, effectiveK));
                for (int i = 0; i < effectiveK; i++) {
                    logThreadSafe(String.format(Locale.US, "  #%d -> Slot Index: %d, Score: %.6f", i + 1, indices[i], scores[i]));
                }

            } catch (Throwable t) {
                logThreadSafe("ERROR: " + t.getClass().getSimpleName() + " - " + t.getMessage());
            } finally {
                mainHandler.post(() -> btnSearch.setEnabled(true));
            }
        });
    }

    private void log(String message) {
        consoleText.append(message + "\n");
        // Auto scroll to bottom
        consoleScroll.post(() -> consoleScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void logThreadSafe(final String message) {
        mainHandler.post(() -> log(message));
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        if (index != null && !index.isClosed()) {
            index.close();
        }
        super.onDestroy();
    }
}
