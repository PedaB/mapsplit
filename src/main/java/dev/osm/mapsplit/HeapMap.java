package dev.osm.mapsplit;

/*
 * Mapsplit - A simple but fast tile splitter for large OSM data
 * 
 * Written in 2011 by Peda (osm-mapsplit@won2.de)
 * 
 * To the extent possible under law, the author(s) have dedicated all copyright and related and neighboring rights to
 * this software to the public domain worldwide. This software is distributed without any warranty.
 * 
 * You should have received a copy of the CC0 Public Domain Dedication along with this software. If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 * 
 * Further work by Simon Poole 2018/19
 */

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jetbrains.annotations.NotNull;

import dev.osm.mapsplit.array.PrimitiveLongArray;
import dev.osm.mapsplit.array.JavaArray;
import dev.osm.mapsplit.array.LargeArray;

// @formatter:off
/**
 * An HashMap in Heap memory, optimized for size to use in OSM
 * 
 * Structure of the values:
 * 
 *     6                 4                   3    2 2 22
 *     3                 8                   2    8 7 54
 *     XXXX XXXX XXXX XXXX YYYY YYYY YYYY YYYY uuuu uNNE nnnn nnnn nnnn nnnn nnnn nnnn
 *
 *     X - tile number
 *     Y - tile number
 *     u - unused
 *     N - bits indicating immediate "neigbours"
 *     E - extended "neighbour" list used
 *     n - bits for "short" neighbour index, in long list mode used as index
 *
 *     Tiles indexed in "short" list (T original tile)
 *           -  - 
 *           2  1  0  1  2
 *       
 *     -2    0  1  2  3  4
 *     -1    5  6  7  8  9
 *      0   10 11  T 12 13
 *      1   14 15 16 17 18
 *      2   19 20 21 22 23
 * 
 */
// @formatter:on
@SuppressWarnings("unchecked")
public class HeapMap implements OsmMap {
    
    private static final Logger LOGGER = Logger.getLogger(LargeArray.class.getName());

    // used on keys
    private static final int  BUCKET_FULL_SHIFT = 63;
    private static final long BUCKET_FULL_MASK  = 1l << BUCKET_FULL_SHIFT;
    private static final long KEY_MASK          = ~BUCKET_FULL_MASK;

    // see HEAPMAP.md for details
    static final int          TILE_X_SHIFT = 48;
    static final int          TILE_Y_SHIFT = 32;
    private static final long TILE_X_MASK  = Const.MAX_TILE_NUMBER << TILE_X_SHIFT;
    private static final long TILE_Y_MASK  = Const.MAX_TILE_NUMBER << TILE_Y_SHIFT;

    private static final int   TILE_EXT_SHIFT            = 24;
    private static final long  TILE_EXT_MASK             = 1l << TILE_EXT_SHIFT;
    static final long          TILE_MARKER_MASK          = 0xFFFFFFl;
    private static final int   NEIGHBOUR_SHIFT           = TILE_EXT_SHIFT + 1;
    private static final long  NEIGHBOUR_MASK            = 3l << NEIGHBOUR_SHIFT;
    private static final float DEFAULT_FILL_FACTOR       = 0.75f;
    private static final int   INITIAL_EXTENDED_SET_SIZE = 1000;

    private long capacity;

    private long  size = 0;
    private PrimitiveLongArray keys;
    private PrimitiveLongArray values;

    private int     extendedBuckets = 0;
    private int[][] extendedSet     = new int[INITIAL_EXTENDED_SET_SIZE][];

    private long  hits       = 0;
    private long  misses     = 0;
    private float fillFactor = DEFAULT_FILL_FACTOR;
    private long  threshold;

    class HeapMapError extends Error {
        private static final long serialVersionUID = 1L;

        /**
         * Construct an Error indicating that we've encountered a serious issue
         * 
         * @param message the message
         */
        HeapMapError(@NotNull String message) {
            super(message);
        }
    }

    /**
     * Construct a new map
     * 
     * @param capacity the (fixed) capacity of the map
     */
    public HeapMap(long capacity) {
        this(capacity, DEFAULT_FILL_FACTOR);
    }

    /**
     * Construct a new map
     * 
     * @param capacity the (fixed) capacity of the map
     * @param fill fill factor to use
     */
    public HeapMap(long capacity, float fill) {
        this.capacity = capacity;
        keys = getArray(capacity);
        values = getArray(capacity);
        fillFactor = fill;
        threshold = (long) (capacity * fillFactor);
        MapSplit.setupLogging(LOGGER);
    }

