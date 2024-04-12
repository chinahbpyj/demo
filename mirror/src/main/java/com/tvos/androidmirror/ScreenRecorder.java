package com.tvos.androidmirror;

import android.annotation.SuppressLint;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;

import com.tvos.androidmirror.util.CommonHelper;
import com.tvos.androidmirror.util.LOG;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Created by feiwei on 15-11-12.
 */
@SuppressLint("NewApi")
public class ScreenRecorder {
	private static final String TAG = "ScreenRecorder";
	private static final String MIME_TYPE = "video/avc";
	private static final int IFRAME_INTERVAL = 10; // 0xFFFF Running on meizu mx cannot set too big     100
	private static final String VIDEO_PATH = "/sdcard/tvguo-mirror.dat";
	// drop encoded data and reset i frame while the sending queue exceed it
	private static final int MAX_PENDING_FRAME_BEFORE_DROP = 30;
	
	private int mWidth = 1280;
	private int mHeight = 720;
	private int mBitRate = 3000000;
	private int mFrameRate = 30;

	private MediaCodec.BufferInfo mVideoBufferInfo;
	private MediaCodec mVideoEncoder;
	private Surface mInputSurface;
	private Thread mDrainThread;
	private AirplayMirriorOutputQueue mAirplayMirriorOutputQueue;
	
	// dump local video
	private FileOutputStream mDumpFile;

	private volatile boolean mRunning = false;
	
	public ScreenRecorder() {
	}

