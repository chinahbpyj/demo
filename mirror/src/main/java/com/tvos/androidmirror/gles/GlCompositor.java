package com.tvos.androidmirror.gles;

import android.annotation.SuppressLint;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Process;
import android.util.Log;
import android.view.Surface;

import com.tvos.androidmirror.util.CommonHelper;
import com.tvos.androidmirror.util.LOG;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by lujun on 16-7-6.
 * Handles composition of SurfaceTexture into a single Surface
 */
public class GlCompositor implements SurfaceTexture.OnFrameAvailableListener {
    private static final String TAG = "GlCompositor";
    private static final int FRAME_RATE_LOW_LEVEL = 5;
	private static final int FRANE_RATE_HIGH_LEVEL = 30;
	private static final int MIN_INTERVAL = 1000 / FRANE_RATE_HIGH_LEVEL;
	private static final int MAX_INTERVAL = 1000 / FRAME_RATE_LOW_LEVEL;
	
	private static Object mSyncObject = new Object();
    private boolean isFrameAvailable = false; 
	private boolean isRunning = true;

    /**
     * MediaCodec's surface
     */
    private Surface mSurface;
    private int mWidth;
    private int mHeight;
    private GlWindow mTopWindow;
    private Thread mCompositionThread;
    private Semaphore mStartCompletionSemaphore;
    private InputSurface mEglHelper;
    private int mGlProgramId = 0;
    private int mGluMVPMatrixHandle;
    private int mGluSTMatrixHandle;
    private int mGlaPositionHandle;
    private int mGlaTextureHandle;
    private float[] mMVPMatrix = new float[16];
    private boolean isStarting = true;

    private static final String VERTEX_SHADER =
            "uniform mat4 uMVPMatrix;\n" +
                    "uniform mat4 uSTMatrix;\n" +
                    "attribute vec4 aPosition;\n" +
                    "attribute vec4 aTextureCoord;\n" +
                    "varying vec2 vTextureCoord;\n" +
                    "void main() {\n" +
                    "  gl_Position = uMVPMatrix * aPosition;\n" +
                    "  vTextureCoord = (uSTMatrix * aTextureCoord).xy;\n" +
                    "}\n";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
                    "precision mediump float;\n" +
                    "varying vec2 vTextureCoord;\n" +
                    "uniform samplerExternalOES sTexture;\n" +
                    "void main() {\n" +
                    "  gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
                    "}\n";

    public void startComposition(Surface surface, int w, int h) throws Exception {
    	isStarting = true;
        mSurface = surface;
        mWidth = w;
        mHeight = h;
        mCompositionThread = new Thread(new CompositionRunnable(), "Composition Thread");
        mStartCompletionSemaphore = new Semaphore(0);
        mCompositionThread.start();
        waitForStartCompletion();
    }

    public void stopComposition() {
    	isRunning = false;	
    	mCompositionThread.interrupt();
    	
        mCompositionThread = null;
        mSurface = null;
        mStartCompletionSemaphore = null;
    }

    public Surface getWindowSurface() {
        if(mTopWindow == null) return null;
        return mTopWindow.getSurface();
    }

    int n = 1;
    int i = 0;
    @Override
    public void onFrameAvailable(SurfaceTexture surface) {
    	synchronized (mSyncObject) {
    		LOG.d(TAG, Thread.currentThread() + " | onFrameAvailable TIME: " + CommonHelper.currentTime() + " n | " + (n++));
            GlWindow w = mTopWindow;
            if (w != null) {
                w.markTextureUpdated();
                requestUpdate();
            } else {
                Log.w(TAG, "top window gone");
            }
            i++;
		}
    }

    private void requestUpdate() {
        Thread compositionThread = mCompositionThread;
        if (compositionThread == null || !compositionThread.isAlive()) {
            return;
        }
        isFrameAvailable = true;
        mSyncObject.notifyAll();
    }

    private int loadShader(int shaderType, String source) throws GlException {
        int shader = GLES20.glCreateShader(shaderType);
        checkGlError("glCreateShader type=" + shaderType);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            Log.e(TAG, "Could not compile shader " + shaderType + ":");
            Log.e(TAG, " " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            shader = 0;
        }
        return shader;
    }

