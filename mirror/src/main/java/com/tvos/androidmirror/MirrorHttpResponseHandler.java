package com.tvos.androidmirror;

import android.util.Log;

import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.handler.codec.http.HttpResponse;

public class MirrorHttpResponseHandler extends SimpleChannelUpstreamHandler {
	public static final String TAG = "HttpEventResponseHandler";

	// public static Channel temp = null;

	public void httpResponseReceived(ChannelHandlerContext context, HttpResponse response) {
		Log.i("HttpEventResponseHandler", response.toString());

		if (response.toString().contains("OK")) {
		    String audioport;
		    if ((audioport = response.headers().get("Audio-Port")) != null) {
		        AirplayClientInterface.getInstance().setAudioPort(Integer.parseInt(audioport));
		    }
			AirplayClientInterface.getInstance().onRequireMirrorSuccess();
		} else {
			AirplayClientInterface.getInstance().onRequireMirrorFailed();
		}
		// try {
		// context.getPipeline().getChannel().close().await();
		// } catch (InterruptedException e) {
		//
		// e.printStackTrace();
		// }
		//
		// if (!(response.getStatus().equals(HttpResponseStatus.OK))) {
		// Log.i("HttpEventResponseHandler", "resp status != OK, but " + response.getStatus());
		// DefaultHttpResponse toIOS = new DefaultHttpResponse(RtspVersions.RTSP_1_0, RtspResponseStatuses.OK);
		// toIOS.headers().set("Content-Length", String.valueOf(0));
		// temp.write(toIOS);
		// } else {
		// temp.write(response);
		// }
	}

	public void messageReceived(ChannelHandlerContext context, MessageEvent event) throws Exception {
		Log.d(TAG, "==>messageReceived:" + event.toString());
		if (event.getMessage() instanceof HttpResponse) {
			HttpResponse response = (HttpResponse) event.getMessage();
			Log.i(TAG, "HttpResponse Received status=" + response.getStatus());
			httpResponseReceived(context, response);
			return;
		}
		Log.e(TAG, "Unkown Message");
	}
	
	@Override
	public void exceptionCaught(ChannelHandlerContext context, ExceptionEvent exception) throws Exception {
		// TODO Auto-generated method stub
		Log.e(TAG, "Exception info: " + exception.toString());
		super.exceptionCaught(context, exception);
	}
}