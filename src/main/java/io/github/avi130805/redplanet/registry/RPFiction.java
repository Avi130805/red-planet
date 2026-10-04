package io.github.avi130805.redplanet.registry;

import static io.github.avi130805.redplanet.registry.RPRegistration.item;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;

/**
 * Fiction-layer items that pass between features (docs/DESIGN.md section 8.7): dropped by one, used by another.
 * Everything here is fiction, labelled as such in docs/SCIENCE.md.
 */
public final class RPFiction {
	/** Statically charged dust from a dust wraith: fuel for the thruster pack, and a battery ingredient. */
	public static final Item CHARGED_DUST = item("charged_dust");
	/** Scrap of the Areans' iron-chromium alloy, from custodians and ruins: smelted with chromium into Arean alloy. */
	public static final Item AREAN_ALLOY_SCRAP = item("arean_alloy_scrap");
	/** A custodian's power source: a compact power cell for electronics and Arean machines. */
	public static final Item CUSTODIAN_CORE = item("custodian_core", Item::new, new Item.Properties().rarity(Rarity.UNCOMMON));

	private RPFiction() {
	}

	public static void init() {
	}
}
