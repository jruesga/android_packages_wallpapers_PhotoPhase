/*
 * Copyright (C) 2013 The CyanogenMod Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.cyanogenmod.wallpapers.photophase;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.opengl.GLES20;
import android.opengl.GLException;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Handler;
import android.util.Log;

import org.cyanogenmod.wallpapers.photophase.GLESUtil.GLColor;
import org.cyanogenmod.wallpapers.photophase.preferences.PreferencesProvider;
import org.cyanogenmod.wallpapers.photophase.shapes.ColorShape;
import org.cyanogenmod.wallpapers.photophase.transitions.Transition;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * The EGL renderer of PhotoPhase Live Wallpaper.
 */
public class PhotoPhaseRenderer implements GLSurfaceView.Renderer {

    private static final String TAG = "PhotoPhaseRenderer";

    private static final boolean DEBUG = true;

    private final long mInstance;
    private static long sInstances;

    final Context mContext;
    private final Handler mHandler;
    final GLESSurfaceDispatcher mDispatcher;
    TextureManager mTextureManager;

    PhotoPhaseWallpaperWorld mWorld;
    ColorShape mOverlay;

    long mLastRunningTransition;

    int mWidth = -1;
    int mHeight = -1;
    int mMeasuredHeight  = -1;

    private final float[] mMVPMatrix = new float[16];
    private final float[] mProjMatrix = new float[16];
    private final float[] mVMatrix = new float[16];

    private final Object mDrawing = new Object();

    final Object mMediaSync = new Object();
    private PendingIntent mMediaScanIntent;