    /**
     * Get an array appropriate for the given length
     * 
     * @param length the size of the array
     * @return an appropriate instance implementing PrimitiveLongArray
     */
    private static PrimitiveLongArray getArray(long length) {
        return length > Integer.MAX_VALUE ? new LargeArray(length) : new JavaArray(length);
    }

    @Override
    public long getCapacity() {
        return capacity;
    }

    @Override
    public int tileX(long value) {
        return (int) ((value & TILE_X_MASK) >>> TILE_X_SHIFT);
    }

    @Override
    public int tileY(long value) {
        return (int) ((value & TILE_Y_MASK) >>> TILE_Y_SHIFT);
    }

    @Override
    public int neighbour(long value) {
        return (int) ((value & NEIGHBOUR_MASK) >>> NEIGHBOUR_SHIFT);
    }

    /**
     * The hash function
     * 
     * @param key the key
     * @return the hash for key
     */
    private static long hash(long key) {
        return 0x7fffffffffffffffL & (1664525L * key + 1013904223L);
    }

    /*
     * (non-Javadoc)
     * 
     * @see OsmMap#put(long, int, int, int)
     */
    @Override
    public void put(long key, int tileX, int tileY, int neighbours) {
        if (key < 0) {
            throw new IllegalArgumentException("Ids are limited to positive longs");
        }
        long value = ((long) tileX) << TILE_X_SHIFT | ((long) tileY) << TILE_Y_SHIFT | ((long) neighbours) << NEIGHBOUR_SHIFT;
        put(key, value);
    }

    /**
     * Put a key and the value in to the map
     * 
     * @param key the key
     * @param value the value
     */
    private void put(long key, long value) {
        if (size > threshold) {
            expand(capacity * 2);
        }
        long bucket = hash(key) % capacity;
        long count = 0;
        while (true) {
            if (values.get(bucket) == 0l) {
                keys.set(bucket, key);
                values.set(bucket, value);
                size++;
                return;
            }
            if (count == 0) {
                // mark bucket as "overflow bucket"
                keys.set(bucket, keys.get(bucket) | BUCKET_FULL_MASK);
            }
            bucket++;
            count++;
            bucket = bucket % capacity;
        }
    }

    /**
     * Expand the map to a larger capacity
     * 
     * @param newCapacity the new capacity
     */
    private void expand(long newCapacity) {
        LOGGER.log(Level.INFO, "Expanding array current {0}, new {1}, current size {2}, current threshold {3}", new Object[] { capacity, newCapacity, size, threshold });
        PrimitiveLongArray oldKeys = keys;
        PrimitiveLongArray oldValues = values;
        long oldCapacity = capacity;
        capacity = newCapacity;
        keys = getArray(capacity);
        values = getArray(capacity);
        threshold = (long) (capacity * fillFactor);
        size = 0; // reset
        for (long i = 0; i < oldCapacity; i++) {
            long value = oldValues.get(i);
            if (value != 0) {
                final long key = oldKeys.get(i) & ~BUCKET_FULL_MASK;
                put(key, value);
            }
        }
        oldKeys.free();
        oldValues.free();
    }

    /**
     * Get the bucket index for the value
     * 
     * @param key the key
     * @return the index of -1 if not found
     */
    private long getBucket(long key) {
        long count = 0;
        long bucket = hash(key) % capacity;

        while (true) {
            if (values.get(bucket) != 0l) {
                if ((keys.get(bucket) & KEY_MASK) == key) {
                    hits++;
                    return bucket;
                }
            } else {
                return -1;
            }

            if (count == 0) {
                // we didn't have an overflow so the value is not stored yet
                if (keys.get(bucket) >= 0l) {
                    return -1;
                }
            }

            misses++;
            bucket++;
            count++;
            bucket = bucket % capacity;
        }
    }

    /*
     * (non-Javadoc)
     * 
     * @see OsmMap#get(long)
     */
    @Override
    public long get(long key) {
        long bucket = getBucket(key);

        if (bucket == -1) {
            return 0;
        }
        return values.get(bucket);
    }

    /**
     * Merge two int arrays, removing dupes
     * 
     * @param old the original array
     * @param add the additional array
     * @param len the additional number of ints to allocate
     * @return the new array
     */
    private int[] merge(@NotNull int[] old, @NotNull int[] add, int len) {
        int curLen = old.length;
        int[] tmp = new int[curLen + len];
        System.arraycopy(old, 0, tmp, 0, old.length);

        for (int i = 0; i < len; i++) {
            int toAdd = add[i];
            boolean contained = false;

            for (int j = 0; j < curLen; j++) {
                if (toAdd == tmp[j]) {
                    contained = true;
                    break;
                }
            }

            if (!contained) {
                tmp[curLen++] = toAdd;
            }
        }

        int[] result = new int[curLen];
        System.arraycopy(tmp, 0, result, 0, curLen);

        return result;
    }

