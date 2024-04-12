package com.tvos.androidmirror;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import androidx.core.app.NotificationCompat;

import com.tvos.androidmirror.util.LOG;

@SuppressLint("NewApi")
public class CaptureService extends Service {
    private static final String TAG = "CaptureService";
    
    private ScreenRecorder mScreenRecorder;
    private MediaProjection mMediaProjection;
    private MediaProjection.Callback mCallback;
    private VirtualDisplay mVirtualDisplay;
    private int mScreenDensity;
    private int mWidth;
    private int mHeight;
    private int mResolution;
    private int mBitrate;
    private int mFrameRate;
    private int mAudioMode;
    private boolean isInited = false;
    private AudioRecorder mAudioRecorder = null;
    private AirplayClientInterface mAirplayClientService = null;
    //private GlCompositor mCompositor= null;
    private int mMirrorMode;

    // default build in display
    private Display mDefaultDisplay = null;
    // screen rotation. when rotation changed, notify virtual display follow the change
    private int mDisplayRotation;
    // default display size according to the rotation
    private Point mDisplayPoint = new Point();

    private HandlerThread mWorker = null;
    private MyHandler mWorkerHandler = null;

    private static final int MSG_START = 0;
    private static final int MSG_STOP = 1;
    private static final int MSG_CONFIGURE_CHANGED = 2;
    private class MyHandler extends Handler {
        public MyHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            Log.i(TAG, "MyHandler handle message : " + msg);
            switch (msg.what){
                case MSG_START:
                    startMirror();
                    break;
                case MSG_CONFIGURE_CHANGED:
                    reConfigureScreenRecordandStart();
                    break;
                default:
            }
        }
    }

    private void reConfigureScreenRecordandStart(){
        Log.d(TAG,"reConfigureScreenRecordandStart()");
        // reconfigure screen record
        mScreenRecorder.reConfigureScreenRecorder(mWidth, mHeight,
                mBitrate, mFrameRate);

        if (mVirtualDisplay != null) {
            // reconfigure virtual display
            mVirtualDisplay.resize(mWidth, mHeight, mScreenDensity);

            mVirtualDisplay.setSurface(mScreenRecorder.getInputSurface());
        }

    }


    @Override
    public void onCreate() {
        Log.i(TAG, "Service onCreate");
        if (Build.VERSION.SDK_INT >=29) {//8.0后才支持
            startForeground(1, getNotification(this, "Demo APP", "正在使用屏幕镜像功能"));
        }
        mDefaultDisplay = ((WindowManager)getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
    }

    private Notification getNotification(Context context, String title, String text) {
        // boolean isOngoing = true;//是否持续(为不消失的常驻通知)
        String channelName = "TvGuoNotification";
        String channelId = "Guo_Screen_Mirror_001";
        String category = Notification.CATEGORY_SERVICE;
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        Intent nfIntent = new Intent(context, CaptureService.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, nfIntent, PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, channelId)
                .setContentIntent(pendingIntent) //设置PendingIntent
                .setSmallIcon(R.mipmap.ic_launcher) //设置状态栏内的小图标
                .setContentTitle(title) //设置标题
                .setContentText(text) //设置内容
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)//设置通知公开可见
                .setOngoing(true)//设置持续(不消失的常驻通知)
                .setCategory(category)//设置类别
                .setPriority(NotificationCompat.PRIORITY_MAX);//优先级为：重要通知
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {//安卓8.0以上系统要求通知设置Channel,否则会报错
            NotificationChannel notificationChannel = new NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH);
            notificationChannel.setLockscreenVisibility(NotificationCompat.VISIBILITY_PUBLIC);//锁屏显示通知
            notificationManager.createNotificationChannel(notificationChannel);
            builder.setChannelId(channelId);
        }
        return builder.build();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Log.d(TAG, "Configure changed to : " + newConfig);
        // get new screen rotate
        int rotate = mDefaultDisplay.getRotation();
        Point newPoint = new Point();
        mDefaultDisplay.getSize(newPoint);
        Log.d(TAG, "current config rotation: " + mDisplayRotation + "display size = [" + mDisplayPoint.x + "x" + mDisplayPoint.y + "]");
        if( rotate != mDisplayRotation || newPoint != mDisplayPoint) {
            // current foreground app rotate changed, notify re-construct virtual display
            mDisplayRotation = rotate;
            mDisplayPoint = newPoint;
            getEncodeParams();
            Log.d(TAG, "config changed rotation: " + mDisplayRotation + "display size = [" + mDisplayPoint.x + "x" + mDisplayPoint.y + "]");
            mWorkerHandler.sendEmptyMessage(MSG_CONFIGURE_CHANGED);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "Service onStartCommand intent :" + intent + " ; flags = " + flags
                + " ; startId = " + startId + " ; extras = " + intent.getExtras());
        int mResultCode = intent.getIntExtra("ResultCode", 0);
        if(mResultCode ==0){
            return Service.START_NOT_STICKY;
        }
        Intent mResultData = (Intent) intent.getParcelableExtra("ResultData");
        mScreenDensity = intent.getIntExtra("ScreenDensity", 0);
        mResolution = intent.getIntExtra("Resolution", 720);
        mBitrate = intent.getIntExtra("Bitrate", 0);
        mFrameRate = intent.getIntExtra("Framerate", 30);
        mAudioMode = intent.getIntExtra("AudioMode", 0);
        mMirrorMode = intent.getIntExtra("Mode",0);

        Log.d(TAG, "density : " + mScreenDensity + "mFrameRate : " + mFrameRate +
                "; mResolution: " + mResolution + "; mMirrorMode = " + mMirrorMode);

        Log.e(TAG, "mMediaProjection[" + mMediaProjection + "], mCallback[" + mCallback + "]");
        if (mCallback != null && mMediaProjection != null) {
            mMediaProjection.unregisterCallback(mCallback);
            mMediaProjection.stop();
            mMediaProjection = null;
        }
		mMediaProjection = ((MediaProjectionManager) getApplicationContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE))
				.getMediaProjection(mResultCode, mResultData);
        if (mMediaProjection == null) {
        	Intent failedIntent = new Intent("MediaProjectionFailed");
        	sendBroadcast(failedIntent);
        	stopSelf();
        	return Service.START_NOT_STICKY;
        }
        mAirplayClientService = AirplayClientInterface.getInstance();
        AirplayMirriorOutputQueue.getInstance();

        if( mWorker == null ) {
            mWorker = new HandlerThread("CaptureServiceWorker");
            mWorker.start();
            mWorkerHandler = new MyHandler(mWorker.getLooper());
            mWorkerHandler.sendEmptyMessageDelayed(MSG_START,300);
        }

        return Service.START_NOT_STICKY;
    }


    private void startMirror() {
        mScreenRecorder = new ScreenRecorder();
        mDefaultDisplay.getSize(mDisplayPoint);
        getEncodeParams();
        Log.d(TAG, "Current display config : [" + mDisplayPoint.x + "x" + mDisplayPoint.y+"]");

    	if (!isInited) {
        	mScreenRecorder.initScreenRecorder(mWidth, mHeight,
                    mBitrate, mFrameRate, false);
            if (mAudioMode != 0) {
            	mAudioRecorder = new AudioRecorder();
            	try {
                    mAudioRecorder.StartAudioRecorder(mMediaProjection);
                }catch (UnsupportedOperationException exception){
                    Intent failedIntent = new Intent("MediaProjectionFailed");
                    sendBroadcast(failedIntent);
                    stopSelf();
                    return;
                }
            }
            isInited = true;
        }
        mScreenRecorder.startScreenRecorder();
        mCallback = new MediaProjectionCallback();
        if (mMediaProjection != null) {
            mMediaProjection.registerCallback(mCallback, null);
            mVirtualDisplay = createVirtualDisplay();
            Log.i(TAG, "created display is " + mVirtualDisplay.toString());
        }
    }

    private VirtualDisplay createVirtualDisplay() {
    	VirtualDisplay display = null;
    	switch (mMirrorMode) {
    	    // unitify handle fluency and quality mirror, the encode params are different
            case AirplayClientInterface.MODE_FLUENCY_FIRST:
            case AirplayClientInterface.MODE_QUALITY_FIRST:
                display = initWithModeQualityFirst();
                break;
            default:
                Log.e(TAG, "unknown mirror mode " + mMirrorMode);
                break;
		}
        LOG.d(TAG, "==================== init ScreenRecorder done with mirror mode " + mMirrorMode);
        return display;
    }

    private VirtualDisplay initWithModeQualityFirst() {
        if (mMediaProjection == null) {
            return null;
        }
    	VirtualDisplay display =mMediaProjection.createVirtualDisplay("Recording Display",
                mWidth, mHeight, mScreenDensity,
                 DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, mScreenRecorder.getInputSurface(), null, null);
    	
    	return display;
    }
    
    @Override
    public IBinder onBind(Intent arg0) {
        Log.i(TAG, "Service onBind");
        return new CaptureServiceBinder();
    }

    @Override
    public void onDestroy() {
    	Log.i(TAG, "Service onDestroy");

        if (mMediaProjection != null) {
            if (mCallback != null) {
                mMediaProjection.unregisterCallback(mCallback);
            }
            mMediaProjection.stop();
            mMediaProjection = null;
        }

        if (mScreenRecorder != null) {
        	mScreenRecorder.stopScreenRecorder();
        }
        if (mAudioRecorder != null) {
        	mAudioRecorder.StopAudioRecorder();
        }
        if(mAirplayClientService != null) {
            mAirplayClientService.disconnectMirror();
        }
        if( mWorker != null ) {
            mWorker.quitSafely();
        }

        if (Build.VERSION.SDK_INT >= 29){
            stopForeground(true);// 停止前台服务--参数：表示是否移除之前的通知
        }
    }

    private void getEncodeParams(){
        boolean needswap = mDisplayPoint.x > mDisplayPoint.y ? false : true;
        // round down to 16 alignment
        if( mResolution == 720 ) {
            mWidth = 1280;
            mHeight = 720;
        } else {
            mWidth = 1920;
            mHeight = 1080;
        }
        if( needswap ) {
            int tmp = mWidth;
            mWidth = mHeight;
            mHeight = tmp;
        }
    }

    private class MediaProjectionCallback extends MediaProjection.Callback {

		@Override
		public void onStop() {
			Log.i(TAG, "MediaProjection is stopped");
			AirplayClientInterface.getInstance().StopMirror();
		}
    }

    public class CaptureServiceBinder extends Binder {
        public CaptureService getService(){
            return CaptureService.this;
        }
    }

 }
