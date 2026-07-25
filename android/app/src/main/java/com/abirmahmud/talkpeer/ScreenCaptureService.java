package com.abirmahmud.talkpeer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * Captures the device screen via MediaProjection and hands JPEG frames back
 * to ScreenCapturePlugin, which forwards them to the web app. Runs as a
 * foreground service because Android requires that for any app using
 * MediaProjection — and, since Android 14, requires the service to declare
 * the "mediaProjection" foreground service type specifically, or the OS
 * throws a SecurityException the moment createVirtualDisplay() is called.
 */
public class ScreenCaptureService extends Service {

    public static final String CHANNEL_ID = "talkpeer_screen_share";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";
    public static final String ACTION_STOP = "com.abirmahmud.talkpeer.STOP_SCREEN_CAPTURE";

    public interface FrameListener {
        void onFrame(String base64Jpeg, int width, int height);
        void onStopped();
    }

    private static FrameListener frameListener;

    public static void setFrameListener(FrameListener listener) {
        frameListener = listener;
    }

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread captureThread;
    private Handler captureHandler;

    private int width, height, density;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(1, buildNotification());

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        if (resultData == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        MediaProjectionManager mpm =
            (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mediaProjection = mpm.getMediaProjection(resultCode, resultData);

        if (mediaProjection == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        mediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopSelf();
            }
        }, null);

        setupVirtualDisplay();

        return START_NOT_STICKY;
    }

    private void setupVirtualDisplay() {
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(metrics);
        density = metrics.densityDpi;

        // Cap the capture resolution — sending full device resolution as
        // base64 JPEGs over the JS bridge would be far too slow/heavy.
        // This keeps things smooth on mid-range phones.
        int maxDim = 960;
        int rw = metrics.widthPixels;
        int rh = metrics.heightPixels;
        float scale = Math.min(1f, (float) maxDim / Math.max(rw, rh));
        width = Math.max(2, (Math.round(rw * scale) / 2) * 2);
        height = Math.max(2, (Math.round(rh * scale) / 2) * 2);

        captureThread = new HandlerThread("TalkPeerScreenCapture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);

        virtualDisplay = mediaProjection.createVirtualDisplay(
            "TalkPeerScreenShare",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.getSurface(), null, captureHandler
        );

        imageReader.setOnImageAvailableListener(reader -> {
            Image image = null;
            try {
                image = reader.acquireLatestImage();
                if (image == null) return;
                Bitmap bmp = imageToBitmap(image);
                if (bmp != null) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    bmp.compress(Bitmap.CompressFormat.JPEG, 55, bos);
                    if (frameListener != null) {
                        String b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
                        frameListener.onFrame(b64, bmp.getWidth(), bmp.getHeight());
                    }
                    bmp.recycle();
                }
            } catch (Exception e) {
                // A dropped frame isn't fatal — the next one arrives shortly.
            } finally {
                if (image != null) image.close();
            }
        }, captureHandler);
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * width;

        Bitmap bitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
        );
        bitmap.copyPixelsFromBuffer(buffer);
        if (rowPadding == 0) return bitmap;
        Bitmap cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height);
        bitmap.recycle();
        return cropped;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Screen sharing", NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent stopIntent = new Intent(this, ScreenCaptureService.class);
        stopIntent.setAction(ACTION_STOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
            | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, flags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TalkPeer")
            .setContentText("Sharing your screen")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop sharing", stopPendingIntent)
            .setOngoing(true)
            .build();
    }

    @Override
    public void onDestroy() {
        if (frameListener != null) frameListener.onStopped();
        if (virtualDisplay != null) virtualDisplay.release();
        if (imageReader != null) imageReader.close();
        if (mediaProjection != null) mediaProjection.stop();
        if (captureThread != null) captureThread.quitSafely();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
