package io.github.avi130805.redplanet.gametest.client;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** Screenshots of the creatures in their habitats. Run alone with {@code -PclientTests=creatures}. */
public class CreaturesClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		if (!ClientTestSupport.enabled("creatures")) {
			return;
		}
	}
}
