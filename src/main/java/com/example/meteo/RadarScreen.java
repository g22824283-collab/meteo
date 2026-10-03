package com.example.meteo;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.List;

public class RadarScreen extends Screen {
    private static final int RANGE = StormManager.RADAR_RANGE;
    private static final int STEP = 3;          // размер «пикселя» радара на экране
    private static final int REBUILD_TICKS = 5; // как часто пересчитывать картинку

    private final BlockPos pos;
    private List<StormCell> cells;
    private int ticks;
    private boolean velocity;
    private int cx, cy, R;

    // кэш картинки радара: считается раз в несколько тиков, а не каждый кадр
    private int[] grid = new int[0];
    private int gridN;
    private boolean rebuild = true;

    // кэш колец дальности
    private int layoutR = -1;
    private int[] ring = new int[0];

    public RadarScreen(BlockPos pos, List<StormCell> cells) {
        super(Text.literal("Метеорадар"));
        this.pos = pos;
        this.cells = cells;
    }

    public void update(List<StormCell> c) {
        this.cells = c;
        this.rebuild = true;
    }

    @Override
    protected void init() {
        addDrawableChild(ButtonWidget.builder(Text.literal(modeLabel()), b -> {
            velocity = !velocity;
            rebuild = true;
            b.setMessage(Text.literal(modeLabel()));
        }).dimensions(width - 130, height - 28, 120, 20).build());
    }

