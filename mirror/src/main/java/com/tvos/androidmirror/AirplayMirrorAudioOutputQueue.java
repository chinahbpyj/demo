package com.tvos.androidmirror;

import android.os.Process;
import android.util.Log;

import com.tvos.androidmirror.util.LOG;

import java.util.LinkedList;

/**
 * Created by feiwei on 15-12-30.
 */
public class AirplayMirrorAudioOutputQueue {
    private String TAG = "AirplayMirrorAudioOutputQueue";
    private final LinkedList<AirplayMirrorData> frameQueue = new LinkedList<AirplayMirrorData>();
    private Thread queueThread = null;
    private AirplayClientInterface mAirplayClientService = null;
    private volatile boolean isClosing = false;

    public static AirplayMirrorAudioOutputQueue mAudioOutputQueue = null;


    public static AirplayMirrorAudioOutputQueue getInstance() {
        if (mAudioOutputQueue == null) {
        	mAudioOutputQueue = new AirplayMirrorAudioOutputQueue();
        }

        return mAudioOutputQueue;
    }

    public LinkedList<AirplayMirrorData> getFrameQueue() {
        return this.frameQueue;
    }

    public int start() {
        mAirplayClientService = AirplayClientInterface.getInstance();
        frameQueue.clear();
        if (queueThread == null) {
            Log.i(TAG, "Create new queue thread...");
            isClosing = false;
            queueThread = new Thread(new EnQueuer());
            queueThread.setDaemon(true);
            queueThread.setName("Audio Output Enqueue Thread");
            queueThread.setPriority(Thread.MAX_PRIORITY);
            queueThread.start();
        } else {
            Log.i(TAG, "Enqueue thread already exists...isAlive=" + queueThread.isAlive());
        }
        return 0;
    }

    public void stop() {
        Log.i(TAG, "Stop mirror " + mAirplayClientService.getTargetIpAddress());
        isClosing = true;
        if (queueThread != null) {
            queueThread.interrupt();
            queueThread = null;
        }
        mAirplayClientService.StopMirror();
    }

    public boolean enqueue(final AirplayMirrorData data) {
        synchronized (frameQueue) {
            frameQueue.addLast(data);
            if (!isClosing) {
                frameQueue.notifyAll();
            }
        }

        return true;
    }

    private class EnQueuer implements Runnable{

        @Override
        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            Log.i(TAG, "Set mirror output thread priority [" + Process.getThreadPriority(Process.myTid()) + "]");

            try {
                AirplayMirrorData data;
                while (!isClosing) {
                    synchronized (frameQueue) {
                        while (!frameQueue.isEmpty()) {
                            data = frameQueue.removeFirst();
                            mAirplayClientService.SendMirrorStreamData(data.getData(), data.getFlag());
                            LOG.d(TAG, "send AAC-ELD frame data size: " + data.getData().length
                                    + "; Output Queue size: " + frameQueue.size());
                        }
                        frameQueue.wait();
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Audio output thread exception:");
                e.printStackTrace();
            } finally {
                Log.i(TAG, "Audio output thread stopped....");
            }
        }
    }
}
