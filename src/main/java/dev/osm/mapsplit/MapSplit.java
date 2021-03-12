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
 */

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.sql.SQLException;
import java.text.DateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.imintel.mbtiles4j.MBTilesWriteException;
import org.imintel.mbtiles4j.MBTilesWriter;
import org.imintel.mbtiles4j.model.MetadataEntry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.openstreetmap.osmosis.core.container.v0_6.BoundContainer;
import org.openstreetmap.osmosis.core.container.v0_6.EntityContainer;
import org.openstreetmap.osmosis.core.container.v0_6.NodeContainer;
import org.openstreetmap.osmosis.core.container.v0_6.RelationContainer;
import org.openstreetmap.osmosis.core.container.v0_6.WayContainer;
import org.openstreetmap.osmosis.core.domain.v0_6.Bound;
import org.openstreetmap.osmosis.core.domain.v0_6.Entity;
import org.openstreetmap.osmosis.core.domain.v0_6.Node;
import org.openstreetmap.osmosis.core.domain.v0_6.Relation;
import org.openstreetmap.osmosis.core.domain.v0_6.RelationMember;
import org.openstreetmap.osmosis.core.domain.v0_6.Way;
import org.openstreetmap.osmosis.core.domain.v0_6.WayNode;
import org.openstreetmap.osmosis.core.task.v0_6.RunnableSource;
import org.openstreetmap.osmosis.core.task.v0_6.Sink;
import org.openstreetmap.osmosis.osmbinary.file.BlockOutputStream;

import crosby.binary.osmosis.OsmosisReader;
import crosby.binary.osmosis.OsmosisSerializer;

public class MapSplit {

    private static final String MAPSPLIT_TAG = "mapsplit";

    private static final String PBF_EXT = ".pbf";

    private static final Logger LOGGER = Logger.getLogger(MapSplit.class.getName());

    private static final int MAX_ZOOM_OUT_DIFF = 5;

    private final int zoom;

    // all data after this appointment date is considered new or modified
    private Date appointmentDate;

    private Date latestDate = new Date(0);

    // the size of the border (in percent for a tile's height and width) for single tiles
    private double border = 0.1;

    // the input file we're going to split
    private File input;

    // maximum number of files open at the same time
    private int maxFiles;

    // internal store to check if reading the file worked
    private boolean complete = false;

    // verbose outpu
    private boolean verbose = false;

    private final boolean completeRelations;
    private final boolean completeAreas;

    // the hashmap for all nodes in the osm map
    private final OsmMap nmap;

    // the hashmap for all ways in the osm map
    private final OsmMap wmap;

    // the hashmap for all relations in the osm map
    private final OsmMap rmap;

    // a map of ways that need to be added in a second run
    private HashMap<Long, Collection<Long>> extraWayMap = null;

    // a bitset telling the algorithm which tiles need to be re-renderd
    private final UnsignedSparseBitSet modifiedTiles = new UnsignedSparseBitSet();

    private final Map<Integer, UnsignedSparseBitSet> optimizedModifiedTiles = new HashMap<>();

    // the serializer (OSM writers) for any modified tile
    private Map<Integer, OsmosisSerializer> outFiles;

    // output for mbtiles
    private Map<Integer, ByteArrayOutputStream> outBlobs;

    // new zoom levels for tiles during optimization
    private final Map<Integer, Byte> zoomMap = new HashMap<>();

    // relations with potential forward references
    private final Set<Relation> postProcessRelations = new HashSet<>();