    /**
     * Add tiles to the extended tile list for an element
     * 
     * @param index the index in to the array of the tile lists
     * @param tiles a List containing the tile numbers
     */
    private void appendNeighbours(int index, @NotNull Collection<Long> tiles) {
        int[] old = extendedSet[index];
        int[] set = new int[4 * tiles.size()];
        int pos = 0;

        for (long l : tiles) {
            int tx = tileX(l);
            int ty = tileY(l);
            int neighbour = neighbour(l);

            set[pos++] = tx << Const.MAX_ZOOM | ty;
            if ((neighbour & NEIGHBOURS_EAST) != 0) {
                set[pos++] = (tx + 1) << Const.MAX_ZOOM | ty;
            }
            if ((neighbour & NEIGHBOURS_SOUTH) != 0) {
                set[pos++] = tx << Const.MAX_ZOOM | (ty + 1);
            }
            if (neighbour == NEIGHBOURS_SOUTH_EAST) {
                set[pos++] = (tx + 1) << Const.MAX_ZOOM | (ty + 1);
            }
        }

        int[] result = merge(old, set, pos);
        extendedSet[index] = result;
    }

    /**
     * Start using the list of additional tiles instead of the bits directly in the value
     * 
     * @param bucket the index of the value
     * @param tiles the List of additional tiles
     */
    private void extendToNeighbourSet(long bucket, @NotNull Collection<Long> tiles) {
        long val = values.get(bucket);

        if ((val & TILE_MARKER_MASK) != 0) {
            // add current stuff to tiles list
            List<Integer> tmpList = parseMarker(val);
            for (int i : tmpList) {
                long tx = i >>> Const.MAX_ZOOM;
                long ty = i & Const.MAX_TILE_NUMBER;
                long temp = tx << TILE_X_SHIFT | ty << TILE_Y_SHIFT;

                tiles.add(temp);
            }

            // delete old marker from val
            val &= ~TILE_MARKER_MASK;
        }

        int cur = extendedBuckets++;

        // if we don't have enough sets, increase the array...
        if (cur >= extendedSet.length) {
            if (extendedSet.length >= TILE_MARKER_MASK / 2) { // assumes TILE_MARKER_MASK starts at 0
                throw new IllegalStateException("Too many extended tile entries to expand");
            }
            int[][] tmp = new int[2 * extendedSet.length][];
            System.arraycopy(extendedSet, 0, tmp, 0, extendedSet.length);
            extendedSet = tmp;
        }

        val |= 1l << TILE_EXT_SHIFT;
        val |= (long) cur;
        values.set(bucket, val);

        extendedSet[cur] = new int[0];

        appendNeighbours(cur, tiles);
    }

    /*
     * (non-Javadoc)
     * 
     * @see OsmMap#update(long, java.util.List)
     */
    @Override
    public void update(long key, @NotNull Collection<Long> tiles) {

        long bucket = getBucket(key);
        if (bucket == -1) {
            return;
        }
        long val = values.get(bucket);
        int tx = tileX(val);
        int ty = tileY(val);

        // neighbour list is already too large so we use the "large store"
        if ((val & TILE_EXT_MASK) != 0) {
            int idx = (int) (val & TILE_MARKER_MASK);
            appendNeighbours(idx, tiles);
            return;
        }

        // create a expanded temp set for neighbourhood tiles
        Collection<Long> expanded = new TreeSet<>();
        for (long tile : tiles) {
            expanded.add(tile);

            long x = tileX(tile);
            long y = tileY(tile);
            int neighbour = neighbour(tile);

            if ((neighbour & NEIGHBOURS_EAST) != 0) {
                expanded.add((x + 1) << TILE_X_SHIFT | y << TILE_Y_SHIFT);
            }
            if ((neighbour & NEIGHBOURS_SOUTH) != 0) {
                expanded.add(x << TILE_X_SHIFT | (y + 1) << TILE_Y_SHIFT);
            }
            if (neighbour == NEIGHBOURS_SOUTH_EAST) {
                expanded.add((x + 1) << TILE_X_SHIFT | (y + 1) << TILE_Y_SHIFT);
            }
        }

        // now we use the 24 reserved bits for the tiles list..
        boolean extend = false;
        for (long tile : expanded) {

            int tmpX = tileX(tile);
            int tmpY = tileY(tile);

            if (tmpX == tx && tmpY == ty) {
                continue;
            }

            int dx = tmpX - tx + 2;
            int dy = tmpY - ty + 2;

            int idx = dy * 5 + dx;
            if (idx >= 12) {
                idx--;
            }

            if (dx < 0 || dy < 0 || dx > 4 || dy > 4) {
                // .. damn, not enough space for "small store"
                // -> use "large store" instead
                extend = true;
                break;
            } else {
                val |= 1l << idx;
            }
        }

        if (extend) {
            extendToNeighbourSet(bucket, tiles);
        } else {
            values.set(bucket, val);
        }
    }

