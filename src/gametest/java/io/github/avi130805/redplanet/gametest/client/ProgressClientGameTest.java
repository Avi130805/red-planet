package io.github.avi130805.redplanet.gametest.client;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** Screenshots of the Mars atlas and the landing-site plaques. Run alone with {@code -PclientTests=progress}. */
public class ProgressClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		if (!ClientTestSupport.enabled("progress")) {
			return;
		}
	}
}
