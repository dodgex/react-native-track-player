package com.guichaguri.trackplayer.service;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.support.v4.media.session.PlaybackStateCompat;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.media.session.MediaButtonReceiver;

import com.facebook.react.HeadlessJsTaskService;
import com.facebook.react.ReactInstanceManager;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.jstasks.HeadlessJsTaskConfig;
import com.guichaguri.trackplayer.service.Utils;
import com.guichaguri.trackplayer.module.MusicEvents;
import javax.annotation.Nullable;

/**
 * @author Guichaguri
 */
public class MusicService extends HeadlessJsTaskService {

    private Runnable tickTimerRunnable = null;

    MusicManager manager;
    Handler handler;

    @Nullable
    @Override
    protected HeadlessJsTaskConfig getTaskConfig(Intent intent) {
        return new HeadlessJsTaskConfig("TrackPlayer", Arguments.createMap(), 0, true);
    }

    @Override
    public void onHeadlessJsTaskFinish(int taskId) {
        // Overridden to prevent the service from being terminated
    }

    public void emit(String event, Bundle data) {
        Intent intent = new Intent(Utils.EVENT_INTENT);

        intent.putExtra("event", event);
        if(data != null) intent.putExtra("data", data);

        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    public synchronized void toggleTimerTick(int state) {
        switch (state) {
            case PlaybackStateCompat.STATE_PLAYING:
                if (tickTimerRunnable == null) {
                    tickTimerRunnable = new Runnable() {
                        final long start = System.currentTimeMillis() / 1000;

                        @Override
                        public void run() {
                            long now = System.currentTimeMillis() / 1000;

                            Bundle bundle = new Bundle();
                            bundle.putLong("time", now - start);
                            emit(MusicEvents.PLAYBACK_TIMER_TICK, bundle);

                            handler.postDelayed(tickTimerRunnable, 1000);
                        }
                    };
                    tickTimerRunnable.run();
                }
                break;

            case PlaybackStateCompat.STATE_NONE:
            case PlaybackStateCompat.STATE_PAUSED:
            case PlaybackStateCompat.STATE_STOPPED:
                if (tickTimerRunnable != null) {
                    handler.removeCallbacks(tickTimerRunnable);
                    tickTimerRunnable = null;
                }
                break;
        }
    }

    public void destroy() {
        if(handler != null) {
            handler.removeMessages(0);
            handler = null;
        }

        if(manager != null) {
            manager.destroy();
            manager = null;
        }
    }

    private void onStartForeground() {
        boolean serviceForeground = false;

        if(manager != null) {
            // The session is only active when the service is on foreground
            serviceForeground = manager.getMetadata().getSession().isActive();
        }

        if(!serviceForeground) {
            ReactInstanceManager reactInstanceManager = getReactNativeHost().getReactInstanceManager();
            ReactContext reactContext = reactInstanceManager.getCurrentReactContext();

            // Checks whether there is a React activity
            if(reactContext == null || !reactContext.hasCurrentActivity()) {
                String channel = Utils.getNotificationChannel((Context) this);

                // Sets the service to foreground with an empty notification
                startForeground(1, new NotificationCompat.Builder(this, channel).build());
                // Stops the service right after
                stopSelf();
            }
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        if(Utils.CONNECT_INTENT.equals(intent.getAction())) {
            return new MusicBinder(this, manager);
        }

        return super.onBind(intent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if(intent != null && Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) {
            // Check if the app is on background, then starts a foreground service and then ends it right after
            onStartForeground();
            
            if(manager != null) {
                MediaButtonReceiver.handleIntent(manager.getMetadata().getSession(), intent);
            }
            
            return START_NOT_STICKY;
        }

        manager = new MusicManager(this);
        handler = new Handler();

        super.onStartCommand(intent, flags, startId);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        destroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);

        if (manager == null || manager.shouldStopWithApp()) {
            if (manager != null) {
                manager.getPlayback().stop();
            }
            destroy();
            stopSelf();
        }
    }
}