    private List<Integer> parseMarker(long value) {
        List<Integer> result = new ArrayList<>();
        int tx = tileX(value);
        int ty = tileY(value);

        for (int i = 0; i < 24; i++) {
            // if bit is not set, continue..
            if (((value >> i) & 1) == 0) {
                continue;
            }

            int v = i >= 12 ? i + 1 : i;

            int tmpX = v % 5 - 2;
            int tmpY = v / 5 - 2;

            result.add((tx + tmpX) << Const.MAX_ZOOM | (ty + tmpY));
        }
        return result;
    }

    /**
     * Return a list with the contents of an int array
     * 
     * @param set the array
     * @return a List of Integer
     */
    @NotNull
    private List<Integer> asList(@NotNull int[] set) {
        List<Integer> result = new ArrayList<>();

        for (int i = 0; i < set.length; i++) {
            result.add(set[i]);
        }

        return result;
    }

    /*
     * (non-Javadoc)
     * 
     * @see OsmMap#getAllTiles(long)
     */
    @Override
    public List<Integer> getAllTiles(long key) {

        List<Integer> result;
        long value = get(key);

        if (value == 0) {
            return null;
        }

        int tx = tileX(value);
        int ty = tileY(value);
        int neighbour = neighbour(value);

        if ((value & TILE_EXT_MASK) != 0) {
            int idx = (int) (value & TILE_MARKER_MASK);
            return asList(extendedSet[idx]);

            /*
             * TODO: testen, dieser Teil sollte nicht noetig sein, da schon im extendedSet! set.add(tx << 13 | ty); if
             * ((neighbour & OsmMap.NEIGHBOURS_EAST) != 0) set.add(tx+1 << 13 | ty); if ((neighbour &
             * OsmMap.NEIGHBOURS_SOUTH) != 0) set.add(tx << 13 | ty+1);
             * 
             * return set;
             */
        }

        result = parseMarker(value);

        // TODO: some tiles (neighbour-tiles) might be double-included in the list, is this a problem?!

        // add the tile (and possible neighbours)
        result.add(tx << Const.MAX_ZOOM | ty);
        if ((neighbour & OsmMap.NEIGHBOURS_EAST) != 0) {
            result.add((tx + 1) << Const.MAX_ZOOM | ty);
        }
        if ((neighbour & OsmMap.NEIGHBOURS_SOUTH) != 0) {
            result.add(tx << Const.MAX_ZOOM | (ty + 1));
        }

        return result;
    }

    /*
     * (non-Javadoc)
     * 
     * @see OsmMap#getLoad()
     */
    @Override
    public double getLoad() {
        int count = 0;
        for (int i = 0; i < capacity; i++) {
            if (values.get(i) != 0) {
                count++;
            }
        }
        return ((double) count) / ((double) capacity);
    }

    /*
     * (non-Javadoc)
     * 
     * @see OsmMap#getMissHitRatio()
     */
    @Override
    public double getMissHitRatio() {
        return ((double) misses) / ((double) hits);
    }

    @Override
    public Iterator<Long> keyIterator() {
        return new Iterator<Long>() {
            long i = -1;
            final long length = keys.length();
            
            @Override
            public boolean hasNext() {              
                if (i < length - 1) {
                    long next = i + 1;
                    long nextValue = values.get(next);
                    while (nextValue==0 && next < length - 1) {
                        next++;
                        nextValue = values.get(next);
                    }
                    return nextValue!=0;
                }
                return false;
            }

            @Override
            public Long next() {
                if (i < length - 1) {
                    i++;
                    long nextValue = values.get(i);
                    while (nextValue == 0 && i < length - 1) {
                        i++;
                        nextValue = values.get(i);
                    }
                    if  (nextValue!=0) {
                        return keys.get(i) & KEY_MASK;
                    }
                }     
                throw new NoSuchElementException();
            }
        };
    }
}
