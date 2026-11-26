package io.github.t9homas.lceviaproxy.net;

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.protocols.v1_18_2to1_19.provider.AckSequenceProvider;
import io.netty.channel.Channel;
import net.raphimc.viaproxy.util.logging.Logger;

import java.util.concurrent.TimeUnit;

/**
 * 1.19+ clients predict block changes (a door opens at once) and undo the prediction when the server confirms the click.
 * Old servers have no confirmation, so ViaVersion sends it right away, before the LCE server's block updates arrive:
 * the door flickered back for a frame or two. For LCE servers the confirmation comes a bit later, after the updates
 * (the plugin also holds back a door's top half for 120 ms).
 */
public final class DelayedBlockAck extends AckSequenceProvider {

    private static final long DELAY_MS = 250;
    private static boolean installed;

    public static synchronized void install() {
        if (installed) return;
        installed = true;
        try {
            Via.getManager().getProviders().use(AckSequenceProvider.class, new DelayedBlockAck());
        } catch (Throwable t) {
            Logger.LOGGER.warn("LCE: could not delay block confirmations: " + t);
        }
    }

    @Override
    public void handleSequence(final UserConnection user, final int sequence) {
        final Channel channel = user.getChannel();
        if (channel == null || channel.pipeline().get(LceCodec.NAME) == null) {
            super.handleSequence(user, sequence); // not an LCE server
            return;
        }
        channel.eventLoop().schedule(() -> {
            if (!channel.isActive()) return;
            try {
                super.handleSequence(user, sequence);
            } catch (Exception e) {
                Logger.LOGGER.warn("LCE: could not confirm a block change: " + e);
            }
        }, DELAY_MS, TimeUnit.MILLISECONDS);
    }

}
