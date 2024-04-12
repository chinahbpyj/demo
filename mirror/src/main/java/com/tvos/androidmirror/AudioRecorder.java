package com.tvos.androidmirror;

import android.annotation.SuppressLint;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.HandlerThread;
import android.os.Process;

import com.tvos.androidmirror.util.LOG;

import java.nio.ByteBuffer;
import java.util.LinkedList;

@SuppressLint("NewApi")
public class AudioRecorder {
    private static final int SAMPLE_RATE = 48000;    // 44.1[KHz] is only setting guaranteed to be available on all devices.
    private AudioRecord mAudioRecorder = null;
    private MediaCodec mAudioEncoder = null;
    private final MediaCodec.BufferInfo mAudioBufferInfo;
    private final AirplayMirrorAudioOutputQueue mAudioOutputQueue;

    private LinkedList<AudioData> mAudioRawDataList;

    private ByteBuffer[] mInputBuffers = null;

    private volatile boolean isCapturing = false;
    private volatile boolean isStoping = false;

    public static final int SAMPLES_PER_FRAME = 1024;    // AAC, bytes/frame/channel

    public AudioRecorder() {
        final MediaFormat audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2);
        audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectELD);
        audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, 48 * 1024);
        audioFormat.setInteger(MediaFormat.KEY_CHANNEL_MASK, AudioFormat.CHANNEL_IN_STEREO);
        audioFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 2);

        try {
            mAudioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            mAudioEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mAudioEncoder.start();
        } catch (Exception e) {
            e.printStackTrace();
            AirplayClientInterface.getInstance().hasExeception = true;
        }
        mAudioBufferInfo = new MediaCodec.BufferInfo();

        HandlerThread mAudioEncoderThread = new HandlerThread("AudioRecordLoop");
        mAudioEncoderThread.setPriority(Thread.MAX_PRIORITY);
        mAudioEncoderThread.start();

        mAudioOutputQueue = AirplayMirrorAudioOutputQueue.getInstance();
        mAudioOutputQueue.start();
    }

    private AudioPlaybackCaptureConfiguration createAudioPlaybackCaptureConfig(MediaProjection mediaProjection) {
        AudioPlaybackCaptureConfiguration.Builder confBuilder = new AudioPlaybackCaptureConfiguration.Builder(mediaProjection);
        confBuilder.addMatchingUsage(AudioAttributes.USAGE_MEDIA);
        confBuilder.addMatchingUsage(AudioAttributes.USAGE_GAME);
        confBuilder.addMatchingUsage(AudioAttributes.USAGE_UNKNOWN);
        return confBuilder.build();
    }

    private AudioFormat createAudioFormat() {
        AudioFormat.Builder builder = new AudioFormat.Builder();
        builder.setEncoding(AudioFormat.ENCODING_PCM_16BIT);
        builder.setSampleRate(SAMPLE_RATE);
        builder.setChannelMask(AudioFormat.CHANNEL_IN_STEREO);
        return builder.build();
    }

    private AudioRecord createAudioRecord(MediaProjection mediaProjection) {
        int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord.Builder builder = new AudioRecord.Builder();
        builder.setAudioFormat(createAudioFormat());
        builder.setBufferSizeInBytes(minBufferSize * 3);
        builder.setAudioPlaybackCaptureConfig(createAudioPlaybackCaptureConfig(mediaProjection));
        return builder.build();
    }

    public void StartAudioRecorder(MediaProjection mediaProjection) {
        if (Build.VERSION.SDK_INT >= 29 && mediaProjection != null) {
            mAudioRecorder = createAudioRecord(mediaProjection);
        } else {

            final int min_buffer_size = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT);
            int buffer_size = min_buffer_size * 3;
            try {
                mAudioRecorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT, buffer_size);
            } catch (Exception e) {
                e.printStackTrace();
                AirplayClientInterface.getInstance().hasExeception = true;
                //throw new RuntimeException("Create AudioRecord failed:  " + e.getMessage());
            }
        }
        mAudioRawDataList = new LinkedList<AudioData>();
        isCapturing = true;
        isStoping = false;
        Thread feedEncodeThread = new Thread("Feed Audio Encoder") {
            @Override
            public void run() {
                try {
                    while (isCapturing && !isStoping) {
                        synchronized (mAudioRawDataList) {
                            if (mAudioRawDataList.isEmpty()) {
                                mAudioRawDataList.wait();
                            }
                            AudioData data = mAudioRawDataList.removeFirst();
                            int index = mAudioEncoder.dequeueInputBuffer(1000000);
                            if (index < 0) {
                                mAudioRawDataList.addFirst(data);
                                continue;
                            }
                            ByteBuffer inputBuffer = mInputBuffers[index];
                            inputBuffer.clear();
                            inputBuffer.put(data.mBuffer);
                            mAudioEncoder.queueInputBuffer(index, 0, data.mLength, data.mPts, 0);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    mAudioRawDataList.clear();
                }
            }
        };


        Thread drainEncodeThread = new Thread("Drain Audio Encoder") {
            @Override
            public void run() {
                try {
                    while (isCapturing && !isStoping) {
                        int bufferIndex = mAudioEncoder.dequeueOutputBuffer(mAudioBufferInfo, 1000000);
                        if (bufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                            //break;
                        } else if (bufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {

                        } else if (bufferIndex < 0) {
                            // not sure what's going on, ignore it
                        } else {
                            ByteBuffer encodedData = mAudioEncoder.getOutputBuffer(bufferIndex);
                            if (encodedData == null) {
                                throw new RuntimeException("couldn't fetch buffer at index " + bufferIndex);
                            }
                            if (mAudioBufferInfo.size != 0) {
                                encodedData.position(mAudioBufferInfo.offset);
                                encodedData.limit(mAudioBufferInfo.offset + mAudioBufferInfo.size);

                                byte[] data = new byte[mAudioBufferInfo.size];
                                encodedData.get(data);
                                AirplayMirrorData mirrorData = new AirplayMirrorData();
                                mirrorData.setMirrorData(data, AirplayMirrorData.DATA_AUDIO_FRAME);
                                mAudioOutputQueue.enqueue(mirrorData);

                                if (LOG.IS_DUMP_LOCAL_AUDIO_PCM) {
                                    AirplayUtils.writeToFile(LOG.PCM_DUMP_FILE, data);
                                }
                            }
                            mAudioEncoder.releaseOutputBuffer(bufferIndex, false);
                            if ((mAudioBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                break;
                            }
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {

                }
            }
        };

        Thread thread = new Thread() {
            @Override
            public void run() {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
                int size = SAMPLES_PER_FRAME * 4;
                mAudioRecorder.startRecording();
                mInputBuffers = mAudioEncoder.getInputBuffers();
                ByteBuffer buf = null;
                int readBytes;
                while (isCapturing && !isStoping) {
                    buf = ByteBuffer.allocateDirect(size);
                    buf.clear();
                    readBytes = mAudioRecorder.read(buf, size);
                    if (readBytes > 0) {
                        audioEncoder(buf, readBytes, getPTSUs());
                    }
                }
                isCapturing = false;
                mAudioRecorder.stop();
                mAudioRecorder.release();
                mAudioRecorder = null;
            }
        };
        thread.start();
        feedEncodeThread.start();
        drainEncodeThread.start();
    }

    public synchronized void StopAudioRecorder() {
        isStoping = true;
        if (mAudioEncoder != null) {
            mAudioEncoder.stop();
            mAudioEncoder.release();
            mAudioEncoder = null;
        }
    }

    private void audioEncoder(final ByteBuffer buffer, final int length, final long presentationTimeUs) {
        AudioData data = new AudioData(buffer, length, presentationTimeUs);
        synchronized (mAudioRawDataList) {
            mAudioRawDataList.addLast(data);
            mAudioRawDataList.notifyAll();
        }
    }

    /**
     * previous presentationTimeUs for writing
     */
    private long prevOutputPTSUs = 0;

    /**
     * get next encoding presentationTimeUs
     *
     * @return
     */
    protected long getPTSUs() {
        long result = System.nanoTime() / 1000L;
        // presentationTimeUs should be monotonic
        // otherwise muxer fail to write
        if (result < prevOutputPTSUs)
            result = (prevOutputPTSUs - result) + result;
        return result;
    }

    private class AudioData {
        public ByteBuffer mBuffer;
        public long mPts;
        public int mLength;

        AudioData(ByteBuffer buf, int length, long pts) {
            mBuffer = buf;
            mLength = length;
            mPts = pts;
        }
    }
}
