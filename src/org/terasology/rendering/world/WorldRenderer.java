/*
 * Copyright 2011 Benjamin Glatzel <benjamin.glatzel@me.com>.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terasology.rendering.world;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.terasology.entitySystem.EntityManager;
import org.terasology.game.Terasology;
import org.terasology.logic.characters.Player;
import org.terasology.logic.entities.Entity;
import org.terasology.logic.generators.ChunkGeneratorTerrain;
import org.terasology.logic.manager.*;
import org.terasology.logic.systems.MeshRenderer;
import org.terasology.logic.world.*;
import org.terasology.math.TeraMath;
import org.terasology.model.blocks.management.BlockManager;
import org.terasology.model.structures.AABB;
import org.terasology.performanceMonitor.PerformanceMonitor;
import org.terasology.rendering.interfaces.IGameObject;
import org.terasology.rendering.particles.BlockParticleEmitter;
import org.terasology.rendering.physics.BulletPhysicsRenderer;
import org.terasology.rendering.primitives.ChunkMesh;

import javax.imageio.ImageIO;
import javax.vecmath.Vector3d;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Level;

import static org.lwjgl.opengl.GL11.*;

/**
 * The world of Terasology. At its most basic the world contains chunks (consisting of a fixed amount of blocks)
 * and the player.
 * <p/>
 * The world is randomly generated by using a bunch of Perlin noise generators initialized
 * with a favored seed value.
 *
 * @author Benjamin Glatzel <benjamin.glatzel@me.com>
 */
public final class WorldRenderer implements IGameObject {

    /* WORLD PROVIDER */
    private final IWorldProvider _worldProvider;

    /* PLAYER */
    private Player _player;

    /* CHUNKS */
    private final ArrayList<Chunk> _chunksInProximity = new ArrayList<Chunk>();
    private int _chunkPosX, _chunkPosZ;

    /* RENDERING */
    private final LinkedList<Chunk> _renderQueueChunksOpaque = new LinkedList<Chunk>();
    private final PriorityQueue<Chunk> _renderQueueChunksSortedWater = new PriorityQueue<Chunk>();
    private final PriorityQueue<Chunk> _renderQueueChunksSortedBillboards = new PriorityQueue<Chunk>();
    private final LinkedList<IGameObject> _renderQueueOpaque = new LinkedList<IGameObject>();
    private final LinkedList<IGameObject> _renderQueueTransparent = new LinkedList<IGameObject>();

    /* CORE GAME OBJECTS */
    private final PortalManager _portalManager;
    private final MobManager _mobManager;
    private final MeshRenderer _entityRendererSystem;;

    /* PARTICLE EMITTERS */
    private final BlockParticleEmitter _blockParticleEmitter = new BlockParticleEmitter(this);

    /* HORIZON */
    private final Skysphere _skysphere;

    /* TICKING */
    private int _tick = 0;
    private int _tickTock = 0;
    private long _lastTick;

    /* UPDATING */
    private final ChunkUpdateManager _chunkUpdateManager;

    /* EVENTS */
    private final WorldTimeEventManager _worldTimeEventManager;

    /* PHYSICS */
    private final BulletPhysicsRenderer _bulletRenderer;

    /* BLOCK GRID */
    private final BlockGrid _blockGrid;

    /* STATISTICS */
    private int _statDirtyChunks = 0, _statVisibleChunks = 0, _statIgnoredPhases = 0;

    /* OTHER SETTINGS */
    private boolean _wireframe;

    /**
     * Initializes a new (local) world for the single player mode.
     *
     * @param title The title/description of the world
     * @param seed  The seed string used to generate the terrain
     */
    public WorldRenderer(String title, String seed, EntityManager manager) {
        _worldProvider = new LocalWorldProvider(title, seed);
        _skysphere = new Skysphere(this);
        _chunkUpdateManager = new ChunkUpdateManager();
        _worldTimeEventManager = new WorldTimeEventManager(_worldProvider);
        _portalManager = new PortalManager(this);
        _mobManager = new MobManager(this);
        _blockGrid = new BlockGrid();
        _bulletRenderer = new BulletPhysicsRenderer(this);
        _entityRendererSystem = new MeshRenderer(manager);

        initTimeEvents();
    }

