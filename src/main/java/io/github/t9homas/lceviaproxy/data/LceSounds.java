package io.github.t9homas.lceviaproxy.data;

import java.util.Map;

/**
 * LCE sends sounds as an index into its sound list (eSOUND_TYPE). These are the identifiers in that order,
 * remapped to the Java 1.6.4 sound names where LCE still uses older names.
 */
public final class LceSounds {

    private static final String[] NAMES = {
            "mob.chicken", "mob.chickenhurt", "mob.chickenplop", "mob.cow", "mob.cowhurt", "mob.pig", "mob.pigdeath", "mob.sheep",
            "mob.wolf.growl", "mob.wolf.whine", "mob.wolf.panting", "mob.wolf.bark", "mob.wolf.hurt", "mob.wolf.death", "mob.wolf.shake",
            "mob.blaze.breathe", "mob.blaze.hit", "mob.blaze.death", "mob.ghast.moan", "mob.ghast.scream", "mob.ghast.death",
            "mob.ghast.fireball", "mob.ghast.charge", "mob.endermen.idle", "mob.endermen.hit", "mob.endermen.death", "mob.endermen.portal",
            "mob.zombiepig.zpig", "mob.zombiepig.zpighurt", "mob.zombiepig.zpigdeath", "mob.zombiepig.zpigangry", "mob.silverfish.say",
            "mob.silverfish.hit", "mob.silverfish.kill", "mob.silverfish.step", "mob.skeleton", "mob.skeletonhurt", "mob.spider",
            "mob.spiderdeath", "mob.slime", "mob.slimeattack", "mob.creeper", "mob.creeperdeath", "mob.zombie", "mob.zombiehurt",
            "mob.zombiedeath", "mob.zombie.wood", "mob.zombie.woodbreak", "mob.zombie.metal", "mob.magmacube.big", "mob.magmacube.small",
            "mob.cat.purr", "mob.cat.purreow", "mob.cat.meow", "mob.cat.hit", "random.bow", "random.bowhit", "random.explode", "random.fizz",
            "random.pop", "random.fuse", "random.drink", "random.eat", "random.burp", "random.splash", "random.click", "random.glass",
            "random.orb", "random.break", "random.chestopen", "random.chestclosed", "random.door_open", "random.door_close",
            "ambient.weather.rain", "ambient.weather.thunder", "ambient.cave.cave", "ambient.cave.cave2", "portal.portal", "portal.trigger",
            "portal.travel", "fire.ignite", "fire.fire", "damage.hurtflesh", "damage.fallsmall", "damage.fallbig", "note.harp", "note.bd",
            "note.snare", "note.hat", "note.bassattack", "tile.piston.in", "tile.piston.out", "liquid.water", "liquid.lavapop", "liquid.lava",
            "step.stone", "step.wood", "step.gravel", "step.grass", "step.metal", "step.cloth", "step.sand", "mob.enderdragon.end",
            "mob.enderdragon.growl", "mob.enderdragon.hit", "mob.enderdragon.wings", "mob.irongolem.throw", "mob.irongolem.hit",
            "mob.irongolem.death", "mob.irongolem.walk", "damage.thorns", "random.anvil_break", "random.anvil_land", "random.anvil_use",
            "mob.villager.haggle", "mob.villager.idle", "mob.villager.hit", "mob.villager.death", "mob.villager.yes", "mob.villager.no",
            "mob.zombie.infect", "mob.zombie.unfect", "mob.zombie.remedy", "step.snow", "step.ladder", "dig.cloth", "dig.grass",
            "dig.gravel", "dig.sand", "dig.snow", "dig.stone", "dig.wood", "fireworks.launch", "fireworks.blast", "fireworks.blast_far",
            "fireworks.largeBlast", "fireworks.largeBlast_far", "fireworks.twinkle", "fireworks.twinkle_far", "mob.bat.idle", "mob.bat.hurt",
            "mob.bat.death", "mob.bat.takeoff", "mob.wither.spawn", "mob.wither.idle", "mob.wither.hurt", "mob.wither.death",
            "mob.wither.shoot", "mob.cow.step", "mob.chicken.step", "mob.pig.step", "mob.enderman.stare", "mob.enderman.scream",
            "mob.sheep.shear", "mob.sheep.step", "mob.skeleton.death", "mob.skeleton.step", "mob.spider.step", "mob.wolf.step",
            "mob.zombie.step", "liquid.swim", "mob.horse.land", "mob.horse.armor", "mob.horse.leather", "mob.horse.zombie.death",
            "mob.horse.skeleton.death", "mob.horse.donkey.death", "mob.horse.death", "mob.horse.zombie.hit", "mob.horse.skeleton.hit",
            "mob.horse.donkey.hit", "mob.horse.hit", "mob.horse.zombie.idle", "mob.horse.skeleton.idle", "mob.horse.donkey.idle",
            "mob.horse.idle", "mob.horse.donkey.angry", "mob.horse.angry", "mob.horse.gallop", "mob.horse.breathe", "mob.horse.wood",
            "mob.horse.soft", "mob.horse.jump", "mob.witch.idle", "mob.witch.hurt", "mob.witch.death", "mob.slime.big", "mob.slime.small",
            "eating", "random.levelup", "fire.new_ignite"
    };

    private static final Map<String, String> RENAMED = Map.ofEntries(
            Map.entry("mob.chicken", "mob.chicken.say"),
            Map.entry("mob.chickenhurt", "mob.chicken.hurt"),
            Map.entry("mob.chickenplop", "mob.chicken.plop"),
            Map.entry("mob.cow", "mob.cow.say"),
            Map.entry("mob.cowhurt", "mob.cow.hurt"),
            Map.entry("mob.pig", "mob.pig.say"),
            Map.entry("mob.pigdeath", "mob.pig.death"),
            Map.entry("mob.sheep", "mob.sheep.say"),
            Map.entry("mob.skeleton", "mob.skeleton.say"),
            Map.entry("mob.skeletonhurt", "mob.skeleton.hurt"),
            Map.entry("mob.spider", "mob.spider.say"),
            Map.entry("mob.spiderdeath", "mob.spider.death"),
            Map.entry("mob.slime", "mob.slime.big"),
            Map.entry("mob.slimeattack", "mob.slime.attack"),
            Map.entry("mob.creeper", "mob.creeper.say"),
            Map.entry("mob.creeperdeath", "mob.creeper.death"),
            Map.entry("mob.zombie", "mob.zombie.say"),
            Map.entry("mob.zombiehurt", "mob.zombie.hurt"),
            Map.entry("mob.zombiedeath", "mob.zombie.death"),
            Map.entry("damage.hurtflesh", "damage.hit"),
            Map.entry("ambient.cave.cave2", "ambient.cave.cave"),
            Map.entry("mob.enderman.stare", "mob.endermen.stare"),
            Map.entry("mob.enderman.scream", "mob.endermen.scream"),
            Map.entry("eating", "random.eat"),
            Map.entry("fire.new_ignite", "fire.ignite")
    );

    private LceSounds() {
    }

    /**
     * @return the 1.6.4 sound name, or null if unknown
     */
    public static String name(final int id) {
        if (id < 0 || id >= NAMES.length) {
            return null;
        }
        final String n = NAMES[id];
        return RENAMED.getOrDefault(n, n);
    }

}
