package com.example.meteo;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.GameRules;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

import java.util.*;

public class StormManager {
    public static final int CLOUD_BASE = 150;
    public static final int WALL_CLOUD_Y = CLOUD_BASE - 18;
    public static final int RADAR_RANGE = 320;

    /** Максимальная твёрдость блока, который торнадо EF0..EF5 способен сорвать. */
    private static final double[] BREAK_LIMIT = {0.6, 2.0, 2.5, 3.5, 5.0, 10.0};
    /** Потолок летящих обломков вокруг торнадо. */
    private static final int MAX_DEBRIS = 60;
    /** Порог (мс на тик), выше которого мод сам урезает эффекты. */
    private static final double SHED_MS = 6.0;

    private static final Map<ServerWorld, List<StormCell>> CELLS = new HashMap<>();
    private static final Set<ServerWorld> FORCED_WEATHER = new HashSet<>();
    private static double costEma = 0; // скользящее среднее собственной нагрузки мода, мс/тик

    public static List<StormCell> cells(ServerWorld w) {
        return CELLS.computeIfAbsent(w, k -> new ArrayList<>());
    }

    public static void clear() {
        CELLS.clear();
        FORCED_WEATHER.clear();
        costEma = 0;
    }

    public static StormCell spawn(ServerWorld w, StormType t, double x, double z, float heading, boolean mature) {
        Random r = w.random;
        float scale = 0.85f + r.nextFloat() * 0.3f;
        int life = 14000 + r.nextInt(10000);
        int age = mature ? life / 2 : life / 10;
        StormCell c = new StormCell(t, x, z, heading, t.along * scale, t.across * scale, age, life);
        cells(w).add(c);
        return c;
    }

    private static StormType pickType(Random r) {
        int n = r.nextInt(100);
        if (n < 40) return StormType.SINGLE_CELL;
        if (n < 70) return StormType.MULTICELL;
        if (n < 82) return StormType.SUPERCELL;
        return StormType.SQUALL_LINE;
    }

    public static int pickEf(Random r) {
        int n = r.nextInt(100);
        if (n < 25) return 0;
        if (n < 55) return 1;
        if (n < 77) return 2;
        if (n < 90) return 3;
        if (n < 98) return 4;
        return 5;
    }

    private static String dir(double dx, double dz) {
        double a = Math.toDegrees(Math.atan2(dx, -dz));
        if (a < 0) a += 360;
        String[] n = {"С", "СВ", "В", "ЮВ", "Ю", "ЮЗ", "З", "СЗ"};
        return n[(int) Math.round(a / 45) % 8];
    }

    /** Уровень детализации частиц по расстоянию: чем дальше игрок, тем меньше частиц. */
    private static double lod(double distance) {
        if (distance < 120) return 1.0;
        if (distance < 250) return 0.6;
        return 0.35;
    }

    private static int n(int base, double lod) {
        return Math.max(1, (int) Math.round(base * lod));
    }

    private static void spawnNatural(ServerWorld w, List<ServerPlayerEntity> players) {
        Random r = w.random;
        ServerPlayerEntity p = players.get(r.nextInt(players.size()));
        double ang = r.nextDouble() * Math.PI * 2;
        double dist = 350 + r.nextInt(150);
        double x = p.getX() + Math.cos(ang) * dist;
        double z = p.getZ() + Math.sin(ang) * dist;
        float heading = (float) (ang + Math.PI + (r.nextDouble() - 0.5) * 0.6);
        spawn(w, pickType(r), x, z, heading, false);
    }

    /** Запускает торнадо и предупреждает игроков поблизости. */
    public static void beginTornado(ServerWorld w, StormCell c, int ef, int startAge) {
        int life = 2400 + w.random.nextInt(3600);
        life = Math.max(1200, Math.min(life, c.lifetime - c.age - 200));
        c.startTornado(ef, life, startAge);
        double[] tp = c.torPos();
        for (ServerPlayerEntity p : w.getPlayers()) {
            double dx = tp[0] - p.getX(), dz = tp[1] - p.getZ();
            double d = Math.hypot(dx, dz);
            if (d > 900) continue;
            Text msg = Text.literal("⚠ Предупреждение о торнадо (EF" + ef + "): " + (int) d + " м, " + dir(dx, dz));
            p.sendMessage(msg, false);
            p.sendMessage(msg, true);
        }
    }

    public static void tick(ServerWorld w) {
        if (w.getRegistryKey() != World.OVERWORLD) return;
        long start = System.nanoTime();
        tickInner(w, costEma > SHED_MS);
        costEma = costEma * 0.95 + (System.nanoTime() - start) / 1e6 * 0.05;
    }

