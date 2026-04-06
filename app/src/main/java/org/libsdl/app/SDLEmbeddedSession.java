package org.libsdl.app;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.RelativeLayout;

import com.dosbox.emu.DOSBoxJNI;

public final class SDLEmbeddedSession {
    private static final String TAG = "SDLEmbedded";

    public interface ExitHandler {
        void onExit();
    }

    private final RelativeLayout container;
    private final EmbeddedSDLActivity host;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean destroyed;

    private SDLEmbeddedSession(RelativeLayout container, EmbeddedSDLActivity host) {
        this.container = container;
        this.host = host;
    }

    public static SDLEmbeddedSession start(
            Context context,
            Window hostWindow,
            RelativeLayout container,
            String[] arguments,
            ExitHandler exitHandler
    ) {
        SDLActivity.initialize();
        EmbeddedSDLActivity host = new EmbeddedSDLActivity(context, hostWindow, arguments, exitHandler);
        SDLActivity.mSingleton = host;

        try {
            host.loadLibraries();
        } catch (UnsatisfiedLinkError error) {
            Log.e(TAG, "Failed loading SDL libraries", error);
            SDLActivity.mBrokenLibraries = true;
            return null;
        } catch (Exception error) {
            Log.e(TAG, "Failed initializing SDL libraries", error);
            SDLActivity.mBrokenLibraries = true;
            return null;
        }

        if (Build.VERSION.SDK_INT >= 12) {
            SDLActivity.mJoystickHandler = new SDLJoystickHandler_API12();
        } else {
            SDLActivity.mJoystickHandler = new SDLJoystickHandler();
        }

        DOSBoxJNI.nativeResetSdlAndroidSessionState();
        DOSBoxJNI.nativeSetEmbeddedSessionMode(true);

        SDLActivity.mLayout = container;
        SDLActivity.mSurface = new SDLSurface(context);
        container.removeAllViews();
        container.addView(
                SDLActivity.mSurface,
                new RelativeLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                )
        );

        return new SDLEmbeddedSession(container, host);
    }

    public void resume() {
        if (destroyed) {
            return;
        }

        SDLActivity.mHasFocus = true;
        SDLActivity.handleResume();
    }

    public void pause() {
        if (destroyed) {
            return;
        }

        SDLActivity.handlePause();
        SDLActivity.mHasFocus = false;
    }

    public void requestQuit() {
        if (destroyed) {
            return;
        }

        SDLActivity.mExitCalledFromJava = true;
        SDLActivity.nativeQuit();
    }

    public void destroy() {
        if (destroyed) {
            return;
        }
        destroyed = true;
        host.setSuppressExitHandler(true);

        boolean threadStopped = true;

        try {
            SDLActivity.mHasFocus = false;
            SDLActivity.mExitCalledFromJava = true;
            if (SDLActivity.mIsSurfaceReady) {
                SDLActivity.handlePause();
                SDLActivity.mIsSurfaceReady = false;
                SDLActivity.onNativeSurfaceDestroyed();
            }
            if (SDLActivity.mSDLThread != null) {
                SDLActivity.nativeQuit();
                final long deadline = SystemClock.uptimeMillis() + 8000L;
                while (SDLActivity.mSDLThread != null
                        && SDLActivity.mSDLThread.isAlive()
                        && SystemClock.uptimeMillis() < deadline) {
                    SDLActivity.mSDLThread.join(250L);
                }
                threadStopped = SDLActivity.mSDLThread == null || !SDLActivity.mSDLThread.isAlive();
                if (threadStopped) {
                    SDLActivity.mSDLThread = null;
                } else {
                    Log.w(TAG, "Embedded SDL thread did not stop before timeout");
                }
            }
        } catch (Exception error) {
            Log.w(TAG, "Failed while stopping embedded SDL session", error);
            threadStopped = false;
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            container.removeAllViews();
            if (threadStopped) {
                DOSBoxJNI.nativeSetEmbeddedSessionMode(false);
                DOSBoxJNI.nativeResetSdlAndroidSessionState();
                SDLActivity.initialize();
            }
        } else if (threadStopped) {
            mainHandler.post(() -> {
                container.removeAllViews();
                DOSBoxJNI.nativeSetEmbeddedSessionMode(false);
                DOSBoxJNI.nativeResetSdlAndroidSessionState();
                SDLActivity.initialize();
            });
        } else {
            mainHandler.post(container::removeAllViews);
        }
    }

    private static final class EmbeddedSDLActivity extends SDLActivity {
        private final Window hostWindow;
        private final String[] arguments;
        private final ExitHandler exitHandler;
        private final Handler mainHandler = new Handler(Looper.getMainLooper());
        private volatile boolean suppressExitHandler;

        EmbeddedSDLActivity(Context baseContext, Window hostWindow, String[] arguments, ExitHandler exitHandler) {
            attachBaseContext(baseContext);
            this.hostWindow = hostWindow;
            this.arguments = arguments != null ? arguments : new String[0];
            this.exitHandler = exitHandler;
        }

        @Override
        protected String[] getArguments() {
            return arguments;
        }

        @Override
        public int getRequestedOrientation() {
            return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        }

        @Override
        public void finish() {
            if (!suppressExitHandler && exitHandler != null) {
                mainHandler.post(exitHandler::onExit);
            }
        }

        void setSuppressExitHandler(boolean suppressExitHandler) {
            this.suppressExitHandler = suppressExitHandler;
        }

        @Override
        public Window getWindow() {
            return hostWindow;
        }

        @Override
        public void setTitle(CharSequence title) {
            Log.d(TAG, "SDL title: " + title);
        }
    }
}