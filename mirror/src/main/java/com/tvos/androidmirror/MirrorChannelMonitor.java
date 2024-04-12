package com.tvos.androidmirror;

import android.util.Log;

import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.SimpleChannelHandler;

public class MirrorChannelMonitor extends SimpleChannelHandler {
    private String TAG = "MirrorChannelMonitor";

    public void channelOpen(ChannelHandlerContext context, ChannelStateEvent event) throws Exception {
        Log.i(TAG, "Connection open...");
        super.channelOpen(context, event);
    }

    @Override
    public void channelClosed(final ChannelHandlerContext ctx, final ChannelStateEvent evt) throws Exception {
        Log.i(TAG, "Client " + evt.getChannel().getRemoteAddress() + " disconnected on " + evt.getChannel().getLocalAddress());
        AirplayClientInterface.getInstance().onMirrorDisconnected();
        super.channelClosed(ctx, evt);
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, final ExceptionEvent evt) throws Exception {
        // 对读取超时异常进行判断
        Log.e(TAG, "Handler raised exception", evt.getCause());
        //ctx.getChannel().close();

        super.exceptionCaught(ctx, evt);
    }

    @Override
    public void channelConnected(final ChannelHandlerContext ctx, final ChannelStateEvent e) throws Exception {
        Log.i(TAG, "Client " + e.getChannel().getRemoteAddress() + " connected on " + e.getChannel().getLocalAddress());
    }
}