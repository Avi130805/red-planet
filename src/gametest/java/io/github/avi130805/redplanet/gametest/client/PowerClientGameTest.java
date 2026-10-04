package io.github.avi130805.redplanet.gametest.client;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** Screenshots of the power and ISRU machines. Run alone with {@code -PclientTests=power}. */
public class PowerClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		if (!ClientTestSupport.enabled("power")) {
			return;
		}
	}
}