    private static void tickInner(ServerWorld w, boolean shed) {
        List<StormCell> list = cells(w);
        List<ServerPlayerEntity> players = w.getPlayers();
        long t = w.getTime();

        if (!players.isEmpty() && t % 200 == 0 && list.size() < 3 && w.random.nextFloat() < 0.2f) {
            spawnNatural(w, players);
        }

        Iterator<StormCell> it = list.iterator();
        while (it.hasNext()) {
            StormCell c = it.next();
            c.tick();
            if (c.expired()) it.remove();
        }

        // нет гроз или нет игроков — эффекты считать не для кого
        if (list.isEmpty()) {
            if (FORCED_WEATHER.remove(w)) w.setWeather(600, 0, false, false);
            return;
        }
        if (players.isEmpty()) return;

        double shedMul = shed ? 0.5 : 1.0;
        boolean anyOver = false;

        for (StormCell c : list) {
            float k = c.intensity();
            if (k < 0.1f) continue;

            if (c.type == StormType.SUPERCELL && !c.torDone && !c.tornadoActive()
                    && k >= 0.85f && t % 100 == 0 && w.random.nextFloat() < 0.3f) {
                beginTornado(w, c, pickEf(w.random), 0);
            }

            int interval = Math.max(8, (int) (c.type.lightningTicks / k));
            if (t % interval == 0) strike(w, c);

            double size = Math.max(c.along, c.across);
            double reach = size + 256;
            double[] meso = c.hasMeso() ? c.mesoCenter() : null;

            for (ServerPlayerEntity p : players) {
                double d = Math.hypot(p.getX() - c.x, p.getZ() - c.z);
                if (d > reach) continue;
                double lod = lod(d - size) * shedMul;
                long phase = t + p.getId(); // разносим нагрузку разных игроков по тикам

                if (phase % 10 == 0) clouds(w, p, c, k, lod);
                if (meso != null && phase % 2 == 0
                        && Math.hypot(p.getX() - meso[0], p.getZ() - meso[1]) < 300) {
                    wallCloud(w, p, c, k, lod);
                }

                float dbz = c.dbz(p.getX(), p.getZ(), k);
                if (dbz >= 20) {
                    anyOver = true;
                    if (c.type.gusts && dbz >= 30) {
                        p.addVelocity(Math.cos(c.heading) * 0.03, 0, Math.sin(c.heading) * 0.03);
                        p.velocityModified = true;
                    }
                    if (c.type.hail && dbz >= 50 && t % 20 == 0) hail(w, p);
                }
            }

            if (c.tornadoActive()) tornadoTick(w, c, players, t, shed);
        }

        if (anyOver) {
            if (t % 100 == 0 || !w.isThundering()) {
                w.setWeather(0, 400, true, true);
                FORCED_WEATHER.add(w);
            }
        } else if (FORCED_WEATHER.remove(w)) {
            w.setWeather(600, 0, false, false);
        }
    }

    // ---------- молнии, град ----------

    private static void strike(ServerWorld w, StormCell c) {
        double[] p = c.randomPoint(w.random, 0.8, 0);
        BlockPos xz = new BlockPos((int) Math.floor(p[0]), 0, (int) Math.floor(p[1]));
        if (!w.isChunkLoaded(xz)) return;
        int y = w.getTopY(Heightmap.Type.MOTION_BLOCKING, xz.getX(), xz.getZ());
        LightningEntity bolt = EntityType.LIGHTNING_BOLT.create(w);
        if (bolt == null) return;
        bolt.refreshPositionAfterTeleport(xz.getX() + 0.5, y, xz.getZ() + 0.5);
        w.spawnEntity(bolt);
    }

    private static void hail(ServerWorld w, ServerPlayerEntity p) {
        if (!w.isSkyVisible(p.getBlockPos())) return;
        w.spawnParticles(ParticleTypes.ITEM_SNOWBALL, p.getX(), p.getY() + 3, p.getZ(), 40, 2.5, 1.5, 2.5, 0.1);
        p.damage(w.getDamageSources().generic(), 1.0f);
    }

    // ---------- частицы ----------

    private static void puff(ServerWorld w, ServerPlayerEntity p, double x, double y, double z, int count, double sx, double sy) {
        w.spawnParticles(p, ParticleTypes.CAMPFIRE_COSY_SMOKE, true, x, y, z, count, sx, sy, sx, 0.0);
    }