    private final BroadcastReceiver mSettingsChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Check what flags are been requested
            boolean recreateWorld = intent.getBooleanExtra(PreferencesProvider.EXTRA_FLAG_RECREATE_WORLD, false);
            boolean redraw = intent.getBooleanExtra(PreferencesProvider.EXTRA_FLAG_REDRAW, false);
            boolean emptyTextureQueue = intent.getBooleanExtra(PreferencesProvider.EXTRA_FLAG_EMPTY_TEXTURE_QUEUE, false);
            boolean mediaReload = intent.getBooleanExtra(PreferencesProvider.EXTRA_FLAG_MEDIA_RELOAD, false);
            boolean mediaIntervalChanged = intent.getBooleanExtra(PreferencesProvider.EXTRA_FLAG_MEDIA_INTERVAL_CHANGED, false);
            if (emptyTextureQueue) {
                if (mTextureManager != null) {
                    mTextureManager.emptyTextureQueue(true);
                }
            }
            if (mediaReload) {
                synchronized (mMediaSync) {
                    if (mTextureManager != null) {
                        mTextureManager.reloadMedia();
                        scheduleOrCancelMediaScan();
                    }
                }
            }
            if (mediaIntervalChanged) {
                scheduleOrCancelMediaScan();
            }
            if (recreateWorld) {
                // Recreate the wallpaper world
                try {
                    mWorld.recreateWorld(mWidth, mMeasuredHeight);
                } catch (GLException e) {
                    Log.e(TAG, "Cannot recreate the wallpaper world.", e);
                }
            }
            if (redraw) {
                mDispatcher.requestRender();
            }
        }
    };

    private final Runnable mTransitionThread = new Runnable() {
        /**
         * {@inheritDoc}
         */
        @Override
        public void run() {
            // Run in GLES's thread
            mDispatcher.dispatch(new Runnable() {
                @Override
                public void run() {
                    // Select a new transition
                    mWorld.selectTransition();
                    mLastRunningTransition = System.currentTimeMillis();

                    // Now force continuously render while transition is applied
                    mDispatcher.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
                }
            });
        }
    };

    /**
     * Constructor of <code>PhotoPhaseRenderer<code>
     *
     * @param ctx The current context
     * @param dispatcher The GLES dispatcher
     */
    public PhotoPhaseRenderer(Context ctx, GLESSurfaceDispatcher dispatcher) {
        super();
        mContext = ctx;
        mHandler = new Handler();
        mDispatcher = dispatcher;
        mInstance = sInstances;
        sInstances++;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + (int) (mInstance ^ (mInstance >>> 32));
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        PhotoPhaseRenderer other = (PhotoPhaseRenderer) obj;
        if (mInstance != other.mInstance)
            return false;
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return "PhotoPhaseRenderer [instance: " + mInstance + "]";
    }

    /**
     * Method called when when renderer is created
     */
    public void onCreate() {
        if (DEBUG) Log.d(TAG, "onCreate [" + mInstance + "]");
        // Register a receiver to listen for media reload request
        IntentFilter filter = new IntentFilter();
        filter.addAction(PreferencesProvider.ACTION_SETTINGS_CHANGED);
        mContext.registerReceiver(mSettingsChangedReceiver, filter);

        // Check whether the media scan is active
        int interval = PreferencesProvider.Preferences.Media.getRefreshFrecuency();
        if (interval != PreferencesProvider.Preferences.Media.MEDIA_RELOAD_DISABLED) {
            // Schedule a media scan
            scheduleMediaScan(interval);
        }
    }

    /**
     * Method called when when renderer is destroyed
     */
    public void onDestroy() {
        if (DEBUG) Log.d(TAG, "onDestroy [" + mInstance + "]");
        // Register a receiver to listen for media reload request
        mContext.unregisterReceiver(mSettingsChangedReceiver);
        recycle();
        mWidth = -1;
        mHeight = -1;
        mMeasuredHeight = -1;
    }

    /**
     * Method called when the renderer should be paused
     */
    public void onPause() {
        if (DEBUG) Log.d(TAG, "onPause [" + mInstance + "]");
        mHandler.removeCallbacks(mTransitionThread);
        if (mTextureManager != null) {
            mTextureManager.setPause(true);
        }
    }

    /**
     * Method called when the renderer should be resumed
     */
    public void onResume() {
        if (DEBUG) Log.d(TAG, "onResume [" + mInstance + "]");
        if (mTextureManager != null) {
            mTextureManager.setPause(false);
        }
    }

    void scheduleOrCancelMediaScan() {
        int interval = PreferencesProvider.Preferences.Media.getRefreshFrecuency();
        if (interval != PreferencesProvider.Preferences.Media.MEDIA_RELOAD_DISABLED) {
            scheduleMediaScan(interval);
        } else {
            cancelMediaScan();
        }
    }

    /**
     * Method that schedules a new media scan
     *
     * @param interval The new interval
     */
    private void scheduleMediaScan(int interval) {
        AlarmManager am = (AlarmManager)mContext.getSystemService(Context.ALARM_SERVICE);

        Intent i = new Intent(PreferencesProvider.ACTION_SETTINGS_CHANGED);
        i.putExtra(PreferencesProvider.EXTRA_FLAG_MEDIA_RELOAD, Boolean.TRUE);
        mMediaScanIntent = PendingIntent.getBroadcast(mContext, 0, i, PendingIntent.FLAG_CANCEL_CURRENT);

        long milliseconds = PreferencesProvider.Preferences.Media.getRefreshFrecuency() * 1000L;
        am.set(AlarmManager.RTC, System.currentTimeMillis() + milliseconds, mMediaScanIntent);
    }

    /**
     * Method that cancels a pending media scan
     */
    private void cancelMediaScan() {
        if (mMediaScanIntent != null) {
            AlarmManager am = (AlarmManager)mContext.getSystemService(Context.ALARM_SERVICE);
            am.cancel(mMediaScanIntent);
            mMediaScanIntent = null;
        }
    }

    /**
     * Method that destroy all the internal references
     */
    private void recycle() {
        if (DEBUG) Log.d(TAG, "recycle [" + mInstance + "]");
        synchronized (mDrawing) {
            // Remove any pending handle
            if (mHandler != null && mTransitionThread != null) {
                mHandler.removeCallbacks(mTransitionThread);
            }

            // Delete the world
            if (mWorld != null) mWorld.recycle();
            if (mTextureManager != null) mTextureManager.recycle();
            if (mOverlay != null) mOverlay.recycle();
            mWorld = null;
            mTextureManager = null;
            mOverlay = null;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onSurfaceCreated(GL10 glUnused, EGLConfig config) {
        if (DEBUG) Log.d(TAG, "onSurfaceCreated [" + mInstance + "]");

        mWidth = -1;
        mHeight = -1;
        mMeasuredHeight = -1;

        // We have a 2d (fake) scenario, disable all unnecessary tests. Deep are
        // necessary for some 3d effects
        GLES20.glDisable(GL10.GL_DITHER);
        GLESUtil.glesCheckError("glDisable");
        GLES20.glDisable(GL10.GL_CULL_FACE);
        GLESUtil.glesCheckError("glDisable");
        GLES20.glEnable(GL10.GL_DEPTH_TEST);
        GLESUtil.glesCheckError("glEnable");
        GLES20.glDepthMask(false);
        GLESUtil.glesCheckError("glDepthMask");
        GLES20.glDepthFunc(GLES20.GL_LEQUAL);
        GLESUtil.glesCheckError("glDepthFunc");

        // Create the texture manager and recycle the old one
        if (mTextureManager == null) {
            // Precalculate the window size for the TextureManager. In onSurfaceChanged
            // the best fixed size will be set. The disposition size is simple for a better
            // performance of the internal arrays
            final Configuration conf = mContext.getResources().getConfiguration();
            int orientation = mContext.getResources().getConfiguration().orientation;
            int w = (int) AndroidHelper.convertDpToPixel(mContext, conf.screenWidthDp);
            int h = (int) AndroidHelper.convertDpToPixel(mContext, conf.screenWidthDp);
            Rect dimensions = new Rect(0, 0, w / 2, h / 2);
            int cc = (orientation == Configuration.ORIENTATION_PORTRAIT)
                        ? PreferencesProvider.Preferences.Layout.getPortraitDisposition().size()
                        : PreferencesProvider.Preferences.Layout.getLandscapeDisposition().size();

            recycle();
            mTextureManager = new TextureManager(mContext, mDispatcher, cc, dimensions);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onSurfaceChanged(GL10 glUnused, int width, int height) {
        if (DEBUG) Log.d(TAG, "onSurfaceChanged [" + mInstance + "," + width + "x" + height + "]");

        // Check if the size was changed
        if (mWidth == width && mHeight == height) {
            return;
        }

        // Save the width and height to avoid recreate the world
        mWidth = width;
        mHeight = height;
        mMeasuredHeight = mHeight - AndroidHelper.calculateStatusBarHeight(mContext);

        // Calculate a better fixed size for the pictures
        Rect dimensions = new Rect(0, 0, width / 2, mMeasuredHeight / 2);
        mTextureManager.setDimensions(dimensions);
        mTextureManager.setPause(false);

        // Create the wallpaper
        mWorld = new PhotoPhaseWallpaperWorld(mContext, mTextureManager);

        // Create all other shapes
        final float[] vertex = {
                                -1.0f,  1.0f, 0.0f,
                                -1.0f, -1.0f, 0.0f,
                                 1.0f, -1.0f, 0.0f,
                                 1.0f,  1.0f, 0.0f
                               };
        mOverlay = new ColorShape(mContext, vertex, Colors.getOverlay());

        // Set the viewport and the fustrum to use
        GLES20.glViewport(0, 0, width, mMeasuredHeight);
        GLESUtil.glesCheckError("glViewport");
        Matrix.frustumM(mProjMatrix, 0, 1.0f, -1.0f, -1.0f, 1.0f, 1.0f, 2.0f);

        // Recreate the wallpaper world
        try {
            mWorld.recreateWorld(width, mMeasuredHeight);
        } catch (GLException e) {
            Log.e(TAG, "Cannot recreate the wallpaper world.", e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onDrawFrame(GL10 glUnused) {
        synchronized (mDrawing) {
            // Draw the background
            drawBackground();

            if (mWorld != null) {
                // Set the projection, view and model
                Matrix.setLookAtM(mVMatrix, 0, 0.0f, 0.0f, -1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f);
                Matrix.multiplyMM(mMVPMatrix, 0, mProjMatrix, 0, mVMatrix, 0);

                // Now draw the world (all the photo frames with effects)
                mWorld.draw(mMVPMatrix);

                // Check if we have some pending transition or transition has exceed its timeout
                if (!mWorld.hasRunningTransition() || isTransitionTimeout()) {
                    mDispatcher.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);

                    // Now start a delayed thread to generate the next effect
                    mHandler.removeCallbacks(mTransitionThread);
                    mWorld.deselectTransition(mMVPMatrix);
                    mLastRunningTransition = 0;
                    mHandler.postDelayed(mTransitionThread,
                            PreferencesProvider.Preferences.General.Transitions.getTransitionInterval());
                }
            }

            // Draw the overlay
            drawOverlay();
        }
    }

    /**
     * Check whether the transition has exceed the timeout
     *
     * @return boolean if the transition has exceed the timeout
     */
    private boolean isTransitionTimeout() {
        long now = System.currentTimeMillis();
        long diff = now - mLastRunningTransition;
        return mLastRunningTransition != 0 && diff > Transition.MAX_TRANSTION_TIME;
    }

    /**
     * Method that draws the background of the wallpaper
     */
    private static void drawBackground() {
        GLColor bg = Colors.getBackground();
        GLES20.glClearColor(bg.r, bg.g, bg.b, bg.a);
        GLESUtil.glesCheckError("glClearColor");
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        GLESUtil.glesCheckError("glClear");
    }

    /**
     * Method that draws the overlay of the wallpaper
     */
    private void drawOverlay() {
        if (mOverlay != null) {
            mOverlay.setAlpha(PreferencesProvider.Preferences.General.getWallpaperDim() / 100.0f);
            mOverlay.draw(mMVPMatrix);
        }
    }

}
