package com.weibo.api.motan.transport.netty4;

import com.weibo.api.motan.common.ChannelState;
import com.weibo.api.motan.common.MotanConstants;
import com.weibo.api.motan.common.URLParamType;
import com.weibo.api.motan.core.DefaultThreadFactory;
import com.weibo.api.motan.core.StandardThreadExecutor;
import com.weibo.api.motan.exception.MotanFrameworkException;
import com.weibo.api.motan.rpc.Request;
import com.weibo.api.motan.rpc.Response;
import com.weibo.api.motan.rpc.URL;
import com.weibo.api.motan.transport.AbstractServer;
import com.weibo.api.motan.transport.MessageHandler;
import com.weibo.api.motan.transport.TransportException;
import com.weibo.api.motan.util.LoggerUtil;
import com.weibo.api.motan.util.StatisticCallback;
import com.weibo.api.motan.util.StatsUtil;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author sunnights
 */
public class NettyServer extends AbstractServer implements StatisticCallback {
    protected NettyServerChannelManage channelManage = null;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private MessageHandler messageHandler;
    private StandardThreadExecutor standardThreadExecutor = null;
    private Class<? extends ServerChannel> channelType;

    private AtomicInteger rejectCounter = new AtomicInteger(0);

    public AtomicInteger getRejectCounter() {
        return rejectCounter;
    }

    public NettyServer(URL url, MessageHandler messageHandler) {
        super(url);
        this.messageHandler = messageHandler;
    }

    @Override
    public boolean isBound() {
        return serverChannel != null && serverChannel.isActive();
    }

    @Override
    public Response request(Request request) throws TransportException {
        throw new MotanFrameworkException("NettyServer request(Request request) method not support: url: " + url);
    }

