package dev.osm.mapsplit.array;

public interface PrimitiveLongArray {
    /**
     * Set a value at index
     * 
     * @param index the position in the array
     * @param value the value to set
     */
    void set(long index, long value);

    /**
     * Get the value at index
     * 
     * @param index the index
     * @return the array value at index
     */
    long get(long index);
    
    /**
     * Get the size of the array
     * 
     * @return the size of the array
     */
    long length();
    
    /**
     * Release any resources that will not be collected by the GC
     */
    default void free() {
        // NOP
    }
}