    /** Частица с вращением вокруг оси (cx,cz): циклонически, против часовой стрелки при взгляде сверху. */
    private static void swirl(ServerWorld w, ServerPlayerEntity p, ParticleEffect type,
                              double x, double y, double z, double cx, double cz, double speed, double up) {
        double dx = x - cx, dz = z - cz;
        double d = Math.max(1.0, Math.hypot(dx, dz));
        w.spawnParticles(p, type, true, x, y, z, 0, dz / d * speed, up, -dx / d * speed, 1.0);
    }

    /** Структура облака: основание, башня, наковальня + особенности типа. */
    private static void clouds(ServerWorld w, ServerPlayerEntity p, StormCell c, float k, double lod) {
        Random r = w.random;
        double top = CLOUD_BASE + c.type.towerHeight * k;
        boolean sc = c.type == StormType.SUPERCELL;

        for (int i = 0, m = n(10, lod); i < m; i++) { // основание
            double[] q = c.randomPoint(r, 1.0, 0);
            puff(w, p, q[0], CLOUD_BASE + r.nextInt(5), q[1], 3, 6, 1.5);
        }

        if (!sc) {
            for (int i = 0, m = n(10, lod); i < m; i++) { // башня восходящего потока
                double h = r.nextDouble();
                double[] q = c.randomPoint(r, 0.5 * (1 - 0.35 * h), 0);
                puff(w, p, q[0], CLOUD_BASE + h * (top - CLOUD_BASE), q[1], 3, 5, 4);
            }
        } else {
            // вращающаяся башня мезоциклона: спиральные «борозды» (barber pole)
            double[] tc = c.toWorld(-0.15 * c.along, 0.1 * c.across);
            double rt = 0.3 * Math.min(c.along, c.across);
            for (int i = 0, m = n(16, lod); i < m; i++) {
                double h = r.nextDouble();
                double ang = -(c.age * 0.04) + h * 7.0 + (r.nextDouble() - 0.5) * 0.6;
                double rad = rt * (0.9 - 0.25 * h) * (0.85 + 0.3 * r.nextDouble());
                puff(w, p, tc[0] + Math.cos(ang) * rad, CLOUD_BASE + h * (top - CLOUD_BASE),
                        tc[1] + Math.sin(ang) * rad, 2, 3, 3);
            }
        }

        double aScale = sc ? 1.3 : 1.15, aShift = sc ? 0.55 : 0.35;
        for (int i = 0, m = n(12, lod); i < m; i++) { // наковальня, снесённая по ветру
            double[] q = c.randomPoint(r, aScale, aShift);
            puff(w, p, q[0], top + r.nextInt(3), q[1], 3, 7, 1);
        }

        if (sc) supercellFeatures(w, p, c, top, lod);

        if (c.type == StormType.SQUALL_LINE) { // шельфовое облако по фронту
            for (int i = 0, m = n(12, lod); i < m; i++) {
                double v = (r.nextDouble() * 2 - 1) * c.across;
                double[] q = c.toWorld(c.along * 0.9, v);
                puff(w, p, q[0], CLOUD_BASE - 8 + r.nextInt(4), q[1], 3, 5, 1.5);
            }
        }
    }

    private static void supercellFeatures(ServerWorld w, ServerPlayerEntity p, StormCell c, double top, double lod) {
        Random r = w.random;
        // overshooting top — купол над восходящим потоком
        double[] tc = c.toWorld(-0.15 * c.along, 0.1 * c.across);
        for (int i = 0, m = n(3, lod); i < m; i++) {
            puff(w, p, tc[0] + (r.nextDouble() - 0.5) * 8, top + 4 + r.nextInt(8),
                    tc[1] + (r.nextDouble() - 0.5) * 8, 3, 4, 3);
        }
        // мамматусы под наковальней
        for (int i = 0, m = n(8, lod); i < m; i++) {
            double[] q = c.randomPoint(r, 1.2, 0.6);
            puff(w, p, q[0], top - 7 - r.nextInt(5), q[1], 2, 1.5, 1.5);
        }
        // линия вспомогательных кучевых облаков (flanking line) на тыловой стороне
        for (int i = 0, m = n(6, lod); i < m; i++) {
            double f = r.nextDouble();
            double[] q = c.toWorld(-c.along * (0.5 + 0.6 * f), c.across * (0.5 + 0.7 * f));
            puff(w, p, q[0], CLOUD_BASE + r.nextDouble() * 35, q[1], 3, 4, 5);
        }
        // хвостовое облако притока (tail cloud) — к облаку-стене
        for (int i = 0, m = n(8, lod); i < m; i++) {
            double s = (i + r.nextDouble()) / m;
            double[] q = c.toWorld(StormCell.MESO_U * c.along + s * c.along * 1.4,
                    StormCell.MESO_V * c.across - s * c.across * 0.3);
            puff(w, p, q[0], CLOUD_BASE - 14 + s * 6, q[1], 2, 2.5, 1);
        }
    }

