package com.example.meteo;

/** Типы гроз. Размеры в блоках, скорость в блоках/тик. */
public enum StormType {
    //            название                  dBZ  вдоль поперёк молнии скор.  башня  град   шквал
    SINGLE_CELL("Одноячейковая гроза",       50f,  45f,  45f, 120, 0.06, 60,  false, false),
    MULTICELL  ("Многоячейковая гроза",      58f,  70f,  90f,  60, 0.08, 80,  false, false),
    SUPERCELL  ("Суперячейка",               70f,  55f,  65f,  25, 0.10, 120, true,  false),
    SQUALL_LINE("Линия шквалов",             62f,  22f, 150f,  45, 0.14, 70,  false, true);

    public final String title;
    public final float maxDbz;
    public final float along;      // полуось вдоль движения
    public final float across;     // полуось поперёк движения
    public final int lightningTicks;
    public final double speed;
    public final int towerHeight;
    public final boolean hail;
    public final boolean gusts;

    StormType(String title, float maxDbz, float along, float across, int lightningTicks,
              double speed, int towerHeight, boolean hail, boolean gusts) {
        this.title = title;
        this.maxDbz = maxDbz;
        this.along = along;
        this.across = across;
        this.lightningTicks = lightningTicks;
        this.speed = speed;
        this.towerHeight = towerHeight;
        this.hail = hail;
        this.gusts = gusts;
    }
}
