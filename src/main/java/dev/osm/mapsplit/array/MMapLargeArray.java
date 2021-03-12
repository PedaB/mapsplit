package dev.osm.mapsplit.array;

import java.io.File;
import java.io.IOException;

import xerial.larray.MappedLByteArray;
import xerial.larray.japi.LArrayJ;
import xerial.larray.mmap.MMapMode;

/**
 * Wrapper around normal long[]
 * 
 * @author simon
 *
 */
public class MMapLargeArray implements PrimitiveLongArray {
    final MappedLByteArray array;

    /**
     * Construct a new instance
     * 
     * @param length size of the array
     * @throws IOException
     */
    public MMapLargeArray(long length) throws IOException {
        array = LArrayJ.mmap(File.createTempFile("temp", null), MMapMode.PRIVATE);
    }

    @Override
    public void set(long index, long value) {
        array.putLong(index, value);
    }

    @Override
    public long get(long index) {
        return array.getLong(index);
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
