package dev.osm.mapsplit.array;

import java.util.logging.Level;
import java.util.logging.Logger;

import dev.osm.mapsplit.MapSplit;

public class LargeArray implements PrimitiveLongArray {

    private static final Logger LOGGER = Logger.getLogger(LargeArray.class.getName());

    private final long     length;
    private final long[][] array;
    private final int      maxSubArrayLength;

    /**
     * Construct an array of long that can be larger than standard Javas limits
     * 
     * @param length how large the array should be
     */
    public LargeArray(long length) {
        this(Integer.MAX_VALUE/2, length);
    }

    /**
     * Construct an array of long that can be larger than standard Javas limits
     * 
     * @param maxSubArrayLength max. length of each sub-array, only useful for testing
     * @param length how large the array should be
     */
    public LargeArray(int maxSubArrayLength, long length) {
        this.length = length;
        this.maxSubArrayLength = maxSubArrayLength;
        int arrayCount = (int) (length / maxSubArrayLength);
        int rest = (int) (length % maxSubArrayLength);
        final boolean hasRest = rest > 0;
        if (hasRest) {
            arrayCount++;
        }
        MapSplit.setupLogging(LOGGER);
        LOGGER.log(Level.INFO, "Allocating LongArray length {0}, {1} sub-arrays", new Object[] { length, arrayCount });
        array = new long[arrayCount][];
        for (int i = 0; i < (arrayCount - (hasRest ? 1 : 0)); i++) {
            array[i] = new long[maxSubArrayLength];
        }
        if (hasRest) {
            array[arrayCount - 1] = new long[rest];
        }
    }

    @Override
    public void set(long index, long value) {
        int a = (int) (index / maxSubArrayLength);
        int pos = (int) (index - (a * maxSubArrayLength));
        array[a][pos] = value;
    }

    @Override
    public long get(long index) {
        int a = (int) (index / maxSubArrayLength);
        int pos = (int) (index - (a * maxSubArrayLength));
        return array[a][pos];
    }

    @Override
    public long length() {
        return length;
    }
}
