package dev.osm.mapsplit.array;

/**
 * Wrapper around normal long[]
 * 
 * @author simon
 *
 */
public class JavaArray implements PrimitiveLongArray {
    final long[] array;

    /**
     * Construct a new instance
     * 
     * @param length size of the array
     */
    public JavaArray(long length) {
        if (length > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("can't allocate Java array of size " + length);
        }
        array = new long[(int) length];
    }

    @Override
    public void set(long index, long value) {
        array[(int) index] = value;
    }

    @Override
    public long get(long index) {
        return array[(int) index];
    }

    @Override
    public long length() {
        return array.length;
    }
}