    class DataFormatException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        /**
         * Construct a new exception indicating data format errors
         * 
         * @param message the message
         */
        public DataFormatException(@NotNull String message) {
            super(message);
        }
    }

    /**
     * Construct a new MapSplit instance
     * 
     * @param zoom maximum zoom level to generate tiles for
     * @param appointmentDate only add changes from after this date (doesn't really work)
     * @param mapSizes initial sizes of the maps for OSM objects
     * @param maxFiles maximum number of files/tiles to keep open at the same time
     * @param border grow tiles (content) by this factor
     * @param inputFile the input PBF file
     * @param completeRelations include all relation member objects in every tile the relation is present in (very
     *            expensive)
     * @param completeAreas include all multipolygon relation member objects in every tile the relation is present in
     *            (very expensive)
     */
    public MapSplit(int zoom, Date appointmentDate, long[] mapSizes, int maxFiles, double border, File inputFile, boolean completeRelations,
            boolean completeAreas) {
        this.zoom = zoom;
        this.border = border;
        this.input = inputFile;
        this.appointmentDate = appointmentDate;
        this.maxFiles = maxFiles;
        this.completeRelations = completeRelations;
        this.completeAreas = completeAreas;
        nmap = new HeapMap(mapSizes[0]);
        wmap = new HeapMap(mapSizes[1]);
        rmap = new HeapMap(mapSizes[2]);
        if (completeRelations || completeAreas) {
            extraWayMap = new HashMap<>();
        }

        optimizedModifiedTiles.put(zoom, modifiedTiles);
    }

    /**
     * Calculate the longitude for a tile
     * 
     * @param x the x number for the tile
     * @return the longitude
     */
    private double tile2lon(int x) {
        return (x / Math.pow(2.0, zoom)) * 360.0 - 180.0;
    }

    /**
     * Calculate the latitude for a tile
     * 
     * @param y the y number for the tile
     * @return the latitude
     */
    private double tile2lat(int y) {
        double n = Math.PI - 2.0 * Math.PI * y / Math.pow(2, zoom);
        return (180.0 / Math.PI * Math.atan(0.5 * (Math.pow(Math.E, n) - Math.pow(Math.E, -n))));
    }

    /**
     * Calculate tile X number for a given longitude
     * 
     * @param lon the longitude
     * @return the tile X number
     */
    private int lon2tileX(double lon) {
        int xtile = (int) Math.floor((lon + 180) / 360 * (1 << zoom));
        if (xtile < 0) {
            xtile = 0;
        }
        if (xtile >= (1 << zoom)) {
            xtile = ((1 << zoom) - 1);
        }
        return xtile;
    }

    /**
     * Calculate tile Y number for a given latitude
     * 
     * @param lat the latitude
     * @return the tile y number
     */
    private int lat2tileY(double lat) {
        int ytile = (int) Math.floor((1 - Math.log(Math.tan(Math.toRadians(lat)) + 1 / Math.cos(Math.toRadians(lat))) / Math.PI) / 2 * (1 << zoom));
        if (ytile < 0) {
            ytile = 0;
        }
        if (ytile >= (1 << zoom)) {
            ytile = ((1 << zoom) - 1);
        }
        return ytile;
    }

    /**
     * Calculate the Bound for the given tile
     * 
     * @param tileX tile X number
     * @param tileY tile Y number
     * @return a Bound object (a bound box for the tile)
     */
    public Bound getBound(int tileX, int tileY) {

        double l = tile2lon(tileX);
        double r = tile2lon(tileX + 1);
        double t = tile2lat(tileY);
        double b = tile2lat(tileY + 1);

        double dx = r - l;
        double dy = b - t;

        l -= border * dx;
        r += border * dx;
        t -= border * dy;
        b += border * dy;

        return new Bound(r, l, t, b, MAPSPLIT_TAG);
    }

    /**
     * Fill out holes
     * 
     * @param tiles the current tiles
     */
    private void checkAndFill(@NotNull Collection<Long> tiles) {

        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;

        // determine the min/max tile nrs
        for (long tile : tiles) {
            int tx = nmap.tileX(tile);
            int ty = nmap.tileY(tile);

            minX = Math.min(minX, tx);
            minY = Math.min(minY, ty);
            maxX = Math.max(maxX, tx);
            maxY = Math.max(maxY, ty);
        }

        // enlarge min/max to have a border and to cope with possible neighbour tiles
        minX -= 2;
        minY -= 2;
        maxX += 2;
        maxY += 2;
        int sizeX = maxX - minX + 1;
        int sizeY = maxY - minY + 1;

        // fill the helperSet which marks any set tile
        BitSet helperSet = new BitSet();
        for (long tile : tiles) {
            int tx = nmap.tileX(tile) - minX;
            int ty = nmap.tileY(tile) - minY;
            int neighbour = nmap.neighbour(tile);

            helperSet.set(tx + ty * sizeX);
            if ((neighbour & OsmMap.NEIGHBOURS_EAST) != 0) {
                helperSet.set(tx + 1 + ty * sizeX);
            }
            if ((neighbour & OsmMap.NEIGHBOURS_SOUTH) != 0) {
                helperSet.set(tx + (ty + 1) * sizeX);
            }
            if (neighbour == OsmMap.NEIGHBOURS_SOUTH_EAST) {
                helperSet.set(tx + 1 + (ty + 1) * sizeX);
            }
        }

        // start with tile 1,1 and fill region...
        Deque<Integer> stack = new ArrayDeque<>();
        stack.push(1 + 1 * sizeX);

        // fill all tiles that are reachable by a 4-neighbourhood
        while (!stack.isEmpty()) {
            int val = stack.pop();

            boolean isSet = helperSet.get(val);
            helperSet.set(val);

            if (val >= sizeX * sizeY) {
                continue;
            }

            int ty = val / sizeX;
            int tx = val % sizeX;

            if ((tx == 0) || (ty == 0) || (ty >= sizeY)) {
                continue;
            }

            if (!isSet) {
                stack.push(tx + 1 + ty * sizeX);
                stack.push(tx - 1 + ty * sizeX);
                stack.push(tx + (ty + 1) * sizeX);
                stack.push(tx + (ty - 1) * sizeX);
            }
        }

        // now check if there are not-set bits left (i.e. holes in tiles)
        int idx = -1;
        while (true) {
            idx = helperSet.nextClearBit(idx + 1);

            if (idx >= sizeX * sizeY) {
                break;
            }

            int tx = idx % sizeX;
            int ty = idx / sizeX;

            if ((tx == 0) || (ty == 0)) {
                continue;
            }

            tx += minX;
            ty += minY;

            // TODO: make this a bit nicer by delegating the id-generation to the map code
            int c = tx << Const.MAX_ZOOM | ty;
            tiles.add(((long) c) << HeapMap.TILE_Y_SHIFT);
            modifiedTiles.set(c);
        }
    }

    /**
     * calculate the lon-offset for the given border size
     * 
     * @param lon the longitude
     * @return the offset
     */
    private double deltaX(double lon) {
        int tx = lon2tileX(lon);

        double x1 = tile2lon(tx);
        double x2 = tile2lon(tx + 1);

        return border * (x2 - x1);
    }

    /**
     * calculate the lat-offset for the given border size
     * 
     * @param lat the latitude
     * @return the offset
     */
    private double deltaY(double lat) {
        int ty = lat2tileY(lat);

        double y1 = tile2lat(ty);
        double y2 = tile2lat(ty + 1);

        return border * (y2 - y1);
    }

    /**
     * Add tile and neighbours to modifiedTiles
     * 
     * @param tx tile x number
     * @param ty tile y number
     * @param neighbour bit map for neighbour tiles
     */
    private void setModifiedTiles(int tx, int ty, int neighbour) {
        modifiedTiles.set(tx << Const.MAX_ZOOM | ty);
        if ((neighbour & OsmMap.NEIGHBOURS_EAST) != 0) {
            modifiedTiles.set((tx + 1) << Const.MAX_ZOOM | ty);
        }
        if ((neighbour & OsmMap.NEIGHBOURS_SOUTH) != 0) {
            modifiedTiles.set(tx << Const.MAX_ZOOM | (ty + 1));
        }
        if (neighbour == OsmMap.NEIGHBOURS_SOUTH_EAST) {
            modifiedTiles.set((tx + 1) << Const.MAX_ZOOM | (ty + 1));
        }
    }

    /**
     * Add a Node
     * 
     * @param n the Node
     * @param lat latitude in WGS84 coords
     * @param lon longitude in WGS84 coords
     */
    private void addNodeToMap(Node n, double lat, double lon) {
        int tileX = lon2tileX(lon);
        int tileY = lat2tileY(lat);
        int neighbour = OsmMap.NEIGHBOURS_NONE;

        // check and add border if needed
        double dx = deltaX(lon);
        if (lon2tileX(lon + dx) > tileX) {
            neighbour = OsmMap.NEIGHBOURS_EAST;
        } else if (lon2tileX(lon - dx) < tileX) {
            tileX--;
            neighbour = OsmMap.NEIGHBOURS_EAST;
        }
        double dy = deltaY(lat);
        if (lat2tileY(lat + dy) > tileY) {
            neighbour += OsmMap.NEIGHBOURS_SOUTH;
        } else if (lat2tileY(lat - dy) < tileY) {
            tileY--;
            neighbour += OsmMap.NEIGHBOURS_SOUTH;
        }

        // mark current tile (and neighbours) to be re-rendered
        if (n.getTimestamp().after(appointmentDate)) {
            setModifiedTiles(tileX, tileY, neighbour);
        }

        // mark the latest changes made to this map
        if (n.getTimestamp().after(latestDate)) {
            latestDate = n.getTimestamp();
        }

        nmap.put(n.getId(), tileX, tileY, neighbour);
    }

    /**
     * Add a Way
     * 
     * @param way the Way
     */
    private void addWayToMap(@NotNull Way way) {

        boolean modified = way.getTimestamp().after(appointmentDate);
        Set<Long> tileList = new TreeSet<>();

        // mark the latest changes made to this map
        if (way.getTimestamp().after(latestDate)) {
            latestDate = way.getTimestamp();
        }

        List<Long> tiles = new ArrayList<>();
        for (WayNode wayNode : way.getWayNodes()) {
            // get tileNrs for given node
            long tile = nmap.get(wayNode.getNodeId());

            // don't ignore missing nodes
            if (tile == 0) {
                if (verbose) {
                    LOGGER.log(Level.INFO, "way {0} missing node {1}", new Object[] { way.getId(), wayNode.getNodeId() });
                }
                return;
            }
            tiles.add(tile);
        }

        for (long tile : tiles) {
            // mark tiles (and possible neighbours) as modified
            if (modified) {
                int tx = nmap.tileX(tile);
                int ty = nmap.tileY(tile);
                int neighbour = nmap.neighbour(tile);
                setModifiedTiles(tx, ty, neighbour);
            }

            tileList.add(tile);
        }

        // TODO check/verify if 8 tiles is ok or if there might be corner-cases with only 4 tiles
        // with more than 8 (or 4?!) tiles in the list we might have a "hole"
        if (tileList.size() >= 8) {
            checkAndFill(tileList);
        }

        // bootstrap a tilepos for the way
        long id = way.getWayNodes().get(0).getNodeId();
        long val = nmap.get(id);
        int tx = nmap.tileX(val);
        int ty = nmap.tileY(val);

        // put way into map with a "random" base tile
        wmap.put(way.getId(), tx, ty, OsmMap.NEIGHBOURS_NONE);
        // update map so that the way knows which tiles it belongs to
        wmap.update(way.getId(), tileList);

        for (WayNode wayNode : way.getWayNodes()) {
            // update map so that the node knows about any additional
            // tile it has to be stored in
            nmap.update(wayNode.getNodeId(), tileList);
        }
    }

    /**
     * Iterate over the way nodes and add tileList to the list of tiles they are supposed to be in
     * 
     * @param way the Way we are processing
     * @param tileList the List of tiles
     */
    private void addExtraWayToMap(@NotNull Way way, @NotNull Collection<Long> tileList) {

        for (WayNode wayNode : way.getWayNodes()) {

            // update map so that the node knows about any additional
            // tile it has to be stored in
            nmap.update(wayNode.getNodeId(), tileList);
        }
    }

    /**
     * Add a Relation
     * 
     * @param r the Relation
     */
    private void addRelationToMap(@NotNull Relation r) {

        boolean modified = r.getTimestamp().after(appointmentDate);
        Collection<Long> tileList = new TreeSet<>();

        boolean nodeWarned = false; // suppress multiple warnings about missing Nodes
        boolean wayWarned = false; // suppress multiple warnings about missing Ways
        boolean relationWarned = false; // suppress multiple warnings about missing Relations

        if (r.getTimestamp().after(latestDate)) {
            latestDate = r.getTimestamp();
        }

        for (RelationMember m : r.getMembers()) {
            switch (m.getMemberType()) {
            case Node:
                long tile = nmap.get(m.getMemberId());

                // The referenced node is not in our data set
                if (tile == 0) {
                    if (verbose && !nodeWarned) {
                        LOGGER.log(Level.INFO, "Non-complete Relation {0} (missing a node)", r.getId());
                        nodeWarned = true;
                    }
                    continue;
                }

                // mark tiles as modified
                if (modified) {
                    int tx = nmap.tileX(tile);
                    int ty = nmap.tileY(tile);
                    int neighbour = nmap.neighbour(tile);
                    setModifiedTiles(tx, ty, neighbour);
                }

                tileList.add(tile);
                break;

            case Way:
                List<Integer> list = wmap.getAllTiles(m.getMemberId());

                // The referenced way is not in our data set
                if (list == null) {
                    if (verbose && !wayWarned) {
                        LOGGER.log(Level.INFO, "Non-complete Relation {0} (missing a way)", r.getId());
                        wayWarned = true;
                    }
                    continue;
                }

                if (modified) {
                    for (Integer i : list) {
                        modifiedTiles.set(i);
                    }
                }

                // TODO: make this a bit more generic / nicer code :/
                for (int i : list) {
                    tileList.add(((long) i) << HeapMap.TILE_Y_SHIFT);
                }
                break;

            case Relation:
                list = rmap.getAllTiles(m.getMemberId());

                // The referenced relation is not in our data set
                if (list == null) {
                    if (verbose && !relationWarned) {
                        LOGGER.log(Level.INFO, "Non-complete Relation {0} (missing a relation)", r.getId());
                        relationWarned = true;
                    }
                    postProcessRelations.add(r);
                    continue;
                }

                if (modified) {
                    for (Integer i : list) {
                        modifiedTiles.set(i);
                    }
                }

                for (int i : list) {
                    tileList.add(((long) i) << HeapMap.TILE_Y_SHIFT);
                }
                break;
            default:
                LOGGER.log(Level.WARNING, "Unknown member type {0}", m.getMemberType());
            }
        }

        // Just in case, this can happen due to silly input data :'(
        if (tileList.isEmpty()) {
            LOGGER.log(Level.WARNING, "Ignoring relation with no elements in tiles");
            return;
        }

        // no need to fill tile list here as that will have already happened for any element with geometry

        long val = tileList.iterator().next();
        int tx = rmap.tileX(val);
        int ty = rmap.tileY(val);

        // put relation into map with a "random" base tile
        rmap.put(r.getId(), tx, ty, OsmMap.NEIGHBOURS_NONE);
        // update map so that the relation knows in which tiles it is needed
        rmap.update(r.getId(), tileList);

        if (completeRelations || (completeAreas && hasTag(r, "type", "multipolygon"))) {
            // only add members to all the tiles if the appropriate option is enabled
            for (RelationMember m : r.getMembers()) {
                switch (m.getMemberType()) {
                case Node:
                    nmap.update(m.getMemberId(), tileList);
                    break;
                case Way:
                    wmap.update(m.getMemberId(), tileList);
                    extraWayMap.put(m.getMemberId(), tileList);
                    break;
                case Relation:
                    rmap.update(m.getMemberId(), tileList);
                    break;
                case Bound:
                    break;
                default:
                    LOGGER.log(Level.WARNING, "Unknown member type {0}", m.getMemberType());
                }
            }
        }
    }

    /**
     * Check if a Entity has a specific tag
     * 
     * @param e the Entity
     * @param key the tag key
     * @param value the tag value
     * @return true if the tag is present
     */
    private static boolean hasTag(@NotNull Entity e, @Nullable String key, @Nullable String value) {
        return e.getTags().stream().anyMatch(tag -> tag.getKey().equals(key) && tag.getValue().equals(value));
    }

    int nCount = 0;
    int wCount = 0;
    int rCount = 0;

    /**
     * Setup the OSM object to tiles mappings
     *
     * @param verbose verbose output if true
     * @param metadata we want to write metadata if true
     * @throws IOException if reading the input caused an issue
     * @throws InterruptedException if a Thread was interrupted
     */
    public void setup(final boolean verbose, final boolean metadata) throws IOException, InterruptedException {

        this.verbose = verbose;

        RunnableSource reader = new OsmosisReader(new FileInputStream(input));
        reader.setSink(new Sink() {
            @Override
            public void complete() {
                complete = true;
            }

            /**
             * Throw an exception if the metadata flag is set but we are reading data without any
             * 
             * @param e the OSM object to check
             */
            void checkMetadata(@NotNull Entity e) {
                if (metadata && (e.getVersion() == -1)) { // this doesn't seem to be really documented
                    throw new DataFormatException(String.format("%s %d is missing a valid version and metadata flag is set", e.getType(), e.getId()));
                }
            }

            @Override
            public void process(EntityContainer ec) {
                if (ec instanceof NodeContainer) {
                    Node n = ((NodeContainer) ec).getEntity();
                    checkMetadata(n);
                    addNodeToMap(n, n.getLatitude(), n.getLongitude());
                    if (verbose) {
                        nCount++;
                        if ((nCount % (nmap.getCapacity() / 20)) == 0) {
                            LOGGER.log(Level.INFO, "{0} nodes processed", nCount);
                        }
                    }
                } else if (ec instanceof WayContainer) {
                    Way w = ((WayContainer) ec).getEntity();
                    checkMetadata(w);
                    addWayToMap(w);
                    if (verbose) {
                        wCount++;
                        if ((wCount % (wmap.getCapacity() / 20)) == 0) {
                            LOGGER.log(Level.INFO, "{0} ways processed", wCount);
                        }
                    }
                } else if (ec instanceof RelationContainer) {
                    Relation r = ((RelationContainer) ec).getEntity();
                    checkMetadata(r);
                    addRelationToMap(r);
                    if (verbose) {
                        rCount++;
                        if ((rCount % (rmap.getCapacity() / 20)) == 0) {
                            LOGGER.log(Level.INFO, "{0} relations processed", rCount);
                        }
                    }
                } else if (ec instanceof BoundContainer) {
                    // nothing todo, we ignore bound tags
                } else {
                    LOGGER.log(Level.WARNING, "Unknown Element while reading");
                    LOGGER.log(Level.WARNING, ec.toString());
                    LOGGER.log(Level.WARNING, ec.getEntity().toString());
                }
            }

            @Override
            public void initialize(Map<String, Object> metaData) {
                // not used
            }

            @Override
            public void close() {
                // not used
            }
        });

        if (verbose) {
            LOGGER.log(Level.INFO, "Initial pass started");
        }
        Thread readerThread = new Thread(reader);
        readerThread.start();
        while (readerThread.isAlive()) {
            try {
                readerThread.join();
            } catch (InterruptedException e) { // NOSONAR
                LOGGER.log(Level.WARNING, "readerThread interupted {0}", e.getMessage());
                throw e;
            }
        }

        if (!complete) {
            throw new IOException("Could not read file fully");
        }

        if (verbose) {
            LOGGER.log(Level.INFO, "We have read:\n{0} nodes\n{1} ways\n{2} relations", new Object[] { nCount, wCount, rCount });
        }

        if (!postProcessRelations.isEmpty()) {
            int preSize = postProcessRelations.size();
            int postSize = preSize;
            if (verbose) {
                LOGGER.log(Level.INFO, "Post processing {0} relations with forward references", new Object[] { preSize });
            }
            do {
                preSize = postSize;
                List<Relation> temp = new ArrayList<>(postProcessRelations);
                postProcessRelations.clear();
                for (Relation r : temp) {
                    addRelationToMap(r);
                }
                postSize = postProcessRelations.size();
                if (verbose) {
                    LOGGER.log(Level.INFO, "{0} incomplete relations left", new Object[] { postSize });
                }
            } while (postSize < preSize);
        }

        // Second run if we are in complete-relation-mode
        if (extraWayMap != null) {

            complete = false;

            reader = new OsmosisReader(new FileInputStream(input));
            reader.setSink(new Sink() {
                @Override
                public void complete() {
                    complete = true;
                }

                @Override
                public void process(EntityContainer ec) {
                    if (ec instanceof WayContainer) {
                        Way w = ((WayContainer) ec).getEntity();
                        Collection<Long> tileList = extraWayMap.get(w.getId());
                        if (tileList != null) {
                            addExtraWayToMap(w, tileList);
                        }
                    }
                }

                @Override
                public void initialize(Map<String, Object> metaData) {
                    // not used
                }

                @Override
                public void close() {
                    // not used
                }
            });

            readerThread = new Thread(reader);
            readerThread.start();
            while (readerThread.isAlive()) {
                try {
                    readerThread.join();
                } catch (InterruptedException e) { // NOSONAR
                    LOGGER.log(Level.WARNING, "readerThread interupted {0}", e.getMessage());
                    throw e;
                }
            }

            if (!complete) { // NOSONAR
                throw new IOException("Could not read file fully in second run");
            }
        }
    }

    /**
     * Optimize the tile stack
     * 
     * @param nodeLimit the minimum number of Nodes a tile should contain
     * 
     */
    private void optimize(final int nodeLimit) {
        if (verbose) {
            LOGGER.log(Level.INFO, "Optimizing ...");
        }
        long statsStart = System.currentTimeMillis();
        // count Node tile use
        // at high zoom levels this will contains
        // lots of Nodes that are in more than
        // one tile
        Map<Integer, Integer> stats = new HashMap<>();
        Iterator<Long> it = nmap.keyIterator();
        while (it.hasNext()) {
            long k = it.next();
            List<Integer> tiles = nmap.getAllTiles(k);
            if (tiles != null) {
                for (Integer t : tiles) {
                    Integer count = stats.get(t);
                    if (count != null) {
                        count++;
                        stats.put(t, count);
                    } else {
                        stats.put(t, 1);
                    }
                }
            } else {
                LOGGER.log(Level.INFO, "tiles null for {0}", k);
            }
        }
        long nodeCount = 0;
        List<Integer> keys = new ArrayList<>(stats.keySet());
        Collections.sort(keys);
        for (Integer key : keys) {
            int value = stats.get(key);
            nodeCount += value;
            if (!zoomMap.containsKey(key)) { // not mapped
                if (value < nodeLimit) {
                    CountResult prevResult = null;
                    for (int z = 1; z < MAX_ZOOM_OUT_DIFF; z++) {
                        int newZoom = zoom - z;
                        CountResult result = getCounts(key, z, stats);
                        if (result.total < 4 * nodeLimit) {
                            if (result.total > nodeLimit || z == (MAX_ZOOM_OUT_DIFF - 1)) {
                                for (int i = 0; i < result.keys.length; i++) {
                                    if (result.counts[i] != null) {
                                        zoomMap.put(result.keys[i], (byte) newZoom);
                                    }
                                }
                                break; // found optimal size
                            }
                            prevResult = result; // store this and try next zoom
                        } else {
                            if (prevResult != null) {
                                for (int i = 0; i < prevResult.keys.length; i++) {
                                    if (prevResult.counts[i] != null) {
                                        zoomMap.put(prevResult.keys[i], (byte) (newZoom + 1));
                                    }
                                }
                            }
                            break; // last iteration was better
                        }
                    }
                }
            }
        }
        for (Entry<Integer, Byte> optimzedTile : zoomMap.entrySet()) {
            int idx = optimzedTile.getKey();
            int newTileZoom = optimzedTile.getValue();
            modifiedTiles.clear(idx);
            idx = mapToNewTile(idx, newTileZoom);
            UnsignedSparseBitSet tileSet = optimizedModifiedTiles.get(newTileZoom);
            if (tileSet == null) {
                tileSet = new UnsignedSparseBitSet();
                optimizedModifiedTiles.put(newTileZoom, tileSet);
            }
            tileSet.set(idx);
        }
        if (verbose) {
            LOGGER.log(Level.INFO, "Tiles {0} avg node count {1} merged tiles {2}", new Object[] { stats.size(), nodeCount / stats.size(), zoomMap.size() });
            LOGGER.log(Level.INFO, "Stats took {0} s", (System.currentTimeMillis() - statsStart) / 1000);
        }
    }

    class CountResult {
        int       total;
        int[]     keys;
        Integer[] counts;
    }

    /**
     * Get usage stats for zoomed out tiles
     * 
     * @param idx the original tile index
     * @param zoomDiff how many levels to zoom out
     * @param stats a map containing the per tile stats
     * @return a CountResult object
     */
    CountResult getCounts(int idx, int zoomDiff, @NotNull Map<Integer, Integer> stats) {
        // determine the counts for the other tiles in the zoomed out tile
        int x0 = ((idx >>> Const.MAX_ZOOM) >> zoomDiff) << zoomDiff;
        int y0 = ((idx & (int) Const.MAX_TILE_NUMBER) >> zoomDiff) << zoomDiff;
        int side = 2 << (zoomDiff - 1);
        int[] keys = new int[side * side];
        for (int i = 0; i < side; i++) {
            for (int j = 0; j < side; j++) {
                keys[i * side + j] = ((x0 + i) << Const.MAX_ZOOM) | (y0 + j);
            }
        }
        Integer[] counts = new Integer[keys.length];
        int total = 0;
        for (int i = 0; i < keys.length; i++) {
            counts[i] = stats.get(keys[i]);
            if (counts[i] != null) {
                total += counts[i];
            }
        }
        CountResult result = new CountResult();
        result.total = total;
        result.keys = keys;
        result.counts = counts;
        return result;
    }

    /**
     * Taking a packed tile id, return the tile it is in on a lower zoom level
     * 
     * @param idx the packed tile id
     * @param newTileZoom the new zoom level (less than the base zoom)
     * @return packed tile id at newTileZoom
     */
    private int mapToNewTile(int idx, int newTileZoom) {
        int xNew = (idx >>> Const.MAX_ZOOM) >> (zoom - newTileZoom);
        int yNew = (idx & (int) Const.MAX_TILE_NUMBER) >> (zoom - newTileZoom);
        return xNew << Const.MAX_ZOOM | yNew;
    }

    /**
     * Check if the coordinates are inside a polygon
     * 
     * @param x longitude
     * @param y latitude
     * @param polygon the polygon
     * @return true is inside
     */
    private boolean isInside(double x, double y, @NotNull double[] polygon) {
        boolean in = false;
        int lines = polygon.length / 2;

        for (int i = 0, j = lines - 1; i < lines; j = i++) {
            if (((polygon[2 * i + 1] > y) != (polygon[2 * j + 1] > y))
                    && (x < (polygon[2 * j] - polygon[2 * i]) * (y - polygon[2 * i + 1]) / (polygon[2 * j + 1] - polygon[2 * i + 1]) + polygon[2 * i])) {
                in = !in;
            }
        }
        return in;
    }

    /**
     * Check if the corners of a tile are inside a polygon
     * 
     * @param tx tile x number
     * @param ty tile y number
     * @param polygon the polygon
     * @return true if a corner is inside
     */
    private boolean isInside(int tx, int ty, @NotNull double[] polygon) {

        for (int u = 0; u < 2; u++) {
            for (int v = 0; v < 2; v++) {
                double x = tile2lon(tx + u);
                double y = tile2lat(ty + v);
                if (isInside(x, y, polygon)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Check if a tile intersects with / is covered by a polygon Note this only checks the corners of the tile so isn't
     * really correct and should be replaced by a suitable correct algorithm
     * 
     * @param tx tile x number
     * @param ty tile y number
     * @param inside outer rings (the tile should be "inside")
     * @param outside inner rings (the tile should be "outside")
     * @return true if the tile intersects / is covered by the polygon
     */
    private boolean isInside(int tx, int ty, @NotNull List<double[]> inside, @NotNull List<double[]> outside) {

        boolean in = false;
        for (double[] polygon : inside) {
            in = isInside(tx, ty, polygon);
            if (in) {
                break;
            }
        }

        if (!in) {
            return false;
        }

        for (double[] polygon : outside) {
            if (isInside(tx, ty, polygon)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Remove all tiles that are not in the provided polygon
     * 
     * @param polygonFile the path for a file containing the polygon
     * @throws IOException if reading fails
     */
    public void clipPoly(@NotNull String polygonFile) throws IOException {

        List<double[]> inside = new ArrayList<>();
        List<double[]> outside = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(polygonFile)));) {
            /* String name = */ br.readLine(); // unused.. NOSONAR

            String poly = br.readLine();
            while (!"END".equals(poly)) {

                int pos = 0;
                int size = 128;
                double[] data = new double[2 * size];

                String coords = br.readLine();
                while (!"END".equals(coords)) {

                    coords = coords.trim();
                    int idx = coords.indexOf(' ');
                    double lon = Double.parseDouble(coords.substring(0, idx));
                    double lat = Double.parseDouble(coords.substring(idx + 1));

                    // check if there's enough space to store
                    if (pos >= size) {
                        double[] tmp = new double[4 * size];
                        System.arraycopy(data, 0, tmp, 0, 2 * size);
                        size *= 2;
                        data = tmp;
                    }

                    // store data
                    data[2 * pos] = lon;
                    data[2 * pos + 1] = lat;
                    pos++;

                    coords = br.readLine();
                }

                if (pos != size) {
                    double[] tmp = new double[2 * pos];
                    System.arraycopy(data, 0, tmp, 0, 2 * pos);
                    data = tmp;
                }

                if (poly.startsWith("!")) {
                    outside.add(data);
                } else {
                    inside.add(data);
                }

                // read next polygon, if there's any
                poly = br.readLine();
            }
        }
        // now walk modifiedTiles and clear bits that are not inside polygon
        int idx = 0;
        while (true) {
            idx = modifiedTiles.nextSetBit(UnsignedSparseBitSet.inc(idx));
            if (idx == -1) {
                break;
            }

            int tx = idx >>> Const.MAX_ZOOM;
            int ty = (int) (idx & Const.MAX_TILE_NUMBER);

            boolean in = isInside(tx, ty, inside, outside);

            if (!in) {
                modifiedTiles.clear(idx);
            }
        }
    }

    /**
     * Read the input file, process the OSM elements and write them out
     * 
     * @param basename the basename for individual tile files or the name of a MBTiles format sqlite database
     * @param metadata write metadata (version, timestamp, etc)
     * @param verbose verbose output if true
     * @param mbTiles write to a MBTiles format sqlite database instead of writing individual tiles
     * @throws IOException if reading or creating the files has an issue
     * @throws InterruptedException if one of the Threads was interrupted
     */
    public void store(@NotNull String basename, boolean metadata, boolean verbose, boolean mbTiles) throws IOException, InterruptedException {

        MBTilesWriter w = null;
        if (mbTiles) {
            try {
                w = new MBTilesWriter(new File(basename));
                w.getConnection().setAutoCommit(false);
            } catch (MBTilesWriteException | SQLException e1) {
                throw new IOException(e1);
            }
        }
        Bound bounds = null;
        int minZoom = zoom;
        for (Entry<Integer, UnsignedSparseBitSet> omt : optimizedModifiedTiles.entrySet()) {
            final UnsignedSparseBitSet tileSet = omt.getValue();
            final int currentZoom = omt.getKey();
            if (currentZoom < minZoom) {
                minZoom = currentZoom;
            }
            int ymax = 1 << currentZoom; // for conversion to TMS schema
            if (verbose) {
                LOGGER.log(Level.INFO, "Processing {0} tiles for zoom {1}", new Object[] { tileSet.cardinality(), currentZoom });
            }

            int idx = 0;
            // We might call this code several times if we have more tiles
            // to store than open files allowed
            while (true) {

                complete = false;
                outFiles = new HashMap<>();
                if (mbTiles) {
                    outBlobs = new HashMap<>();
                }

                // Setup out-files...
                int count = 0;
                while (true) {
                    idx = tileSet.nextSetBit(UnsignedSparseBitSet.inc(idx));
                    if (idx == -1) {
                        // created all tiles for this zoom level
                        break;
                    }

                    if (outFiles.get(idx) == null) {

                        int tileX = idx >>> Const.MAX_ZOOM;
                        int tileY = (int) (idx & Const.MAX_TILE_NUMBER);

                        OutputStream target = null;
                        if (mbTiles) {
                            target = new ByteArrayOutputStream();
                        } else {
                            String file;
                            if (basename.contains("%x") && basename.contains("%y")) {
                                file = basename.replace("%x", Integer.toString(tileX)).replace("%y", Integer.toString(tileY)).replace("%z",
                                        Integer.toString(currentZoom));
                                if (!file.endsWith(PBF_EXT)) {
                                    file = file + PBF_EXT;
                                }
                            } else {
                                file = basename + currentZoom + "/" + tileX + "_" + tileY + PBF_EXT;
                            }
                            File outputFile = new File(file);
                            File parent = outputFile.getParentFile();
                            parent.mkdirs();
                            target = new FileOutputStream(file);
                        }

                        OsmosisSerializer serializer = new OsmosisSerializer(new BlockOutputStream(target));

                        serializer.setUseDense(true);
                        serializer.configOmit(!metadata);

                        // write out the bound for that tile
                        Bound bound = getBound(tileX, tileY);
                        BoundContainer bc = new BoundContainer(bound);
                        serializer.process(bc);

                        outFiles.put(idx, serializer);

                        if (mbTiles) {
                            outBlobs.put(idx, (ByteArrayOutputStream) target);
                        }
                    }

                    if ((maxFiles != -1) && (++count >= maxFiles)) {
                        break;
                    }
                }

                // Now start writing output...

                RunnableSource reader = new OsmosisReader(new FileInputStream(input));

                class BoundSink implements Sink {

                    Bound        overallBounds = null;
                    Set<Integer> mappedTiles   = new HashSet<>();

                    /**
                     * Get the overall bounds of the data
                     * 
                     * @return a Bound object or null
                     */
                    Bound getBounds() {
                        return overallBounds;
                    }

                    @Override
                    public void complete() {
                        complete = true;
                    }

                    @Override
                    public void process(EntityContainer ec) {
                        long id = ec.getEntity().getId();

                        List<Integer> tiles;

                        if (ec instanceof NodeContainer) {
                            tiles = nmap.getAllTiles(id);
                        } else if (ec instanceof WayContainer) {
                            tiles = wmap.getAllTiles(id);
                        } else if (ec instanceof RelationContainer) {
                            tiles = rmap.getAllTiles(id);
                        } else if (ec instanceof BoundContainer) {
                            Bound bounds = ((BoundContainer) ec).getEntity();
                            if (overallBounds == null) {
                                overallBounds = bounds;
                            } else {
                                overallBounds.union(bounds);
                            }
                            return;
                        } else {
                            LOGGER.log(Level.WARNING, "Unknown Element while reading");
                            LOGGER.log(Level.WARNING, "{0}", ec);
                            LOGGER.log(Level.WARNING, "{0}", ec.getEntity());
                            return;
                        }

                        if (tiles == null) {
                            // No tile where we could store the given entity into
                            // This probably is a degenerated relation ;)
                            return;
                        }

                        mappedTiles.clear();
                        for (int i : tiles) {
                            // map original zoom tiles to optimized ones
                            // and remove duplicates
                            Byte newZoom = zoomMap.get(i);
                            if (newZoom != null) {
                                i = mapToNewTile(i, newZoom);
                            } else {
                                newZoom = (byte) zoom;
                            }
                            if (currentZoom == newZoom) {
                                mappedTiles.add(i);
                            }
                        }

                        for (int i : mappedTiles) {
                            if (tileSet.get(i)) {
                                OsmosisSerializer ser = outFiles.get(i);
                                if (ser != null) {
                                    ser.process(ec);
                                }
                            }
                        }
                    }

                    @Override
                    public void initialize(Map<String, Object> metaData) {
                        // do nothing
                    }

                    @Override
                    public void close() {
                        // do nothing
                    }
                }

                BoundSink sink = new BoundSink();
                reader.setSink(sink);

                Thread readerThread = new Thread(reader);
                readerThread.start();
                while (readerThread.isAlive()) {
                    try {
                        readerThread.join();
                    } catch (InterruptedException e) { // NOSONAR
                        LOGGER.log(Level.WARNING, "readerThread interupted " + e.getMessage());
                        throw e;
                    }
                }

                if (!complete) {
                    throw new IOException("Could not fully read file in storing run");
                }

                // Finish and close files...
                for (Entry<Integer, OsmosisSerializer> entry : outFiles.entrySet()) {
                    OsmosisSerializer ser = entry.getValue();
                    ser.complete();
                    ser.flush();
                    ser.close();
                    if (mbTiles) {
                        int tileX = entry.getKey() >>> Const.MAX_ZOOM;
                        int tileY = (int) (entry.getKey() & Const.MAX_TILE_NUMBER);
                        int y = ymax - tileY - 1; // TMS scheme
                        ByteArrayOutputStream blob = outBlobs.get(entry.getKey());
                        try {
                            w.addTile(blob.toByteArray(), currentZoom, tileX, y);
                        } catch (MBTilesWriteException e) { // NOSONAR
                            LOGGER.log(Level.WARNING, "{0} z:{1} x:{2} y:{3}", new Object[] { e.getMessage(), currentZoom, tileX, tileY });
                            throw new IOException(e);
                        }
                    }
                }

                if (verbose) {
                    LOGGER.log(Level.INFO, "Wrote {0} tiles, continuing with next block of tiles", outFiles.size());
                }
                // remove mappings form this pass
                outFiles.clear();
                if (mbTiles) {
                    outBlobs.clear();
                }
                if (idx == -1) {
                    // written all tiles for this zoom level
                    bounds = sink.getBounds();
                    break;
                }
            }
        }

        // Add MBTiles metadata parts
        if (mbTiles) {
            MetadataEntry ent = new MetadataEntry();
            File file = new File(basename);
            ent.setTilesetName(file.getName()).setTilesetType(MetadataEntry.TileSetType.BASE_LAYER).setTilesetVersion(Const.MBT_VERSION)
                    .setAttribution(Const.OSM_ATTRIBUTION).addCustomKeyValue("format", Const.MSF_MIME_TYPE)
                    .addCustomKeyValue("minzoom", Integer.toString(minZoom)).addCustomKeyValue("maxzoom", Integer.toString(zoom))
                    .addCustomKeyValue("latest_date", Long.toString(latestDate.getTime()));
            if (bounds != null) {
                ent.setTilesetBounds(bounds.getLeft(), bounds.getBottom(), bounds.getRight(), bounds.getTop());
            } else {
                ent.setTilesetBounds(-180, -85, 180, 85);
            }
            try {
                w.addMetadataEntry(ent);
                w.getConnection().commit();
            } catch (MBTilesWriteException | SQLException e) { // NOSONAR
                throw new IOException(e);
            }
            w.close();
        }
    }

    /**
     * Set up options from the command line and run the tiler
     * 
     * @param zoom zoom level to generate tiles for
     * @param inputFile the input PBF file
     * @param outputBase base filename
     * @param polygonFile a file containing a coverage polygon
     * @param mapSizes sizes of the maps for OSM objects
     * @param maxFiles maximum files/tiles to have open at once
     * @param border grow tiles (content) by this factor
     * @param appointmentDate only add changes from after this date (doesn't really work)
     * @param metadata include metadata (timestamp etc)
     * @param verbose verbose output
     * @param timing output timing information
     * @param completeRelations include all relation member objects in every tile the relation is present in (very
     *            expensive)
     * @param completeAreas include all multipolygon relation member objects in every tile the relation is present in
     * @param mbTiles generate a MBTiles format SQLite file instead of individual tiles
     * @param nodeLimit if > 0 optimize tiles so that they contain at least nodeLimit Nodes
     * @return the "last changed" date
     * @throws InterruptedException if one of the Threads was interrupted
     * @throws IOException if IO went wrong
     */
    private static Date run(int zoom, @NotNull String inputFile, @NotNull String outputBase, @Nullable String polygonFile, long[] mapSizes, int maxFiles,
            double border, Date appointmentDate, boolean metadata, boolean verbose, boolean timing, boolean completeRelations, boolean completeAreas,
            boolean mbTiles, int nodeLimit) throws IOException, InterruptedException {

        long startup = System.currentTimeMillis();

        MapSplit split = new MapSplit(zoom, appointmentDate, mapSizes, maxFiles, border, new File(inputFile), completeRelations, completeAreas);

        long time = System.currentTimeMillis();
        split.setup(verbose, metadata);
        time = System.currentTimeMillis() - time;

        double nratio = split.nmap.getMissHitRatio();
        double wratio = split.wmap.getMissHitRatio();
        double rratio = split.rmap.getMissHitRatio();

        if (polygonFile != null) {
            if (verbose) {
                LOGGER.log(Level.INFO, "Clip tiles with polygon given by \"{0}\"", polygonFile);
            }
            split.clipPoly(polygonFile);
        }

        long modified = split.modifiedTiles.cardinality();

        if (timing) {
            LOGGER.log(Level.INFO, "Initial reading and datastructure setup took {0} ms", time);
        }
        if (verbose) {
            LOGGER.log(Level.INFO, "We have {0} modified tiles to store.", modified);
        }

        if (nodeLimit > 0) {
            split.optimize(nodeLimit);
        }

        time = System.currentTimeMillis();
        split.store(outputBase, metadata, verbose, mbTiles);
        time = System.currentTimeMillis() - time;
        if (timing) {
            LOGGER.log(Level.INFO, "Saving tiles took {0} ms", time);
            long overall = System.currentTimeMillis() - startup;
            LOGGER.log(Level.INFO, "Overall runtime: {0} ms", overall);
            LOGGER.log(Level.INFO, " == {0} min", (overall / 1000 / 60));
        }

        if (verbose) {
            LOGGER.log(Level.INFO, "HeapMap's load:");
            LOGGER.log(Level.INFO, "Nodes    : {0}", split.nmap.getLoad());
            LOGGER.log(Level.INFO, "Ways     : {0}", split.wmap.getLoad());
            LOGGER.log(Level.INFO, "Relations: {0}", split.rmap.getLoad());
            LOGGER.log(Level.INFO, "HeapMap's MissHitRatio:");
            LOGGER.log(Level.INFO, "Nodes    : {0}", nratio);
            LOGGER.log(Level.INFO, "Ways     : {0}", wratio);
            LOGGER.log(Level.INFO, "Relations: {0}", rratio);
        }

        return split.latestDate;
    }

    /**
     * Main class (what else?)
     * 
     * @param args command line arguments
     * @throws IOException if IO failed
     * @throws InterruptedException if a Thread was interrupted
     */
    public static void main(String[] args) throws IOException, InterruptedException {

        Date appointmentDate;
        String inputFile = null;
        String outputBase = "";
        String polygonFile = null;
        boolean verbose = false;
        boolean timing = false;
        boolean metadata = false;
        boolean completeRelations = false;
        boolean completeAreas = false;
        boolean mbTiles = false;
        String dateFile = null;
        long[] mapSizes = new long[] { Const.NODE_MAP_SIZE, Const.WAY_MAP_SIZE, Const.RELATION_MAP_SIZE };
        int maxFiles = -1;
        double border = 0.0;
        int zoom = 13;
        int nodeLimit = 0;

        // set up logging
        LogManager.getLogManager().reset();
        setupLogging(LOGGER);

        // arguments
        Option helpOption = Option.builder("h").longOpt("help").desc("this help").build();
        Option verboseOption = Option.builder("v").longOpt("verbose").desc("verbose information during processing").build();
        Option timingOption = Option.builder("t").longOpt("timing").desc("output timing information").build();
        Option metadataOption = Option.builder("m").longOpt("metadata")
                .desc("store metadata in the tiles (version, timestamp), if the input file is missing the metadata abort").build();
        Option completeRelationOption = Option.builder("c").longOpt("complete").desc("store complete data for all relations").build();
        Option completeAreaOption = Option.builder("C").longOpt("complete-areas").desc("store complete data for multi polygons").build();
        Option mbTilesOption = Option.builder("M").longOpt("mbtiles").desc("store in a MBTiles format sqlite database").build();
        Option maxFilesOption = Option.builder("f").longOpt("maxfiles").hasArg().desc("maximum number of open files at a time").build();
        Option borderOption = Option.builder("b").longOpt("border").hasArg()
                .desc("enlarge tiles by val ([0-1]) of the tile's size to get a border around the tile.").build();
        Option polygonOption = Option.builder("p").longOpt("polygon").hasArg().desc("only save tiles that intersect or lie within the given polygon file.")
                .build();
        Option dateOption = Option.builder("d").longOpt("date").hasArg().desc(
                "file containing the date since when tiles are being considered to have changed after the split the latest change in infile is going to be stored in file")
                .build();
        Option sizeOption = Option.builder("s").longOpt("size").hasArg().desc(
                "n,w,r the initial size for the node-, way- and relation maps to use (should be at least twice the number of IDs). If not supplied, defaults will be taken.")
                .build();
        Option inputOption = Option.builder("i").longOpt("input").hasArgs().desc("a file in OSM pbf format").required().build();
        Option outputOption = Option.builder("o").longOpt("output").hasArg().desc(
                "if creating a MBTiles files this is the name of the file, otherwise this is the base name of all tiles that will be written. The filename may contain '%x' and '%y' which will be replaced with the tilenumbers")
                .required().build();
        Option zoomOption = Option.builder("z").longOpt("zoom").hasArg()
                .desc("zoom level to create the tiles at must be between 0 and 16 (inclusive), default is 13").build();

        Option optimizeOption = Option.builder("O").longOpt("optimize").hasArg()
                .desc("optimize the tile stack, agrument is minimum number of Nodes a tile should contain, default is to not optimize").build();

        Options options = new Options();

        options.addOption(helpOption);
        options.addOption(verboseOption);
        options.addOption(timingOption);
        options.addOption(metadataOption);
        options.addOption(completeRelationOption);
        options.addOption(completeAreaOption);
        options.addOption(mbTilesOption);
        options.addOption(maxFilesOption);
        options.addOption(borderOption);
        options.addOption(polygonOption);
        options.addOption(dateOption);
        options.addOption(sizeOption);
        options.addOption(inputOption);
        options.addOption(outputOption);
        options.addOption(zoomOption);
        options.addOption(optimizeOption);

        CommandLineParser parser = new DefaultParser();
        try {
            // parse the command line arguments
            CommandLine line = parser.parse(options, args);
            if (line.hasOption("h")) {
                HelpFormatter formatter = new HelpFormatter();
                formatter.printHelp(MAPSPLIT_TAG, options);
                return;
            }
            if (line.hasOption("v")) {
                verbose = true;
            }
            if (line.hasOption("t")) {
                timing = true;
            }
            if (line.hasOption("m")) {
                metadata = true;
            }
            if (line.hasOption("c")) {
                completeRelations = true;
            }
            if (line.hasOption("C")) {
                completeAreas = true;
            }
            if (line.hasOption("M")) {
                mbTiles = true;
            }
            if (line.hasOption("f")) {
                String tmp = line.getOptionValue("maxfiles");
                maxFiles = Integer.valueOf(tmp);
            }
            if (line.hasOption("d")) {
                dateFile = line.getOptionValue("date");
            }
            if (line.hasOption("p")) {
                polygonFile = line.getOptionValue("polygon");
            }
            if (line.hasOption("s")) {
                String tmp = line.getOptionValue("size");
                String[] vals = tmp.split(",");
                for (int j = 0; j < 3; j++) {
                    mapSizes[j] = Long.valueOf(vals[j]);
                }
            }
            if (line.hasOption("b")) {
                String tmp = line.getOptionValue("border");
                try {
                    border = Double.valueOf(tmp);
                    if (border < 0) {
                        border = 0;
                    }
                    if (border > 1) {
                        border = 1;
                    }
                } catch (NumberFormatException e) {
                    LOGGER.log(Level.WARNING, "Could not parse border parameter, falling back to defaults");
                }
            }
            if (line.hasOption("i")) {
                inputFile = line.getOptionValue("input");
            }
            if (line.hasOption("o")) {
                outputBase = line.getOptionValue("output");
            }
            if (line.hasOption("z")) {
                String tmp = line.getOptionValue("zoom");
                zoom = Integer.valueOf(tmp);
            }
            if (line.hasOption("O")) {
                String tmp = line.getOptionValue("optimize");
                nodeLimit = Integer.valueOf(tmp);
            }
        } catch (ParseException | NumberFormatException exp) {
            LOGGER.log(Level.WARNING, exp.getMessage());
            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp(MAPSPLIT_TAG, options);
            return;
        }

        // Date-setup as fall-back option
        DateFormat df = DateFormat.getDateTimeInstance();
        appointmentDate = new Date(0);

        if (dateFile == null && verbose) {
            LOGGER.log(Level.INFO, "No datefile given. Writing all available tiles.");
        } else if (dateFile != null) {

            File file = new File(dateFile);

            if (file.exists()) {
                try (DataInputStream dis = new DataInputStream(new FileInputStream(file))) {
                    String line = dis.readUTF();

                    if (line != null) {
                        try {
                            appointmentDate = df.parse(line);
                        } catch (java.text.ParseException pe) {
                            if (verbose) {
                                LOGGER.log(Level.INFO, "Could not parse datefile.");
                            }
                        }
                    }
                }
            } else if (verbose) {
                LOGGER.log(Level.INFO, "Datefile does not exist, writing all tiles");
            }
        }

        if (verbose) {
            LOGGER.log(Level.INFO, "Reading: {0}", inputFile);
            LOGGER.log(Level.INFO, "Writing: {0}", outputBase);
        }

        // Actually run the splitter...
        Date latest = run(zoom, inputFile, outputBase, polygonFile, mapSizes, maxFiles, border, appointmentDate, metadata, verbose, timing, completeRelations, // NOSONAR
                completeAreas, mbTiles, nodeLimit);
        if (verbose) {
            LOGGER.log(Level.INFO, "Last changes to the map had been done on {0}", df.format(latest));
        }
        if (dateFile != null) {
            try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(dateFile));) {
                dos.writeUTF(df.format(latest));
            }
        }
    }

    /**
     * Set up logging
     * 
     * @param the Logger to use
     */
    public static void setupLogging(@NotNull Logger logger) {
        SimpleFormatter fmt = new SimpleFormatter();
        Handler stdoutHandler = new FlushStreamHandler(System.out, fmt); // NOSONAR
        stdoutHandler.setLevel(Level.INFO);
        LOGGER.addHandler(stdoutHandler);
        Handler stderrHandler = new FlushStreamHandler(System.err, fmt); // NOSONAR
        stderrHandler.setLevel(Level.WARNING);
        LOGGER.addHandler(stderrHandler);
    }
}
