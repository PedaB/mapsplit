package dev.osm.mapsplit;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.imintel.mbtiles4j.MBTilesReadException;
import org.imintel.mbtiles4j.MBTilesReader;
import org.imintel.mbtiles4j.Tile;
import org.imintel.mbtiles4j.model.MetadataEntry;
import org.jetbrains.annotations.Nullable;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class BenchMark {

    private static final Logger LOGGER = Logger.getLogger(BenchMark.class.getName());

    private static final String MSF_OUTPUT_FILENAME = "build/tmp/tiles/liechtenstein.msf";
    private static final String MSF_OUTPUT_DIR      = "build/tmp/tiles/";
    private static final String TEST_DATA_PBF       = "test-data/liechtenstein-latest.osm.pbf";

    /**
     * Pre-test setup
     */
    @Before
    public void setup() {
        File output = new File(MSF_OUTPUT_FILENAME);
        output.delete();
        File path = new File(MSF_OUTPUT_DIR);
        path.mkdirs();
    }

    /**
     * Basic test to check that stuff happens
     */
    @Test
    public void split() {
        try {
            File output = new File(MSF_OUTPUT_FILENAME);
            Assert.assertFalse(output.exists());
            long start = System.currentTimeMillis();
            MapSplit.main(new String[] { "-mtvM", "-i", TEST_DATA_PBF, "-o", MSF_OUTPUT_FILENAME, "-f", "2000", "-z", "16", "-O", "2000" });
            LOGGER.log(Level.INFO, "Run time {0} ms", System.currentTimeMillis() - start);
            checkMetadata(12, 16);
            Tile t = getTile(15, 17250, 11506);
            Assert.assertNotNull(t);
        } catch (Exception e) {
            Assert.fail(e.getMessage());
        }
    }

    /**
     * Check that the metadata in the MBTiles is what we expect
     * 
     * @param min min zoom in metadata
     * @param max max zoom in metadata
     * @throws MBTilesReadException if we can't read or access the MBTiles file
     */
    private void checkMetadata(int min, int max) throws MBTilesReadException {
        MBTilesReader r = null;
        try {
            r = new MBTilesReader(new File(MSF_OUTPUT_FILENAME));
            MetadataEntry md = r.getMetadata();
            final Map<String, String> required = new HashMap<>();
            md.getRequiredKeyValuePairs().forEach(new Consumer<Entry<String, String>>() {

                @Override
                public void accept(Entry<String, String> e) {
                    required.put(e.getKey(), e.getValue());
                }

            });
            final Map<String, String> custom = new HashMap<>();
            md.getCustomKeyValuePairs().forEach(new Consumer<Entry<String, String>>() {

                @Override
                public void accept(Entry<String, String> e) {
                    custom.put(e.getKey(), e.getValue());
                }

            });
            Assert.assertEquals(Const.MSF_MIME_TYPE, required.get("format"));
            Assert.assertEquals(min, r.getMinZoom());
            Assert.assertEquals(max, r.getMaxZoom());
            Assert.assertEquals(1544288785000L, Long.parseLong(custom.get("latest_date")));
        } finally {
            if (r != null) {
                r.close();
            }
        }
    }

    /**
     * Get a tile from a MSF format file
     * 
     * @param z zoom
     * @param x x tile number
     * @param y y tile number in google/OSM schema
     * @return a Tile or null if not found
     */
    @Nullable
    private Tile getTile(int z, int x, int y) {
        MBTilesReader r = null;
        int tmsY = Integer.MIN_VALUE;
        try {
            r = new MBTilesReader(new File(MSF_OUTPUT_FILENAME));
            int ymax = 1 << z;
            tmsY = ymax - y - 1; // TMS scheme
            return r.getTile(z, x, tmsY);
        } catch (MBTilesReadException e) {
            LOGGER.warning(e.getMessage() + " z:" + z + " x:" + x + " y:" + y + "/" + tmsY);
            return null;
        } finally {
            if (r != null) {
                r.close();
            }
        }
    }
}
