package com.example.meteo;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.math.random.Random;

public class StormCell {
    /** Положение мезоциклона/торнадо в локальных координатах (доли полуосей): у «крюка» на тыловой стороне. */
    public static final double MESO_U = -0.45, MESO_V = 0.35;
    /** Радиус воздействия торнадо (блоки) по шкале EF0..EF5. */
    public static final double[] TOR_RADIUS = {3, 4, 6, 8, 10, 12};
    /** Скорость ветра в вихре для доплеровского режима радара, м/с. */
    public static final double[] TOR_WIND = {35, 50, 65, 80, 95, 115};
    public static final int TOR_DESCENT = 300; // тиков от воронки до касания земли

    public final StormType type;
    public double x, z;
    public final float heading, along, across;
    public int age;
    public final int lifetime;

    // кэш: тригонометрия и радиус охвата считаются один раз
    private final double cosH, sinH, reach;

    public int torAge = -1;   // -1 = торнадо нет
    public int torLife;
    public int torEF;
    public boolean torDone;

    public StormCell(StormType type, double x, double z, float heading, float along, float across, int age, int lifetime) {
        this.type = type;
        this.x = x;
        this.z = z;
        this.heading = heading;
        this.along = along;
        this.across = across;
        this.age = age;
        this.lifetime = lifetime;
        this.cosH = Math.cos(heading);
        this.sinH = Math.sin(heading);
        this.reach = Math.max(along, across);
    }

    public void tick() {
        age++;
        x += cosH * type.speed;
        z += sinH * type.speed;
        if (torAge >= 0) {
            torAge++;
            if (torAge >= torLife) {
                torAge = -1;
                torDone = true;
            }
        }
    }

    public boolean expired() {
        return age >= lifetime;
    }

    /** 0..1: рост, зрелость, распад. */
    public float intensity() {
        double t = Math.min(1.0, (double) age / lifetime);
        return (float) Math.max(0.0, Math.min(1.0, Math.sin(Math.PI * t) * 1.6));
    }

    // ---------- мезоциклон и торнадо ----------

    public boolean hasMeso() {
        return type == StormType.SUPERCELL && intensity() >= 0.5f;
    }

    public double[] mesoCenter() {
        return toWorld(MESO_U * along, MESO_V * across);
    }

    public void startTornado(int ef, int life, int startAge) {
        this.torEF = ef;
        this.torLife = life;
        this.torAge = startAge;
    }

    public boolean tornadoActive() {
        return torAge >= 0;
    }

    /** 0..1: воронка опускается из облака-стены к земле. */
    public float torProgress() {
        return torAge < 0 ? 0f : Math.min(1f, torAge / (float) TOR_DESCENT);
    }

    public boolean torTouchdown() {
        return torAge >= TOR_DESCENT;
    }

    /** 1.0 в зрелости, сужается на стадии «верёвки» перед распадом. */
    public float torSize() {
        int left = torLife - torAge;
        return left < 500 ? Math.max(0.35f, left / 500f) : 1f;
    }

    /** Позиция торнадо у земли: под облаком-стеной, с небольшим блужданием. */
    public double[] torPos() {
        double[] m = mesoCenter();
        return new double[]{m[0] + Math.sin(torAge * 0.015) * 5, m[1] + Math.cos(torAge * 0.011) * 5};
    }

    // ---------- геометрия ----------

    public double[] toWorld(double u, double v) {
        return new double[]{x + u * cosH - v * sinH, z + u * sinH + v * cosH};
    }

    public float dbz(double px, double pz) {
        return dbz(px, pz, intensity());
    }

    /** Отражаемость (dBZ) в точке; интенсивность передаётся снаружи, чтобы не считать sin() на каждый пиксель. */
    public float dbz(double px, double pz, float k) {
        double dx = px - x, dz = pz - z;
        if (k <= 0f || Math.abs(dx) > reach || Math.abs(dz) > reach) return 0; // быстрый отсев
        double u = dx * cosH + dz * sinH;
        double v = -dx * sinH + dz * cosH;
        double nu = u / along, nv = v / across;
        double d2 = nu * nu + nv * nv;
        if (d2 >= 1) return 0;
        double val = 1 - d2;
        if (type == StormType.MULTICELL) {
            val *= 0.7 + 0.3 * Math.sin(u * 0.12) * Math.cos(v * 0.12);
        }
        if (type == StormType.SUPERCELL) {
            double hu = (u + along * 0.55) / (along * 0.3);
            double hv = (v - across * 0.45) / (across * 0.3);
            double hd2 = hu * hu + hv * hv;
            if (hd2 < 1) val = Math.max(val, 0.35 + 0.5 * (1 - hd2)); // «крюк»
        }
        return (float) (val * type.maxDbz * k);
    }

    /** Ветер в точке (м/с, x и z) — для доплеровского режима радара. */
    public double[] wind(double px, double pz) {
        float k = intensity();
        double wx = cosH * type.speed * 200;
        double wz = sinH * type.speed * 200;
        if (type.gusts) {
            wx += cosH * 15 * k;
            wz += sinH * 15 * k;
        }
        if (type == StormType.SUPERCELL && k >= 0.5f) {
            double[] m = mesoCenter();
            double[] r = rankine(px - m[0], pz - m[1], 0.45 * Math.min(along, across), 30 * k);
            wx += r[0];
            wz += r[1];
        }
        if (tornadoActive()) {
            double[] tp = torPos();
            double[] r = rankine(px - tp[0], pz - tp[1], 8, TOR_WIND[torEF] * torProgress() * torSize());
            wx += r[0];
            wz += r[1];
        }
        return new double[]{wx, wz};
    }

    /** Вихрь Рэнкина, циклонический (против часовой стрелки, вид сверху; север = -z). */
    private static double[] rankine(double dx, double dz, double rc, double vmax) {
        double d = Math.max(0.5, Math.hypot(dx, dz));
        double v = d < rc ? vmax * d / rc : vmax * rc / d;
        if (d > rc * 4) v = 0;
        return new double[]{dz / d * v, -dx / d * v};
    }

    public double[] randomPoint(Random r, double scale, double shiftAlong) {
        double ang = r.nextDouble() * Math.PI * 2;
        double rad = Math.sqrt(r.nextDouble());
        double u = Math.cos(ang) * rad * along * scale + shiftAlong * along;
        double v = Math.sin(ang) * rad * across * scale;
        return toWorld(u, v);
    }

    public void write(PacketByteBuf b) {
        b.writeByte(type.ordinal());
        b.writeDouble(x);
        b.writeDouble(z);
        b.writeFloat(heading);
        b.writeFloat(along);
        b.writeFloat(across);
        b.writeInt(age);
        b.writeInt(lifetime);
        b.writeInt(torAge);
        b.writeInt(torLife);
        b.writeByte(torEF);
        b.writeBoolean(torDone);
    }

    public static StormCell read(PacketByteBuf b) {
        StormType t = StormType.values()[b.readByte()];
        double x = b.readDouble(), z = b.readDouble();
        float h = b.readFloat(), a = b.readFloat(), c = b.readFloat();
        int age = b.readInt(), life = b.readInt();
        StormCell cell = new StormCell(t, x, z, h, a, c, age, life);
        cell.torAge = b.readInt();
        cell.torLife = b.readInt();
        cell.torEF = b.readByte();
        cell.torDone = b.readBoolean();
        return cell;
    }
}
