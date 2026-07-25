package com.abirmahmud.talkpeer;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    private static final int PERMISSION_REQUEST_CODE = 1001;
    private static final String[] REQUIRED_PERMISSIONS = {
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(ScreenCapturePlugin.class);
        super.onCreate(savedInstanceState);

        // A WebView doesn't automatically show Android's own camera/mic
        // permission prompts the way Chrome does — request them up front
        // so the OS-level permission exists before the page ever calls
        // getUserMedia().
        requestAppPermissionsIfNeeded();

        // Without this, getUserMedia()/getDisplayMedia() calls inside
        // the web app fail even after the OS permission above is granted,
        // because the WebView itself still has to explicitly approve the
        // page's permission request.
        //
        // FIX: this used to call request.grant(request.getResources())
        // unconditionally — approving the page's camera/mic request even
        // if the real Android runtime permission was never actually
        // granted (dialog still pending, dismissed, or later revoked in
        // Settings). The WebView would then hand the page a "successful"
        // MediaStreamTrack that's muted at the OS level: it looks live in
        // the UI, but produces black video / silent audio, because there's
        // no real hardware access behind it — exactly the "camera looks on
        // but nobody else sees or hears anything" symptom. Now the grant
        // is checked against the actual permission state, and if it isn't
        // really granted yet, we re-prompt instead of pretending it's fine.
        this.bridge.getWebView().setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    boolean camGranted = ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
                    boolean micGranted = ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;

                    java.util.List<String> toGrant = new java.util.ArrayList<>();
                    for (String resource : request.getResources()) {
                        if (resource.equals(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
                            if (camGranted) toGrant.add(resource);
                        } else if (resource.equals(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                            if (micGranted) toGrant.add(resource);
                        } else {
                            toGrant.add(resource); // unrelated resource types (e.g. protected media) — allow through
                        }
                    }

                    if (!toGrant.isEmpty()) {
                        request.grant(toGrant.toArray(new String[0]));
                    } else {
                        request.deny();
                    }

                    // The real OS permission isn't actually granted yet — ask
                    // for it now instead of leaving the page stuck with a
                    // silently-dead camera/mic.
                    if (!camGranted || !micGranted) {
                        requestAppPermissionsIfNeeded();
                    }
                });
            }
        });

        // Handle the case where the app was launched fresh (cold start) via
        // an https://abir.ovh/<code> App Link — see handleIncomingLink().
        handleIncomingLink(getIntent());
    }

    // App Links use singleTask launch mode (see AndroidManifest.xml), so if
    // TalkPeer is already running and someone taps another meeting link,
    // Android routes it here instead of restarting the app via onCreate.
    @Override
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingLink(intent);
    }

    // Pulls the room code out of an https://abir.ovh/<code> link and feeds
    // it to the already-loaded web app by setting location.hash — the
    // app's existing checkDL()/hashchange handling picks it up from there
    // exactly like a normal web hash-based invite link, no separate code
    // path needed on the JS side.
    private void handleIncomingLink(Intent intent) {
        if (intent == null) return;
        Uri data = intent.getData();
        if (data == null) return;
        String path = data.getPath();
        if (path == null || path.length() <= 1) return;
        String code = path.replaceFirst("^/+", "").replaceFirst("/+$", "");
        if (code.isEmpty()) return;
        // Small delay: on a cold start the WebView may still be finishing
        // its initial page load when onCreate runs — give it a moment so
        // the hashchange listener is guaranteed to already be attached.
        this.bridge.getWebView().postDelayed(() -> {
            String js = "location.hash=" + org.json.JSONObject.quote(code) + ";";
            this.bridge.getWebView().evaluateJavascript(js, null);
        }, 1200);
    }

    private void requestAppPermissionsIfNeeded() {
        boolean needsRequest = false;
        for (String perm : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                needsRequest = true;
                break;
            }
        }
        if (needsRequest) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE);
        }
    }
}
