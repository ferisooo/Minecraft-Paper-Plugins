package com.ferisooo.herobrine;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
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
 * Reflection-only, packet-driven player NPC used to render Herobrine as a
 * Steve-skinned figure with a (optionally) glowing outline. Mojang-mapped
 * class names are resolved at runtime so the plugin builds against plain
 * {@code paper-api} without {@code paperweight-userdev}.
 *
 * <p>The NPC is purely visual: a {@code ServerPlayer} object is constructed
 * (so the client gets a real profile + skin) but it is never registered with
 * {@code ServerLevel.players} or the chunk tracker. Visibility is driven by
 * manually broadcasting spawn / move / despawn packets to nearby real
 * players. Mobs ignore it, it has no server-side hitbox — exactly what a
 * stalker wants. Damage and health are tracked logically by the plugin (see
 * {@link HerobrineEntity}); player hits are detected with a ray-trace on the
 * arm-swing event rather than a real hitbox.
 *
 * <p>This is a trimmed adaptation of the same technique used elsewhere in the
 * repository (the KawaiiCompanion NPC). If any reflection step fails,
 * {@link #spawn} returns {@code null} after logging — the caller falls back
 * gracefully.
 */
public final class HerobrineNms {

    private static volatile Refs refs;

    /** Every live Herobrine handle, so broadcast loops never push packets through our own stub listener. */
    private static final java.util.Set<Object> HANDLES =
            Collections.synchronizedSet(Collections.newSetFromMap(new java.util.IdentityHashMap<>()));

    private static final class Refs {
        Class<?> minecraftServer, serverLevel, serverPlayer, entity, livingEntity, equipmentSlot, nmsItemStack;
        Class<?> clientInformation, gameProfile, property, propertyMap, componentCls, packetCls;
        Class<?> playerInfoUpdatePacket, playerInfoUpdateAction, addEntityPacket, removeEntitiesPacket;
        Class<?> teleportEntityPacket, rotateHeadPacket, playerInfoRemovePacket;
        Class<?> entityPositionSyncPacket, positionMoveRotation, moveEntityRotPacket, moveEntityPosRotPacket;
        Class<?> craftServer, craftWorld, craftPlayer, serverPacketListenerCls;
        Class<?> vec3, animatePacket;

        Method craftServerGetServer, craftWorldGetHandle, craftPlayerGetHandle;
        Method gameProfileGetProperties, propertyMapPut, clientInfoCreateDefault, componentLiteral;
        Method entitySetPos, entityGetId, entitySetYRot, entitySetXRot, entitySetYHeadRot;
        Method entitySetCustomName, entitySetCustomNameVisible, entitySetGlowingTag, entityGetType;
        Method connectionSend;

        Field connectionField, propertyMapBackingField;

        Constructor<?> gameProfileCtor2, gameProfileCtor3, propertyMapCtor, serverPlayerCtor;
        Constructor<?> playerInfoUpdateCtor, addEntityCtor, addEntityCtorRaw, removeEntitiesCtor;
        Constructor<?> teleportEntityCtor, teleportEntityCtorNew, entityPositionSyncCtor, posMoveRotCtor;
        Constructor<?> moveEntityRotCtor, moveEntityPosRotCtor, vec3Ctor, rotateHeadCtor;
        Constructor<?> playerInfoRemoveCtor, animateCtor;

        Object actionAddPlayer, actionUpdateListed;
    }

    private final Plugin plugin;
    private final Object serverPlayer;
    private final int entityId;
    private final UUID profileId;
    private World world;
    private double x, y, z;
    private float yaw, pitch;
    private boolean alive = true;
    /** Players who already have him rendered, so resends don't re-spawn (flicker). */
    private final java.util.Set<UUID> rendered = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private double sentX, sentY, sentZ;

    private HerobrineNms(Plugin plugin, Object sp, int id, UUID profileId,
                         World world, double x, double y, double z, float yaw, float pitch) {
        this.plugin = plugin; this.serverPlayer = sp; this.entityId = id; this.profileId = profileId;
        this.world = world; this.x = x; this.y = y; this.z = z; this.yaw = yaw; this.pitch = pitch;
    }

    /** Construct + spawn the NPC, broadcasting initial visibility packets. Returns {@code null} on reflection failure. */
    public static HerobrineNms spawn(Plugin plugin, String profileName, UUID profileId,
                                     String textureValue, String textureSignature,
                                     Location loc, boolean glowing) {
        Logger log = plugin.getLogger();
        if (loc.getWorld() == null) { log.warning("[Herobrine] NMS spawn: location has no world"); return null; }
        try {
            Refs r = refs();

            Object props = newMutablePropertyMap(r);
            Object texProp = null;
            if (textureValue != null && !textureValue.isBlank()) {
                texProp = r.property.getConstructor(String.class, String.class, String.class)
                        .newInstance("textures", textureValue,
                                (textureSignature == null || textureSignature.isBlank()) ? null : textureSignature);
                r.propertyMapPut.invoke(props, "textures", texProp);
            }
            Object profile;
            if (r.gameProfileCtor3 != null) {
                profile = r.gameProfileCtor3.newInstance(profileId, profileName, props);
            } else {
                profile = r.gameProfileCtor2.newInstance(profileId, profileName);
                if (texProp != null) {
                    Object profileProps = r.gameProfileGetProperties.invoke(profile);
                    if (r.propertyMapBackingField != null) {
                        r.propertyMapBackingField.set(profileProps,
                                com.google.common.collect.LinkedHashMultimap.create());
                    }
                    r.propertyMapPut.invoke(profileProps, "textures", texProp);
                }
            }

            Object server = r.craftServerGetServer.invoke(Bukkit.getServer());
            Object level = r.craftWorldGetHandle.invoke(loc.getWorld());
            Object clientInfo = r.clientInfoCreateDefault.invoke(null);
            Object sp = r.serverPlayerCtor.newInstance(server, level, profile, clientInfo);

            // No real network: give the listener field a constructor-less stub so
            // player.connection isn't null during PlayerInfoUpdate packet build.
            Object stubListener = unsafeAllocate(r.serverPacketListenerCls);
            r.connectionField.set(sp, stubListener);

            r.entitySetPos.invoke(sp, loc.getX(), loc.getY(), loc.getZ());
            r.entitySetYRot.invoke(sp, loc.getYaw());
            r.entitySetXRot.invoke(sp, loc.getPitch());
            r.entitySetYHeadRot.invoke(sp, loc.getYaw());
            int eid = (int) r.entityGetId.invoke(sp);
            if (glowing) r.entitySetGlowingTag.invoke(sp, true);

            HerobrineNms npc = new HerobrineNms(plugin, sp, eid, profileId, loc.getWorld(),
                    loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
            HANDLES.add(sp);
            npc.sentX = npc.x; npc.sentY = npc.y; npc.sentZ = npc.z;
            npc.broadcastSpawnPackets(r);
            return npc;
        } catch (Throwable t) {
            log.log(Level.WARNING, "[Herobrine] NMS spawn failed: " + t, t);
            return null;
        }
    }

    public boolean isDead() { return !alive; }
    public World getWorld() { return world; }
    public Location getLocation() { return new Location(world, x, y, z, yaw, pitch); }
    public int getEntityId() { return entityId; }

    /** Move + rotate. Cross-world is despawn + respawn. */
    public void teleport(Location loc) {
        if (!alive || loc.getWorld() == null) return;
        try {
            Refs r = refs();
            if (loc.getWorld() != world) {
                broadcastDespawnPackets(r);
                rendered.clear(); // new world has different viewers
                world = loc.getWorld();
                applyPos(r, loc);
                broadcastSpawnPackets(r);
                sentX = x; sentY = y; sentZ = z;
                return;
            }
            applyPos(r, loc);
            broadcastTeleport(r);
            sentX = x; sentY = y; sentZ = z;
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "[Herobrine] teleport failed: " + t, t);
        }
    }

    private void applyPos(Refs r, Location loc) throws Throwable {
        this.x = loc.getX(); this.y = loc.getY(); this.z = loc.getZ();
        this.yaw = loc.getYaw(); this.pitch = loc.getPitch();
        r.entitySetPos.invoke(serverPlayer, x, y, z);
        r.entitySetYRot.invoke(serverPlayer, yaw);
        r.entitySetXRot.invoke(serverPlayer, pitch);
        r.entitySetYHeadRot.invoke(serverPlayer, yaw);
    }

    /** Smooth incremental move so clients interpolate + animate a walk. Falls back to teleport for big jumps. */
    public void smoothMoveTo(Location target) {
        if (!alive || target.getWorld() == null) return;
        try {
            Refs r = refs();
            if (target.getWorld() != world) { teleport(target); return; }
            double dx = target.getX() - sentX, dy = target.getY() - sentY, dz = target.getZ() - sentZ;
            if (r.moveEntityPosRotCtor == null
                    || Math.abs(dx) >= 7.99 || Math.abs(dy) >= 7.99 || Math.abs(dz) >= 7.99) {
                teleport(target); return;
            }
            applyPos(r, target);
            short dxs = (short) Math.round(dx * 4096.0), dys = (short) Math.round(dy * 4096.0), dzs = (short) Math.round(dz * 4096.0);
            byte yawB = (byte) ((int) (yaw * 256.0F / 360.0F)), pitchB = (byte) ((int) (pitch * 256.0F / 360.0F));
            broadcastPacket(r, r.moveEntityPosRotCtor.newInstance(entityId, dxs, dys, dzs, yawB, pitchB, true));
            broadcastPacket(r, r.rotateHeadCtor.newInstance(serverPlayer, yawB));
            sentX += dxs / 4096.0; sentY += dys / 4096.0; sentZ += dzs / 4096.0;
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "[Herobrine] smoothMoveTo failed: " + t, t);
        }
    }

    /** Face a target location (turns head + body). */
    public void lookAt(Location target) {
        if (!alive || target.getWorld() != world) return;
        double dx = target.getX() - x, dy = target.getY() - (y + 1.62), dz = target.getZ() - z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        float newYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float newPitch = (float) Math.toDegrees(-Math.atan2(dy, dist));
        try {
            Refs r = refs();
            this.yaw = newYaw; this.pitch = newPitch;
            r.entitySetYRot.invoke(serverPlayer, newYaw);
            r.entitySetXRot.invoke(serverPlayer, newPitch);
            r.entitySetYHeadRot.invoke(serverPlayer, newYaw);
            byte yawB = (byte) ((int) (newYaw * 256.0F / 360.0F)), pitchB = (byte) ((int) (newPitch * 256.0F / 360.0F));
            if (r.moveEntityRotCtor != null) {
                broadcastPacket(r, r.moveEntityRotCtor.newInstance(entityId, yawB, pitchB, true));
            }
            broadcastPacket(r, r.rotateHeadCtor.newInstance(serverPlayer, yawB));
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "[Herobrine] lookAt failed: " + t, t);
        }
    }

    /** Play a main-hand swing (attack animation). */
    public void swing() {
        if (!alive) return;
        try {
            Refs r = refs();
            if (r.animateCtor == null) return;
            broadcastPacket(r, r.animateCtor.newInstance(serverPlayer, 0));
        } catch (Throwable ignored) { }
    }

    /** Remove from all clients. Idempotent. */
    public void despawn() {
        if (!alive) return;
        alive = false;
        rendered.clear();
        try { broadcastDespawnPackets(refs()); }
        catch (Throwable t) { plugin.getLogger().log(Level.WARNING, "[Herobrine] despawn failed: " + t, t); }
        finally { HANDLES.remove(serverPlayer); }
    }

    /** Re-send spawn packets to one observer (e.g. someone who just walked into range). */
    public void resendTo(Player observer) {
        if (!alive || observer == null || !observer.isOnline() || observer.getWorld() != world) return;
        if (rendered.contains(observer.getUniqueId())) return; // already sees him — don't re-spawn
        try {
            Refs r = refs();
            Object handle = r.craftPlayerGetHandle.invoke(observer);
            if (HANDLES.contains(handle)) return;
            Object conn = r.connectionField.get(handle);
            sendPackets(r, conn, buildSpawnPackets(r));
            rendered.add(observer.getUniqueId());
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "[Herobrine] resendTo failed: " + t, t);
        }
    }

    // ============== internals ==============

    private void forEachViewer(Refs r, ViewerOp op) throws Throwable {
        for (Player viewer : world.getPlayers()) {
            Object handle = r.craftPlayerGetHandle.invoke(viewer);
            if (HANDLES.contains(handle)) continue;
            Object conn = r.connectionField.get(handle);
            if (conn == null) continue;
            op.send(conn);
        }
    }

    @FunctionalInterface private interface ViewerOp { void send(Object connection) throws Throwable; }

    private void broadcastSpawnPackets(Refs r) throws Throwable {
        List<Object> packets = buildSpawnPackets(r);
        forEachViewer(r, conn -> sendPackets(r, conn, packets));
        // Remember who now sees him so resends don't re-spawn (which flickers
        // the skin + snaps his position).
        for (Player viewer : world.getPlayers()) rendered.add(viewer.getUniqueId());
    }

    private void broadcastDespawnPackets(Refs r) throws Throwable {
        Object infoRemove = r.playerInfoRemoveCtor.newInstance(Collections.singletonList(profileId));
        Object remove = r.removeEntitiesCtor.newInstance(new int[]{entityId});
        List<Object> packets = List.of(remove, infoRemove);
        forEachViewer(r, conn -> sendPackets(r, conn, packets));
    }

    private List<Object> buildSpawnPackets(Refs r) throws Throwable {
        @SuppressWarnings({"unchecked", "rawtypes"})
        EnumSet<?> actions = EnumSet.of((Enum) r.actionAddPlayer, (Enum) r.actionUpdateListed);
        Object infoUpdate = r.playerInfoUpdateCtor.newInstance(actions, Collections.singletonList(serverPlayer));
        Object addEntity;
        if (r.addEntityCtor != null) {
            addEntity = r.addEntityCtor.newInstance(serverPlayer);
        } else {
            Object playerType = r.entityGetType.invoke(serverPlayer);
            Object zeroVec = r.vec3Ctor.newInstance(0.0, 0.0, 0.0);
            addEntity = r.addEntityCtorRaw.newInstance(entityId, profileId, x, y, z, pitch, yaw,
                    playerType, 0, zeroVec, (double) yaw);
        }
        byte headYawByte = (byte) ((int) (yaw * 256.0F / 360.0F));
        Object headRot = r.rotateHeadCtor.newInstance(serverPlayer, headYawByte);
        return List.of(infoUpdate, addEntity, headRot);
    }

    private void broadcastTeleport(Refs r) throws Throwable {
        Object packet = buildTeleportPacket(r);
        if (packet != null) {
            broadcastPacket(r, packet);
            byte headYawByte = (byte) ((int) (yaw * 256.0F / 360.0F));
            broadcastPacket(r, r.rotateHeadCtor.newInstance(serverPlayer, headYawByte));
        } else {
            broadcastDespawnPackets(r);
            broadcastSpawnPackets(r);
        }
    }

    private Object buildTeleportPacket(Refs r) throws Throwable {
        if (r.teleportEntityCtor != null) return r.teleportEntityCtor.newInstance(serverPlayer);
        if (r.entityPositionSyncCtor != null && r.posMoveRotCtor != null) {
            Object pos = r.vec3Ctor.newInstance(x, y, z), delta = r.vec3Ctor.newInstance(0.0, 0.0, 0.0);
            Object pmr = r.posMoveRotCtor.newInstance(pos, delta, yaw, pitch);
            return r.entityPositionSyncCtor.newInstance(entityId, pmr, true);
        }
        if (r.teleportEntityCtorNew != null && r.posMoveRotCtor != null) {
            Object pos = r.vec3Ctor.newInstance(x, y, z), delta = r.vec3Ctor.newInstance(0.0, 0.0, 0.0);
            Object pmr = r.posMoveRotCtor.newInstance(pos, delta, yaw, pitch);
            return r.teleportEntityCtorNew.newInstance(entityId, pmr, java.util.Set.of(), true);
        }
        return null;
    }

    private static void sendPackets(Refs r, Object connection, List<Object> packets) throws Throwable {
        if (connection == null) return;
        for (Object p : packets) r.connectionSend.invoke(connection, p);
    }

    private void broadcastPacket(Refs r, Object packet) throws Throwable {
        forEachViewer(r, conn -> r.connectionSend.invoke(conn, packet));
    }

    private static Object unsafeAllocate(Class<?> cls) throws Throwable {
        Class<?> unsafeCls = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeCls.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        Method allocateInstance = unsafeCls.getMethod("allocateInstance", Class.class);
        return allocateInstance.invoke(unsafe, cls);
    }

    private static Object newMutablePropertyMap(Refs r) throws Throwable {
        Object props = (r.propertyMapCtor != null) ? r.propertyMapCtor.newInstance() : unsafeAllocate(r.propertyMap);
        if (r.propertyMapBackingField != null) {
            r.propertyMapBackingField.set(props, com.google.common.collect.LinkedHashMultimap.create());
        }
        return props;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Refs refs() throws Throwable {
        Refs r = refs;
        if (r != null) return r;
        synchronized (HerobrineNms.class) {
            if (refs != null) return refs;
            r = new Refs();

            r.minecraftServer = Class.forName("net.minecraft.server.MinecraftServer");
            r.serverLevel = Class.forName("net.minecraft.server.level.ServerLevel");
            r.serverPlayer = Class.forName("net.minecraft.server.level.ServerPlayer");
            r.entity = Class.forName("net.minecraft.world.entity.Entity");
            r.livingEntity = Class.forName("net.minecraft.world.entity.LivingEntity");
            r.equipmentSlot = Class.forName("net.minecraft.world.entity.EquipmentSlot");
            r.nmsItemStack = Class.forName("net.minecraft.world.item.ItemStack");
            r.clientInformation = Class.forName("net.minecraft.server.level.ClientInformation");
            r.gameProfile = Class.forName("com.mojang.authlib.GameProfile");
            r.property = Class.forName("com.mojang.authlib.properties.Property");
            r.propertyMap = Class.forName("com.mojang.authlib.properties.PropertyMap");
            r.componentCls = Class.forName("net.minecraft.network.chat.Component");
            r.packetCls = Class.forName("net.minecraft.network.protocol.Packet");
            r.playerInfoUpdatePacket = Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket");
            r.playerInfoUpdateAction = Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket$Action");
            r.addEntityPacket = Class.forName("net.minecraft.network.protocol.game.ClientboundAddEntityPacket");
            r.removeEntitiesPacket = Class.forName("net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket");
            r.teleportEntityPacket = Class.forName("net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket");
            r.rotateHeadPacket = Class.forName("net.minecraft.network.protocol.game.ClientboundRotateHeadPacket");
            r.playerInfoRemovePacket = Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket");

            r.craftServer = Bukkit.getServer().getClass();
            r.craftWorld = Class.forName("org.bukkit.craftbukkit.CraftWorld");
            r.craftPlayer = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer");

            r.craftServerGetServer = r.craftServer.getMethod("getServer");
            r.craftWorldGetHandle = r.craftWorld.getMethod("getHandle");
            r.craftPlayerGetHandle = r.craftPlayer.getMethod("getHandle");

            Method propsMethod;
            try { propsMethod = r.gameProfile.getMethod("properties"); }
            catch (NoSuchMethodException nsme) { propsMethod = r.gameProfile.getMethod("getProperties"); }
            r.gameProfileGetProperties = propsMethod;
            r.propertyMapPut = r.propertyMap.getMethod("put", Object.class, Object.class);
            try { r.propertyMapCtor = r.propertyMap.getDeclaredConstructor(); r.propertyMapCtor.setAccessible(true); }
            catch (NoSuchMethodException ignored) { r.propertyMapCtor = null; }
            for (Field f : r.propertyMap.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (com.google.common.collect.Multimap.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true); r.propertyMapBackingField = f; break;
                }
            }
            try { r.gameProfileCtor3 = r.gameProfile.getConstructor(UUID.class, String.class, r.propertyMap); }
            catch (NoSuchMethodException ignored) { r.gameProfileCtor3 = null; }
            try { r.gameProfileCtor2 = r.gameProfile.getConstructor(UUID.class, String.class); }
            catch (NoSuchMethodException ignored) { r.gameProfileCtor2 = null; }
            if (r.gameProfileCtor3 == null && r.gameProfileCtor2 == null) {
                throw new NoSuchMethodException("GameProfile: no usable constructor");
            }
            r.clientInfoCreateDefault = r.clientInformation.getMethod("createDefault");
            r.componentLiteral = r.componentCls.getMethod("literal", String.class);
            r.serverPlayerCtor = r.serverPlayer.getConstructor(r.minecraftServer, r.serverLevel, r.gameProfile, r.clientInformation);

            r.entitySetPos = r.entity.getMethod("setPos", double.class, double.class, double.class);
            r.entityGetId = r.entity.getMethod("getId");
            r.entitySetYRot = r.entity.getMethod("setYRot", float.class);
            r.entitySetXRot = r.entity.getMethod("setXRot", float.class);
            r.entitySetYHeadRot = r.entity.getMethod("setYHeadRot", float.class);
            r.entitySetCustomName = r.entity.getMethod("setCustomName", r.componentCls);
            r.entitySetCustomNameVisible = r.entity.getMethod("setCustomNameVisible", boolean.class);
            r.entitySetGlowingTag = r.entity.getMethod("setGlowingTag", boolean.class);
            r.entityGetType = r.entity.getMethod("getType");

            r.connectionField = r.serverPlayer.getDeclaredField("connection");
            r.connectionField.setAccessible(true);
            r.serverPacketListenerCls = Class.forName("net.minecraft.server.network.ServerGamePacketListenerImpl");
            r.connectionSend = r.serverPacketListenerCls.getMethod("send", r.packetCls);

            r.playerInfoUpdateCtor = r.playerInfoUpdatePacket.getConstructor(EnumSet.class, java.util.Collection.class);
            r.vec3 = Class.forName("net.minecraft.world.phys.Vec3");
            r.vec3Ctor = r.vec3.getConstructor(double.class, double.class, double.class);
            Class<?> entityType = Class.forName("net.minecraft.world.entity.EntityType");
            try { r.addEntityCtor = r.addEntityPacket.getConstructor(r.entity); }
            catch (NoSuchMethodException ignored) { r.addEntityCtor = null; }
            try {
                r.addEntityCtorRaw = r.addEntityPacket.getConstructor(int.class, UUID.class,
                        double.class, double.class, double.class, float.class, float.class,
                        entityType, int.class, r.vec3, double.class);
            } catch (NoSuchMethodException ignored) { r.addEntityCtorRaw = null; }
            if (r.addEntityCtor == null && r.addEntityCtorRaw == null) {
                throw new NoSuchMethodException("ClientboundAddEntityPacket: no usable constructor");
            }
            r.removeEntitiesCtor = r.removeEntitiesPacket.getConstructor(int[].class);

            try { r.positionMoveRotation = Class.forName("net.minecraft.world.entity.PositionMoveRotation"); }
            catch (ClassNotFoundException ignored) {
                try { r.positionMoveRotation = Class.forName("net.minecraft.network.protocol.game.PositionMoveRotation"); }
                catch (ClassNotFoundException ignored2) { r.positionMoveRotation = null; }
            }
            if (r.positionMoveRotation != null) {
                try { r.posMoveRotCtor = r.positionMoveRotation.getConstructor(r.vec3, r.vec3, float.class, float.class); }
                catch (NoSuchMethodException ignored) { r.posMoveRotCtor = null; }
            }
            try { r.teleportEntityCtor = r.teleportEntityPacket.getConstructor(r.entity); }
            catch (NoSuchMethodException ignored) { r.teleportEntityCtor = null; }
            if (r.teleportEntityCtor == null && r.positionMoveRotation != null) {
                try { r.teleportEntityCtorNew = r.teleportEntityPacket.getConstructor(int.class, r.positionMoveRotation, java.util.Set.class, boolean.class); }
                catch (NoSuchMethodException ignored) { r.teleportEntityCtorNew = null; }
            }
            try {
                r.entityPositionSyncPacket = Class.forName("net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket");
                if (r.positionMoveRotation != null) {
                    r.entityPositionSyncCtor = r.entityPositionSyncPacket.getConstructor(int.class, r.positionMoveRotation, boolean.class);
                }
            } catch (Throwable ignored) { r.entityPositionSyncPacket = null; r.entityPositionSyncCtor = null; }
            try {
                r.moveEntityRotPacket = Class.forName("net.minecraft.network.protocol.game.ClientboundMoveEntityPacket$Rot");
                r.moveEntityRotCtor = r.moveEntityRotPacket.getConstructor(int.class, byte.class, byte.class, boolean.class);
            } catch (Throwable ignored) { r.moveEntityRotPacket = null; r.moveEntityRotCtor = null; }
            try {
                r.moveEntityPosRotPacket = Class.forName("net.minecraft.network.protocol.game.ClientboundMoveEntityPacket$PosRot");
                r.moveEntityPosRotCtor = r.moveEntityPosRotPacket.getConstructor(int.class,
                        short.class, short.class, short.class, byte.class, byte.class, boolean.class);
            } catch (Throwable ignored) { r.moveEntityPosRotPacket = null; r.moveEntityPosRotCtor = null; }

            r.rotateHeadCtor = r.rotateHeadPacket.getConstructor(r.entity, byte.class);
            try {
                r.animatePacket = Class.forName("net.minecraft.network.protocol.game.ClientboundAnimatePacket");
                r.animateCtor = r.animatePacket.getConstructor(r.entity, int.class);
            } catch (Throwable ignored) { r.animatePacket = null; r.animateCtor = null; }
            r.playerInfoRemoveCtor = r.playerInfoRemovePacket.getConstructor(List.class);

            r.actionAddPlayer = Enum.valueOf((Class<Enum>) r.playerInfoUpdateAction, "ADD_PLAYER");
            r.actionUpdateListed = Enum.valueOf((Class<Enum>) r.playerInfoUpdateAction, "UPDATE_LISTED");

            refs = r;
            return r;
        }
    }
}
