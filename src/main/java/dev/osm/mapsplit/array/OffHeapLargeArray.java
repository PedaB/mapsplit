package dev.osm.mapsplit.array;

import xerial.larray.LLongArray;
import xerial.larray.japi.LArrayJ;

/**
 * Wrapper around normal long[]
 * 
 * @author simon
 *
 */
public class OffHeapLargeArray implements PrimitiveLongArray {
    final LLongArray array;

    /**
     * Construct a new instance
     * 
     * @param length size of the array
     */
    public OffHeapLargeArray(long length) {
        array = LArrayJ.newLLongArray(length);
    }

    @Override
    public void set(long index, long value) {
        array.update(index, value);
    }

    @Override
    public long get(long index) {
        return array.apply(index);
    }

    @Override
    public long length() {
        return array.size();
    }
    
    @Override
    public void free() {
        array.free();
    }
}