    @Override
    public boolean open() {
        if (isAvailable()) {
            LoggerUtil.warn("NettyServer ServerChannel already Open: url=" + url);
            return state.isAliveState();
        }
        if (bossGroup == null) {
            bossGroup = epollIsAvailable() ? new EpollEventLoopGroup(1) : new NioEventLoopGroup(1);
            workerGroup = epollIsAvailable() ? new EpollEventLoopGroup() : new NioEventLoopGroup();
        }

        LoggerUtil.info("NettyServer ServerChannel start Open: url=" + url);
        boolean shareChannel = url.getBooleanParameter(URLParamType.shareChannel.getName(), URLParamType.shareChannel.getBooleanValue());
        final int maxContentLength = url.getIntParameter(URLParamType.maxContentLength.getName(), URLParamType.maxContentLength.getIntValue());
        int maxServerConnection = url.getIntParameter(URLParamType.maxServerConnection.getName(), URLParamType.maxServerConnection.getIntValue());
        int workerQueueSize = url.getIntParameter(URLParamType.workerQueueSize.getName(), URLParamType.workerQueueSize.getIntValue());

        int minWorkerThread, maxWorkerThread;

        if (shareChannel) {
            minWorkerThread = url.getIntParameter(URLParamType.minWorkerThread.getName(), MotanConstants.NETTY_SHARECHANNEL_MIN_WORKDER);
            maxWorkerThread = url.getIntParameter(URLParamType.maxWorkerThread.getName(), MotanConstants.NETTY_SHARECHANNEL_MAX_WORKDER);
        } else {
            minWorkerThread = url.getIntParameter(URLParamType.minWorkerThread.getName(), MotanConstants.NETTY_NOT_SHARECHANNEL_MIN_WORKDER);
            maxWorkerThread = url.getIntParameter(URLParamType.maxWorkerThread.getName(), MotanConstants.NETTY_NOT_SHARECHANNEL_MAX_WORKDER);
        }

        standardThreadExecutor = (standardThreadExecutor != null && !standardThreadExecutor.isShutdown()) ? standardThreadExecutor
                : new StandardThreadExecutor(minWorkerThread, maxWorkerThread, workerQueueSize, new DefaultThreadFactory("NettyServer-" + url.getServerPortStr(), true));
        standardThreadExecutor.prestartAllCoreThreads();

        channelManage = new NettyServerChannelManage(maxServerConnection);

        channelType = epollIsAvailable() ? EpollServerSocketChannel.class : NioServerSocketChannel.class;

        ServerBootstrap serverBootstrap = new ServerBootstrap();
        serverBootstrap.group(bossGroup, workerGroup)
                .channel(channelType)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast("channel_manage", channelManage);
                        pipeline.addLast("decoder", new NettyDecoder(codec, NettyServer.this, maxContentLength));
                        pipeline.addLast("encoder", new NettyEncoder());
                        NettyChannelHandler handler = new NettyChannelHandler(NettyServer.this, messageHandler, standardThreadExecutor);
                        pipeline.addLast("handler", handler);
                    }
                });
        serverBootstrap.option(ChannelOption.SO_LINGER, -1);
        serverBootstrap.option(ChannelOption.SO_BACKLOG, 128);
        serverBootstrap.childOption(ChannelOption.TCP_NODELAY, true);
        serverBootstrap.childOption(ChannelOption.SO_KEEPALIVE, true);
        ChannelFuture channelFuture = serverBootstrap.bind(new InetSocketAddress(url.getPort()));
        channelFuture.syncUninterruptibly();
        serverChannel = channelFuture.channel();
        state = ChannelState.ALIVE;
        StatsUtil.registryStatisticCallback(this);
        LoggerUtil.info("NettyServer ServerChannel finish Open: url=" + url);
        return state.isAliveState();
    }

    private static boolean epollIsAvailable() {
        boolean available;
        try {
            available = Epoll.isAvailable();
        } catch (NoClassDefFoundError e) {
            LoggerUtil.debug("Epoll is unavailable, skipping", e);
            return false;
        } catch (RuntimeException | Error e) {
            LoggerUtil.warn("Epoll is unavailable, skipping", e);
            return false;
        }
        if (!available) {
            LoggerUtil.debug("Epoll is unavailable, skipping", Epoll.unavailabilityCause());
        }
        return available;
    }

    @Override
    public synchronized void close() {
        close(0);
    }

    @Override
    public synchronized void close(int timeout) {
        if (state.isCloseState()) {
            return;
        }

        try {
            cleanup();
            if (state.isUnInitState()) {
                LoggerUtil.info("NettyServer close fail: state={}, url={}", state.value, url.getUri());
                return;
            }

            // 设置close状态
            state = ChannelState.CLOSE;
            LoggerUtil.info("NettyServer close Success: url={}", url.getUri());
        } catch (Exception e) {
            LoggerUtil.error("NettyServer close Error: url=" + url.getUri(), e);
        }
    }

    public void cleanup() {
        // close listen socket
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
            bossGroup = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
            workerGroup = null;
        }
        // close all clients's channel
        if (channelManage != null) {
            channelManage.close();
        }
        // shutdown the threadPool
        if (standardThreadExecutor != null) {
            standardThreadExecutor.shutdownNow();
        }
        // 取消统计回调的注册
        StatsUtil.unRegistryStatisticCallback(this);
    }

    @Override
    public boolean isClosed() {
        return state.isCloseState();
    }

    @Override
    public boolean isAvailable() {
        return state.isAliveState();
    }

    @Override
    public URL getUrl() {
        return url;
    }

    @Override
    public String statisticCallback() {
        return String.format("identity: %s connectionCount: %s taskCount: %s queueCount: %s maxThreadCount: %s maxTaskCount: %s executorRejectCount: %s",
                url.getIdentity(), channelManage.getChannels().size(), standardThreadExecutor.getSubmittedTasksCount(),
                standardThreadExecutor.getQueue().size(), standardThreadExecutor.getMaximumPoolSize(),
                standardThreadExecutor.getMaxSubmittedTaskCount(), rejectCounter.getAndSet(0));
    }
}