    /**
     * Updates the list of chunks around the player.
     *
     * @param force Forces the update
     * @return True if the list was changed
     */
    public boolean updateChunksInProximity(boolean force) {
        int newChunkPosX = calcPlayerChunkOffsetX();
        int newChunkPosZ = calcPlayerChunkOffsetZ();

        int viewingDistance = Config.getInstance().getActiveViewingDistance();

        if (_chunkPosX != newChunkPosX || _chunkPosZ != newChunkPosZ || force) {

            _chunksInProximity.clear();

            for (int x = -(viewingDistance / 2); x < (viewingDistance / 2); x++) {
                for (int z = -(viewingDistance / 2); z < (viewingDistance / 2); z++) {
                    Chunk c = _worldProvider.getChunkProvider().loadOrCreateChunk(calcPlayerChunkOffsetX() + x, calcPlayerChunkOffsetZ() + z);
                    _chunksInProximity.add(c);
                }
            }

            _chunkPosX = newChunkPosX;
            _chunkPosZ = newChunkPosZ;

            Collections.sort(_chunksInProximity);
            return true;
        }

        return false;
    }

    public boolean isInRange(Vector3d pos) {
        Vector3d dist = new Vector3d();
        dist.sub(_player.getPosition(), pos);

        double distLength = dist.length();

        return distLength < (Config.getInstance().getActiveViewingDistance() * 8);
    }

    /**
     * Creates the world time events to play the game's soundtrack at specific times.
     */
    public void initTimeEvents() {
        // SUNRISE
        _worldTimeEventManager.addWorldTimeEvent(new WorldTimeEvent(0.1, true) {
            @Override
            public void run() {
                AudioManager.playMusic("Sunrise");
            }
        });

        // AFTERNOON
        _worldTimeEventManager.addWorldTimeEvent(new WorldTimeEvent(0.25, true) {
            @Override
            public void run() {
                AudioManager.playMusic("Afternoon");
            }
        });

        // SUNSET
        _worldTimeEventManager.addWorldTimeEvent(new WorldTimeEvent(0.4, true) {
            @Override
            public void run() {
                AudioManager.playMusic("Sunset");
            }
        });

        // NIGHT
        _worldTimeEventManager.addWorldTimeEvent(new WorldTimeEvent(0.6, true) {
            @Override
            public void run() {
                AudioManager.playMusic("Dimlight");
            }
        });

        // NIGHT
        _worldTimeEventManager.addWorldTimeEvent(new WorldTimeEvent(0.75, true) {
            @Override
            public void run() {
                AudioManager.playMusic("OtherSide");
            }
        });

        // BEFORE SUNRISE
        _worldTimeEventManager.addWorldTimeEvent(new WorldTimeEvent(0.9, true) {
            @Override
            public void run() {
                AudioManager.playMusic("Resurface");
            }
        });
    }

    /**
     * Updates the currently visible chunks (in sight of the player).
     */
    public void updateAndQueueVisibleChunks() {
        _statDirtyChunks = 0;
        _statVisibleChunks = 0;
        _statIgnoredPhases = 0;

        boolean noMoreUpdates = false;
        for (int i = 0; i < _chunksInProximity.size(); i++) {
            Chunk c = _chunksInProximity.get(i);

            if (isChunkVisible(c)) {
                if (c.triangleCount(ChunkMesh.RENDER_PHASE.OPAQUE) > 0)
                    _renderQueueChunksOpaque.add(c);
                else
                    _statIgnoredPhases++;

                if (c.triangleCount(ChunkMesh.RENDER_PHASE.WATER_AND_ICE) > 0)
                    _renderQueueChunksSortedWater.add(c);
                else
                    _statIgnoredPhases++;

                if (c.triangleCount(ChunkMesh.RENDER_PHASE.BILLBOARD_AND_TRANSLUCENT) > 0)
                    _renderQueueChunksSortedBillboards.add(c);
                else
                    _statIgnoredPhases++;

                c.update();

                if (c.isDirty())
                    _statDirtyChunks++;

                if ((c.isDirty() || c.isLightDirty() || c.isFresh()) && !noMoreUpdates) {
                    if (!_chunkUpdateManager.queueChunkUpdate(c, ChunkUpdateManager.UPDATE_TYPE.DEFAULT)) {
                        noMoreUpdates = true;
                    }
                }
            } else if (i > Config.getInstance().getMaxChunkVBOs()) {
                // Make sure not too many chunk VBOs are available in the video memory at the same time
                // Otherwise VBOs are moved into system memory which is REALLY slow and causes lag
                c.clearMeshes();
            }
        }
    }

    private void queueRenderer() {
        PerformanceMonitor.startActivity("Update and Queue Chunks");
        updateAndQueueVisibleChunks();
        PerformanceMonitor.endActivity();

        _renderQueueTransparent.add(_bulletRenderer);
        _renderQueueTransparent.add(_mobManager);
        _renderQueueTransparent.add(_blockParticleEmitter);
        _renderQueueTransparent.add(_blockGrid);
    }