    /** Облако-стена (wall cloud): опущенное вращающееся основание под мезоциклоном. */
    private static void wallCloud(ServerWorld w, ServerPlayerEntity p, StormCell c, float k, double lod) {
        Random r = w.random;
        double[] m = c.mesoCenter();
        for (int i = 0, cnt = n(8, lod); i < cnt; i++) {
            double ang = r.nextDouble() * Math.PI * 2;
            double rad = 3 + r.nextDouble() * 9;
            swirl(w, p, ParticleTypes.LARGE_SMOKE,
                    m[0] + Math.cos(ang) * rad, WALL_CLOUD_Y + 1 - r.nextDouble() * 4, m[1] + Math.sin(ang) * rad,
                    m[0], m[1], 0.25 * k, 0.0);
        }
    }

    /** Воронка: конденсационная трубка от облака-стены до земли + облако мусора. gy считается один раз на тик. */
    private static void funnel(ServerWorld w, ServerPlayerEntity p, StormCell c, double[] tp, int gy, double lod) {
        if (gy >= WALL_CLOUD_Y - 8) return;
        Random r = w.random;

        float prog = c.torProgress(), size = c.torSize();
        double bottom = WALL_CLOUD_Y - (WALL_CLOUD_Y - gy) * prog;
        double height = WALL_CLOUD_Y - bottom;
        int ef = c.torEF;
        double rg = (1.2 + ef * 0.6) * size;     // радиус у земли
        double rt = (10 + ef * 2.5) * size;      // радиус у основания облака
        double tilt = (1 - size) * 30;           // наклон «верёвки»
        double hx = Math.cos(c.heading), hz = Math.sin(c.heading);

        for (int i = 0, m = n(22, lod); i < m; i++) {
            double h = r.nextDouble();
            double rad = rg + (rt - rg) * Math.pow(h, 1.8);
            double ang = r.nextDouble() * Math.PI * 2;
            double ax = tp[0] + hx * tilt * h, az = tp[1] + hz * tilt * h;
            swirl(w, p, ParticleTypes.CLOUD,
                    ax + Math.cos(ang) * rad, bottom + h * height, az + Math.sin(ang) * rad,
                    ax, az, 0.35, 0.02);
        }

        if (prog >= 1f) {
            BlockState ground = w.getBlockState(new BlockPos((int) Math.floor(tp[0]), gy - 1, (int) Math.floor(tp[1])));
            BlockStateParticleEffect debris = new BlockStateParticleEffect(ParticleTypes.BLOCK, ground);
            for (int i = 0, m = n(6, lod); i < m; i++) {
                double ang = r.nextDouble() * Math.PI * 2;
                double rad = rg * (1 + 2 * r.nextDouble());
                double x = tp[0] + Math.cos(ang) * rad, z = tp[1] + Math.sin(ang) * rad;
                double y = gy + r.nextDouble() * 4;
                swirl(w, p, ParticleTypes.LARGE_SMOKE, x, y, z, tp[0], tp[1], 0.4, 0.15);
                swirl(w, p, debris, x, y, z, tp[0], tp[1], 0.5, 0.4);
            }
        }
    }

    // ---------- торнадо: воздействие ----------

    private static void tornadoTick(ServerWorld w, StormCell c, List<ServerPlayerEntity> players, long t, boolean shed) {
        double[] tp = c.torPos();
        BlockPos tb = new BlockPos((int) Math.floor(tp[0]), 0, (int) Math.floor(tp[1]));
        if (!w.isChunkLoaded(tb)) return;
        int gy = w.getTopY(Heightmap.Type.MOTION_BLOCKING, tb.getX(), tb.getZ()); // один раз на тик
        double shedMul = shed ? 0.5 : 1.0;

        for (ServerPlayerEntity p : players) {
            double d = Math.hypot(p.getX() - tp[0], p.getZ() - tp[1]);
            if (d < 450 && (t + p.getId()) % 2 == 0) funnel(w, p, c, tp, gy, lod(d - 60) * shedMul);
        }
        if (!c.torTouchdown()) return;

        int ef = c.torEF;
        double radius = StormCell.TOR_RADIUS[ef] * c.torSize();

        if (t % 2 == 0) liftEntities(w, tp, radius, ef, gy);
        if (t % 4 == 0 && !shed) destroyBlocks(w, tp, radius, ef, gy);
        if (t % 20 == 0) {
            w.playSound(null, tp[0], gy, tp[1], SoundEvents.ITEM_ELYTRA_FLYING, SoundCategory.WEATHER, 8f, 0.5f);
        }
    }

