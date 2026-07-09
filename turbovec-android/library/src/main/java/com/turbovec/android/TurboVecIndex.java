package com.turbovec.android;

import java.io.IOException;
import java.util.Arrays;

/**
 * Android wrapper for turbovec's native TurboQuant index.
 *
 * <p>The AAR packages {@code libturbovec_jni.so} for {@code arm64-v8a}. Instances own a native
 * index handle and must be closed when no longer needed.</p>
 *
 * <p><b>Storage quantization</b> is configured at construction time via {@code bitWidth} (2, 3, 4,
 * or 8). Vectors are compressed into turbovec's TurboQuant format regardless of how coordinates are
 * supplied.</p>
 *
 * <p><b>Coordinate value types</b> select the buffer layout at add/search time:</p>
 * <ul>
 *   <li>{@link ValueType#FP32} — {@code float[]} IEEE-754 binary32 coordinates</li>
 *   <li>{@link ValueType#INT8} — {@code byte[]} signed 8-bit integer coordinates (−128…127)</li>
 *   <li>{@link ValueType#FP16} — {@code short[]} raw IEEE-754 binary16 (half-float) bit patterns</li>
 * </ul>
 *
 * INT8 and FP16 buffers are converted to float32 in native code before quantization, so search
 * results match an equivalent FP32 index for the same coordinate values.
 */
public final class TurboVecIndex implements AutoCloseable {
    static {
        System.loadLibrary("turbovec_jni");
    }

    /**
     * Supported coordinate buffer layouts for add/search.
     *
     * <p>These describe input value precision, not the index's TurboQuant storage bit width (see
     * {@link #TurboVecIndex(int, int)} {@code bitWidth}).</p>
     */
    public enum ValueType {
        /** 32-bit float coordinates ({@code float[]}). */
        FP32,
        /** Signed 8-bit integer coordinates ({@code byte[]}). */
        INT8,
        /** 16-bit half-float coordinates as raw bit patterns ({@code short[]}). */
        FP16
    }

    private long handle;

    /**
     * Creates an index with a fixed dimensionality.
     *
     * @param dim vector dimensionality; must be positive and a multiple of 8
     * @param bitWidth quantization bit width, one of 2, 3, 4, or 8
     */
    public TurboVecIndex(int dim, int bitWidth) {
        if (dim <= 0 || dim % 8 != 0) {
            throw new IllegalArgumentException("dim must be positive and a multiple of 8");
        }
        validateBitWidth(bitWidth);
        this.handle = nativeNew(dim, bitWidth);
    }

    private TurboVecIndex(long handle) {
        if (handle == 0L) {
            throw new IllegalStateException("native turbovec handle is null");
        }
        this.handle = handle;
    }

    /**
     * Creates a lazy index whose dimensionality is committed by the first add call.
     */
    public static TurboVecIndex lazy(int bitWidth) {
        validateBitWidth(bitWidth);
        return new TurboVecIndex(nativeNewLazy(bitWidth));
    }