    /**
     * Renders the world.
     */
    public void render() {
        /* QUEUE RENDERER */
        queueRenderer();

        PostProcessingRenderer.getInstance().beginRenderScene();

        /* SKYSPHERE */
        PerformanceMonitor.startActivity("Render Sky");
        _player.getActiveCamera().lookThroughNormalized();
        _skysphere.render();
        PerformanceMonitor.endActivity();

        /* WORLD RENDERING */
        PerformanceMonitor.startActivity("Render World");
        _player.getActiveCamera().lookThrough();
        _player.render();

        // Render all chunks and entities
        Chunk.resetStats();

        glEnable(GL_LIGHT0);

        boolean headUnderWater = _player.isHeadUnderWater();

        if (_wireframe)
            glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);

        PerformanceMonitor.startActivity("RenderOpaque");

        while (_renderQueueOpaque.size() > 0)
            _renderQueueOpaque.poll().render();

        PerformanceMonitor.endActivity();

        PerformanceMonitor.startActivity("Render ChunkOpaque");

        /*
         * FIRST RENDER PASS: OPAQUE ELEMENTS
         */
        while (_renderQueueChunksOpaque.size() > 0)
            _renderQueueChunksOpaque.poll().render(ChunkMesh.RENDER_PHASE.OPAQUE);

        PerformanceMonitor.endActivity();

        PerformanceMonitor.startActivity("Render ChunkTransparent");

        /*
         * SECOND RENDER PASS: BILLBOARDS
         */
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        while (_renderQueueChunksSortedBillboards.size() > 0)
            _renderQueueChunksSortedBillboards.poll().render(ChunkMesh.RENDER_PHASE.BILLBOARD_AND_TRANSLUCENT);

        PerformanceMonitor.endActivity();

        PerformanceMonitor.startActivity("Render Transparent");

        while (_renderQueueTransparent.size() > 0)
            _renderQueueTransparent.poll().render();
        _entityRendererSystem.render();

        PerformanceMonitor.endActivity();


        PerformanceMonitor.startActivity("Render ChunkWaterIce");

        // Make sure the water surface is rendered if the player is swimming
        if (headUnderWater) {
            glDisable(GL11.GL_CULL_FACE);
        }

        /*
        * THIRD (AND FOURTH) RENDER PASS: WATER AND ICE
        */
        while (_renderQueueChunksSortedWater.size() > 0) {
            Chunk c = _renderQueueChunksSortedWater.poll();

            for (int j = 0; j < 2; j++) {

                if (j == 0) {
                    glColorMask(false, false, false, false);
                    c.render(ChunkMesh.RENDER_PHASE.WATER_AND_ICE);
                } else {
                    glColorMask(true, true, true, true);
                    c.render(ChunkMesh.RENDER_PHASE.WATER_AND_ICE);
                }
            }
        }

        /* EXTRACTION OVERLAY */
        _player.renderExtractionOverlay();

        glDisable(GL_BLEND);

        if (headUnderWater)
            glEnable(GL11.GL_CULL_FACE);

