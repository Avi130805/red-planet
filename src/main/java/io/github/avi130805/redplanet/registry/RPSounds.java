package io.github.avi130805.redplanet.registry;

import io.github.avi130805.redplanet.RedPlanet;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

/**
 * Sound events (assets: {@code sounds.json}, generated with the sounds by {@code tools/sounds/generate_sounds.py}).
 * The server only sends a variable-range sound to players within 16 blocks, so loud events (the rocket, sonic
 * booms) are fixed-range: their range matches the attenuation distance in sounds.json.
 */
public final class RPSounds {
	public static final SoundEvent ROCKET_RAPTOR_IGNITION = fixed("rocket.raptor_ignition", 384.0F);
	public static final SoundEvent ROCKET_BOOSTER_ROAR = fixed("rocket.booster_roar", 512.0F);
	public static final SoundEvent ROCKET_SHIP_ROAR = fixed("rocket.ship_roar", 384.0F);
	public static final SoundEvent ROCKET_ENGINE_DISTANT = fixed("rocket.engine_distant", 1024.0F);
	public static final SoundEvent ROCKET_VACUUM_HUM = fixed("rocket.vacuum_hum", 48.0F);
	public static final SoundEvent ROCKET_HOT_STAGING = fixed("rocket.hot_staging", 512.0F);
	public static final SoundEvent ROCKET_STAGE_SEPARATION = fixed("rocket.stage_separation", 64.0F);
	public static final SoundEvent ROCKET_SONIC_BOOM = fixed("rocket.sonic_boom", 1024.0F);
	public static final SoundEvent ROCKET_VENT = fixed("rocket.vent", 96.0F);
	public static final SoundEvent ROCKET_DELUGE = fixed("rocket.deluge", 256.0F);
	public static final SoundEvent ROCKET_FLAP_ACTUATOR = fixed("rocket.flap_actuator", 32.0F);
	public static final SoundEvent ROCKET_LANDING_LEGS = fixed("rocket.landing_legs", 64.0F);
	public static final SoundEvent ROCKET_TOUCHDOWN = fixed("rocket.touchdown", 256.0F);
	public static final SoundEvent ROCKET_ENGINE_CUTOFF = fixed("rocket.engine_cutoff", 384.0F);
	public static final SoundEvent ROCKET_ENTRY_PLASMA = fixed("rocket.entry_plasma", 256.0F);
	public static final SoundEvent ROCKET_COUNTDOWN_BEEP = fixed("rocket.countdown_beep", 32.0F);
	public static final SoundEvent ROCKET_GO_TONE = fixed("rocket.go_tone", 32.0F);
	public static final SoundEvent ROCKET_ALARM = fixed("rocket.alarm", 32.0F);
	public static final SoundEvent ROCKET_CHOPSTICKS = fixed("rocket.chopsticks", 256.0F);
	public static final SoundEvent MARS_WIND = variable("mars.wind");
	public static final SoundEvent MARS_DUST_STORM = variable("mars.dust_storm");
	public static final SoundEvent MARS_DUST_DEVIL = fixed("mars.dust_devil", 64.0F);
	public static final SoundEvent MARS_SUBLIMATION = variable("mars.sublimation");
	public static final SoundEvent MARS_INGENUITY = fixed("mars.ingenuity", 32.0F);
	public static final SoundEvent MACHINE_MOXIE = variable("machine.moxie");
	public static final SoundEvent MACHINE_SABATIER = variable("machine.sabatier");
	public static final SoundEvent MACHINE_ELECTROLYZER = variable("machine.electrolyzer");
	public static final SoundEvent MACHINE_SOLAR_DEPLOY = fixed("machine.solar_deploy", 24.0F);
	public static final SoundEvent MACHINE_HABITAT_PRESSURIZE = fixed("machine.habitat_pressurize", 24.0F);
	public static final SoundEvent MACHINE_HABITAT_LEAK = fixed("machine.habitat_leak", 24.0F);
	public static final SoundEvent MACHINE_AIRLOCK = fixed("machine.airlock", 24.0F);
	public static final SoundEvent SUIT_BREATHING = variable("suit.breathing");
	public static final SoundEvent SUIT_LOW_OXYGEN = variable("suit.low_oxygen");
	public static final SoundEvent SUIT_HELMET_SEAL = variable("suit.helmet_seal");
	public static final SoundEvent SUIT_DOSIMETER_CLICK = variable("suit.dosimeter_click");
	public static final SoundEvent UI_TELEMETRY_EVENT = variable("ui.telemetry_event");
	public static final SoundEvent UI_INTERLUDE_WHOOSH = variable("ui.interlude_whoosh");

	private RPSounds() {
	}

	private static SoundEvent variable(String path) {
		Identifier id = RedPlanet.id(path);
		return Registry.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createVariableRangeEvent(id));
	}

	private static SoundEvent fixed(String path, float range) {
		Identifier id = RedPlanet.id(path);
		return Registry.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createFixedRangeEvent(id, range));
	}

	public static void init() {
	}
}