    /**
     * Loads an index previously written by {@link #write(String)}.
     */
    public static TurboVecIndex load(String path) throws IOException {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("path must not be null or empty");
        }
        return new TurboVecIndex(nativeLoad(path));
    }

    /**
     * Adds a flat row-major vector batch.
     *
     * @param vectors flat array of {@code n * dim} float32 values
     * @param dim dimensionality for this batch; commits lazy indexes on first add
     */
    public synchronized void add(float[] vectors, int dim) {
        ensureOpen();
        if (vectors == null) {
            throw new IllegalArgumentException("vectors must not be null");
        }
        validateVectorBatchLength("vectors", vectors.length, dim);
        nativeAdd(handle, vectors, dim);
    }

    /**
     * Adds a flat row-major signed 8-bit integer vector batch.
     *
     * <p>Values are interpreted as raw signed coordinates in {@code [-128, 127]} and converted to
     * float32 in native code before indexing. Equivalent to {@link #add(byte[], int)}.</p>
     *
     * @param vectors flat array of {@code n * dim} int8 values
     * @param dim dimensionality for this batch; commits lazy indexes on first add
     */
    public synchronized void addInt8(byte[] vectors, int dim) {
        add(vectors, dim);
    }

    /**
     * Adds a flat row-major signed 8-bit integer vector batch.
     *
     * <p>Values are interpreted as raw signed coordinates in {@code [-128, 127]} and converted to
     * float32 in native code before indexing.</p>
     *
     * @param vectors flat array of {@code n * dim} int8 values
     * @param dim dimensionality for this batch; commits lazy indexes on first add
     */
    public synchronized void add(byte[] vectors, int dim) {
        ensureOpen();
        if (vectors == null) {
            throw new IllegalArgumentException("vectors must not be null");
        }
        validateVectorBatchLength("vectors", vectors.length, dim);
        nativeAddInt8(handle, vectors, dim);
    }

    /**
     * Adds a flat row-major IEEE-754 binary16 vector batch.
     *
     * <p>Each {@code short} is interpreted as raw half-float bits, for example values produced by
     * {@code android.util.Half} helpers or model runtimes that expose FP16 buffers.</p>
     *
     * @param vectors flat array of {@code n * dim} float16 bit patterns
     * @param dim dimensionality for this batch; commits lazy indexes on first add
     */
    public synchronized void addFp16(short[] vectors, int dim) {
        addFloat16(vectors, dim);
    }

    /**
     * Adds a flat row-major IEEE-754 binary16 vector batch.
     *
     * <p>Each {@code short} is interpreted as raw half-float bits, for example values produced by
     * {@code android.util.Half} helpers or model runtimes that expose FP16 buffers.</p>
     *
     * @param vectors flat array of {@code n * dim} float16 bit patterns
     * @param dim dimensionality for this batch; commits lazy indexes on first add
     */
    public synchronized void addFloat16(short[] vectors, int dim) {
        ensureOpen();
        if (vectors == null) {
            throw new IllegalArgumentException("vectors must not be null");
        }
        validateVectorBatchLength("vectors", vectors.length, dim);
        nativeAddFloat16(handle, vectors, dim);
    }

    /**
     * Searches a flat row-major query batch and returns top-k slot indices.
     */
    public synchronized SearchResult search(float[] queries, int k) {
        return search(queries, k, null);
    }

    /**
     * Searches with an optional slot mask.
     *
     * @param queries flat array of {@code nq * dim} float32 query values
     * @param k requested top-k result count
     * @param mask optional slot mask, where {@code true} entries are eligible
     */
    public synchronized SearchResult search(float[] queries, int k, boolean[] mask) {
        ensureOpen();
        if (queries == null) {
            throw new IllegalArgumentException("queries must not be null");
        }
        if (k < 0) {
            throw new IllegalArgumentException("k must be non-negative");
        }
        validateQueryLength(queries.length);
        validateMask(mask);
        return nativeSearch(handle, queries, k, mask);
    }

    /**
     * Searches signed 8-bit integer query vectors. Equivalent to {@link #search(byte[], int)}.
     */
    public synchronized SearchResult searchInt8(byte[] queries, int k) {
        return searchInt8(queries, k, null);
    }

    /**
     * Searches signed 8-bit integer query vectors with an optional slot mask.
     */
    public synchronized SearchResult searchInt8(byte[] queries, int k, boolean[] mask) {
        return search(queries, k, mask);
    }

    /**
     * Searches signed 8-bit integer query vectors.
     */
    public synchronized SearchResult search(byte[] queries, int k) {
        return search(queries, k, null);
    }

    /**
     * Searches signed 8-bit integer query vectors with an optional slot mask.
     */
    public synchronized SearchResult search(byte[] queries, int k, boolean[] mask) {
        ensureOpen();
        if (queries == null) {
            throw new IllegalArgumentException("queries must not be null");
        }
        if (k < 0) {
            throw new IllegalArgumentException("k must be non-negative");
        }
        validateQueryLength(queries.length);
        validateMask(mask);
        return nativeSearchInt8(handle, queries, k, mask);
    }

    /**
     * Searches IEEE-754 binary16 query vectors. Equivalent to {@link #searchFloat16(short[], int)}.
     */
    public synchronized SearchResult searchFp16(short[] queries, int k) {
        return searchFp16(queries, k, null);
    }

    /**
     * Searches IEEE-754 binary16 query vectors with an optional slot mask. Equivalent to
     * {@link #searchFloat16(short[], int, boolean[])}.
     */
    public synchronized SearchResult searchFp16(short[] queries, int k, boolean[] mask) {
        return searchFloat16(queries, k, mask);
    }

    /**
     * Searches IEEE-754 binary16 query vectors.
     *
     * <p>Each {@code short} is interpreted as raw half-float bits.</p>
     */
    public synchronized SearchResult searchFloat16(short[] queries, int k) {
        return searchFloat16(queries, k, null);
    }

    /**
     * Searches IEEE-754 binary16 query vectors with an optional slot mask.
     */
    public synchronized SearchResult searchFloat16(short[] queries, int k, boolean[] mask) {
        ensureOpen();
        if (queries == null) {
            throw new IllegalArgumentException("queries must not be null");
        }
        if (k < 0) {
            throw new IllegalArgumentException("k must be non-negative");
        }
        validateQueryLength(queries.length);
        validateMask(mask);
        return nativeSearchFloat16(handle, queries, k, mask);
    }

    /**
     * Writes this index to a turbovec {@code .tv} file.
     */
    public synchronized void write(String path) throws IOException {
        ensureOpen();
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("path must not be null or empty");
        }
        nativeWrite(handle, path);
    }

    /**
     * Precomputes native search caches so the first query does not pay warm-up cost.
     */
    public synchronized void prepare() {
        ensureOpen();
        nativePrepare(handle);
    }

    /**
     * Removes the vector at {@code index} by swapping in the last vector.
     *
     * @return the previous index of the moved vector
     */
    public synchronized int swapRemove(int index) {
        ensureOpen();
        if (index < 0 || index >= size()) {
            throw new IndexOutOfBoundsException("index " + index + " out of bounds");
        }
        return nativeSwapRemove(handle, index);
    }

    public synchronized int size() {
        ensureOpen();
        return nativeSize(handle);
    }

    /**
     * Returns the committed dimensionality, or 0 for an uncommitted lazy index.
     */
    public synchronized int dim() {
        ensureOpen();
        return nativeDim(handle);
    }

    public synchronized int bitWidth() {
        ensureOpen();
        return nativeBitWidth(handle);
    }

    @Override
    public synchronized void close() {
        if (handle != 0L) {
            nativeFree(handle);
            handle = 0L;
        }
    }

    public synchronized boolean isClosed() {
        return handle == 0L;
    }

    private void ensureOpen() {
        if (handle == 0L) {
            throw new IllegalStateException("TurboVecIndex is closed");
        }
    }

    private static void validateVectorBatchLength(String name, int length, int dim) {
        if (dim <= 0 || length % dim != 0) {
            throw new IllegalArgumentException(name + " length must be a multiple of dim");
        }
    }

    private void validateQueryLength(int length) {
        int currentDim = dim();
        if (currentDim > 0 && length % currentDim != 0) {
            throw new IllegalArgumentException("queries length must be a multiple of index dim");
        }
    }

    private void validateMask(boolean[] mask) {
        if (mask != null && mask.length != size()) {
            throw new IllegalArgumentException("mask length must match index size");
        }
    }

    private static void validateBitWidth(int bitWidth) {
        if (bitWidth != 2 && bitWidth != 3 && bitWidth != 4 && bitWidth != 8) {
            throw new IllegalArgumentException("bitWidth must be one of 2, 3, 4, or 8");
        }
    }

    private static native long nativeNew(int dim, int bitWidth);

    private static native long nativeNewLazy(int bitWidth);

    private static native long nativeLoad(String path) throws IOException;

    private static native void nativeFree(long handle);

    private static native void nativeAdd(long handle, float[] vectors, int dim);

    private static native void nativeAddInt8(long handle, byte[] vectors, int dim);

    private static native void nativeAddFloat16(long handle, short[] vectors, int dim);

    private static native SearchResult nativeSearch(long handle, float[] queries, int k, boolean[] mask);

    private static native SearchResult nativeSearchInt8(long handle, byte[] queries, int k, boolean[] mask);

    private static native SearchResult nativeSearchFloat16(long handle, short[] queries, int k, boolean[] mask);

    private static native void nativeWrite(long handle, String path) throws IOException;

    private static native void nativePrepare(long handle);

    private static native int nativeSwapRemove(long handle, int index);

    private static native int nativeSize(long handle);

    private static native int nativeDim(long handle);

    private static native int nativeBitWidth(long handle);

    public static final class SearchResult {
        private final float[] scores;
        private final long[] indices;
        private final int queryCount;
        private final int k;

        private SearchResult(float[] scores, long[] indices, int queryCount, int k) {
            this.scores = scores;
            this.indices = indices;
            this.queryCount = queryCount;
            this.k = k;
        }

        public float[] scores() {
            return scores;
        }

        public long[] indices() {
            return indices;
        }

        public int queryCount() {
            return queryCount;
        }

        /**
         * Effective result count per query. This can be smaller than requested k when a mask is used.
         */
        public int k() {
            return k;
        }

        public float[] scoresForQuery(int queryIndex) {
            checkQueryIndex(queryIndex);
            return Arrays.copyOfRange(scores, queryIndex * k, (queryIndex + 1) * k);
        }

        public long[] indicesForQuery(int queryIndex) {
            checkQueryIndex(queryIndex);
            return Arrays.copyOfRange(indices, queryIndex * k, (queryIndex + 1) * k);
        }

        private void checkQueryIndex(int queryIndex) {
            if (queryIndex < 0 || queryIndex >= queryCount) {
                throw new IndexOutOfBoundsException("queryIndex " + queryIndex + " out of bounds");
            }
        }
    }
}
