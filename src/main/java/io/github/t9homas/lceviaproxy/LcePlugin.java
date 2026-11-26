package io.github.t9homas.lceviaproxy;

import io.github.t9homas.lceviaproxy.net.LceCodec;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.util.AttributeKey;
import net.lenni0451.lambdaevents.EventHandler;
import net.raphimc.netminecraft.constants.MCPipeline;
import net.raphimc.viaproxy.ViaProxy;
import net.raphimc.viaproxy.plugins.ViaProxyPlugin;
import net.raphimc.viaproxy.plugins.events.PreConnectEvent;
import net.raphimc.viaproxy.plugins.events.Proxy2ServerChannelInitializeEvent;
import net.raphimc.viaproxy.plugins.events.types.ITyped;
import net.raphimc.viaproxy.proxy.proxy2server.Proxy2ServerChannelInitializer;
import net.raphimc.viaproxy.proxy.session.ProxyConnection;
import net.raphimc.viaproxy.util.logging.Logger;

import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Hashtable;

public class LcePlugin extends ViaProxyPlugin {

    /**
     * Set on the client (C2P) channel when the backend is an LCE server.
     */
    public static final AttributeKey<Boolean> LCE_TARGET = AttributeKey.valueOf("lceviaproxy-target");

    /**
     * ViaLegacy's pre-netty stream splitter; the LCE codec takes its place.
     */
    private static final String PRE_NETTY_CODEC = "vialegacy-pre-netty-length-codec";

    private static LceConfig config;

    private static LceMessages messages;

    public static LceMessages messages() {
        return messages;
    }

    public static LceConfig config() {
        return config;
    }

    @Override
    public void onEnable() {
        config = new LceConfig(this.getDataFolder());
        messages = new LceMessages(this.getDataFolder());
        LceVersions.register();
        ViaProxy.EVENT_MANAGER.register(this);
        Logger.LOGGER.info("LCERevelations loaded: added \"" + LceVersions.LCE_REVELATIONS.getName() + "\" server version");
    }

    @EventHandler
    private void onPreConnect(final PreConnectEvent event) {
        final Channel c2p = event.getClientChannel();
        if (!LceVersions.isLce(event.getServerVersion())) {
            if (c2p != null) {
                c2p.attr(LCE_TARGET).set(null);
            }
            return;
        }

        SocketAddress address = event.getServerAddress();
        if (address instanceof InetSocketAddress inet && config.srvLookup() && inet.getPort() == 25565) {
            final InetSocketAddress srv = lookupSrv(inet.getHostString());
            if (srv != null) {
                Logger.LOGGER.info("LCE: following SRV record of " + inet.getHostString() + " -> " + srv.getHostString() + ":" + srv.getPort());
                address = srv;
                event.setServerAddress(srv);
            }
        }

        if (c2p != null) {
            c2p.attr(LCE_TARGET).set(Boolean.TRUE);
        }
        event.setServerVersion(LceVersions.baseVersion());
        if (config.lceSkins()) io.github.t9homas.lceviaproxy.skin.LceSkins.install();
        io.github.t9homas.lceviaproxy.net.DelayedBlockAck.install();
        Logger.LOGGER.info("Connecting to LCE Revelations server " + address);
    }

    @EventHandler
    private void onProxy2ServerChannelInit(final Proxy2ServerChannelInitializeEvent event) {
        if (event.getType() != ITyped.Type.POST || event.isLegacyPassthrough()) {
            return;
        }
        final ProxyConnection proxyConnection = ProxyConnection.fromChannel(event.getChannel());
        if (proxyConnection == null || proxyConnection.getC2P() == null || !Boolean.TRUE.equals(proxyConnection.getC2P().attr(LCE_TARGET).get())) {
            return;
        }

        final ChannelPipeline pipeline = event.getChannel().pipeline();
        if (pipeline.get(MCPipeline.ENCRYPTION_HANDLER_NAME) != null) {
            pipeline.remove(MCPipeline.ENCRYPTION_HANDLER_NAME);
        }
        if (pipeline.get(Proxy2ServerChannelInitializer.VIAPROXY_HAPROXY_ENCODER_NAME) != null) {
            Logger.LOGGER.warn("HAProxy protocol is not supported for LCE backends, ignoring it");
            pipeline.remove(Proxy2ServerChannelInitializer.VIAPROXY_HAPROXY_ENCODER_NAME);
        }
        final LceCodec codec = new LceCodec(config);
        if (pipeline.get(PRE_NETTY_CODEC) != null) {
            pipeline.replace(PRE_NETTY_CODEC, LceCodec.NAME, codec);
        } else {
            pipeline.addBefore(MCPipeline.SIZER_HANDLER_NAME, LceCodec.NAME, codec);
        }
    }

    private static InetSocketAddress lookupSrv(final String host) {
        if (host == null || host.isEmpty() || Character.isDigit(host.charAt(host.length() - 1)) || host.contains(":")) {
            return null; // IP literal
        }
        try {
            final Hashtable<String, String> env = new Hashtable<>();
            env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            env.put("java.naming.provider.url", "dns:");
            env.put("com.sun.jndi.dns.timeout.retries", "1");
            final Attributes attributes = new InitialDirContext(env).getAttributes("_minecraft._tcp." + host, new String[]{"SRV"});
            final Attribute srv = attributes.get("srv");
            if (srv == null || srv.size() == 0) {
                return null;
            }
            final String[] parts = srv.get(0).toString().split(" ");
            final int port = Integer.parseInt(parts[2]);
            String target = parts[3];
            if (target.endsWith(".")) {
                target = target.substring(0, target.length() - 1);
            }
            return new InetSocketAddress(target, port);
        } catch (Throwable e) {
            return null;
        }
    }

}
