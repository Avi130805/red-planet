package io.github.avi130805.redplanet.gametest.client;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** Screenshots of the guardians' fight, terraformed ground and the thruster pack. Run alone with {@code -PclientTests=endgame}. */
public class EndgameClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		if (!ClientTestSupport.enabled("endgame")) {
			return;
		}
	}
}
