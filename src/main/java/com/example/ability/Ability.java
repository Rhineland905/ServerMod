package com.example.ability;

public enum Ability {

    WARDEN(
        "warden",
        "Горит днём на солнце, мобы не агрятся, но железные и снежные големы атакуют"
    ),

    DEMON(
        "demon",
        "Не получает урон от огня, горит в воде, быстрее движется в лаве"
    ),

    FISH(
        "fish",
        "Горит на солнце вне воды/дождя, никогда не тонет, без воды >30 сек — умирает, под водой быстрее копает"
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