	public void initScreenRecorder(int width, int height, int bitrate, int framerate, boolean isVBREnable) {
		/* set para */
		mWidth = width;
		mHeight = height;
		mBitRate = bitrate;
		mFrameRate = framerate;
		/* init mediacodec */
		mVideoBufferInfo = new MediaCodec.BufferInfo();
		
		MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, mWidth, mHeight);
		format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
		format.setInteger(MediaFormat.KEY_BIT_RATE, mBitRate);
		format.setInteger(MediaFormat.KEY_FRAME_RATE, mFrameRate);
		format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);
		LOG.d(TAG, "init ScreenRecorder w/ media format: " + format);

		// Create a MediaCodec encoder and configure it. Get a Surface we can use for recording into.
		try {
			mVideoEncoder = MediaCodec.createEncoderByType(MIME_TYPE);
			mVideoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
			mInputSurface = mVideoEncoder.createInputSurface();
			mVideoEncoder.start();
			Log.i(TAG, "encoder is started");
		} catch (Exception e) {
			Log.e(TAG, "MediaCodec configure failed : " + e + " ; release screen recorder");
			releaseVideoEncoder();
			AirplayClientInterface.getInstance().hasExeception = true;
			//throw new RuntimeException("MediaCodec config failed:  " + e.getMessage());
		}
	}

	public Surface getInputSurface() {
		return mInputSurface;
	}

	public synchronized void startScreenRecorder() {
	    Log.i(TAG, "StartScreenRecorder");
	    if( mAirplayMirriorOutputQueue == null ) {
			mAirplayMirriorOutputQueue = AirplayMirriorOutputQueue.getInstance();
			mAirplayMirriorOutputQueue.start();
		}

	    startDrainThread();

		// dump local video
		if(LOG.IS_DUMP_LOCAL_VIDEO) {
			try {
				mDumpFile = new FileOutputStream(new File(VIDEO_PATH));
			} catch (IOException ioe) {
				throw new RuntimeException("Create dump file failed", ioe);
			}
		}
	}

	public synchronized void stopScreenRecorder() {
		Log.i(TAG, "StopScreenRecorder");
		stopDrainThread();
		if(mAirplayMirriorOutputQueue != null) {
			Log.e(TAG, "stopScreenRecorder: Queue size " + mAirplayMirriorOutputQueue.size());
			mAirplayMirriorOutputQueue.stop();
        }

		// dump local video
		if (mDumpFile != null) {
			try {
				mDumpFile.close();
				mDumpFile = null;
			}catch (IOException e) {
				Log.e(TAG, "Close dump file get exception : " + e);
			}
		}
	}

	public synchronized void reConfigureScreenRecorder(int width, int height, int bitrate, int framerate) {
		// stop drain thread first
		stopDrainThread();
		initScreenRecorder(width,height,bitrate,framerate,false);
		startDrainThread();
	}

	private boolean drainVideoEncoder() {
		try {
			int n = 0;
			while (mRunning && !Thread.interrupted()) {
				if (mVideoEncoder == null) continue; // mVideoEncoder 出现过在偶然的情况下为null，以至下一行报空指针异常
				int bufferIndex = mVideoEncoder.dequeueOutputBuffer(mVideoBufferInfo, 100000);//100ms 等待时间 , -1代表一直等待，0代表不等待。此处单位为微秒
				if (bufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
					// nothing available yet
					LOG.d(TAG, " INFO_TRY_AGAIN_LATER ");
				}  else if (bufferIndex < 0) {
					// not sure what's going on, ignore it
					LOG.d(TAG, "bufferIndex < 0 ");
				} else if (bufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
					// dump local video
					LOG.d(TAG, " INFO_OUTPUT_FORMAT_CHANGED ");
				} else {
					ByteBuffer encodedData = mVideoEncoder.getOutputBuffer(bufferIndex);
					LOG.d(TAG, "VideoEncoder output TIME: " + CommonHelper.currentTime() + " n | " + (n++));
					if (encodedData == null) {
						throw new RuntimeException("couldn't fetch buffer at index " + bufferIndex);
					}
					if (mVideoBufferInfo.size != 0) {
						encodedData.position(mVideoBufferInfo.offset);
						encodedData.limit(mVideoBufferInfo.offset + mVideoBufferInfo.size);
						byte[] data = new byte[mVideoBufferInfo.size];
						encodedData.get(data);
						AirplayMirrorData mirrorData = new AirplayMirrorData();
						if ((mVideoBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
							mirrorData.setMirrorData(data, AirplayMirrorData.DATA_VIDEO_CSD);
						} else {
							mirrorData.setMirrorData(data, AirplayMirrorData.DATA_VIDEO_FRAME);
						}
						mAirplayMirriorOutputQueue.enqueue(mirrorData);
						if(mAirplayMirriorOutputQueue.size() > MAX_PENDING_FRAME_BEFORE_DROP) {
							Log.w(TAG, "mAirplayMirriorOutputQueue pending buffer > "
									+ MAX_PENDING_FRAME_BEFORE_DROP + "; clean queue, and request i frame");
							mAirplayMirriorOutputQueue.clear();
							resetIFrame();
						}

						// dump to local file
						if( mDumpFile != null ){
							try{
								mDumpFile.write(data, 0, mVideoBufferInfo.size);
							} catch (IOException e) {
								Log.e(TAG, "Dump video file failed : " + e);
							}
						}
					}
					mVideoEncoder.releaseOutputBuffer(bufferIndex, false);
					if ((mVideoBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
						break;
					}
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			releaseVideoEncoder();
		}
		
		return false;
	}
	
	private void resetIFrame() {
		LOG.d(TAG, "======== reset I-Frame ========");
		Bundle bundle = new Bundle();
		bundle.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
		mVideoEncoder.setParameters(bundle);
	}

	private void releaseVideoEncoder() {
		LOG.d(TAG, "======== releaseVideoEncoder ========");
		if (mVideoEncoder != null) {
			try {
				//mVideoEncoder.flush();
				mVideoEncoder.stop();
				mVideoEncoder.release();
				mVideoEncoder = null;
			} catch (IllegalStateException e) {
				LOG.e(TAG, e.getMessage());
				e.printStackTrace();
			} finally {
				mVideoEncoder = null;
			}
		}
		if (mInputSurface != null) {
			mInputSurface.release();
			mInputSurface = null;
		}
	}

	private void startDrainThread() {
		Log.d(TAG, "======== startDrainThread =======");
		if( mDrainThread == null) {
			mDrainThread = new Thread(new Runnable() {
				@Override
				public void run() {
					drainVideoEncoder();
				}
			}, "DrainThread");;
		}
		mRunning = true;
		mDrainThread.start();
	}

	private void stopDrainThread() {
		LOG.d(TAG, "======== stopDrainThread ========");
		mRunning = false;
		if(mDrainThread != null) {
			mDrainThread.interrupt();
			try {
				mDrainThread.join();
			} catch (InterruptedException e) {
				Log.e(TAG, "mDrainThread.join got exception : " + e);
			}
			mDrainThread = null;
		}
	}
}
