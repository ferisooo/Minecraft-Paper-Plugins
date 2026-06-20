package com.ferisooo.kawaiicompanion;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reflection-only wrapper around a NMS {@code ServerPlayer} used as the
 * companion entity. Mojang-mapped class names are looked up by string at
 * runtime so we don't need {@code paperweight-userdev} in the build.
 *
 * <p><b>Phase 1 scope.</b> The {@code ServerPlayer} is constructed (so we
 * have a real player object with a proper skin profile + entity ID), but
 * it is <i>not</i> registered with {@code ServerLevel.players} or the chunk
 * tracker. Visibility is driven by manually broadcasting packets to every
 * online player on spawn / move / despawn. The companion looks like a
 * real player but mobs/pressure plates ignore it. Phase 2 will add the
 * level-side registration so the world reacts to it.
 *
 * <p><b>Movement packets.</b> 1.21.2 removed the simple
 * {@code ClientboundTeleportEntityPacket(Entity)} constructor and replaced
 * it with a record-style shape that takes a {@code PositionMoveRotation}.
 * For position updates we prefer (in order): the legacy {@code (Entity)}
 * ctor when present, then {@code ClientboundEntityPositionSyncPacket}
 * (1.21.2+, simpler API), then the new teleport packet's record-style
 * ctor, and only as a last resort despawn + respawn. For rotation-only
 * updates (e.g. "glance at the owner" head pitch) we use the very stable
 * {@code ClientboundMoveEntityPacket.Rot} packet — that path is hot and
 * was the source of a bad despawn/respawn flicker on 1.21.2+ before this
 * split.
 *
 * <p>If reflection fails at any step, {@link #spawn} returns {@code null}
 * and logs the failure — the caller can fall back gracefully and the
 * player gets to see why it broke instead of a silent no-op.
 */
public final class NmsCompanion {

    // ============== Reflection cache ==============
    private static volatile Refs refs;

    /**
     * Identity set of every NMS {@code ServerPlayer} that's currently a
     * live companion. Broadcast loops iterate {@code world.getPlayers()},
     * which — once we splice the companion into {@code ServerLevel.players}
     * for mob targeting — also returns the companion herself (and any other
     * companions in the world). Sending a packet through her stub listener
     * NPEs (her listener has no real {@code Connection}), so we skip any
     * handle in this set during broadcasts.
     */
    private static final java.util.Set<Object> COMPANION_HANDLES =
            java.util.Collections.synchronizedSet(
                    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));

    private static final class Refs {
        Class<?> minecraftServer;
        Class<?> serverLevel;
        Class<?> serverPlayer;
        Class<?> entity;
        Class<?> livingEntity;
        Class<?> equipmentSlot;
        Class<?> nmsItemStack;
        Class<?> clientInformation;
        Class<?> gameProfile;
        Class<?> property;
        Class<?> propertyMap;
        Class<?> componentCls;

        Class<?> packetCls;
        Class<?> playerInfoUpdatePacket;
        Class<?> playerInfoUpdateAction;
        Class<?> addEntityPacket;
        Class<?> removeEntitiesPacket;
        Class<?> teleportEntityPacket;
        Class<?> rotateHeadPacket;
        Class<?> playerInfoRemovePacket;

        // 1.21.2+ position-update primitives. Any of these may be null on
        // older builds; buildTeleportPacket() chooses the best available.
        Class<?> entityPositionSyncPacket;
        Class<?> positionMoveRotation;
        Class<?> relative;
        Class<?> moveEntityRotPacket; // ClientboundMoveEntityPacket$Rot
        Class<?> moveEntityPosRotPacket; // ClientboundMoveEntityPacket$PosRot

        // ServerLevel.players — the per-level player list mob target
        // selectors scan via Level.getNearestPlayer(...). Added to via
        // reflection in registerWithWorld() so mobs find her.
        Field levelPlayersField;

        Class<?> craftServer;
        Class<?> craftWorld;
        Class<?> craftPlayer;
        Class<?> craftItemStack;
        Class<?> serverPacketListenerCls; // ServerGamePacketListenerImpl — we stuff a dummy of this into companion's connection field

        // Plumbing so the stub listener has a real (but disconnected)
        // Connection inside it, instead of a null one. Without this any
        // server-internal `player.connection.send(packet)` against the
        // companion crashes the tick.
        Class<?> connectionCls;       // net.minecraft.network.Connection
        Class<?> packetFlowCls;       // net.minecraft.network.protocol.PacketFlow
        Object   packetFlowEnumValue; // PacketFlow.SERVERBOUND (or CLIENTBOUND fallback)
        Constructor<?> connectionCtor;
        Field listenerConnectionField;       // ServerCommonPacketListenerImpl.connection
        Field connectionPendingActionsField; // Connection.pendingActions (Queue)

        Method craftServerGetServer;
        Method craftWorldGetHandle;
        Method craftPlayerGetHandle;
        Method craftItemStackAsNMSCopy;

        Method gameProfileGetProperties;
        Method propertyMapPut;
        Method clientInfoCreateDefault;
        Method componentLiteral;

        Constructor<?> gameProfileCtor2; // older authlib: (UUID, String)
        Constructor<?> gameProfileCtor3; // newer authlib: (UUID, String, PropertyMap)
        Constructor<?> propertyMapCtor;  // no-arg PropertyMap() — may be absent on 1.21.10+ authlib
        Field propertyMapBackingField;   // PropertyMap's internal Multimap field (we overwrite it with a mutable one)
        Constructor<?> serverPlayerCtor;
        Method entitySetPos;
        Method entityGetId;
        Method entitySetYRot;
        Method entitySetXRot;
        Method entitySetYHeadRot;
        Method entitySetCustomName;
        Method entitySetCustomNameVisible;
        Method entitySetGlowingTag;
        Method livingSetItemSlot;        // 1.20.5+: (EquipmentSlot, ItemStack, boolean)
        Method livingSetItemSlot2;       // older: (EquipmentSlot, ItemStack)
        Field connectionField;
        Method connectionSend;

        Constructor<?> playerInfoUpdateCtor;
        // Tablist hiding: rebuild each player-info Entry with listed=false and
        // resend through the (EnumSet, List<Entry>) constructor. All nullable —
        // if unavailable we fall back to the normal (listed) packet.
        Constructor<?> playerInfoUpdateListCtor;
        java.lang.reflect.Field playerInfoUpdateEntriesField;
        Constructor<?> playerInfoEntryCanonicalCtor;
        java.lang.reflect.RecordComponent[] entryComponents;
        Constructor<?> addEntityCtor;       // 1.21.1-: (Entity)
        Constructor<?> addEntityCtorRaw;    // 1.21.2+: (int, UUID, x,y,z, xRot,yRot, EntityType, int, Vec3, double)
        Constructor<?> removeEntitiesCtor;
        Constructor<?> teleportEntityCtor;     // 1.21.1-: (Entity)
        Constructor<?> teleportEntityCtorNew;  // 1.21.2+: (int, PositionMoveRotation, Set<Relative>, boolean)
        Constructor<?> entityPositionSyncCtor; // 1.21.2+: (int, PositionMoveRotation, boolean)
        Constructor<?> posMoveRotCtor;         // (Vec3, Vec3, float, float)
        Constructor<?> moveEntityRotCtor;      // (int, byte, byte, boolean)
        Constructor<?> moveEntityPosRotCtor;   // (int, short, short, short, byte, byte, boolean)

        // Equipment + animation packets — needed to render the sword in
        // her hand and to play the swing animation during idle/guard
        // behavior. SetEquipmentPacket takes a List<Pair<EquipmentSlot,
        // ItemStack>>; AnimatePacket takes (Entity, int action) where
        // action 0 == swing main arm.
        Class<?> pairCls;
        Method   pairOfMethod;
        Class<?> setEquipmentPacket;
        Constructor<?> setEquipmentCtor;
        Method   livingGetItemBySlot;
        Class<?> animatePacket;
        Constructor<?> animateCtor;
        Constructor<?> rotateHeadCtor;
        Constructor<?> playerInfoRemoveCtor;

        // For the raw-int AddEntity fallback we also need EntityType.PLAYER
        // (read off the actual ServerPlayer instance) and a Vec3(0,0,0) for
        // initial deltaMovement.
        Class<?> vec3;
        Constructor<?> vec3Ctor;
        Method entityGetType;

        Object actionAddPlayer;
        Object actionUpdateListed;

        // ---- Swim pose support ----
        // Pose enum + SWIMMING/STANDING values for the pose entity-data
        // field. Setting POSE = SWIMMING is what triggers the horizontal
        // swim animation in modern clients (1.13+).
        Class<?> poseCls;
        Object poseSwimming;
        Object poseStanding;
        Method entitySetPose;
        Method entityGetEntityData;
        // Method on Entity that flips the SWIMMING shared-flag bit (bit 4
        // of the SharedFlagsByte). The pose alone drives rendering, but
        // vanilla also sets this flag — mirroring keeps the entity state
        // consistent for any mod/shader that reads from it.
        Method entitySetSwimming;
        // Same idea for crouching (SHIFT_KEY_DOWN flag bit 1) — vanilla
        // sets this whenever a player presses sneak, and the pose +
        // flag pair is what scoreboard objectives / mod hooks read.
        Method entitySetShiftKeyDown;
        // Sprint flag (SharedFlagsByte bit 3). Toggled when the
        // companion catches up to the owner so the client renders the
        // sprint particles + faster arm-swing animation.
        Method entitySetSprinting;
        // SynchedEntityData.getNonDefaultValues() — returns the full list
        // of fields that differ from defaults, suitable for re-syncing
        // a client that may have missed prior dirty packets.
        Method synchedDataGetNonDefault;
        // SynchedEntityData.packDirty() — returns + clears the list of
        // fields that changed since the last call. Critical for
        // transitions where the post-change value is the DEFAULT (e.g.
        // pose SWIMMING → STANDING) — getNonDefaultValues would
        // return empty for that, so the client never learns about the
        // change. packDirty correctly captures "this just flipped back
        // to default" because the change itself is what's tracked.
        Method synchedDataPackDirty;
        Class<?> setEntityDataPacketCls;
        Constructor<?> setEntityDataPacketCtor;
    }

    // ============== Per-instance state ==============
    private final Plugin plugin;
    private final Object serverPlayer;
    private final int entityId;
    private final UUID profileId;
    private World world;
    private double x, y, z;
    private float yaw, pitch;
    private boolean alive = true;

    // Last position the *client* knows about, in absolute coordinates.
    // PosRot delta packets are quantized to 1/4096 block, and we accumulate
    // sub-quantum residuals here so a long walk stays exact instead of
    // drifting a block off over time.
    private double sentX, sentY, sentZ;

    // True if we actually managed to splice the companion into ServerLevel
    // .players (so mobs target her). Tracked per-instance so despawn()
    // knows whether to unsplice.
    private boolean worldRegistered;

    /**
     * Real-but-disconnected {@code net.minecraft.network.Connection}
     * stuffed into the companion's stub listener. Server-internal calls
     * like {@code player.connection.send(timePacket)} land here and queue
     * to the connection's {@code pendingActions} — which we drain via
     * {@link #clearStubPending()} so the queue doesn't grow unbounded
     * over a long server uptime.
     */
    private Object dummyConnection;

    private NmsCompanion(Plugin plugin, Object serverPlayer, int entityId, UUID profileId,
                         World world, double x, double y, double z, float yaw, float pitch) {
        this.plugin = plugin;
        this.serverPlayer = serverPlayer;
        this.entityId = entityId;
        this.profileId = profileId;
        this.world = world;
        this.x = x; this.y = y; this.z = z;
        this.yaw = yaw; this.pitch = pitch;
    }

    /**
     * Construct + spawn the companion, broadcasting initial visibility
     * packets. Returns {@code null} if NMS reflection failed; the caller
     * should fall back (and the failure will already be logged).
     */
    public static NmsCompanion spawn(Plugin plugin, String profileName, UUID profileId,
                                     String textureValue, String textureSignature,
                                     Location loc, String customName,
                                     ItemStack chestplate, ItemStack leggings, ItemStack boots,
                                     boolean glowing, boolean worldIntegrated) {
        Logger log = plugin.getLogger();
        if (loc.getWorld() == null) {
            log.warning("(✧) NMS spawn: location has no world");
            return null;
        }
        try {
            Refs r = refs();

            // Build a mutable PropertyMap with the texture, then construct
            // the GameProfile. Newer authlib (Paper 1.21.10+) makes both the
            // freshly-constructed PropertyMap *and* the one returned by
            // GameProfile.properties() back onto an ImmutableMultimap, so we
            // forcibly swap the backing field to LinkedHashMultimap before
            // any put() — see newMutablePropertyMap().
            Object props = newMutablePropertyMap(r);
            Object texProp = null;
            if (textureValue != null && !textureValue.isBlank()) {
                texProp = r.property
                        .getConstructor(String.class, String.class, String.class)
                        .newInstance("textures", textureValue,
                                (textureSignature == null || textureSignature.isBlank())
                                        ? null : textureSignature);
                r.propertyMapPut.invoke(props, "textures", texProp);
            }
            Object profile;
            if (r.gameProfileCtor3 != null) {
                profile = r.gameProfileCtor3.newInstance(profileId, profileName, props);
            } else {
                profile = r.gameProfileCtor2.newInstance(profileId, profileName);
                if (texProp != null) {
                    Object profileProps = r.gameProfileGetProperties.invoke(profile);
                    // Same immutable-backing problem on legacy 2-arg path.
                    if (r.propertyMapBackingField != null) {
                        r.propertyMapBackingField.set(profileProps,
                                com.google.common.collect.LinkedHashMultimap.create());
                    }
                    r.propertyMapPut.invoke(profileProps, "textures", texProp);
                }
            }

            Object server = r.craftServerGetServer.invoke(Bukkit.getServer());
            Object level  = r.craftWorldGetHandle.invoke(loc.getWorld());
            Object clientInfo = r.clientInfoCreateDefault.invoke(null);

            Object sp = r.serverPlayerCtor.newInstance(server, level, profile, clientInfo);
            // Companion has no real network. We Unsafe-allocate a listener
            // (so player.connection isn't null — that fixes the latency()
            // NPE during PlayerInfoUpdate construction) and stuff a real
            // but disconnected Connection inside it. With a non-null
            // Connection, server-internal code that iterates ServerLevel
            // .players and calls player.connection.send(packet) — for time
            // sync, weather, etc. — goes into Connection.send(), which sees
            // channel==null, returns false from isConnected(), and silently
            // queues the packet to pendingActions instead of NPEing on a
            // null channel. The queue is drained periodically by
            // clearStubPending() below to keep memory bounded.
            Object stubListener = unsafeAllocate(r.serverPacketListenerCls);
            Object dummyConn = null;
            if (r.connectionCtor != null && r.listenerConnectionField != null
                    && r.packetFlowEnumValue != null) {
                try {
                    dummyConn = r.connectionCtor.newInstance(r.packetFlowEnumValue);
                    r.listenerConnectionField.set(stubListener, dummyConn);
                } catch (Throwable t) {
                    plugin.getLogger().warning(
                            "(✧) couldn't init stub connection — world-integrated "
                            + "mode may crash. Set world-integrated: false in "
                            + "config.yml if you see NPEs from PaperMC tick code. "
                            + "Cause: " + t);
                }
            }
            r.connectionField.set(sp, stubListener);
            r.entitySetPos.invoke(sp, loc.getX(), loc.getY(), loc.getZ());
            r.entitySetYRot.invoke(sp, loc.getYaw());
            r.entitySetXRot.invoke(sp, loc.getPitch());
            r.entitySetYHeadRot.invoke(sp, loc.getYaw());
            int eid = (int) r.entityGetId.invoke(sp);

            if (customName != null && !customName.isBlank()) {
                Object component = r.componentLiteral.invoke(null, customName);
                r.entitySetCustomName.invoke(sp, component);
                r.entitySetCustomNameVisible.invoke(sp, true);
            }
            if (glowing) {
                r.entitySetGlowingTag.invoke(sp, true);
            }

            applyEquipmentNms(r, sp, "CHEST", chestplate);
            applyEquipmentNms(r, sp, "LEGS",  leggings);
            applyEquipmentNms(r, sp, "FEET",  boots);

            NmsCompanion wrapper = new NmsCompanion(plugin, sp, eid, profileId,
                    loc.getWorld(),
                    loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
            wrapper.dummyConnection = dummyConn;
            // Track this handle globally so other companions' broadcast loops
            // skip her (and so hers skips theirs). Done before the spawn
            // packets fire so the very first broadcast already filters
            // correctly even with two simultaneous summons.
            COMPANION_HANDLES.add(sp);
            // Seed sentX/Y/Z with the spawn position so the first
            // smoothMoveTo() computes a small delta from a known point
            // rather than from (0,0,0).
            wrapper.sentX = wrapper.x;
            wrapper.sentY = wrapper.y;
            wrapper.sentZ = wrapper.z;

            wrapper.broadcastSpawnPackets(r);

            // Splice into ServerLevel.players so mob target selectors find
            // her. Doing this *after* the spawn packets (so viewers already
            // have her rendered) keeps the visible state consistent if the
            // splice fails halfway through.
            if (worldIntegrated) {
                wrapper.registerWithWorld(r);
            }

            return wrapper;
        } catch (Throwable t) {
            log.log(Level.WARNING, "(✧) NMS companion spawn failed: " + t, t);
            return null;
        }
    }

    public boolean isDead() { return !alive; }
    public World getWorld() { return world; }
    public Location getLocation() { return new Location(world, x, y, z, yaw, pitch); }

    /**
     * Drain the stub {@code Connection}'s queued packets. Server-internal
     * code that broadcasts to every player in a level (time, weather, etc.)
     * silently queues packets here instead of NPEing — and over a long
     * uptime the queue would grow into noticeable memory pressure if we
     * never cleared it. The plugin should call this every few seconds.
     * Cheap: just clears a {@code ConcurrentLinkedQueue}.
     */
    public void clearStubPending() {
        if (dummyConnection == null) return;
        try {
            Refs r = refs();
            if (r.connectionPendingActionsField == null) return;
            Object q = r.connectionPendingActionsField.get(dummyConnection);
            if (q instanceof java.util.Queue<?> queue) queue.clear();
        } catch (Throwable ignored) {
            // Drain failures aren't worth logging — the next attempt will
            // either succeed or keep failing harmlessly.
        }
    }

    /** Move + rotate the entity. Cross-world is implemented as despawn + respawn. */
    public void teleport(Location loc) {
        if (!alive || loc.getWorld() == null) return;
        try {
            Refs r = refs();
            if (loc.getWorld() != world) {
                // Cross-world: tear down on the old world's viewers, then
                // re-spawn on the new world. Keeping the same NMS object
                // would require futzing with its private level field, and
                // the reentry isn't on a hot path.
                boolean wasRegistered = worldRegistered;
                unregisterFromWorld(r);
                broadcastDespawnPackets(r);
                world = loc.getWorld();
                this.x = loc.getX(); this.y = loc.getY(); this.z = loc.getZ();
                this.yaw = loc.getYaw(); this.pitch = loc.getPitch();
                r.entitySetPos.invoke(serverPlayer, x, y, z);
                r.entitySetYRot.invoke(serverPlayer, yaw);
                r.entitySetXRot.invoke(serverPlayer, pitch);
                r.entitySetYHeadRot.invoke(serverPlayer, yaw);
                broadcastSpawnPackets(r);
                if (wasRegistered) registerWithWorld(r);
                sentX = x; sentY = y; sentZ = z;
                return;
            }
            this.x = loc.getX(); this.y = loc.getY(); this.z = loc.getZ();
            this.yaw = loc.getYaw(); this.pitch = loc.getPitch();
            r.entitySetPos.invoke(serverPlayer, x, y, z);
            r.entitySetYRot.invoke(serverPlayer, yaw);
            r.entitySetXRot.invoke(serverPlayer, pitch);
            r.entitySetYHeadRot.invoke(serverPlayer, yaw);

            broadcastTeleport(r);
            // Absolute teleport — client position now matches us exactly.
            sentX = x; sentY = y; sentZ = z;
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "(✧) NMS companion teleport failed: " + t, t);
        }
    }

    /**
     * Smooth incremental move. Sends a {@code ClientboundMoveEntityPacket
     * .PosRot} delta packet so the client interpolates and animates limb
     * swings — this is what makes her look like she's <i>walking</i>
     * instead of teleport-snapping every tick.
     *
     * <p>Falls back to {@link #teleport(Location)} when:
     * <ul>
     *   <li>the world differs (cross-world),</li>
     *   <li>any axis delta exceeds the ~7.99-block PosRot packet limit
     *       (e.g. the owner just elytra'd off a cliff),</li>
     *   <li>the PosRot constructor isn't available on this Paper build.</li>
     * </ul>
     */
    public void smoothMoveTo(Location target) {
        if (!alive || target.getWorld() == null) return;
        try {
            Refs r = refs();
            if (target.getWorld() != world) {
                teleport(target);
                return;
            }

            double dx = target.getX() - sentX;
            double dy = target.getY() - sentY;
            double dz = target.getZ() - sentZ;

            // PosRot encodes deltas as shorts in 1/4096 block units, max
            // ~7.99 blocks per axis per packet. Anything bigger has to be
            // an absolute teleport — the protocol can't represent it.
            if (r.moveEntityPosRotCtor == null
                    || Math.abs(dx) >= 7.99 || Math.abs(dy) >= 7.99 || Math.abs(dz) >= 7.99) {
                this.yaw = target.getYaw();
                this.pitch = target.getPitch();
                teleport(target);
                return;
            }

            this.x = target.getX();
            this.y = target.getY();
            this.z = target.getZ();
            this.yaw = target.getYaw();
            this.pitch = target.getPitch();
            r.entitySetPos.invoke(serverPlayer, x, y, z);
            r.entitySetYRot.invoke(serverPlayer, yaw);
            r.entitySetXRot.invoke(serverPlayer, pitch);
            r.entitySetYHeadRot.invoke(serverPlayer, yaw);

            short dxs = (short) Math.round(dx * 4096.0);
            short dys = (short) Math.round(dy * 4096.0);
            short dzs = (short) Math.round(dz * 4096.0);
            byte yawByte   = (byte) ((int) (yaw * 256.0F / 360.0F));
            byte pitchByte = (byte) ((int) (pitch * 256.0F / 360.0F));

            Object posRot = r.moveEntityPosRotCtor.newInstance(
                    entityId, dxs, dys, dzs, yawByte, pitchByte, true);
            broadcastPacket(r, posRot);

            // Head yaw is independent of body yaw — refresh so head tracks
            // along with the body.
            Object headPacket = r.rotateHeadCtor.newInstance(serverPlayer, yawByte);
            broadcastPacket(r, headPacket);

            // Advance sent* by the *quantized* delta so residuals accumulate
            // and the next packet picks them up — that's how a long walk
            // stays exactly on-target instead of drifting from rounding.
            sentX += dxs / 4096.0;
            sentY += dys / 4096.0;
            sentZ += dzs / 4096.0;
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "(✧) NMS companion smoothMoveTo failed: " + t, t);
        }
    }

    /**
     * Splice the companion into {@code ServerLevel.players} via reflection
     * so {@code Level.getNearestPlayer(...)} returns her — that's what mob
     * target selectors (zombies, skeletons, creepers, etc) iterate to pick
     * a victim. This does <i>not</i> add her to the entity tracker or tick
     * list, so the server won't try to tick her player-state and trip over
     * the dummy connection.
     *
     * <p>Caveat: plugins that iterate {@code World.getPlayers()} will see
     * her too. Most read-only iteration is fine; calls like
     * {@code player.sendMessage()} will NPE on the dummy connection. If a
     * server has plugins that broadcast to every world player, set
     * {@code world-integrated: false} in the plugin config.
     */
    private void registerWithWorld(Refs r) {
        if (worldRegistered) return;
        try {
            if (r.levelPlayersField == null) {
                plugin.getLogger().warning(
                        "(✧) ServerLevel.players field not found — mobs won't target the companion");
                return;
            }
            // Refuse to splice into level.players unless the stub
            // Connection is actually wired up. Without it, server-internal
            // code that iterates the level player list and calls
            // player.connection.send(...) crashes the next tick — we'd
            // rather lose mob targeting than panic the server.
            if (dummyConnection == null) {
                plugin.getLogger().warning(
                        "(✧) skipping world-integration: stub Connection unavailable on "
                        + "this Paper build, mobs won't target her. "
                        + "Set world-integrated: false in config.yml to silence this.");
                return;
            }
            Object level = r.craftWorldGetHandle.invoke(world);
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) r.levelPlayersField.get(level);
            // Defensive: don't double-add if someone manually summoned twice.
            if (!list.contains(serverPlayer)) list.add(serverPlayer);
            worldRegistered = true;
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING,
                    "(✧) registerWithWorld failed (mobs won't target her): " + t, t);
        }
    }

    private void unregisterFromWorld(Refs r) {
        if (!worldRegistered) return;
        worldRegistered = false;
        try {
            if (r.levelPlayersField == null) return;
            Object level = r.craftWorldGetHandle.invoke(world);
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) r.levelPlayersField.get(level);
            list.remove(serverPlayer);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING,
                    "(✧) unregisterFromWorld failed: " + t, t);
        }
    }

    /**
     * Update head pitch (head tilt) without changing position. Sub-degree
     * drift is invisible client-side (1 byte ≈ 1.4°), so we early-out to
     * avoid spamming packets on every movement tick.
     */
    public void setHeadPitch(float pitchDeg) {
        if (!alive) return;
        try {
            if (Math.abs(pitchDeg - this.pitch) < 1.0f) return;
            Refs r = refs();
            this.pitch = pitchDeg;
            r.entitySetXRot.invoke(serverPlayer, pitchDeg);
            broadcastRotationOnly(r);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "(✧) NMS companion setHeadPitch failed: " + t, t);
        }
    }

    /**
     * Rotate the head independently of the body. Real players' heads
     * track around the body during idle — this is what powers the
     * "looks around" idle animation. We send only a head-rotation packet,
     * so the body keeps its current yaw.
     */
    public void setHeadYaw(float yawDeg) {
        if (!alive) return;
        try {
            Refs r = refs();
            r.entitySetYHeadRot.invoke(serverPlayer, yawDeg);
            byte b = (byte) ((int) (yawDeg * 256.0F / 360.0F));
            Object packet = r.rotateHeadCtor.newInstance(serverPlayer, b);
            broadcastPacket(r, packet);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "(✧) NMS companion setHeadYaw failed: " + t, t);
        }
    }

    /**
     * Rotate the whole body (and the head with it) to a new yaw. Use this
     * when the companion needs to physically pivot — e.g. GUARD mode
     * facing a threat behind her — instead of just snapping her head past
     * the natural neck-rotation limit (which makes her look like an owl).
     */
    public void setBodyYaw(float yawDeg) {
        if (!alive) return;
        try {
            Refs r = refs();
            this.yaw = yawDeg;
            r.entitySetYRot.invoke(serverPlayer, yawDeg);
            r.entitySetYHeadRot.invoke(serverPlayer, yawDeg);
            broadcastRotationOnly(r);
            byte b = (byte) ((int) (yawDeg * 256.0F / 360.0F));
            Object headPacket = r.rotateHeadCtor.newInstance(serverPlayer, b);
            broadcastPacket(r, headPacket);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "(✧) NMS companion setBodyYaw failed: " + t, t);
        }
    }

    /** Equip an item in the main hand and broadcast so clients render it. */
    public void setMainHand(ItemStack item) { setEquipmentSlot("MAINHAND", item); }

    /** Equip an item in the off hand. */
    public void setOffHand(ItemStack item)  { setEquipmentSlot("OFFHAND",  item); }

    /**
     * Toggle the swim pose. Convenience wrapper around {@link #setPose}
     * for backward-compat with code that just wants on/off swimming.
     */
    public void setSwimming(boolean swimming) {
        setPose(swimming ? "SWIMMING" : "STANDING");
    }

    /**
     * Toggle the sprint flag (SharedFlagsByte bit 3). Drives the
     * sprint-particle trail + faster client-side arm swing — without
     * changing the actual pose. Call this when the companion is trying
     * to catch up to a far-away owner; clear it when she's caught up so
     * the standing-still animation plays normally.
     *
     * <p>Cheap when the value's unchanged (caller's responsibility) —
     * each call broadcasts a {@code ClientboundSetEntityDataPacket}
     * with the dirty fields. Silently no-ops if the underlying
     * {@code Entity.setSprinting} method isn't reflectable on this
     * Paper build.
     */
    public void setSprinting(boolean sprinting) {
        if (!alive) return;
        try {
            Refs r = refs();
            if (r.entitySetSprinting == null) return;
            r.entitySetSprinting.invoke(serverPlayer, sprinting);
            broadcastEntityData(r);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING,
                    "(✧) NMS companion setSprinting(" + sprinting + ") failed: " + t, t);
        }
    }

    /**
     * Set the companion's render pose by name. Drives the client model
     * selection — SWIMMING → horizontal swim animation, SLEEPING → flat
     * on the ground, CROUCHING → sneak pose, FALL_FLYING → elytra-style
     * dive, STANDING → upright default.
     *
     * <p>Vanilla mirrors a few of these on the SharedFlagsByte (bit 1
     * for crouching, bit 4 for swimming) so any mod / shader / scoreboard
     * objective reading the flag sees a consistent state. We mirror
     * those here too, and clear the bits when transitioning to a pose
     * that doesn't use them.
     *
     * <p>Always broadcasts a {@code ClientboundSetEntityDataPacket} with
     * the entity's non-default values so every viewer's client picks up
     * the pose change immediately. Caller is responsible for not
     * invoking it every tick when the desired pose hasn't changed
     * (KawaiiCompanion's {@code updatePoseState} caches the last pose
     * for that purpose).
     *
     * <p>If the underlying Paper build doesn't expose the required
     * reflection, this method silently no-ops — Phase 1 still works,
     * she just doesn't visually pose.
     */
    public void setPose(String poseName) {
        if (!alive || poseName == null) return;
        try {
            Refs r = refs();
            if (r.entitySetPose == null || r.poseCls == null) {
                return; // pose plumbing not available — skip silently
            }
            Object poseValue;
            try {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object v = Enum.valueOf((Class<Enum>) r.poseCls, poseName);
                poseValue = v;
            } catch (IllegalArgumentException nope) {
                // Unknown pose name on this Paper version — fall back to
                // STANDING rather than throwing.
                plugin.getLogger().fine("(✧) unknown pose '" + poseName
                        + "' — falling back to STANDING");
                poseValue = r.poseStanding;
            }

            // Mirror SharedFlagsByte bits where vanilla does. Clearing
            // the bit on transitions OUT of a pose is just as important
            // as setting it — otherwise a sleeping companion could end
            // up "still swimming" per the flag byte after she beached
            // herself, which trips up scoreboard objectives.
            boolean isSwim   = "SWIMMING".equals(poseName);
            boolean isCrouch = "CROUCHING".equals(poseName);
            if (r.entitySetSwimming != null) {
                r.entitySetSwimming.invoke(serverPlayer, isSwim);
            }
            if (r.entitySetShiftKeyDown != null) {
                r.entitySetShiftKeyDown.invoke(serverPlayer, isCrouch);
            }
            r.entitySetPose.invoke(serverPlayer, poseValue);
            broadcastEntityData(r);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING,
                    "(✧) NMS companion setPose(" + poseName + ") failed: " + t, t);
        }
    }

    /**
     * Build + broadcast a {@code ClientboundSetEntityDataPacket} with
     * the entity's full set of non-default data values. Used after a
     * pose change to push the new state to every viewer (since our
     * companion isn't tracked by the level, no auto-sync happens).
     */
    private void broadcastEntityData(Refs r) throws Throwable {
        if (r.entityGetEntityData == null || r.setEntityDataPacketCtor == null) return;
        Object data = r.entityGetEntityData.invoke(serverPlayer);

        // Prefer packDirty — it captures changes EVEN WHEN the new value
        // is the field's default. Critical for pose transitions: when
        // SWIMMING flips back to STANDING, the pose field is "default"
        // again, and getNonDefaultValues would return an empty list.
        // The client would never get the standing-up packet and she'd
        // appear stuck horizontal forever. packDirty correctly tracks
        // "this field just changed" regardless of whether the new value
        // is default or not.
        Object list = null;
        if (r.synchedDataPackDirty != null) {
            list = r.synchedDataPackDirty.invoke(data);
        } else if (r.synchedDataGetNonDefault != null) {
            list = r.synchedDataGetNonDefault.invoke(data);
        }
        if (list == null) return;
        if (!(list instanceof java.util.List<?>) || ((java.util.List<?>) list).isEmpty()) return;
        Object packet = r.setEntityDataPacketCtor.newInstance(entityId, list);
        broadcastPacket(r, packet);
    }

    private void setEquipmentSlot(String slotName, ItemStack item) {
        if (!alive) return;
        try {
            Refs r = refs();
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object slot = Enum.valueOf((Class<Enum>) r.equipmentSlot, slotName);
            // null/AIR means "empty slot" — clear it.
            ItemStack toSet = (item == null) ? new ItemStack(org.bukkit.Material.AIR) : item;
            Object nms = r.craftItemStackAsNMSCopy.invoke(null, toSet);
            if (r.livingSetItemSlot != null) {
                r.livingSetItemSlot.invoke(serverPlayer, slot, nms, true);
            } else {
                r.livingSetItemSlot2.invoke(serverPlayer, slot, nms);
            }
            broadcastEquipment(r);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "(✧) NMS companion setEquipmentSlot failed: " + t, t);
        }
    }

    /**
     * Play a main-hand swing animation. Used by GUARD mode when she
     * spots a hostile mob, and during scout idle as a small "looking
     * busy" tic.
     */
    public void swingMainHand() {
        if (!alive) return;
        try {
            Refs r = refs();
            if (r.animateCtor == null) return;
            // Action ID 0 = swing main arm.
            Object packet = r.animateCtor.newInstance(serverPlayer, 0);
            broadcastPacket(r, packet);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "(✧) NMS companion swingMainHand failed: " + t, t);
        }
    }

    /**
     * Build + send a {@code ClientboundSetEquipmentPacket} reflecting all
     * six equipment slots. We send the whole set (rather than just the
     * changed one) because the cost is tiny and it sidesteps the slot-
     * specific edge cases — clients accept "set all of these" cleanly.
     */
    private void broadcastEquipment(Refs r) throws Throwable {
        if (r.setEquipmentCtor == null || r.livingGetItemBySlot == null
                || r.pairOfMethod == null) return;
        List<Object> pairs = new java.util.ArrayList<>(6);
        for (String slotName : new String[] { "MAINHAND", "OFFHAND", "HEAD", "CHEST", "LEGS", "FEET" }) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object slot = Enum.valueOf((Class<Enum>) r.equipmentSlot, slotName);
            Object nmsItem = r.livingGetItemBySlot.invoke(serverPlayer, slot);
            pairs.add(r.pairOfMethod.invoke(null, slot, nmsItem));
        }
        Object packet = r.setEquipmentCtor.newInstance(entityId, pairs);
        broadcastPacket(r, packet);
    }

    /**
     * Send a position/rotation refresh to all viewers. Tries the simplest
     * available packet form first; only falls back to despawn + respawn if
     * none of the per-version primitives resolved (which would mean Paper
     * removed all of them — extremely unlikely).
     */
    private void broadcastTeleport(Refs r) throws Throwable {
        Object packet = buildTeleportPacket(r);
        if (packet != null) {
            broadcastPacket(r, packet);
            byte headYawByte = (byte) ((int) (yaw * 256.0F / 360.0F));
            Object headPacket = r.rotateHeadCtor.newInstance(serverPlayer, headYawByte);
            broadcastPacket(r, headPacket);
        } else {
            // No usable position-update packet — last resort.
            broadcastDespawnPackets(r);
            broadcastSpawnPackets(r);
        }
    }

    /**
     * Pick the best position-update packet for the running server.
     * Order: legacy single-arg teleport ctor → 1.21.2+ position-sync packet
     * → 1.21.2+ teleport ctor with PositionMoveRotation. Returns
     * {@code null} only if none resolved.
     */
    private Object buildTeleportPacket(Refs r) throws Throwable {
        if (r.teleportEntityCtor != null) {
            return r.teleportEntityCtor.newInstance(serverPlayer);
        }
        if (r.entityPositionSyncCtor != null && r.posMoveRotCtor != null) {
            Object pos   = r.vec3Ctor.newInstance(x, y, z);
            Object delta = r.vec3Ctor.newInstance(0.0, 0.0, 0.0);
            Object pmr   = r.posMoveRotCtor.newInstance(pos, delta, yaw, pitch);
            return r.entityPositionSyncCtor.newInstance(entityId, pmr, true);
        }
        if (r.teleportEntityCtorNew != null && r.posMoveRotCtor != null) {
            Object pos   = r.vec3Ctor.newInstance(x, y, z);
            Object delta = r.vec3Ctor.newInstance(0.0, 0.0, 0.0);
            Object pmr   = r.posMoveRotCtor.newInstance(pos, delta, yaw, pitch);
            // Empty Set<Relative> = everything is absolute.
            return r.teleportEntityCtorNew.newInstance(entityId, pmr, java.util.Set.of(), true);
        }
        return null;
    }

    /**
     * Cheap rotation-only update: body yaw/pitch via
     * {@code ClientboundMoveEntityPacket.Rot} (when available). This
     * deliberately does NOT also send a head-rotation packet — letting
     * setHeadPitch() touch head yaw was overwriting whatever
     * setHeadYaw() had just set, so idle "look around" never showed up.
     * Callers that want head yaw synced to body yaw send their own
     * RotateHead packet (see {@link #setBodyYaw}, {@link #smoothMoveTo}).
     */
    private void broadcastRotationOnly(Refs r) throws Throwable {
        if (r.moveEntityRotCtor != null) {
            byte yawByte   = (byte) ((int) (yaw * 256.0F / 360.0F));
            byte pitchByte = (byte) ((int) (pitch * 256.0F / 360.0F));
            Object rotPacket = r.moveEntityRotCtor.newInstance(entityId, yawByte, pitchByte, true);
            broadcastPacket(r, rotPacket);
        }
    }

    /** Remove from all clients and mark dead. Idempotent. */
    public void despawn() {
        if (!alive) return;
        alive = false;
        try {
            Refs r = refs();
            unregisterFromWorld(r);
            broadcastDespawnPackets(r);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "(✧) NMS companion despawn failed: " + t, t);
        } finally {
            // Drop the handle from the global skip-set so the GC isn't
            // pinned by a static IdentityHashMap entry forever.
            COMPANION_HANDLES.remove(serverPlayer);
        }
    }

    /** Re-send spawn packets to a single observer (e.g. someone who just logged in nearby). */
    public void resendTo(Player observer) {
        if (!alive || observer == null || !observer.isOnline()) return;
        if (observer.getWorld() != world) return;
        try {
            Refs r = refs();
            Object viewerHandle = r.craftPlayerGetHandle.invoke(observer);
            // Don't bounce the packets off another companion's stub
            // listener — that's what crashed the server before the
            // COMPANION_HANDLES filter was added.
            if (COMPANION_HANDLES.contains(viewerHandle)) return;
            Object viewerConn = r.connectionField.get(viewerHandle);
            sendPackets(r, viewerConn, buildSpawnPackets(r));
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "(✧) NMS companion resendTo failed: " + t, t);
        }
    }

    // ============== internals ==============

    /**
     * Run {@code consumer} once per real-player viewer connection in the
     * current world, skipping every NMS handle in {@link #COMPANION_HANDLES}
     * and any handle whose listener field is null. The skip is what keeps
     * the broadcast loop from trying to push packets through our own (or
     * another companion's) stub listener and tripping a NPE on its absent
     * {@code Connection}.
     */
    private void forEachViewer(Refs r, ViewerOp op) throws Throwable {
        for (Player viewer : world.getPlayers()) {
            Object viewerHandle = r.craftPlayerGetHandle.invoke(viewer);
            if (COMPANION_HANDLES.contains(viewerHandle)) continue;
            Object viewerConn = r.connectionField.get(viewerHandle);
            if (viewerConn == null) continue;
            op.send(viewerConn);
        }
    }

    @FunctionalInterface
    private interface ViewerOp {
        void send(Object connection) throws Throwable;
    }

    private void broadcastSpawnPackets(Refs r) throws Throwable {
        List<Object> packets = buildSpawnPackets(r);
        forEachViewer(r, conn -> sendPackets(r, conn, packets));
    }

    private void broadcastDespawnPackets(Refs r) throws Throwable {
        Object infoRemove = r.playerInfoRemoveCtor.newInstance(
                Collections.singletonList(profileId));
        Object remove = r.removeEntitiesCtor.newInstance(new int[] { entityId });
        List<Object> packets = List.of(remove, infoRemove);
        forEachViewer(r, conn -> sendPackets(r, conn, packets));
    }

    /**
     * Rebuild a player-info update packet with every entry's {@code listed}
     * flag set to false, so the companion never appears in the tablist while
     * its full profile entry (and therefore its skin) is still sent. Returns
     * {@code defaultPacket} unchanged if the unlisted path isn't available on
     * this server build.
     */
    private Object unlisted(Refs r, EnumSet<?> actions, Object defaultPacket) {
        try {
            if (r.playerInfoEntryCanonicalCtor == null || r.entryComponents == null
                    || r.playerInfoUpdateListCtor == null
                    || r.playerInfoUpdateEntriesField == null) {
                return defaultPacket;
            }
            @SuppressWarnings("unchecked")
            List<Object> entries = (List<Object>) r.playerInfoUpdateEntriesField.get(defaultPacket);
            if (entries == null || entries.isEmpty()) return defaultPacket;
            List<Object> rebuilt = new java.util.ArrayList<>(entries.size());
            for (Object e : entries) {
                Object[] args = new Object[r.entryComponents.length];
                for (int i = 0; i < r.entryComponents.length; i++) {
                    Object val = r.entryComponents[i].getAccessor().invoke(e);
                    if (r.entryComponents[i].getType() == boolean.class) {
                        val = Boolean.FALSE; // the lone boolean component is "listed"
                    }
                    args[i] = val;
                }
                rebuilt.add(r.playerInfoEntryCanonicalCtor.newInstance(args));
            }
            return r.playerInfoUpdateListCtor.newInstance(actions, rebuilt);
        } catch (Throwable t) {
            return defaultPacket;
        }
    }

    private List<Object> buildSpawnPackets(Refs r) throws Throwable {
        // ADD_PLAYER teaches the client her profile + skin; UPDATE_LISTED then
        // marks the entry listed=FALSE so she stays out of the tablist while
        // still rendering with her skin. (The Entry built straight from a
        // ServerPlayer hardcodes listed=true, so unlisted() rebuilds it.)
        @SuppressWarnings({"unchecked", "rawtypes"})
        EnumSet<?> actions = EnumSet.of(
                (Enum) r.actionAddPlayer,
                (Enum) r.actionUpdateListed);
        Object infoUpdate = r.playerInfoUpdateCtor.newInstance(actions,
                Collections.singletonList(serverPlayer));
        infoUpdate = unlisted(r, actions, infoUpdate);

        Object addEntity;
        if (r.addEntityCtor != null) {
            addEntity = r.addEntityCtor.newInstance(serverPlayer);
        } else {
            // Raw-arg form (1.21.2+): pull EntityType off the player itself
            // so we don't have to chase EntityType.PLAYER's static field.
            Object playerType = r.entityGetType.invoke(serverPlayer);
            Object zeroVec    = r.vec3Ctor.newInstance(0.0, 0.0, 0.0);
            addEntity = r.addEntityCtorRaw.newInstance(
                    entityId, profileId,
                    x, y, z,
                    pitch, yaw,           // xRot, yRot
                    playerType, 0,        // type, data
                    zeroVec, (double) yaw // deltaMovement, yHeadRot
            );
        }

        byte headYawByte = (byte) ((int) (yaw * 256.0F / 360.0F));
        Object headRot = r.rotateHeadCtor.newInstance(serverPlayer, headYawByte);

        // Mutable so we can conditionally append equipment + entity-data.
        List<Object> packets = new java.util.ArrayList<>(5);
        packets.add(infoUpdate);
        packets.add(addEntity);
        packets.add(headRot);

        // Build the equipment packet so initial armor / weapon renders
        // without waiting for a setMainHand call. Skip if the SetEquipment
        // ctor wasn't resolvable on this build — she'll just look bare-
        // handed until the player gives her something.
        if (r.setEquipmentCtor != null && r.livingGetItemBySlot != null
                && r.pairOfMethod != null) {
            List<Object> pairs = new java.util.ArrayList<>(6);
            for (String slotName : new String[] { "MAINHAND", "OFFHAND", "HEAD", "CHEST", "LEGS", "FEET" }) {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Object slot = Enum.valueOf((Class<Enum>) r.equipmentSlot, slotName);
                Object nmsItem = r.livingGetItemBySlot.invoke(serverPlayer, slot);
                pairs.add(r.pairOfMethod.invoke(null, slot, nmsItem));
            }
            packets.add(r.setEquipmentCtor.newInstance(entityId, pairs));
        }

        // Include the current entity-data (pose, swim/crouch/sprint flags)
        // so a freshly-rendered viewer — or the same viewer after a
        // cross-world respawn — sees her in the pose she's actually in,
        // instead of defaulting to STANDING until the next pose change.
        // Uses getNonDefaultValues (a full snapshot) rather than packDirty
        // so we don't consume the dirty state the broadcast path relies on.
        Object dataPacket = buildEntityDataSnapshotPacket(r);
        if (dataPacket != null) packets.add(dataPacket);

        return packets;
    }

    /**
     * Build a {@code ClientboundSetEntityDataPacket} carrying the entity's
     * full set of non-default data values (pose, shared flags, etc.), for
     * seeding a viewer's initial render. Returns {@code null} if the pose
     * plumbing isn't available or there's nothing non-default to send.
     */
    private Object buildEntityDataSnapshotPacket(Refs r) throws Throwable {
        if (r.entityGetEntityData == null || r.setEntityDataPacketCtor == null
                || r.synchedDataGetNonDefault == null) return null;
        Object data = r.entityGetEntityData.invoke(serverPlayer);
        Object list = r.synchedDataGetNonDefault.invoke(data);
        if (!(list instanceof java.util.List<?>) || ((java.util.List<?>) list).isEmpty()) return null;
        return r.setEntityDataPacketCtor.newInstance(entityId, list);
    }

    private static void applyEquipmentNms(Refs r, Object sp, String slotName, ItemStack item) throws Throwable {
        if (item == null) return;
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object slot = Enum.valueOf((Class<Enum>) r.equipmentSlot, slotName);
        Object nms  = r.craftItemStackAsNMSCopy.invoke(null, item);
        if (r.livingSetItemSlot != null) {
            r.livingSetItemSlot.invoke(sp, slot, nms, true);
        } else {
            r.livingSetItemSlot2.invoke(sp, slot, nms);
        }
    }

    private static void sendPackets(Refs r, Object connection, List<Object> packets) throws Throwable {
        if (connection == null) return;
        for (Object p : packets) {
            r.connectionSend.invoke(connection, p);
        }
    }

    private void broadcastPacket(Refs r, Object packet) throws Throwable {
        forEachViewer(r, conn -> r.connectionSend.invoke(conn, packet));
    }

    /**
     * Allocate an instance of {@code cls} without invoking any constructor.
     * All instance fields are zero/null afterward — useful when a class has
     * no usable public constructor and we only need to plug it into a field
     * of the right type to satisfy a downstream null-check or simple getter.
     */
    private static Object unsafeAllocate(Class<?> cls) throws Throwable {
        Class<?> unsafeCls = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeCls.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        Method allocateInstance = unsafeCls.getMethod("allocateInstance", Class.class);
        return allocateInstance.invoke(unsafe, cls);
    }

    /**
     * Build a {@code PropertyMap} whose backing multimap is mutable.
     *
     * <p>Authlib in Paper 1.21.10+ both (a) hides the no-arg constructor
     * and (b) initializes the internal multimap to {@code ImmutableMultimap.of()}
     * even when a constructor is reachable. So we either invoke a no-arg
     * ctor when one exists, or allocate an instance via {@code Unsafe} when
     * not — and in either case overwrite the internal field with a mutable
     * {@code LinkedHashMultimap} so subsequent {@code put()} calls succeed.
     */
    private static Object newMutablePropertyMap(Refs r) throws Throwable {
        Object props = (r.propertyMapCtor != null)
                ? r.propertyMapCtor.newInstance()
                : unsafeAllocate(r.propertyMap);
        if (r.propertyMapBackingField != null) {
            r.propertyMapBackingField.set(props,
                    com.google.common.collect.LinkedHashMultimap.create());
        }
        return props;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Refs refs() throws Throwable {
        Refs r = refs;
        if (r != null) return r;
        synchronized (NmsCompanion.class) {
            if (refs != null) return refs;
            r = new Refs();

            r.minecraftServer    = Class.forName("net.minecraft.server.MinecraftServer");
            r.serverLevel        = Class.forName("net.minecraft.server.level.ServerLevel");
            r.serverPlayer       = Class.forName("net.minecraft.server.level.ServerPlayer");
            r.entity             = Class.forName("net.minecraft.world.entity.Entity");
            r.livingEntity       = Class.forName("net.minecraft.world.entity.LivingEntity");
            r.equipmentSlot      = Class.forName("net.minecraft.world.entity.EquipmentSlot");
            r.nmsItemStack       = Class.forName("net.minecraft.world.item.ItemStack");
            r.clientInformation  = Class.forName("net.minecraft.server.level.ClientInformation");
            r.gameProfile        = Class.forName("com.mojang.authlib.GameProfile");
            r.property           = Class.forName("com.mojang.authlib.properties.Property");
            r.propertyMap        = Class.forName("com.mojang.authlib.properties.PropertyMap");
            r.componentCls       = Class.forName("net.minecraft.network.chat.Component");

            r.packetCls               = Class.forName("net.minecraft.network.protocol.Packet");
            r.playerInfoUpdatePacket  = Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket");
            r.playerInfoUpdateAction  = Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket$Action");
            r.addEntityPacket         = Class.forName("net.minecraft.network.protocol.game.ClientboundAddEntityPacket");
            r.removeEntitiesPacket    = Class.forName("net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket");
            r.teleportEntityPacket    = Class.forName("net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket");
            r.rotateHeadPacket        = Class.forName("net.minecraft.network.protocol.game.ClientboundRotateHeadPacket");
            r.playerInfoRemovePacket  = Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket");

            r.craftServer    = Bukkit.getServer().getClass();
            r.craftWorld     = Class.forName("org.bukkit.craftbukkit.CraftWorld");
            r.craftPlayer    = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer");
            r.craftItemStack = Class.forName("org.bukkit.craftbukkit.inventory.CraftItemStack");

            r.craftServerGetServer    = r.craftServer.getMethod("getServer");
            r.craftWorldGetHandle     = r.craftWorld.getMethod("getHandle");
            r.craftPlayerGetHandle    = r.craftPlayer.getMethod("getHandle");
            r.craftItemStackAsNMSCopy = r.craftItemStack.getMethod("asNMSCopy", ItemStack.class);

            // Mojang authlib 6.x renamed GameProfile.getProperties() to
            // properties() (record-component style). Try the new name first,
            // then fall back to the old.
            Method propsMethod;
            try {
                propsMethod = r.gameProfile.getMethod("properties");
            } catch (NoSuchMethodException nsme) {
                propsMethod = r.gameProfile.getMethod("getProperties");
            }
            r.gameProfileGetProperties = propsMethod;
            r.propertyMapPut           = r.propertyMap.getMethod("put", Object.class, Object.class);
            // No-arg PropertyMap() ctor was removed in authlib shipped with
            // Paper 1.21.10+. Even when present, modern authlib initializes
            // the backing multimap to ImmutableMultimap.of() which rejects
            // put(). Treat the ctor as optional and always overwrite the
            // backing field with a mutable LinkedHashMultimap below.
            try {
                r.propertyMapCtor = r.propertyMap.getDeclaredConstructor();
                r.propertyMapCtor.setAccessible(true);
            } catch (NoSuchMethodException ignored) {
                r.propertyMapCtor = null;
            }
            // Find the internal Multimap field — the only instance field on
            // PropertyMap that's a Multimap. ForwardingMultimap.put() delegates
            // to it, so swapping it for a mutable one makes the whole map
            // accept put() calls regardless of how it was constructed.
            for (Field f : r.propertyMap.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (com.google.common.collect.Multimap.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    r.propertyMapBackingField = f;
                    break;
                }
            }
            if (r.propertyMapBackingField == null && r.propertyMapCtor == null) {
                throw new NoSuchMethodException(
                        "PropertyMap: no usable no-arg constructor and no "
                        + "internal Multimap field found — authlib layout "
                        + "changed in a way this plugin doesn't yet support");
            }
            // Prefer the 3-arg constructor (newer authlib's record shape) so
            // we can hand it a mutable PropertyMap with the texture already
            // baked in — properties() on the older 2-arg result is wrapped
            // in ImmutableMultimap and rejects put().
            try {
                r.gameProfileCtor3 = r.gameProfile.getConstructor(
                        UUID.class, String.class, r.propertyMap);
            } catch (NoSuchMethodException ignored) {
                r.gameProfileCtor3 = null;
            }
            try {
                r.gameProfileCtor2 = r.gameProfile.getConstructor(UUID.class, String.class);
            } catch (NoSuchMethodException ignored) {
                r.gameProfileCtor2 = null;
            }
            if (r.gameProfileCtor3 == null && r.gameProfileCtor2 == null) {
                throw new NoSuchMethodException(
                        "GameProfile: neither (UUID, String) nor "
                        + "(UUID, String, PropertyMap) constructor was found");
            }
            r.clientInfoCreateDefault  = r.clientInformation.getMethod("createDefault");
            r.componentLiteral         = r.componentCls.getMethod("literal", String.class);

            r.serverPlayerCtor = r.serverPlayer.getConstructor(
                    r.minecraftServer, r.serverLevel, r.gameProfile, r.clientInformation);

            r.entitySetPos             = r.entity.getMethod("setPos", double.class, double.class, double.class);
            r.entityGetId              = r.entity.getMethod("getId");
            r.entitySetYRot            = r.entity.getMethod("setYRot", float.class);
            r.entitySetXRot            = r.entity.getMethod("setXRot", float.class);
            r.entitySetYHeadRot        = r.entity.getMethod("setYHeadRot", float.class);
            r.entitySetCustomName      = r.entity.getMethod("setCustomName", r.componentCls);
            r.entitySetCustomNameVisible = r.entity.getMethod("setCustomNameVisible", boolean.class);
            r.entitySetGlowingTag      = r.entity.getMethod("setGlowingTag", boolean.class);

            // setItemSlot has two shapes across versions; cache whichever one resolves.
            try {
                r.livingSetItemSlot = r.livingEntity.getMethod("setItemSlot",
                        r.equipmentSlot, r.nmsItemStack, boolean.class);
            } catch (NoSuchMethodException ignored) {
                r.livingSetItemSlot2 = r.livingEntity.getMethod("setItemSlot",
                        r.equipmentSlot, r.nmsItemStack);
            }

            r.connectionField = r.serverPlayer.getDeclaredField("connection");
            r.connectionField.setAccessible(true);
            r.serverPacketListenerCls = Class.forName("net.minecraft.server.network.ServerGamePacketListenerImpl");
            r.connectionSend = r.serverPacketListenerCls.getMethod("send", r.packetCls);

            // ----- Stub Connection plumbing -----
            // A real Connection (with channel=null) makes Connection.send()
            // skip the actual write and just queue to pendingActions, which
            // we drain periodically. Without this, server-internal calls to
            // player.connection.send() against the companion NPE on tick.
            try {
                r.connectionCls = Class.forName("net.minecraft.network.Connection");
                r.packetFlowCls = Class.forName("net.minecraft.network.protocol.PacketFlow");
                // Pick SERVERBOUND if it's there (matches the direction a
                // real player listener handles), CLIENTBOUND otherwise — the
                // value doesn't actually matter functionally since we never
                // send/receive over the wire, but it has to be a valid enum.
                Object[] flowValues = r.packetFlowCls.getEnumConstants();
                if (flowValues != null) {
                    for (Object v : flowValues) {
                        if (v instanceof Enum<?> e && "SERVERBOUND".equals(e.name())) {
                            r.packetFlowEnumValue = v;
                            break;
                        }
                    }
                    if (r.packetFlowEnumValue == null && flowValues.length > 0) {
                        r.packetFlowEnumValue = flowValues[0];
                    }
                }
                r.connectionCtor = r.connectionCls.getConstructor(r.packetFlowCls);

                // Walk up the listener class hierarchy to find the
                // declared `connection` field — declared on the
                // ServerCommonPacketListenerImpl parent in modern Paper.
                Class<?> walker = r.serverPacketListenerCls;
                while (walker != null && walker != Object.class) {
                    try {
                        Field f = walker.getDeclaredField("connection");
                        if (r.connectionCls.isAssignableFrom(f.getType())) {
                            f.setAccessible(true);
                            r.listenerConnectionField = f;
                            break;
                        }
                    } catch (NoSuchFieldException ignored) {}
                    walker = walker.getSuperclass();
                }

                // Find the queue-typed field on Connection — that's
                // `pendingActions`. Naming-defensive: pick by type, not
                // by name, to survive remapping.
                for (Field f : r.connectionCls.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    if (java.util.Queue.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        r.connectionPendingActionsField = f;
                        break;
                    }
                }
            } catch (Throwable ignored) {
                // Stub Connection setup failed — world-integrated mode will
                // still crash on this Paper build. The plugin defaults that
                // mode off and surfaces a warning the first time someone
                // tries to enable it; falling through silently here just
                // keeps spawn() viable for the safe (Phase-1) path.
                r.connectionCls = null;
                r.connectionCtor = null;
                r.listenerConnectionField = null;
                r.connectionPendingActionsField = null;
                r.packetFlowEnumValue = null;
            }

            r.playerInfoUpdateCtor = r.playerInfoUpdatePacket
                    .getConstructor(EnumSet.class, java.util.Collection.class);

            // Resolve the pieces needed to send an UNLISTED player-info entry
            // (so she never shows in the tablist). The Entry built from a
            // ServerPlayer hardcodes listed=true, so we rebuild it with
            // listed=false. Every lookup is optional — on a build where it
            // doesn't resolve, broadcastSpawn falls back to the listed packet.
            try {
                Class<?> entryCls = Class.forName(
                        "net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket$Entry");
                if (entryCls.isRecord()) {
                    java.lang.reflect.RecordComponent[] comps = entryCls.getRecordComponents();
                    Class<?>[] types = new Class<?>[comps.length];
                    for (int i = 0; i < comps.length; i++) types[i] = comps[i].getType();
                    r.playerInfoEntryCanonicalCtor = entryCls.getDeclaredConstructor(types);
                    r.playerInfoEntryCanonicalCtor.setAccessible(true);
                    r.entryComponents = comps;
                }
                r.playerInfoUpdateListCtor = r.playerInfoUpdatePacket
                        .getDeclaredConstructor(EnumSet.class, java.util.List.class);
                r.playerInfoUpdateListCtor.setAccessible(true);
                for (java.lang.reflect.Field f : r.playerInfoUpdatePacket.getDeclaredFields()) {
                    if (java.util.List.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        r.playerInfoUpdateEntriesField = f;
                        break;
                    }
                }
            } catch (Throwable ignored) {
                r.playerInfoEntryCanonicalCtor = null;
                r.entryComponents = null;
                r.playerInfoUpdateListCtor = null;
                r.playerInfoUpdateEntriesField = null;
            }

            // ClientboundAddEntityPacket(Entity) was removed in 1.21.2; the
            // current shape takes raw position/rotation/type values.
            r.vec3          = Class.forName("net.minecraft.world.phys.Vec3");
            r.vec3Ctor      = r.vec3.getConstructor(double.class, double.class, double.class);
            r.entityGetType = r.entity.getMethod("getType");
            Class<?> entityType = Class.forName("net.minecraft.world.entity.EntityType");
            try {
                r.addEntityCtor = r.addEntityPacket.getConstructor(r.entity);
            } catch (NoSuchMethodException ignored) {
                r.addEntityCtor = null;
            }
            try {
                r.addEntityCtorRaw = r.addEntityPacket.getConstructor(
                        int.class, UUID.class,
                        double.class, double.class, double.class,
                        float.class, float.class,
                        entityType,
                        int.class,
                        r.vec3,
                        double.class);
            } catch (NoSuchMethodException ignored) {
                r.addEntityCtorRaw = null;
            }
            if (r.addEntityCtor == null && r.addEntityCtorRaw == null) {
                throw new NoSuchMethodException(
                        "ClientboundAddEntityPacket: neither (Entity) nor "
                        + "the raw 11-arg constructor was found");
            }
            r.removeEntitiesCtor   = r.removeEntitiesPacket.getConstructor(int[].class);

            // ----- Position-update primitives -----
            // PositionMoveRotation (1.21.2+) — moved package between snapshots,
            // so try both known locations.
            try {
                r.positionMoveRotation = Class.forName("net.minecraft.world.entity.PositionMoveRotation");
            } catch (ClassNotFoundException ignored) {
                try {
                    r.positionMoveRotation = Class.forName(
                            "net.minecraft.network.protocol.game.PositionMoveRotation");
                } catch (ClassNotFoundException ignored2) {
                    r.positionMoveRotation = null;
                }
            }
            if (r.positionMoveRotation != null) {
                try {
                    r.posMoveRotCtor = r.positionMoveRotation.getConstructor(
                            r.vec3, r.vec3, float.class, float.class);
                } catch (NoSuchMethodException ignored) {
                    r.posMoveRotCtor = null;
                }
            }
            try {
                r.relative = Class.forName("net.minecraft.world.entity.Relative");
            } catch (ClassNotFoundException ignored) {
                r.relative = null;
            }

            // ClientboundTeleportEntityPacket: legacy (Entity) ctor first,
            // then 1.21.2+ record-style.
            try {
                r.teleportEntityCtor = r.teleportEntityPacket.getConstructor(r.entity);
            } catch (NoSuchMethodException ignored) {
                r.teleportEntityCtor = null;
            }
            if (r.teleportEntityCtor == null && r.positionMoveRotation != null) {
                try {
                    r.teleportEntityCtorNew = r.teleportEntityPacket.getConstructor(
                            int.class, r.positionMoveRotation, java.util.Set.class, boolean.class);
                } catch (NoSuchMethodException ignored) {
                    r.teleportEntityCtorNew = null;
                }
            }

            // ClientboundEntityPositionSyncPacket (1.21.2+): simpler API
            // for "just sync this entity's position", and what we actually
            // prefer when the legacy teleport ctor is gone.
            try {
                r.entityPositionSyncPacket = Class.forName(
                        "net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket");
                if (r.positionMoveRotation != null) {
                    r.entityPositionSyncCtor = r.entityPositionSyncPacket.getConstructor(
                            int.class, r.positionMoveRotation, boolean.class);
                }
            } catch (Throwable ignored) {
                r.entityPositionSyncPacket = null;
                r.entityPositionSyncCtor   = null;
            }

            // ClientboundMoveEntityPacket$Rot — rotation-only updates. Used
            // by setHeadPitch so we don't trigger a full teleport (or worse,
            // despawn+respawn) every tick when standing still.
            try {
                r.moveEntityRotPacket = Class.forName(
                        "net.minecraft.network.protocol.game.ClientboundMoveEntityPacket$Rot");
                r.moveEntityRotCtor = r.moveEntityRotPacket.getConstructor(
                        int.class, byte.class, byte.class, boolean.class);
            } catch (Throwable ignored) {
                r.moveEntityRotPacket = null;
                r.moveEntityRotCtor   = null;
            }

            // ClientboundMoveEntityPacket$PosRot — small position+rotation
            // delta. Sending these (instead of teleport packets) is what
            // makes the client interpolate position smoothly *and* drive
            // the limb-swing walk animation. Fall back to teleport if the
            // class isn't there for some reason.
            try {
                r.moveEntityPosRotPacket = Class.forName(
                        "net.minecraft.network.protocol.game.ClientboundMoveEntityPacket$PosRot");
                r.moveEntityPosRotCtor = r.moveEntityPosRotPacket.getConstructor(
                        int.class,
                        short.class, short.class, short.class,
                        byte.class, byte.class,
                        boolean.class);
            } catch (Throwable ignored) {
                r.moveEntityPosRotPacket = null;
                r.moveEntityPosRotCtor   = null;
            }

            // ServerLevel.players — the field mob target selectors iterate.
            // It's declared on ServerLevel as a private List<ServerPlayer>;
            // we walk the declared fields and pick the (only) List<*> with
            // a Player-ish element so we're robust to renames.
            try {
                for (Field f : r.serverLevel.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    if (!java.util.List.class.isAssignableFrom(f.getType())) continue;
                    // Match on the generic parameter when possible — there
                    // are usually multiple List fields on ServerLevel.
                    java.lang.reflect.Type gt = f.getGenericType();
                    if (gt instanceof java.lang.reflect.ParameterizedType pt
                            && pt.getActualTypeArguments().length == 1
                            && pt.getActualTypeArguments()[0] instanceof Class<?> elem
                            && r.serverPlayer.isAssignableFrom(elem)) {
                        f.setAccessible(true);
                        r.levelPlayersField = f;
                        break;
                    }
                }
            } catch (Throwable ignored) {
                r.levelPlayersField = null;
            }

            r.rotateHeadCtor       = r.rotateHeadPacket.getConstructor(r.entity, byte.class);

            // ----- Equipment + animation packets -----
            // ClientboundSetEquipmentPacket(int id, List<Pair<EquipmentSlot, ItemStack>>)
            // — required for the client to render anything she's holding
            // or wearing. Without an explicit broadcast the spawn packets
            // alone leave her bare-handed.
            try {
                r.pairCls = Class.forName("com.mojang.datafixers.util.Pair");
                r.pairOfMethod = r.pairCls.getMethod("of", Object.class, Object.class);
                r.setEquipmentPacket = Class.forName(
                        "net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket");
                r.setEquipmentCtor = r.setEquipmentPacket.getConstructor(int.class, List.class);
                r.livingGetItemBySlot = r.livingEntity.getMethod("getItemBySlot", r.equipmentSlot);
            } catch (Throwable ignored) {
                r.setEquipmentPacket = null;
                r.setEquipmentCtor   = null;
                r.livingGetItemBySlot = null;
            }
            // ClientboundAnimatePacket(Entity, int action) — action 0 is
            // the main-hand swing.
            try {
                r.animatePacket = Class.forName(
                        "net.minecraft.network.protocol.game.ClientboundAnimatePacket");
                r.animateCtor = r.animatePacket.getConstructor(r.entity, int.class);
            } catch (Throwable ignored) {
                r.animatePacket = null;
                r.animateCtor   = null;
            }
            r.playerInfoRemoveCtor = r.playerInfoRemovePacket.getConstructor(List.class);

            r.actionAddPlayer    = Enum.valueOf((Class<Enum>) r.playerInfoUpdateAction, "ADD_PLAYER");
            r.actionUpdateListed = Enum.valueOf((Class<Enum>) r.playerInfoUpdateAction, "UPDATE_LISTED");

            // ----- Swim pose plumbing -----
            // Resolved best-effort. If any piece is missing on this Paper
            // build the setSwimming() path no-ops gracefully, so a Phase-1
            // companion still spawns + moves correctly.
            try {
                r.poseCls = Class.forName("net.minecraft.world.entity.Pose");
                r.poseSwimming = Enum.valueOf((Class<Enum>) r.poseCls, "SWIMMING");
                r.poseStanding = Enum.valueOf((Class<Enum>) r.poseCls, "STANDING");
                r.entitySetPose = r.entity.getMethod("setPose", r.poseCls);
                r.entityGetEntityData = r.entity.getMethod("getEntityData");
                Class<?> synchedCls = Class.forName("net.minecraft.network.syncher.SynchedEntityData");
                r.synchedDataGetNonDefault = synchedCls.getMethod("getNonDefaultValues");
                try {
                    r.synchedDataPackDirty = synchedCls.getMethod("packDirty");
                } catch (NoSuchMethodException ignored) {
                    r.synchedDataPackDirty = null;
                }
                r.setEntityDataPacketCls = Class.forName(
                        "net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket");
                r.setEntityDataPacketCtor = r.setEntityDataPacketCls.getConstructor(
                        int.class, java.util.List.class);
                try {
                    r.entitySetSwimming = r.entity.getMethod("setSwimming", boolean.class);
                } catch (NoSuchMethodException ignored) {
                    r.entitySetSwimming = null;
                }
                try {
                    r.entitySetShiftKeyDown = r.entity.getMethod("setShiftKeyDown", boolean.class);
                } catch (NoSuchMethodException ignored) {
                    r.entitySetShiftKeyDown = null;
                }
                try {
                    r.entitySetSprinting = r.entity.getMethod("setSprinting", boolean.class);
                } catch (NoSuchMethodException ignored) {
                    r.entitySetSprinting = null;
                }
            } catch (Throwable t) {
                // Pose / data packet plumbing not available — log once and
                // continue. setSwimming() will short-circuit.
                r.poseCls = null;
                r.poseSwimming = null;
                r.poseStanding = null;
                r.entitySetPose = null;
                r.entityGetEntityData = null;
                r.entitySetSwimming = null;
                r.entitySetShiftKeyDown = null;
                r.entitySetSprinting = null;
                r.synchedDataGetNonDefault = null;
                r.synchedDataPackDirty = null;
                r.setEntityDataPacketCls = null;
                r.setEntityDataPacketCtor = null;
            }

            refs = r;
            return r;
        }
    }
}
