package dev.osm.mapsplit.array;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.Test;

import dev.osm.mapsplit.array.JavaArray;
import dev.osm.mapsplit.array.LargeArray;
import dev.osm.mapsplit.array.PrimitiveLongArray;

public class ArrayTest {

    private static final Logger LOGGER = Logger.getLogger(ArrayTest.class.getName());

    private static final long TEST_DATA_SIZE = 1000000000L;

    /**
     * Native array
     */
    @Test
    public void nativeTest() {
        long start = System.currentTimeMillis();
        long[] array = new long[(int) TEST_DATA_SIZE];
        LOGGER.log(Level.INFO, "Native allocation {0} ms", System.currentTimeMillis() - start);
        start = System.currentTimeMillis();
        for (long i = 0; i < TEST_DATA_SIZE; i++) {
            array[(int) i] = i;
        }
        LOGGER.log(Level.INFO, "Native put {0} ms", System.currentTimeMillis() - start);
        start = System.currentTimeMillis();
        for (long i = 0; i < TEST_DATA_SIZE; i++) {
            assertEquals(i, array[(int) i]);
        }
        LOGGER.log(Level.INFO, "Native get {0} ms", System.currentTimeMillis() - start);
    }

    /**
     * Native array wrapped
     */
    @Test
    public void javaArrayTest() {
        long start = System.currentTimeMillis();
        PrimitiveLongArray array = new JavaArray(TEST_DATA_SIZE);
        LOGGER.log(Level.INFO, "JavaArray allocation {0} ms", System.currentTimeMillis() - start);
        start = System.currentTimeMillis();
        for (long i = 0; i < TEST_DATA_SIZE; i++) {
            array.set(i, i);
        }
        LOGGER.log(Level.INFO, "JavaArray put {0} ms", System.currentTimeMillis() - start);
        start = System.currentTimeMillis();
        for (long i = 0; i < TEST_DATA_SIZE; i++) {
            assertEquals(i, array.get(i));
        }
        LOGGER.log(Level.INFO, "JavaArray get {0} ms", System.currentTimeMillis() - start);
    }

    /**
     * LargeArray
     */
    @Test
    public void largeArrayTest() {
        long start = System.currentTimeMillis();
        PrimitiveLongArray array = new LargeArray(TEST_DATA_SIZE);
        LOGGER.log(Level.INFO, "LargeArray allocation {0} ms", System.currentTimeMillis() - start);
        start = System.currentTimeMillis();
        for (long i = 0; i < TEST_DATA_SIZE; i++) {
            array.set(i, i);
        }
        LOGGER.log(Level.INFO, "LargeArray put {0} ms", System.currentTimeMillis() - start);
        start = System.currentTimeMillis();
        for (long i = 0; i < TEST_DATA_SIZE; i++) {
            assertEquals(i, array.get(i));
        }
        LOGGER.log(Level.INFO, "LargeArray get {0} ms", System.currentTimeMillis() - start);
    }

    /**
     * LargeArray test with small segments
     */
    @Test
    public void largeArrayTest2() {
        long start = System.currentTimeMillis();
        long testDataSize = TEST_DATA_SIZE;
        PrimitiveLongArray array = new LargeArray(100, testDataSize);
        LOGGER.log(Level.INFO, "LargeArray allocation {0} ms", System.currentTimeMillis() - start);
        start = System.currentTimeMillis();
        for (long i = 0; i < testDataSize; i++) {
            array.set(i, i);
        }
        LOGGER.log(Level.INFO, "LargeArray put {0} ms", System.currentTimeMillis() - start);
        start = System.currentTimeMillis();
        for (long i = 0; i < testDataSize; i++) {
            assertEquals(i, array.get(i));
        }
        LOGGER.log(Level.INFO, "LargeArray get {0} ms", System.currentTimeMillis() - start);
    }
    
    /**
     * LargeArray test with small segments
     */
    @Test
    public void largeArrayTest3() {
        long start = System.currentTimeMillis();
        long testDataSize = TEST_DATA_SIZE + 55;
        PrimitiveLongArray array = new LargeArray(100, testDataSize);
        LOGGER.log(Level.INFO, "LargeArray allocation {0} ms", System.currentTimeMillis() - start);
        start = System.currentTimeMillis();
        for (long i = 0; i < testDataSize; i++) {
            array.set(i, i);
        }
        LOGGER.log(Level.INFO, "LargeArray put {0} ms", System.currentTimeMillis() - start);
        start = System.currentTimeMillis();
        for (long i = 0; i < testDataSize; i++) {
            assertEquals(i, array.get(i));
        }
        LOGGER.log(Level.INFO, "LargeArray get {0} ms", System.currentTimeMillis() - start);
    }
    
    /**
     * OffHeapLongArray
     */
    @Test
    public void offHeapLargeArrayTest() {
        long start = System.currentTimeMillis();
        PrimitiveLongArray array = new OffHeapLargeArray(TEST_DATA_SIZE);
        LOGGER.log(Level.INFO, "OffHeapLargeArray allocation {0} ms", System.currentTimeMillis() - start);
        start = System.currentTimeMillis();
        for (long i = 0; i < TEST_DATA_SIZE; i++) {
            array.set(i, i);
        }
        LOGGER.log(Level.INFO, "OffHeapLargeArray put {0} ms", System.currentTimeMillis() - start);
        start = System.currentTimeMillis();
        for (long i = 0; i < TEST_DATA_SIZE; i++) {
            assertEquals(i, array.get(i));
        }
        LOGGER.log(Level.INFO, "OffHeapLargeArray get {0} ms", System.currentTimeMillis() - start);
    }
    
    /**
     * OffHeapLongArray
     */
    @Test
    public void mMapLargeArrayTest() {
        long start = System.currentTimeMillis();
        PrimitiveLongArray array = null;
        try {
            array = new MMapLargeArray(TEST_DATA_SIZE);
        } catch (IOException e) {
            fail(e.getMessage());
        }
        LOGGER.log(Level.INFO, "MMapLargeArray allocation {0} ms", System.currentTimeMillis() - start);
        start = System.currentTimeMillis();
        for (long i = 0; i < TEST_DATA_SIZE; i++) {
            array.set(i, i);
        }
        LOGGER.log(Level.INFO, "MMapLargeArray put {0} ms", System.currentTimeMillis() - start);
        start = System.currentTimeMillis();
        for (long i = 0; i < TEST_DATA_SIZE; i++) {
            assertEquals(i, array.get(i));
        }
        LOGGER.log(Level.INFO, "MMapLargeArray get {0} ms", System.currentTimeMillis() - start);
    }
}