        if (_wireframe)
            glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);

        glDisable(GL_LIGHT0);

        PerformanceMonitor.endActivity();

        PostProcessingRenderer.getInstance().endRenderScene();

        /* RENDER THE FINAL POST-PROCESSED SCENE */
        PerformanceMonitor.startActivity("Render Post-Processing");
        PostProcessingRenderer.getInstance().renderScene();
        PerformanceMonitor.endActivity();

        /* FIRST PERSON VIEW ELEMENTS */
        _player.renderFirstPersonViewElements();
    }

    public float getRenderingLightValue() {
        return getRenderingLightValueAt(_player.getPosition());
    }

    public float getRenderingLightValueAt(Vector3d pos) {
        double lightValueSun = ((double) _worldProvider.getLightAtPosition(pos, Chunk.LIGHT_TYPE.SUN));
        lightValueSun = lightValueSun / 15.0;
        lightValueSun *= getDaylight();
        double lightValueBlock = _worldProvider.getLightAtPosition(pos, Chunk.LIGHT_TYPE.BLOCK);
        lightValueBlock = lightValueBlock / 15.0;

        return (float) TeraMath.clamp(lightValueSun + lightValueBlock * (1.0 - lightValueSun));
    }

    public void update(double delta) {
        PerformanceMonitor.startActivity("Update Tick");
        updateTick();
        PerformanceMonitor.endActivity();

        PerformanceMonitor.startActivity("Update Close Chunks");
        updateChunksInProximity(false);
        PerformanceMonitor.endActivity();

        PerformanceMonitor.startActivity("Skysphere");
        _skysphere.update(delta);
        PerformanceMonitor.endActivity();

        PerformanceMonitor.startActivity("Player");
        _player.update(delta);
        PerformanceMonitor.endActivity();

        PerformanceMonitor.startActivity("Mob Manager");
        _mobManager.update(delta);
        PerformanceMonitor.endActivity();

        // Update the particle emitters
        PerformanceMonitor.startActivity("Block Particle Emitter");
        _blockParticleEmitter.update(delta);
        PerformanceMonitor.endActivity();

        // Free unused space
        PerformanceMonitor.startActivity("Flush World Cache");
        _worldProvider.getChunkProvider().flushCache();
        PerformanceMonitor.endActivity();

        // And finally fire any active events
        PerformanceMonitor.startActivity("Fire Events");
        _worldTimeEventManager.fireWorldTimeEvents();
        PerformanceMonitor.endActivity();

        // Simulate world
        PerformanceMonitor.startActivity("Liquid");
        _worldProvider.getLiquidSimulator().simulate(false);
        PerformanceMonitor.endActivity();
        PerformanceMonitor.startActivity("Growth");
        _worldProvider.getGrowthSimulator().simulate(false);
        PerformanceMonitor.endActivity();

        PerformanceMonitor.startActivity("Physics Renderer");
        _bulletRenderer.update(delta);
        PerformanceMonitor.endActivity();
    }

    /**
     * Performs and maintains tick-based logic. If the game is paused this logic is not executed
     * First effect: update the _tick variable that animation is based on
     * Secondary effect: Trigger spawning (via PortalManager) once every second
     * Tertiary effect: Trigger socializing (via MobManager) once every 10 seconds
     */
    private void updateTick() {
        // Update the animation tick
        _tick++;

        // This block is based on seconds or less frequent timings
        if (Terasology.getInstance().getTimeInMs() - _lastTick >= 1000) {
            _tickTock++;
            _lastTick = Terasology.getInstance().getTimeInMs();

            // PortalManager ticks for spawning once a second
            _portalManager.tickSpawn();


            // MobManager ticks for AI every 10 seconds
            if (_tickTock % 10 == 0) {
                _mobManager.tickAI();
            }
        }
    }

    /**
     * Returns the maximum height at a given position.
     *
     * @param x The X-coordinate
     * @param z The Z-coordinate
     * @return The maximum height
     */
    public final int maxHeightAt(int x, int z) {
        for (int y = Chunk.CHUNK_DIMENSION_Y - 1; y >= 0; y--) {
            if (_worldProvider.getBlock(x, y, z) != 0x0)
                return y;
        }

        return 0;
    }

    /**
     * Chunk position of the player.
     *
     * @return The player offset on the x-axis
     */
    private int calcPlayerChunkOffsetX() {
        return (int) (_player.getPosition().x / Chunk.CHUNK_DIMENSION_X);
    }

    /**
     * Chunk position of the player.
     *
     * @return The player offset on the z-axis
     */
    private int calcPlayerChunkOffsetZ() {
        return (int) (_player.getPosition().z / Chunk.CHUNK_DIMENSION_Z);
    }

    /**
     * Sets a new player and spawns him at the spawning point.
     *
     * @param p The player
     */
    public void setPlayer(Player p) {
        if (_player != null) {
            _player.unregisterObserver(_chunkUpdateManager);
            _player.unregisterObserver(_worldProvider.getGrowthSimulator());
            _player.unregisterObserver(_worldProvider.getLiquidSimulator());
        }

        _player = p;
        _player.registerObserver(_chunkUpdateManager);
        _player.registerObserver(_worldProvider.getGrowthSimulator());
        _player.registerObserver(_worldProvider.getLiquidSimulator());

        _player.load();
        _player.setSpawningPoint(_worldProvider.nextSpawningPoint());
        _player.reset();

        // Only respawn the player if no position was loaded
        if (_player.getPosition().equals(new Vector3d(0.0, 0.0, 0.0))) {
            _player.respawn();
        }

        updateChunksInProximity(true);
    }

    /**
     * Creates the first Portal if it doesn't exist yet
     */
    public void initPortal() {
        if (!_portalManager.hasPortal()) {
            Vector3d loc = new Vector3d(_player.getPosition().x, _player.getPosition().y + 4, _player.getPosition().z);
            Terasology.getInstance().getLogger().log(Level.INFO, "Portal location is" + loc);
            _worldProvider.setBlock((int) loc.x - 1, (int) loc.y, (int) loc.z, BlockManager.getInstance().getBlock("PortalBlock").getId(), false, true);
            _portalManager.addPortal(loc);
        }
    }

    /**
     * Disposes this world.
     */
    public void dispose() {
        _worldProvider.dispose();
        _player.dispose();
        AudioManager.getInstance().stopAllSounds();
    }

    public void generateChunks() {
        for (int i = 0; i < _chunksInProximity.size(); i++) {
            Chunk c = _chunksInProximity.get(i);
            c.generateVBOs();

            if (c.isDirty() || c.isLightDirty()) {
                _chunkUpdateManager.queueChunkUpdate(c, ChunkUpdateManager.UPDATE_TYPE.DEFAULT);
            }
        }
    }

    public void printScreen() {
        // TODO: REFACTOR TO USE BACKGROUND THREAD FOR IMAGE COPY & SAVE
        GL11.glReadBuffer(GL11.GL_FRONT);
        int width = Display.getDisplayMode().getWidth();
        int height = Display.getDisplayMode().getHeight();
        //int bpp = Display.getDisplayMode().getBitsPerPixel(); does return 0 - why?
        int bpp = 4;
        ByteBuffer buffer = BufferUtils.createByteBuffer(width * height * bpp); // hardcoded until i know how to get bpp
        GL11.glReadPixels(0, 0, width, height, (bpp == 3) ? GL11.GL_RGB : GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);

        Calendar cal = Calendar.getInstance();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");

        File file = new File(sdf.format(cal.getTime()) + ".png");
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

        for (int x = 0; x < width; x++)
            for (int y = 0; y < height; y++) {
                int i = (x + (width * y)) * bpp;
                int r = buffer.get(i) & 0xFF;
                int g = buffer.get(i + 1) & 0xFF;
                int b = buffer.get(i + 2) & 0xFF;
                image.setRGB(x, height - (y + 1), (0xFF << 24) | (r << 16) | (g << 8) | b);
            }

        try {
            ImageIO.write(image, "png", file);
        } catch (IOException e) {
            Terasology.getInstance().getLogger().log(Level.WARNING, "Could not save image!", e);
        }
    }


    @Override
    public String toString() {
        return String.format("world (biome: %s, time: %.2f, exposure: %.2f, sun: %.2f, cache: %d, dirty: %d, ign: %d, vis: %d, tri: %d, empty: %d, !ready: %d, seed: \"%s\", title: \"%s\")", getActiveBiome(), _worldProvider.getTime(), PostProcessingRenderer.getInstance().getExposure(), _skysphere.getSunPosAngle(), _worldProvider.getChunkProvider().size(), _statDirtyChunks, _statIgnoredPhases, _statVisibleChunks, Chunk._statRenderedTriangles, Chunk._statChunkMeshEmpty, Chunk._statChunkNotReady, _worldProvider.getSeed(), _worldProvider.getTitle());
    }

    public Player getPlayer() {
        return _player;
    }

    public boolean isAABBVisible(AABB aabb) {
        return _player.getActiveCamera().getViewFrustum().intersects(aabb);
    }

    public boolean isChunkVisible(Chunk c) {
        return _player.getActiveCamera().getViewFrustum().intersects(c.getAABB());
    }

    public boolean isEntityVisible(Entity e) {
        return _player.getActiveCamera().getViewFrustum().intersects(e.getAABB());
    }

    public double getDaylight() {
        return _skysphere.getDaylight();
    }

    public BlockParticleEmitter getBlockParticleEmitter() {
        return _blockParticleEmitter;
    }

    public ChunkGeneratorTerrain.BIOME_TYPE getActiveBiome() {
        return _worldProvider.getActiveBiome((int) _player.getPosition().x, (int) _player.getPosition().z);
    }

    public double getActiveHumidity() {
        return _worldProvider.getHumidityAt((int) _player.getPosition().x, (int) _player.getPosition().z);
    }

    public double getActiveTemperature() {
        return _worldProvider.getTemperatureAt((int) _player.getPosition().x, (int) _player.getPosition().z);
    }

    public IWorldProvider getWorldProvider() {
        return _worldProvider;
    }

    public BlockGrid getBlockGrid() {
        return _blockGrid;
    }

    public MobManager getMobManager() {
        return _mobManager;
    }

    public Skysphere getSkysphere() {
        return _skysphere;
    }

    public int getTick() {
        return _tick;
    }

    public ArrayList<Chunk> getChunksInProximity() {
        return _chunksInProximity;
    }

    public boolean isWireframe() {
        return _wireframe;
    }

    public void setWireframe(boolean _wireframe) {
        this._wireframe = _wireframe;
    }

    public BulletPhysicsRenderer getBulletRenderer() {
        return _bulletRenderer;
    }
}
