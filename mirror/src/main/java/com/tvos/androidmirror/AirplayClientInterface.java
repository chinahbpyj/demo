package com.tvos.androidmirror;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.util.Log;

import com.tvos.androidmirror.util.CommonHelper;
import com.tvos.androidmirror.util.LOG;

import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.bootstrap.ConnectionlessBootstrap;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.FixedReceiveBufferSizePredictorFactory;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.group.ChannelGroupFuture;
import org.jboss.netty.channel.group.DefaultChannelGroup;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.channel.socket.oio.OioDatagramChannelFactory;
import org.jboss.netty.handler.codec.http.DefaultHttpRequest;
import org.jboss.netty.handler.codec.http.HttpMethod;
import org.jboss.netty.handler.codec.http.HttpRequestEncoder;
import org.jboss.netty.handler.codec.http.HttpResponseDecoder;
import org.jboss.netty.handler.codec.http.HttpVersion;

import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;

/**
 * Class used to communicate to the AirPlay-enabled device.
 */
public class AirplayClientInterface {
	private static final String TAG = "AirplayClientInterface";
	private static final byte PAYLOAD_TYPE = 0x60;
	
	public static final int MODE_FLUENCY_FIRST = 0;
	public static final int MODE_QUALITY_FIRST = 1; // no fps control
	
	private int mMirrorMode=MODE_FLUENCY_FIRST;
	// executor service for asynchronous tasks to the service
	private ExecutorService mExecutorService;
	private ServiceInfo mServiceInfo = null;
	private String mUuid = null;
	private String mTargetIp = null;
	private Context mContext = null;
	private int mScreenDensity;
	private int mResultCode;
	private Intent mResultData;
	
	// ScreenRecorder settings
	private int mWidth = 1280;
	private int mHeight = 720;
	private int mBitrate = 2 * 1024 * 1024;// 2Mbps
	private int mFrameRate = 30; // 30 fps
	private int mResolution = 720; // 720P
	
	private int mSocketSendBuffuerSize = 128*1024;// 128k bytes,
	private int mHignWaterMark = 64*1024; // 64k bytes, most delay is 0.17s
	private int mLowWaterMark = 32*1024;
	private int mAudioMode = 0;

	private AirplayDiscover mAirplyDiscover;
	private int mMirrorPort = 7100;// default, TVAPP may　be changed
	private int mAudioRtpPort = 9999;
	// callback back to UI
	private ArrayList<AirPlayClientCallback> mCallbackList = new ArrayList<AirPlayClientCallback>();
	private boolean isMirrorStarted = false;
	private boolean isMirrorConnected = false;
	private boolean hasAudioConnection = true;

