package com.unnamedworld.ability;

public enum Ability {

    WARDEN(
        "warden",
        "Горит днём на солнце, мобы не агрятся, но железные и снежные големы атакуют"
    ),

    DEMON(
        "demon",
        "Не получает урон от огня и лавы, дохнет в воде и под дождём. "
        + "Может летать (без видимых крыльев), но полёт ОЧЕНЬ прожорлив — кончилась еда, и ты падаешь"
    ),

    FISH(
        "fish",
        "Горит на солнце вне воды/дождя, никогда не тонет, в воде/дожде — ночное зрение"
    ),

    BLAZE(
        "blaze",
        "Получает урон от воды и дождя — полсердца каждую секунду"
    ),

    VOID(
        "void",
        "Опыт войда даёт +20% к наносимому урону, но тело хрупкое от радиации — получает +10% урона. "
        + "Раз в 4 минуты — приступ лучевой болезни на 10с (отравление, тошнота, слабость), его не пропустить перезаходом"
    ),

    ASSASSIN(
        "assassin",
        "20% шанс парировать ближний удар: урон полностью отбивается и отражается в атакующего. "
        + "Расплата за мастерство — меньше здоровья (15 HP, 75% от обычного). Дальний бой и магию парировать нельзя"
    );

    public final String id;
    public final String description;

    Ability(String id, String description) {
        this.id = id;
        this.description = description;
    }

    public static Ability fromId(String id) {
        for (Ability a : values()) {
            if (a.id.equalsIgnoreCase(id)) return a;
        }
        return null;
    }
}
