package org.enthusia.tags.cosmetics;

import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;

public final class CosmeticDefinition {
    private final String id;
    private final String name;
    private final String category;
    private final CosmeticType type;
    private final Material icon;
    private final Particle particle;
    private final Material item;
    private final Sound sound;
    private final String message;
    private final String permission;
    private final int count;
    private final double spread;
    private final double speed;
    private final double radius;

    public CosmeticDefinition(String id,
                              String name,
                              String category,
                              CosmeticType type,
                              Material icon,
                              Particle particle,
                              Material item,
                              Sound sound,
                              String message,
                              String permission,
                              int count,
                              double spread,
                              double speed,
                              double radius) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.type = type;
        this.icon = icon;
        this.particle = particle;
        this.item = item;
        this.sound = sound;
        this.message = message;
        this.permission = permission;
        this.count = count;
        this.spread = spread;
        this.speed = speed;
        this.radius = radius;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getCategory() {
        return category;
    }

    public CosmeticType getType() {
        return type;
    }

    public Material getIcon() {
        return icon;
    }

    public Particle getParticle() {
        return particle;
    }

    public Material getItem() {
        return item;
    }

    public Sound getSound() {
        return sound;
    }

    public String getMessage() {
        return message;
    }

    public String getPermission() {
        return permission;
    }

    public int getCount() {
        return count;
    }

    public double getSpread() {
        return spread;
    }

    public double getSpeed() {
        return speed;
    }

    public double getRadius() {
        return radius;
    }
}