    private int createProgram(String vertexSource, String fragmentSource) throws GlException {
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        if (vertexShader == 0) {
            return 0;
        }
        int pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        if (pixelShader == 0) {
            return 0;
        }

        int program = GLES20.glCreateProgram();
        checkGlError("glCreateProgram");
        if (program == 0) {
            Log.e(TAG, "Could not create program");
        }
        GLES20.glAttachShader(program, vertexShader);
        checkGlError("glAttachShader");
        GLES20.glAttachShader(program, pixelShader);
        checkGlError("glAttachShader");
        GLES20.glLinkProgram(program);
        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "Could not link program: ");
            Log.e(TAG, GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteProgram(program);
            program = 0;
        }
        return program;
    }

    private void initGl() throws GlException {
        mEglHelper = new InputSurface(mSurface);
        mEglHelper.makeCurrent();
        mGlProgramId = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        mGlaPositionHandle = GLES20.glGetAttribLocation(mGlProgramId, "aPosition");
        checkGlError("glGetAttribLocation aPosition");
        if (mGlaPositionHandle == -1) {
            throw new RuntimeException("Could not get attrib location for aPosition");
        }
        mGlaTextureHandle = GLES20.glGetAttribLocation(mGlProgramId, "aTextureCoord");
        checkGlError("glGetAttribLocation aTextureCoord");
        if (mGlaTextureHandle == -1) {
            throw new RuntimeException("Could not get attrib location for aTextureCoord");
        }
        mGluMVPMatrixHandle = GLES20.glGetUniformLocation(mGlProgramId, "uMVPMatrix");
        checkGlError("glGetUniformLocation uMVPMatrix");
        if (mGluMVPMatrixHandle == -1) {
            throw new RuntimeException("Could not get attrib location for uMVPMatrix");
        }
        mGluSTMatrixHandle = GLES20.glGetUniformLocation(mGlProgramId, "uSTMatrix");
        checkGlError("glGetUniformLocation uSTMatrix");
        if (mGluSTMatrixHandle == -1) {
            throw new RuntimeException("Could not get attrib location for uSTMatrix");
        }
        Matrix.setIdentityM(mMVPMatrix, 0);
        Log.i(TAG, "initGl w:" + mWidth + " h:" + mHeight);
        GLES20.glViewport(0, 0, mWidth, mHeight);
        float[] vMatrix = new float[16];
        float[] projMatrix = new float[16];
        // max window is from (0,0) to (mWidth - 1, mHeight - 1)
        float wMid = mWidth / 2f;
        float hMid = mHeight / 2f;
        // look from positive z to hide windows in lower z
        Matrix.setLookAtM(vMatrix, 0, wMid, hMid, 5f, wMid, hMid, 0f, 0f, 1.0f, 0.0f);
        Matrix.orthoM(projMatrix, 0, -wMid, wMid, -hMid, hMid, 1, 10);
        Matrix.multiplyMM(mMVPMatrix, 0, projMatrix, 0, vMatrix, 0);
        createWindows();
    }

    private void createWindows() throws GlException {
        mTopWindow = new GlWindow(this, 0, 0, mWidth, mHeight);
        mTopWindow.init();
    }

    private void cleanupGl() {
        if (mTopWindow != null) {
            mTopWindow.cleanup();
        }
        if (mEglHelper != null) {
            mEglHelper.release();
        }
    }

    private void doGlRendering() throws GlException {
		LOG.d(TAG, "doGlRendering");
		// make frame buffers to GlWindow(Surface(SurfaceTexture) from VirtualDisplay
        mTopWindow.updateTexImageIfNecessary();
        GLES20.glClearColor(0.0f, 1.0f, 0.0f, 1.0f);
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT | GLES20.GL_COLOR_BUFFER_BIT);

        GLES20.glUseProgram(mGlProgramId);
        GLES20.glUniformMatrix4fv(mGluMVPMatrixHandle, 1, false, mMVPMatrix, 0);
    }
    
    int m = 1;
    private long pre = 0, next = 0, delta = 0;
    private void swapBuffers() {
    	LOG.d(TAG, Thread.currentThread() + " | swapBuffers TIME: " + CommonHelper.currentTime() + " m | " + (m++));
    	  // make frame buffers to EGLDisplay from GlWindow
        mTopWindow.onDraw(mGluSTMatrixHandle, mGlaPositionHandle, mGlaTextureHandle);
        
        final IntBuffer pixels = IntBuffer.allocate(1);
		GLES20.glReadPixels(mWidth / 2, mHeight / 2, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
//		LOG.d(TAG, "glReadPixels returned 0x" + Integer.toHexString(pixels.get(0)));
		
		// make frame buffers to EGLSurface(Surface that created by VideoEncoder)
        mEglHelper.swapBuffers();
        try {
			checkGlError("window draw");
		} catch (GlException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
        next = System.currentTimeMillis();
		delta = next - pre;
		LOG.d(TAG, "swapBuffers() done. Actual FPS is " + (1000f / delta) + ", time delta: " + delta);
		pre = next;
    }
    
    private void waitForStartCompletion() throws Exception {
        if (!mStartCompletionSemaphore.tryAcquire(3000, TimeUnit.MILLISECONDS)) {
            Log.e(TAG, "start timeout");
        }
        mStartCompletionSemaphore = null;
    }

    private class CompositionRunnable implements Runnable {

		@Override
		public void run() {
			// TODO Auto-generated method stub
			Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            Log.i(TAG, "Set composite thread priority [" + Process.getThreadPriority(Process.myTid()) + "]");
			try {
				initGl();
				mStartCompletionSemaphore.release();
				
				synchronized (mSyncObject) {
					LOG.d(TAG, "GlCompositor synchronized at " + CommonHelper.currentTime());
					mSyncObject.wait();
					LOG.d(TAG, "GlCompositor synchronized done at " + CommonHelper.currentTime());
				}
				long last = 0, now = 0, interval;
				while(isRunning) {
					synchronized (mSyncObject) {
						LOG.d(TAG, "isFrameAvailable: " + isFrameAvailable);
						if (isFrameAvailable) {
							doGlRendering();
							isFrameAvailable = false;
							now = System.currentTimeMillis();
							interval = now - last;
							LOG.d(TAG,  "new frame comes after "+ interval + "ms from previous draw");
							if(interval < MIN_INTERVAL ) {
								// the frame comes to fast, delay the draw
								// if new frame comes, the wait will be interrupted and we'll draw new frame
								LOG.d(TAG, "too fast, need to wait " + (MIN_INTERVAL - interval) + "ms");
								mSyncObject.wait(MIN_INTERVAL - interval);
							} else {
								// draw the frame
								last = now;
								LOG.d(TAG, "draw new frame immediately");
								swapBuffers();
								mSyncObject.wait(MAX_INTERVAL);
							}
							if (isStarting) {
								/* to output quickly in MediaCodec pipeline */
								int n = 2;
								while (n > 0) {
									LOG.d(TAG, "Starting swapBuffers in while loop");
									swapBuffers();
									n--;
								}
								isStarting = false;
							}
						} else {
							last = System.currentTimeMillis();
							LOG.d(TAG, "draw delay frame or old frame");
							swapBuffers();
							mSyncObject.wait(MAX_INTERVAL);
						}
					}
				}
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
				Log.e(TAG, "got gl exception " + e.getMessage());
			} finally {
				cleanupGl();
			}
		}
    }

    private class GlWindow {
        private static final int FLOAT_SIZE_BYTES = 4;
        private static final int TRIANGLE_VERTICES_DATA_STRIDE_BYTES = 5 * FLOAT_SIZE_BYTES;
        private static final int TRIANGLE_VERTICES_DATA_POS_OFFSET = 0;
        private static final int TRIANGLE_VERTICES_DATA_UV_OFFSET = 3;
        private int mBlX;
        private int mBlY;
        private int mWidth;
        private int mHeight;
        private int mTextureId = 0; // 0 is invalid
        private volatile SurfaceTexture mSurfaceTexture;
        /**
         * MediaProjection(VirtualDisplay)'s surface
         */
        private volatile Surface mSurface;
        private FloatBuffer mVerticesData;
        private float[] mSTMatrix = new float[16];
        private AtomicInteger mNumTextureUpdated = new AtomicInteger(0);
        private GlCompositor mCompositor;

        /**
         * @param blX X coordinate of bottom-left point of window
         * @param blY Y coordinate of bottom-left point of window
         * @param w window width
         * @param h window height
         */
        public GlWindow(GlCompositor compositor, int blX, int blY, int w, int h) {
            mCompositor = compositor;
            mBlX = blX;
            mBlY = blY;
            mWidth = w;
            mHeight = h;
            int trX = blX + w;
            int trY = blY + h;
            float[] vertices = new float[] {
                    // x, y, z, u, v
                    mBlX, mBlY, 0, 0, 0,
                    trX, mBlY, 0, 1, 0,
                    mBlX, trY, 0, 0, 1,
                    trX, trY, 0, 1, 1
            };
            Log.i(TAG, "create window " + this + " blX:" + mBlX + " blY:" + mBlY + " trX:" + trX + " trY:" + trY);
            mVerticesData = ByteBuffer.allocateDirect( vertices.length * FLOAT_SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer();
            mVerticesData.put(vertices).position(0);
        }

        /**
         * initialize the window for composition. counter-part is cleanup()
         * @throws GlException
         */
        @SuppressLint("NewApi")
		public void init() throws GlException {
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);

            mTextureId = textures[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextureId);
            checkGlError("glBindTexture mTextureID");

            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S,
                    GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T,
                    GLES20.GL_CLAMP_TO_EDGE);
            checkGlError("glTexParameter");
            mSurfaceTexture = new SurfaceTexture(mTextureId);
            mSurfaceTexture.setDefaultBufferSize(mWidth, mHeight);
            mSurface = new Surface(mSurfaceTexture);
            mSurfaceTexture.setOnFrameAvailableListener(mCompositor);
        }

        public void cleanup() {
            mNumTextureUpdated.set(0);
            if (mTextureId != 0) {
                int[] textures = new int[] {
                        mTextureId
                };
                GLES20.glDeleteTextures(1, textures, 0);
            }
            GLES20.glFinish();
            if (mSurface != null) {
                mSurface.release();
                mSurface = null;
            }
            if (mSurfaceTexture != null) {
                mSurfaceTexture.release();
                mSurfaceTexture = null;
            }
        }

        /**
         * make texture as updated so that it can be updated in the next rendering.
         */
        public void markTextureUpdated() {
            mNumTextureUpdated.incrementAndGet();
        }

        /**
         * update texture for rendering if it is updated.
         */
        public void updateTexImageIfNecessary() {
            int numTextureUpdated = mNumTextureUpdated.getAndDecrement();
            if (numTextureUpdated > 0) {
                mSurfaceTexture.updateTexImage();
                mSurfaceTexture.getTransformMatrix(mSTMatrix);
            }
            if (numTextureUpdated < 0) {
                //fail("should not happen");
                Log.e(TAG, "numTextureUpdated < 0 should not happen");
            }
        }

        /**
         * draw the window. It will not be drawn at all if the window is not visible.
         * @param uSTMatrixHandle shader handler for the STMatrix for texture coordinates
         * mapping
         * @param aPositionHandle shader handle for vertex position.
         * @param aTextureHandle shader handle for texture
         */
        public void onDraw(int uSTMatrixHandle, int aPositionHandle, int aTextureHandle) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextureId);
            mVerticesData.position(TRIANGLE_VERTICES_DATA_POS_OFFSET);
            GLES20.glVertexAttribPointer(aPositionHandle, 3, GLES20.GL_FLOAT, false,
                    TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mVerticesData);
            GLES20.glEnableVertexAttribArray(aPositionHandle);

            mVerticesData.position(TRIANGLE_VERTICES_DATA_UV_OFFSET);
            GLES20.glVertexAttribPointer(aTextureHandle, 2, GLES20.GL_FLOAT, false,
                    TRIANGLE_VERTICES_DATA_STRIDE_BYTES, mVerticesData);
            GLES20.glEnableVertexAttribArray(aTextureHandle);
            GLES20.glUniformMatrix4fv(uSTMatrixHandle, 1, false, mSTMatrix, 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }

        public Surface getSurface() {
            return mSurface;
        }
    }

    static void checkGlError(String op) throws GlException {
        int error;
        while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
            Log.e(TAG, op + ": glError " + error);
            throw new GlException(op + ": glError " + error);
        }
    }

    public static class GlException extends Exception {
        public GlException(String msg) {
            super(msg);
        }
    }

}

