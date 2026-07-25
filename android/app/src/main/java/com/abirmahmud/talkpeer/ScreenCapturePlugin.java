package com.abirmahmud.talkpeer;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Build;

import androidx.activity.result.ActivityResult;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Exposed to JS as Capacitor.Plugins.ScreenCapture. Since a WebView has no
 * real getDisplayMedia() support, the web app calls start()/stop() here
 * instead when running inside the Android app, and listens for "frame"
 * events to build a MediaStream out of the captured screen (see
 * www/android-screenshare.js).
 */
@CapacitorPlugin(name = "ScreenCapture")
public class ScreenCapturePlugin extends Plugin implements ScreenCaptureService.FrameListener {

    @PluginMethod
    public void start(PluginCall call) {
        MediaProjectionManager mpm = (MediaProjectionManager)
            getActivity().getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        Intent intent = mpm.createScreenCaptureIntent();
        startActivityForResult(call, intent, "handleProjectionResult");
    }

    @ActivityCallback
    private void handleProjectionResult(PluginCall call, ActivityResult result) {
        if (call == null) return;

        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            call.reject("Screen capture permission denied");
            return;
        }

        ScreenCaptureService.setFrameListener(this);

        Intent serviceIntent = new Intent(getContext(), ScreenCaptureService.class);
        serviceIntent.putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.getResultCode());
        serviceIntent.putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.getData());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getContext().startForegroundService(serviceIntent);
        } else {
            getContext().startService(serviceIntent);
        }

        JSObject ret = new JSObject();
        ret.put("started", true);
        call.resolve(ret);
    }

    @PluginMethod
    public void stop(PluginCall call) {
        Intent serviceIntent = new Intent(getContext(), ScreenCaptureService.class);
        getContext().stopService(serviceIntent);
        ScreenCaptureService.setFrameListener(null);
        call.resolve();
    }

    @Override
    public void onFrame(String base64Jpeg, int width, int height) {
        JSObject data = new JSObject();
        data.put("data", base64Jpeg);
        data.put("width", width);
        data.put("height", height);
        notifyListeners("frame", data);
    }

    @Override
    public void onStopped() {
        notifyListeners("stopped", new JSObject());
    }
}