    private static void liftEntities(ServerWorld w, double[] tp, double radius, int ef, int gy) {
        double reach = radius * 2.2;
        Box box = new Box(tp[0] - reach, gy - 6, tp[1] - reach, tp[0] + reach, gy + 80, tp[1] + reach);
        for (LivingEntity e : w.getEntitiesByClass(LivingEntity.class, box, en -> true)) {
            if (e instanceof ServerPlayerEntity sp && (sp.isCreative() || sp.isSpectator())) continue;
            double dx = e.getX() - tp[0], dz = e.getZ() - tp[1];
            double d = Math.max(1.0, Math.hypot(dx, dz));
            double f = 1.0 - d / reach;
            if (f <= 0) continue;

            double spin = (0.10 + 0.05 * ef) * f;
            Vec3d v = e.getVelocity();
            double vy = e.getY() < gy + 70 ? Math.min(0.9, v.y + (0.07 + 0.025 * ef) * f * 2) : v.y;
            e.setVelocity(v.x * 0.9 + dz / d * spin - dx / d * 0.03 * f,
                    vy,
                    v.z * 0.9 - dx / d * spin - dz / d * 0.03 * f);
            e.velocityModified = true;
            if (w.getTime() % 10 == 0 && f > 0.3) {
                e.damage(w.getDamageSources().generic(), (float) ((1 + ef * 1.5) * f));
            }
        }
    }

    private static void destroyBlocks(ServerWorld w, double[] tp, double radius, int ef, int gy) {
        if (!w.getGameRules().getBoolean(GameRules.DO_MOB_GRIEFING)) return;

        // потолок летящих обломков: не плодим тысячи сущностей
        double span = radius * 3;
        Box area = new Box(tp[0] - span, gy - 6, tp[1] - span, tp[0] + span, gy + 90, tp[1] + span);
        if (w.getEntitiesByClass(FallingBlockEntity.class, area, e -> true).size() >= MAX_DEBRIS) return;

        Random r = w.random;
        int ops = 4 + ef * 3;
        for (int i = 0; i < ops; i++) {
            double ang = r.nextDouble() * Math.PI * 2, rad = Math.sqrt(r.nextDouble()) * radius;
            int bx = (int) Math.floor(tp[0] + Math.cos(ang) * rad);
            int bz = (int) Math.floor(tp[1] + Math.sin(ang) * rad);
            if (!w.isChunkLoaded(new BlockPos(bx, 0, bz))) continue;

            int by = w.getTopY(Heightmap.Type.MOTION_BLOCKING, bx, bz) - 1 - r.nextInt(2);
            BlockPos bp = new BlockPos(bx, by, bz);
            BlockState st = w.getBlockState(bp);
            if (st.isAir() || !st.getFluidState().isEmpty()) continue;
            if (ef < 4 && (st.isIn(BlockTags.DIRT) || st.isIn(BlockTags.SAND) || st.isIn(BlockTags.BASE_STONE_OVERWORLD))) continue;
            float hard = st.getHardness(w, bp);
            if (hard < 0 || hard > BREAK_LIMIT[ef]) continue;
            if (w.getBlockEntity(bp) != null) continue;

            FallingBlockEntity fb = FallingBlockEntity.spawnFromBlock(w, bp, st);
            fb.dropItem = false;
            fb.setDestroyedOnLanding();
            fb.setHurtEntities(1.0f + ef * 0.5f, 20);
            double sp = 0.25 + 0.08 * ef;
            double dx = bx - tp[0], dz = bz - tp[1], d = Math.max(1, Math.hypot(dx, dz));
            fb.setVelocity(dz / d * sp, 0.5 + r.nextDouble() * 0.5, -dx / d * sp);
        }
    }

    // ---------- радар ----------

    public static void sendRadar(ServerPlayerEntity p, BlockPos pos) {
        ServerWorld w = p.getServerWorld();
        List<StormCell> out = new ArrayList<>();
        for (StormCell c : cells(w)) {
            double d = Math.hypot(c.x - pos.getX(), c.z - pos.getZ());
            if (d < RADAR_RANGE + Math.max(c.along, c.across)) out.add(c);
        }
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeBlockPos(pos);
        buf.writeVarInt(out.size());
        for (StormCell c : out) c.write(buf);
        ServerPlayNetworking.send(p, Meteo.RADAR_DATA, buf);
    }
}
