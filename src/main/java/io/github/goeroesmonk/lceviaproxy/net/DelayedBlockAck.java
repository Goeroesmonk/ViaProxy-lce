package io.github.goeroesmonk.lceviaproxy.net;

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.protocols.v1_18_2to1_19.provider.AckSequenceProvider;
import io.netty.channel.Channel;
import net.raphimc.viaproxy.util.logging.Logger;


/**
 * 1.19+ clients predict block changes (a door opens at once) and undo the prediction when the server confirms the click.
 * Old servers have no confirmation, so ViaVersion sends it right away, before the LCE server's block updates arrive:
 * doors and crops flickered back for a frame or two. For LCE servers the confirmation comes after the server's next
 * block update.
 */
public final class DelayedBlockAck extends AckSequenceProvider {

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
        final LceCodec codec = channel == null ? null : (LceCodec) channel.pipeline().get(LceCodec.NAME);
        if (codec == null) {
            super.handleSequence(user, sequence); // not an LCE server
            return;
        }
        if (!channel.eventLoop().inEventLoop()) {
            channel.eventLoop().execute(() -> handleSequence(user, sequence));
            return;
        }
        // confirm after the server's next block update (or after a second)
        codec.queueBlockAck(() -> {
            if (!channel.isActive()) return;
            try {
                super.handleSequence(user, sequence);
            } catch (Exception e) {
                Logger.LOGGER.warn("LCE: could not confirm a block change: " + e);
            }
        });
    }

}
