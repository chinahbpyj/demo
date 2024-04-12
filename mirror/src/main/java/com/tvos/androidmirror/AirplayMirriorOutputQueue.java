package com.tvos.androidmirror;

import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import com.tvos.androidmirror.util.CommonHelper;
import com.tvos.androidmirror.util.LOG;

import java.util.ArrayList;

/**
 * Created by feiwei on 15-11-30.
 */
public class AirplayMirriorOutputQueue {
	private String TAG = "AirplayMirriorOutputQueue";
	private ArrayList<AirplayMirrorData> frameQueue = new ArrayList<AirplayMirrorData>();
	private Thread queueThread = null;
	private AirplayClientInterface mAirplayClientService = null;
	private volatile boolean isClosing = false;

	private static AirplayMirriorOutputQueue mAirplayMirrorOutputQueue = null;
	public static AirplayMirriorOutputQueue getInstance() {
		if (mAirplayMirrorOutputQueue == null) {
			mAirplayMirrorOutputQueue = new AirplayMirriorOutputQueue();
		}
		return mAirplayMirrorOutputQueue;
	}
	private AirplayMirriorOutputQueue() { }

	private HandlerThread mHeartBeatThread = null;
	private Handler mHeartBeatHandler;
	private Runnable mHeartBeatRunnable = new Runnable() {
		@Override
		public void run() {
			sendHeartBeat();
		}
	};
	
	public int start() {
        if (mHeartBeatThread == null) {
            mHeartBeatThread = new HandlerThread("HeartBeatThread");
            mHeartBeatThread.start();
            mHeartBeatHandler = new Handler(mHeartBeatThread.getLooper());
        }

		mAirplayClientService = AirplayClientInterface.getInstance();
		frameQueue.clear();
		if (queueThread == null) {
			Log.i(TAG, "Create new queue thread...");
			isClosing = false;
			queueThread = new Thread(new DrainRunnable());
			queueThread.setDaemon(true);
			queueThread.setName("Mirror Output Enqueue Thread");
			queueThread.setPriority(Thread.MAX_PRIORITY);
			queueThread.start();
		} else {
			LOG.d(TAG, "Enqueue thread already exists...isAlive=" + queueThread.isAlive());
		}

		return 0;
	}

	public int size() {
		synchronized (frameQueue) {
			return frameQueue.size();
		}
	}
	
	public void clear() {
		synchronized (frameQueue) {
			// clear frameQueue except the last csd data
			AirplayMirrorData csd = null;
			while( frameQueue.size() > 0 ){
				AirplayMirrorData tmp = frameQueue.remove(0);
				if( tmp.getFlag() == AirplayMirrorData.DATA_VIDEO_CSD ) {
					csd = tmp;
				}
			}
			if(csd != null) {
				frameQueue.add(csd);
			}
		}
	}
	
	public void stop() {
		Log.i(TAG, "Stop mirror " + mAirplayClientService.getTargetIpAddress());
		isClosing = true;
		if (queueThread != null) {
			queueThread.interrupt();
			queueThread = null;
		}
		if(mHeartBeatThread != null) {
			mHeartBeatThread.quit();
			mHeartBeatThread = null;
			mHeartBeatHandler = null;
		}
	}
	
	public boolean enqueue(AirplayMirrorData mirrorData) {
		synchronized (frameQueue) {
			frameQueue.add(mirrorData);
			if (!isClosing) {
				frameQueue.notifyAll();
			}
		}
		return true;
	}

	private class DrainRunnable implements Runnable {

		@Override
		public void run() {
			try {
				Log.i(TAG, "Mirror encode thread started....");
				AirplayMirrorData mirrorData = null;
				int n = 0;
				while (!isClosing) {
					synchronized (frameQueue) {
						if (frameQueue.isEmpty()) {
							mHeartBeatHandler.postDelayed(mHeartBeatRunnable, 2000);
							frameQueue.wait();
						}

						mirrorData = frameQueue.remove(0);
						LOG.d(TAG, "frameQueue size = " + frameQueue.size()
								+ " ;output TIME: " + CommonHelper.currentTime()
								+ " n | " + (n++) + " length " + mirrorData.getData().length);
						mAirplayClientService.SendMirrorStreamData(mirrorData.getData(), mirrorData.getFlag());
					}
				}
			} catch (Exception e) {
				Log.e(TAG, "Mirror output thread exception:");
				e.printStackTrace();
			} finally {
				frameQueue.clear();
				Log.i(TAG, "Mirror output thread stopped");
			}
		}
	}
	
	private void sendHeartBeat() {
		Log.i(TAG, "sendHeartBeat");
		mHeartBeatHandler.removeCallbacks(mHeartBeatRunnable);
		byte[] data = new byte[4];// fake data
		AirplayMirrorData mirrorData = new AirplayMirrorData();
		mirrorData.setMirrorData(data, AirplayMirrorData.DATA_HEART_BEAT);
		mirrorData.setMirrorData(mirrorData.getData(), mirrorData.getFlag());
		enqueue(mirrorData);
	}

}
