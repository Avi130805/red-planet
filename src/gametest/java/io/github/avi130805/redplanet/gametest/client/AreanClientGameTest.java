package io.github.avi130805.redplanet.gametest.client;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** Screenshots of the Arean ruins, a sanctum, a vault and the Cydonia gate. Run alone with {@code -PclientTests=arean}. */
public class AreanClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		if (!ClientTestSupport.enabled("arean")) {
			return;
		}
	}
}