    private String modeLabel() {
        return velocity ? "Режим: скорость" : "Режим: отражаемость";
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public void tick() {
        ticks++;
        for (StormCell c : cells) c.tick();
        if (ticks % REBUILD_TICKS == 0) rebuild = true;
        if (ticks % 20 == 0) {
            PacketByteBuf b = PacketByteBufs.create();
            b.writeBlockPos(pos);
            ClientPlayNetworking.send(Meteo.RADAR_REQUEST, b);
        }
    }

    private static int color(float dbz) {
        if (dbz < 10) return 0xFF07140A;
        if (dbz < 20) return 0xFF2E9BD6;
        if (dbz < 30) return 0xFF22C55E;
        if (dbz < 40) return 0xFFEAE02A;
        if (dbz < 50) return 0xFFF59E0B;
        if (dbz < 60) return 0xFFE11D1D;
        if (dbz < 70) return 0xFFD61FC7;
        return 0xFFFFFFFF;
    }

    private static int lerp(int a, int b, double t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private float dbzAt(double wx, double wz, float[] ks) {
        float m = 0;
        for (int i = 0; i < ks.length; i++) m = Math.max(m, cells.get(i).dbz(wx, wz, ks[i]));
        return m;
    }

    /** Доплеровский режим: радиальная скорость (зелёный — к радару, красный — от радара). */
    private int velColor(double wx, double wz, float[] ks) {
        int best = -1;
        float bd = 10;
        for (int i = 0; i < ks.length; i++) {
            float d = cells.get(i).dbz(wx, wz, ks[i]);
            if (d >= bd) {
                bd = d;
                best = i;
            }
        }
        if (best < 0) return 0xFF07140A;
        double[] v = cells.get(best).wind(wx, wz);
        double rx = wx - (pos.getX() + 0.5), rz = wz - (pos.getZ() + 0.5);
        double dist = Math.max(1, Math.hypot(rx, rz));
        double vr = (v[0] * rx + v[1] * rz) / dist;
        double t = Math.min(1.0, Math.abs(vr) / 35.0);
        return vr < 0 ? lerp(0xFF2B3B2B, 0xFF22FF66, t) : lerp(0xFF3B2B2B, 0xFFFF3030, t);
    }

    private void buildGrid() {
        int n = (2 * R + STEP - 1) / STEP;
        if (grid.length != n * n) grid = new int[n * n];
        gridN = n;

        float[] ks = new float[cells.size()];
        for (int i = 0; i < ks.length; i++) ks[i] = cells.get(i).intensity(); // sin() один раз на ячейку

        double r2 = (double) R * R;
        for (int j = 0; j < n; j++) {
            double my = -R + j * STEP + STEP / 2.0;
            for (int i = 0; i < n; i++) {
                double mx = -R + i * STEP + STEP / 2.0;
                if (mx * mx + my * my > r2) {
                    grid[j * n + i] = 0; // вне круга
                    continue;
                }
                double wx = pos.getX() + mx / R * RANGE;
                double wz = pos.getZ() + my / R * RANGE;
                grid[j * n + i] = velocity ? velColor(wx, wz, ks) : color(dbzAt(wx, wz, ks));
            }
        }
    }

    /** Рисует кэш, склеивая подряд идущие одинаковые пиксели в один fill(). */
    private void drawGrid(DrawContext ctx) {
        int n = gridN;
        for (int j = 0; j < n; j++) {
            int y = cy - R + j * STEP;
            int i = 0;
            while (i < n) {
                int col = grid[j * n + i];
                if (col == 0) {
                    i++;
                    continue;
                }
                int s = i;
                while (i < n && grid[j * n + i] == col) i++;
                ctx.fill(cx - R + s * STEP, y, cx - R + i * STEP, y + STEP, col);
            }
        }
    }

    private void layout() {
        layoutR = R;
        ring = new int[3 * 120 * 2];
        int k = 0;
        for (int r = 1; r <= 3; r++) {
            int rr = R * r / 3;
            for (int a = 0; a < 360; a += 3) {
                double rad = Math.toRadians(a);
                ring[k++] = (int) (Math.cos(rad) * rr);
                ring[k++] = (int) (Math.sin(rad) * rr);
            }
        }
        rebuild = true;
    }

    private static String dir(double dx, double dz) {
        double a = Math.toDegrees(Math.atan2(dx, -dz));
        if (a < 0) a += 360;
        String[] n = {"С", "СВ", "В", "ЮВ", "Ю", "ЮЗ", "З", "СЗ"};
        return n[(int) Math.round(a / 45) % 8];
    }

    private int sx(double wx) {
        return cx + (int) ((wx - pos.getX()) / RANGE * R);
    }

    private int sy(double wz) {
        return cy + (int) ((wz - pos.getZ()) / RANGE * R);
    }

    private boolean inRange(double wx, double wz) {
        double dx = wx - pos.getX(), dz = wz - pos.getZ();
        return dx * dx + dz * dz <= (double) RANGE * RANGE;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        renderBackground(ctx);
        R = Math.max(40, Math.min(width, height) / 2 - 24);
        cx = width / 2;
        cy = height / 2 + 6;
        if (R != layoutR) layout();
        if (rebuild) {
            buildGrid();
            rebuild = false;
        }
        drawGrid(ctx);

        for (int i = 0; i < ring.length; i += 2) {
            int x = cx + ring[i], y = cy + ring[i + 1];
            ctx.fill(x, y, x + 1, y + 1, 0x66FFFFFF);
        }
        ctx.fill(cx - R, cy, cx + R, cy + 1, 0x33FFFFFF);
        ctx.fill(cx, cy - R, cx + 1, cy + R, 0x33FFFFFF);

        // луч развёртки: короткий шлейф вместо длинного
        double base = (System.currentTimeMillis() % 4000) / 4000.0 * Math.PI * 2;
        for (int t = 0; t < 12; t++) {
            double a = base - t * 0.05;
            double ca = Math.cos(a), sa = Math.sin(a);
            int col = ((0xA0 - t * 12) << 24) | 0x66FF66;
            for (int r = 0; r < R; r += 3) {
                int x = cx + (int) (ca * r), y = cy + (int) (sa * r);
                ctx.fill(x, y, x + 2, y + 2, col);
            }
        }
        ctx.fill(cx - 1, cy - 1, cx + 2, cy + 2, 0xFFFFFFFF);

        // подписи, мезоциклоны, TVS
        int ly = 34;
        for (StormCell c : cells) {
            double dx = c.x - pos.getX(), dz = c.z - pos.getZ();
            int mxp = (int) (c.type.maxDbz * c.intensity());
            String line = c.type.title + " " + mxp + " dBZ, " + (int) Math.hypot(dx, dz) + " м, " + dir(dx, dz);
            if (c.tornadoActive()) line += " · ТОРНАДО EF" + c.torEF;
            ctx.drawText(textRenderer, line, 10, ly, c.tornadoActive() ? 0xFF5555 : 0xFFFFFF, true);
            ly += 11;

            if (inRange(c.x, c.z)) {
                int x = sx(c.x), y = sy(c.z);
                ctx.fill(x - 1, y - 1, x + 2, y + 2, 0xFFFFFFFF);
                ctx.drawText(textRenderer, c.type.title, x + 4, y - 4, 0xFFFFFF, true);
            }
            if (c.hasMeso()) {
                double[] m = c.mesoCenter();
                if (inRange(m[0], m[1])) {
                    int x = sx(m[0]), y = sy(m[1]);
                    ctx.fill(x - 3, y - 3, x + 4, y - 2, 0xFFFFFF00);
                    ctx.fill(x - 3, y + 3, x + 4, y + 4, 0xFFFFFF00);
                    ctx.fill(x - 3, y - 3, x - 2, y + 4, 0xFFFFFF00);
                    ctx.fill(x + 3, y - 3, x + 4, y + 4, 0xFFFFFF00);
                    ctx.drawText(textRenderer, "мезоциклон", x + 6, y + 2, 0xFFFF55, true);
                }
            }
            if (c.tornadoActive()) {
                double[] tp = c.torPos();
                if (inRange(tp[0], tp[1])) {
                    int x = sx(tp[0]), y = sy(tp[1]);
                    ctx.fill(x - 3, y - 3, x + 4, y + 4, 0xFFFFFFFF);
                    ctx.fill(x - 2, y - 2, x + 3, y + 3, 0xFFFF0000);
                    ctx.drawText(textRenderer, c.torTouchdown() ? "TVS · торнадо EF" + c.torEF : "TVS · воронка",
                            x + 6, y - 10, 0xFF5555, true);
                }
            }
        }

        ctx.drawCenteredTextWithShadow(textRenderer,
                "Метеорадар · " + (velocity ? "скорость" : "отражаемость") + " · " + RANGE + " м · С ↑", width / 2, 10, 0xFFFFFF);
        if (cells.isEmpty()) {
            ctx.drawCenteredTextWithShadow(textRenderer, "Осадков не обнаружено", width / 2, 22, 0xAAAAAA);
        }

        if (velocity) {
            ctx.fill(width - 80, 34, width - 68, 44, 0xFF22FF66);
            ctx.drawText(textRenderer, "к радару", width - 64, 35, 0xFFFFFF, true);
            ctx.fill(width - 80, 48, width - 68, 58, 0xFFFF3030);
            ctx.drawText(textRenderer, "от радара", width - 64, 49, 0xFFFFFF, true);
        } else {
            for (int i = 0; i < 7; i++) {
                int y = 34 + i * 14;
                ctx.fill(width - 80, y, width - 68, y + 10, color(10 + i * 10));
                ctx.drawText(textRenderer, (10 + i * 10) + " dBZ", width - 64, y + 1, 0xFFFFFF, true);
            }
        }
        super.render(ctx, mouseX, mouseY, delta);
    }
}