	private ClientBootstrap mMirrorBootstrap = null;
	private ChannelFuture mMirrorFuture = null;
	private Channel mMirrorChannel = null;
	private ConnectionlessBootstrap mAudioBootstrap = null;
	private InetSocketAddress mLocalAddress;
	private InetSocketAddress mRemoteAddress;
	private Channel mAudioChannel = null;
	private ChannelGroup mAllChannels = null;
	private int mAudioRtpSeqNum = 0;
	public boolean hasExeception = false;
	private static final byte[] STREAMINFO = { (byte) 0x62, (byte) 0x70, (byte) 0x6c, (byte) 0x69, (byte) 0x73,
			(byte) 0x74, (byte) 0x30, (byte) 0x30, (byte) 0x58, (byte) 0x64, (byte) 0x65, (byte) 0x76, (byte) 0x69,
			(byte) 0x63, (byte) 0x65, (byte) 0x49, (byte) 0x64, (byte) 0xd1, (byte) 0x0c, (byte) 0x0f, (byte) 0x54,
			(byte) 0x51, (byte) 0x75, (byte) 0x65, (byte) 0x46, (byte) 0x23, (byte) 0x42, (byte) 0xea, (byte) 0x04,
			(byte) 0x57, (byte) 0xc9, (byte) 0x72, (byte) 0xef, (byte) 0x20, (byte) 0x54, (byte) 0x49, (byte) 0x64,
			(byte) 0x44, (byte) 0x70, (byte) 0xd1, (byte) 0x0c, (byte) 0x2b, (byte) 0x23, (byte) 0x41, (byte) 0x67,
			(byte) 0x8c, (byte) 0x29, (byte) 0xc0, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x57, (byte) 0x66,
			(byte) 0x70, (byte) 0x73, (byte) 0x49, (byte) 0x6e, (byte) 0x66, (byte) 0x6f, (byte) 0xd1, (byte) 0x0c,
			(byte) 0x13, (byte) 0x54, (byte) 0x45, (byte) 0x6e, (byte) 0x44, (byte) 0x70, (byte) 0x55, (byte) 0x51,
			(byte) 0x75, (byte) 0x65, (byte) 0x46, (byte) 0x72, (byte) 0x57, (byte) 0x76, (byte) 0x65, (byte) 0x72,
			(byte) 0x73, (byte) 0x69, (byte) 0x6f, (byte) 0x6e, (byte) 0xd1, (byte) 0x0c, (byte) 0x23, (byte) 0xd1,
			(byte) 0x0c, (byte) 0x21, (byte) 0xd1, (byte) 0x0c, (byte) 0x19, (byte) 0x54, (byte) 0x53, (byte) 0x65,
			(byte) 0x6e, (byte) 0x74, (byte) 0x54, (byte) 0x49, (byte) 0x64, (byte) 0x45, (byte) 0x6e, (byte) 0x55,
			(byte) 0x41, (byte) 0x66, (byte) 0x50, (byte) 0x78, (byte) 0x54, (byte) 0xd1, (byte) 0x0c, (byte) 0x1f,
			(byte) 0xd1, (byte) 0x0c, (byte) 0x1b, (byte) 0xd1, (byte) 0x0c, (byte) 0x25, (byte) 0x54, (byte) 0x45,
			(byte) 0x51, (byte) 0x44, (byte) 0x70, (byte) 0x5d, (byte) 0x74, (byte) 0x69, (byte) 0x6d, (byte) 0x65,
			(byte) 0x73, (byte) 0x74, (byte) 0x61, (byte) 0x6d, (byte) 0x70, (byte) 0x49, (byte) 0x6e, (byte) 0x66,
			(byte) 0x6f, (byte) 0xd1, (byte) 0x0c, (byte) 0x27, (byte) 0xa8, (byte) 0x0b, (byte) 0x0e, (byte) 0x10,
			(byte) 0x12, (byte) 0x14, (byte) 0x16, (byte) 0x18, (byte) 0x1a, (byte) 0x55, (byte) 0x42, (byte) 0x65,
			(byte) 0x66, (byte) 0x45, (byte) 0x6e, (byte) 0x54, (byte) 0x53, (byte) 0x75, (byte) 0x62, (byte) 0x53,
			(byte) 0x55, (byte) 0x42, (byte) 0x65, (byte) 0x50, (byte) 0x78, (byte) 0x54, (byte) 0xd1, (byte) 0x0c,
			(byte) 0x15, (byte) 0xd1, (byte) 0x0c, (byte) 0x0d, (byte) 0x59, (byte) 0x73, (byte) 0x65, (byte) 0x73,
			(byte) 0x73, (byte) 0x69, (byte) 0x6f, (byte) 0x6e, (byte) 0x49, (byte) 0x44, (byte) 0xd1, (byte) 0x0c,
			(byte) 0x17, (byte) 0x54, (byte) 0x6e, (byte) 0x61, (byte) 0x6d, (byte) 0x65, (byte) 0xd1, (byte) 0x0c,
			(byte) 0x11, (byte) 0x56, (byte) 0x31, (byte) 0x35, (byte) 0x30, (byte) 0x2e, (byte) 0x33, (byte) 0x33,
			(byte) 0x59, (byte) 0x6c, (byte) 0x61, (byte) 0x74, (byte) 0x65, (byte) 0x6e, (byte) 0x63, (byte) 0x79,
			(byte) 0x4d, (byte) 0x73, (byte) 0xa7, (byte) 0x1e, (byte) 0x20, (byte) 0x22, (byte) 0x24, (byte) 0x26,
			(byte) 0x28, (byte) 0x2a, (byte) 0xd1, (byte) 0x0c, (byte) 0x29, (byte) 0xd6, (byte) 0x01, (byte) 0x03,
			(byte) 0x05, (byte) 0x07, (byte) 0x09, (byte) 0x1c, (byte) 0x02, (byte) 0x04, (byte) 0x06, (byte) 0x08,
			(byte) 0x0a, (byte) 0x1d, (byte) 0x23, (byte) 0x40, (byte) 0x56, (byte) 0x80, (byte) 0x00, (byte) 0x00,
			(byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x55, (byte) 0x53, (byte) 0x6e, (byte) 0x64, (byte) 0x46,
			(byte) 0x72, (byte) 0x55, (byte) 0x45, (byte) 0x6d, (byte) 0x45, (byte) 0x6e, (byte) 0x63, (byte) 0x55,
			(byte) 0x53, (byte) 0x75, (byte) 0x62, (byte) 0x53, (byte) 0x75, (byte) 0x54, (byte) 0x42, (byte) 0x34,
			(byte) 0x45, (byte) 0x6e, (byte) 0x00, (byte) 0xda, (byte) 0x00, (byte) 0x08, (byte) 0x00, (byte) 0x19,
			(byte) 0x00, (byte) 0xc5, (byte) 0x00, (byte) 0xe7, (byte) 0x00, (byte) 0xa9, (byte) 0x00, (byte) 0x2a,
			(byte) 0x00, (byte) 0x49, (byte) 0x00, (byte) 0xbe, (byte) 0x00, (byte) 0x33, (byte) 0x00, (byte) 0x89,
			(byte) 0x00, (byte) 0xa6, (byte) 0x00, (byte) 0xb6, (byte) 0x00, (byte) 0x98, (byte) 0x00, (byte) 0x11,
			(byte) 0x01, (byte) 0x02, (byte) 0x00, (byte) 0xbb, (byte) 0x00, (byte) 0x3e, (byte) 0x00, (byte) 0x3b,
			(byte) 0x00, (byte) 0x5f, (byte) 0x00, (byte) 0xa3, (byte) 0x00, (byte) 0x22, (byte) 0x00, (byte) 0xb3,
			(byte) 0x00, (byte) 0x73, (byte) 0x00, (byte) 0x57, (byte) 0x00, (byte) 0x14, (byte) 0x00, (byte) 0x6d,
			(byte) 0x00, (byte) 0x5a, (byte) 0x00, (byte) 0x78, (byte) 0x00, (byte) 0xcf, (byte) 0x00, (byte) 0x6a,
			(byte) 0x00, (byte) 0xfc, (byte) 0x00, (byte) 0x54, (byte) 0x00, (byte) 0x9d, (byte) 0x00, (byte) 0x51,
			(byte) 0x00, (byte) 0x64, (byte) 0x00, (byte) 0x70, (byte) 0x00, (byte) 0x92, (byte) 0x00, (byte) 0x86,
			(byte) 0x00, (byte) 0xf6, (byte) 0x00, (byte) 0xd7, (byte) 0x00, (byte) 0x43, (byte) 0x00, (byte) 0x27,
			(byte) 0x00, (byte) 0xf0, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
			(byte) 0x02, (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
			(byte) 0x00, (byte) 0x2c, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
			(byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
			(byte) 0x01, (byte) 0x07 };
	private byte[] packetHeader = { (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
			(byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
			(byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
			(byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
			(byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
			(byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
			(byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
			(byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
			(byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
			(byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
			(byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
			(byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
			(byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
			(byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
			(byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
			(byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
			(byte) 0x00, (byte) 0x00 };

	private static AirplayClientInterface sInstance;
	public synchronized static AirplayClientInterface getInstance() {
		if (sInstance == null)
			sInstance = new AirplayClientInterface();
		return sInstance;
	}

	private AirplayClientInterface() {
		mAirplyDiscover = new AirplayDiscover();
	}

    public synchronized void setMirrorPort(int port) {
        this.mMirrorPort = port;
    }

    public synchronized void setAudioPort(int port) {
		this.mAudioRtpPort = port;
	}

	public synchronized void setMirrorMode(int mode) {
		this.mMirrorMode = mode;
	}

	//set the casted video's frame rate
	private synchronized void setFrameRate(int rate) {
		this.mFrameRate = rate;
	}
	
	//set the casted video's bit rate
	public synchronized void setBitrate(int bitrateX) {
		this.mBitrate = bitrateX * 1024 * 1024;
	}
	
	//set phone resolution(480P, 720P, 1080P)
	public synchronized void setResolution(int reso) {
		this.mResolution = reso;
	}
	
	public synchronized void StartMirror(Context context, int screendenstiy,
			int resultcode, Intent resultdata, String uuid, String ip) {
		if (isMirrorStarted || context == null) {
			return;
		}
		Log.i(TAG, "StartMirror");
		mContext = context;
		mScreenDensity = screendenstiy;
		mResultCode = resultcode;
		mResultData = resultdata;
		mUuid = uuid;
		mTargetIp = ip;
		if (Build.VERSION.SDK_INT >= 29) {
			mAudioMode = 1;
		}

		new Thread() {
			@Override
			public void run() {
				requestMirror();
			}
		}.start();
	}

	public synchronized void StopMirror() {
		if (!isMirrorStarted) {
			return;
		}
		Log.i(TAG, "StopMirror");
		isMirrorStarted = false;

		Intent intent = new Intent(mContext, CaptureService.class);
		mContext.stopService(intent);
	}

	public synchronized void RegisterCallback(AirPlayClientCallback callback) {
		if (callback != null) {
			mCallbackList.add(callback);
		}
	}

	public AirplayDiscover getAirplyDiscover() {
		return mAirplyDiscover;
	}

	@SuppressWarnings("deprecation")
	public void setServiceInfo(ServiceInfo serviceInfo) {
		mServiceInfo = serviceInfo;
		mTargetIp = mServiceInfo.getHostAddress();
	}

	public ServiceInfo getServiceInfo() {
		return this.mServiceInfo;
	}

	public void setTargetIpAddress(String ip) {
		mTargetIp = ip;
	}

	public String getTargetIpAddress() {
		return mTargetIp;
	}

	private boolean getRootPermission(String command) {
		Process process = null;
		DataOutputStream os = null;
		try {
			process = Runtime.getRuntime().exec("su");
			os = new DataOutputStream(process.getOutputStream());
			os.writeBytes(command + "\n");
			os.writeBytes("exit\n");
			os.flush();
			process.waitFor();
		} catch (Exception e) {
			return false;
		} finally {
			try {
				if (os != null) {
					os.close();
				}
				process.destroy();
			} catch (Exception e) {
			}
		}
		return true;
	}

	private void requestMirror() {
    	Log.i(TAG, "requestMirror");
        if(isMirrorStarted || mTargetIp == null) return;
        
        try {
            Log.i(TAG, "trying to request mirror to: " + mTargetIp + ":" + mMirrorPort);
            if (mExecutorService == null) {
                mExecutorService = Executors.newCachedThreadPool();
            }
            if (mAllChannels == null) {
                mAllChannels = new DefaultChannelGroup();
            }
            mMirrorBootstrap = new ClientBootstrap(new NioClientSocketChannelFactory(mExecutorService, mExecutorService));
            mMirrorBootstrap.setPipelineFactory(new MirrorPipelineFactory());
            mMirrorBootstrap.setOption("tcpNoDelay", true);
            mMirrorBootstrap.setOption("keepAlive", true);
            mMirrorBootstrap.setOption("sendBufferSize", mSocketSendBuffuerSize);
            mMirrorBootstrap.setOption("writeBufferHighWaterMark", mHignWaterMark);
            mMirrorBootstrap.setOption("writeBufferLowWaterMark", mLowWaterMark);

			LOG.d(TAG, "sendBufferSize = " + mMirrorBootstrap.getOption("sendBufferSize")
				+ "; writeBufferHighWaterMark = " + mMirrorBootstrap.getOption("writeBufferHighWaterMark")
				+ "; writeBufferLowWaterMark = " +  mMirrorBootstrap.getOption("writeBufferLowWaterMark"));

            mMirrorFuture = mMirrorBootstrap.connect(new InetSocketAddress(mTargetIp, mMirrorPort));
            mMirrorChannel = mMirrorFuture.awaitUninterruptibly().getChannel();
            mAllChannels.add(mMirrorChannel);
            
			if (!mMirrorFuture.isSuccess()) {
				Log.e(TAG, "Connect Mirror Server Failed!");
				onRequireMirrorFailed();
				mMirrorFuture.getCause().printStackTrace();
				mMirrorChannel.close();
				mMirrorChannel = null;
				return;
			}
			if (!(mMirrorChannel.isOpen())) {
				Log.e(TAG, "Mirror Channel is not opened!");
				onRequireMirrorFailed();
				mMirrorChannel.close();
				mMirrorChannel = null;
				return;
			}
			
			Log.i(TAG, "Connect Mirror Server...[Success]");
			DefaultHttpRequest localDefaultHttpRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, 
					HttpMethod.GET, "/stream.xml");
			localDefaultHttpRequest.headers().set("Content-Length", "0");
			localDefaultHttpRequest.headers().set("User-Agent", "Tvguo app");
			localDefaultHttpRequest.headers().set("", "");
			LOG.d(TAG, "send Http Request: " + localDefaultHttpRequest.getUri());
			mMirrorChannel.write(localDefaultHttpRequest);
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

	public synchronized void disconnectMirror() {
		Log.i(TAG, "disconnect mirror");
		isMirrorStarted = false;
		mUuid = "";

        new Thread() {
            @Override
            public void run() {
                /* Close channels */
                if (mAllChannels != null) {
                    final ChannelGroupFuture allChannelsClosed = mAllChannels.close();
                    /* Wait for all channels to finish closing */
                    allChannelsClosed.awaitUninterruptibly();
                    mAllChannels.clear();
                    mAllChannels = null;
                }
                if (mExecutorService != null) {
                    mExecutorService.shutdown();
                    mExecutorService = null;
                }
                if (mMirrorBootstrap != null) {
                    mMirrorBootstrap.releaseExternalResources();
                    mMirrorBootstrap = null;
                }
                if (!isMirrorConnected) {
                    for (int i = 0; i < mCallbackList.size(); i++) {
                        AirPlayClientCallback callback = mCallbackList.get(i);
                        if (callback != null) {
                            callback.onStopMirrorCompleted();
                        }
                    }
                }
            }
        }.start();
	}

	public synchronized void onRequireMirrorSuccess() {
		Log.i(TAG, "request mirror device info success");
		isMirrorStarted = true;
		isMirrorConnected = true;
		postMirrorStreaminfo();

		if (hasAudioConnection) {
			mAudioBootstrap = new ConnectionlessBootstrap(new OioDatagramChannelFactory(mExecutorService));
			mAudioBootstrap.setPipelineFactory(new AudioPipelineFactory());
			mLocalAddress = new InetSocketAddress(0);
			mRemoteAddress = new InetSocketAddress(mTargetIp, mAudioRtpPort);
			mAudioBootstrap.setOption("localAddress", mLocalAddress);
			mAudioBootstrap.setOption("remoteAddress", mRemoteAddress);
			mAudioBootstrap.setOption("sendBufferSize", mSocketSendBuffuerSize);
			mAudioBootstrap.setOption("writeBufferHighWaterMark", mHignWaterMark);
			mAudioBootstrap.setOption("writeBufferLowWaterMark", mLowWaterMark);
			mAudioBootstrap.setOption("receiveBufferSize", 1024);
			mAudioBootstrap.setOption("receiveBufferSizePredictorFactory", new FixedReceiveBufferSizePredictorFactory(1024));
			try {
				mAudioChannel = mAudioBootstrap.bind();
				mAudioChannel.connect(mRemoteAddress);
				mAllChannels.add(mAudioChannel);
			} catch (Exception e) {
				e.printStackTrace();
			}
			mAudioRtpSeqNum = 0;

			Log.i(TAG, "Connect Audio Server...[Success] " + "listen on " + mAudioChannel.getLocalAddress().toString());
		}

		Intent intent = new Intent(mContext, CaptureService.class);
		intent.putExtra("ScreenDensity", mScreenDensity);
		intent.putExtra("ResultCode", mResultCode);
		intent.putExtra("ResultData", mResultData);
		intent.putExtra("Resolution", mResolution);
		intent.putExtra("AudioMode", mAudioMode);

		//mFrameRate = (mMirrorMode == MODE_FLUENCY_FIRST) ? 30 : 60;
		mFrameRate = 30; // fix to 30 for performance consideration
		intent.putExtra("Framerate", mFrameRate);
		intent.putExtra("Bitrate", mBitrate);
		intent.putExtra("Mode", mMirrorMode);

		if (Build.VERSION.SDK_INT >= 29){
			mContext.startForegroundService(intent);
		}else{
			mContext.startService(intent);
		}
		
		for (int i = 0; i < mCallbackList.size(); i++) {
			AirPlayClientCallback callback = mCallbackList.get(i);
			if (callback != null) {
			    if (!hasExeception) {
			        callback.onRequireMirrorSuccess();
			    } else {
			        callback.onRequireMirrorFailed();
			        hasExeception = false;
			    }
			}
		}
	}
	
	public synchronized void onRequireMirrorFailed() {
		for (int i = 0; i < mCallbackList.size(); i++) {
			AirPlayClientCallback callback = mCallbackList.get(i);
			if (callback != null) {
				callback.onRequireMirrorFailed();
			}
		}
	}

	public synchronized void onMirrorDisconnected() {

		for (int i = 0; i < mCallbackList.size(); i++) {
			AirPlayClientCallback callback = mCallbackList.get(i);
			if (callback != null) {
				callback.onMirrorDisconnected();
			}
		}
		isMirrorConnected = false;
		if (isMirrorStarted) {
			StopMirror();
		} else {
			for (int i = 0; i < mCallbackList.size(); i++) {
				AirPlayClientCallback callback = mCallbackList.get(i);
				if (callback != null) {
					callback.onStopMirrorCompleted();
				}
			}
		}
	}

	private void postMirrorStreaminfo() {
		if (!isMirrorStarted) return;
		
		DefaultHttpRequest localDefaultHttpRequest = new DefaultHttpRequest(
				HttpVersion.HTTP_1_1, HttpMethod.POST, "/stream");
		localDefaultHttpRequest.headers().set("Content-Type", "application/octet-stream");
		localDefaultHttpRequest.headers().set("X-Apple-Device-ID", "0xD022BE4B9779");
		localDefaultHttpRequest.headers().set("User-Agent", "AirPlay/150.33");
		localDefaultHttpRequest.headers().set("Content-Length", "383");
		localDefaultHttpRequest.headers().set("", "");
		localDefaultHttpRequest.setContent(ChannelBuffers.wrappedBuffer(STREAMINFO));
		Log.i(TAG, "Post stream info: " + localDefaultHttpRequest.getUri());
		mMirrorChannel.write(localDefaultHttpRequest);
		mMirrorChannel.getPipeline().remove("encoder");
	}

	public synchronized int SendMirrorStreamData(byte[] data, int flag) {
		if (!isMirrorConnected) return -1;
		try {
			if (flag == AirplayMirrorData.DATA_VIDEO_FRAME) {
				int frameLen = data.length;
				CommonHelper.int2ByteL(packetHeader, frameLen);
				packetHeader[4] = (byte) 0x0;
				CommonHelper.int2ByteH(data, frameLen - 4);
				
				byte[] data2 = new byte[packetHeader.length + data.length];
				System.arraycopy(packetHeader, 0, data2, 0, packetHeader.length);
				System.arraycopy(data, 0, data2, packetHeader.length, data.length);
				ChannelBuffer buffer = ChannelBuffers.wrappedBuffer(data2);
				mMirrorChannel.write(buffer);
			} else if (flag == AirplayMirrorData.DATA_VIDEO_CSD) {
				int spsLen = 0, ppsLen = 0, k = 4, code = data[k] & 0xff, dataLen = data.length - 4;
				Log.i(TAG, "sending codec info");
				while ((code != 0x00000001) && (dataLen > 0)) {
					int tmp = data[k] & 0xff;
					k++;
					code = (code << 8) | tmp;
					dataLen--;
					spsLen++;
				}
				spsLen -= 4;
				code = (code << 8) | data[k] & 0xff;
				k++;
				dataLen--;
				ppsLen++;
				while (code != 0x00000001 && (dataLen > 0)) {
					int tmp = data[k] & 0xff;
					k++;
					code = (code << 8) | tmp;
					dataLen--;
					ppsLen++;
				}
				Log.i(TAG, "PPS len " + ppsLen);

				byte[] codecInfo = new byte[data.length - 4 - 4 + 8 + 3];
				codecInfo[0] = (byte) 0x01;
				codecInfo[1] = (byte) 0x42;
				codecInfo[2] = (byte) 0x80;
				codecInfo[3] = (byte) 0x20;
				codecInfo[4] = (byte) 0xff;
				codecInfo[5] = (byte) 0xe1;
				codecInfo[6] = (byte) 0x00;
				codecInfo[7] = (byte) spsLen;
				for (int i = 0; i < spsLen; i++) {
					codecInfo[8 + i] = data[4 + i];
				}
				codecInfo[8 + spsLen] = (byte) 0x01;
				codecInfo[8 + spsLen + 2] = (byte) ppsLen;
				for (int i = 0; i < ppsLen; i++) {
					codecInfo[8 + spsLen + 3 + i] = data[4 + spsLen + 4 + i];
				}
				CommonHelper.int2ByteL(packetHeader, 8 + spsLen + 3 + ppsLen);
				packetHeader[4] = (byte) 0x01;
				LOG.d(TAG, "send packet header" + packetHeader.length);
				byte[] data2 = new byte[packetHeader.length + codecInfo.length];
				System.arraycopy(packetHeader, 0, data2, 0, packetHeader.length);
				System.arraycopy(codecInfo, 0, data2, packetHeader.length, codecInfo.length);
				
				ChannelBuffer buffer = ChannelBuffers.wrappedBuffer(data2);
				mMirrorChannel.write(buffer);
			} else if (flag == AirplayMirrorData.DATA_AUDIO_FRAME && hasAudioConnection) {
				if (mAudioChannel == null) {
					throw new RuntimeException("audio channel is null");
				}
				LOG.d(TAG, "sending audio data " + data.length);
				
				RtpDataPacket rtpPacket = new RtpDataPacket();
				rtpPacket.setData(data);
				rtpPacket.setSequenceNumber(mAudioRtpSeqNum);
				rtpPacket.setTimestamp(System.currentTimeMillis());
				rtpPacket.setPayloadType(PAYLOAD_TYPE);
				LOG.d(TAG, "SendMirrorStreamData: AudioRtpSeqNum : " + mAudioRtpSeqNum);
				mAudioChannel.write(rtpPacket, mRemoteAddress);

				mAudioRtpSeqNum++;
			} else if (flag == AirplayMirrorData.DATA_HEART_BEAT) {
				int frameLen = data.length;
				CommonHelper.int2ByteL(packetHeader, frameLen);
				packetHeader[4] = (byte) 0x2;
				CommonHelper.int2ByteH(data, frameLen - 4);

				byte[] data2 = new byte[packetHeader.length + data.length];
				System.arraycopy(packetHeader, 0, data2, 0, packetHeader.length);
				System.arraycopy(data, 0, data2, packetHeader.length, data.length);
				ChannelBuffer buffer = ChannelBuffers.wrappedBuffer(data2);
				mMirrorChannel.write(buffer);
			}
		} catch (Exception e) {
			throw new RuntimeException("mirror socket error", e);
		}

		return 0;
	}

	private class MirrorPipelineFactory implements ChannelPipelineFactory {
		public ChannelPipeline getPipeline() throws Exception {
			ChannelPipeline pipeline = Channels.pipeline();
			pipeline.addLast("monitor", new MirrorChannelMonitor());
			pipeline.addLast("decoder", new HttpResponseDecoder());
			pipeline.addLast("encoder", new HttpRequestEncoder());
			pipeline.addLast("response", new MirrorHttpResponseHandler());
			return pipeline;
		}
	}

	private class AudioPipelineFactory implements ChannelPipelineFactory {
		public ChannelPipeline getPipeline() throws Exception {
			ChannelPipeline pipeline = Channels.pipeline();

			pipeline.addLast("decoder", new RtpDataPacketDecoder());
			pipeline.addLast("encoder", RtpDataPacketEncoder.getInstance());
			return pipeline;
		}
	}
	
	public class AirplayDiscover implements ServiceListener {
		private static final String TAG = "AirplayDiscovery";
		// the service type (which events to listen for)
		private static final String SERVICE_TYPE = "_airplay._tcp.local.";

		// JmDNS library
		private JmDNS jmdns;
		// holder for the device IP address
		private InetAddress deviceAddress;
		// handler
		private Handler handler = new Handler();
		// map of services discovered (continuously updated in background)
		private volatile Map<String, ServiceInfo> mDiscoveryServiceMap = new HashMap<String, ServiceInfo>();

		public void setLocalAddress(InetAddress add) {
			deviceAddress = add;
		}

		public void startDiscovery() {
			try {
				// device address
				// local ip address
				// deviceAddress = getWifiInetAddress();
				if (deviceAddress == null) {
					Log.e(TAG, "Error: Unable to get local IP address");
					return;
				}

				// init jmdns
				jmdns = JmDNS.create(deviceAddress);
				jmdns.addServiceListener(SERVICE_TYPE, AirplayDiscover.this);
				Log.i(TAG, "Using local address " + deviceAddress.getHostAddress());
			} catch (Exception e) {
				Log.e(TAG, "Error: " + e.getMessage() == null ? "Unable to initialize discovery service" : e.getMessage());
			}
		}

		public synchronized void ClearServiceMap() {
			mDiscoveryServiceMap.clear();
		}

		@Override
		public synchronized void serviceAdded(final ServiceEvent event) {
			Log.i(TAG, "Found AirPlay service: " + event.getName());
			mDiscoveryServiceMap.put(event.getInfo().getKey(), event.getInfo());
			handler.post(new Runnable() {
				@Override
				public void run() {
					jmdns.requestServiceInfo(event.getType(), event.getName(),
							1000);
				}
			});
		}

		@Override
		public synchronized void serviceResolved(ServiceEvent event) {
			Log.i(TAG, "Resolved AirPlay service: " + event.getName() + " @ "
					+ event.getInfo().getURL());
			mDiscoveryServiceMap.put(event.getInfo().getKey(), event.getInfo());
			for (int i = 0; i < mCallbackList.size(); i++) {
				AirPlayClientCallback callback = mCallbackList.get(i);
				if (callback != null) {
					callback.onAirplayServiceResolved(mDiscoveryServiceMap);
				}
			}
		}

		@Override
		public void serviceRemoved(ServiceEvent event) {
			Log.i(TAG, "Removed AirPlay service: " + event.getName());
			mDiscoveryServiceMap.remove(event.getInfo().getKey());
		}
	}
}
