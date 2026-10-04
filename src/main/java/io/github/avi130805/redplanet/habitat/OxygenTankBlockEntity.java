package io.github.avi130805.redplanet.habitat;

import io.github.avi130805.redplanet.registry.RPHabitat;
import io.github.avi130805.redplanet.registry.RPSuit;
import io.github.avi130805.redplanet.suit.Oxygen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * A bank of high-pressure oxygen cylinders: six K-size cylinders of 6,900 litres each at 2,200 psi, 59 kg of oxygen
 * (docs/SCIENCE.md, section 5). Habitat regulators draw from the tanks next to them (and next to those); machines that
 * make oxygen fill them. The block keeps its oxygen when picked up.
 */
public class OxygenTankBlockEntity extends BlockEntity {
	public static final float CAPACITY_KG = 59.0F;
	private float kg;

	public OxygenTankBlockEntity(BlockPos pos, BlockState state) {
		super(RPHabitat.OXYGEN_TANK_ENTITY, pos, state);
	}

	public float kg() {
		return this.kg;
	}

	public float room() {
		return CAPACITY_KG - this.kg;
	}

	public float take(float wanted) {
		float taken = Math.max(0.0F, Math.min(wanted, this.kg));
		if (taken > 0.0F) {
			this.kg -= taken;
			this.setChanged();
		}
		return taken;
	}

	public float put(float offered) {
		float added = Math.max(0.0F, Math.min(offered, this.room()));
		if (added > 0.0F) {
			this.kg += added;
			this.setChanged();
		}
		return added;
	}

	/** Comparator output: 0-15 with the fill. */
	public int signal() {
		return this.kg <= 0.0F ? 0 : 1 + Math.round(14.0F * this.kg / CAPACITY_KG);
	}

	@Override
	protected void applyImplicitComponents(DataComponentGetter components) {
		super.applyImplicitComponents(components);
		Oxygen o = components.get(RPSuit.OXYGEN);
		this.kg = o == null ? 0.0F : Math.min(CAPACITY_KG, o.kg());
	}

	@Override
	protected void collectImplicitComponents(DataComponentMap.Builder components) {
		super.collectImplicitComponents(components);
		components.set(RPSuit.OXYGEN, new Oxygen(this.kg, CAPACITY_KG));
	}

	@Override
	public void removeComponentsFromTag(ValueOutput output) {
		output.discard("oxygen_kg");
	}

	@Override
	protected void loadAdditional(ValueInput input) {
		super.loadAdditional(input);
		this.kg = Math.min(CAPACITY_KG, input.getFloatOr("oxygen_kg", 0.0F));
	}

	@Override
	protected void saveAdditional(ValueOutput output) {
		super.saveAdditional(output);
		output.putFloat("oxygen_kg", this.kg);
	}
}
