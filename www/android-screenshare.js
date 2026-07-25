// TalkPeer Android screen-share bridge.
//
// A WebView has no real getDisplayMedia() support, so on Android the native
// ScreenCapture plugin (see MainActivity.java / ScreenCapturePlugin.java)
// captures the screen and streams JPEG frames over the Capacitor bridge.
// This file turns that frame stream into an ordinary MediaStream via
// canvas.captureStream() — from that point on, it's a real video track and
// the rest of the app (peer.call(), etc.) doesn't need to know the
// difference between this and a desktop getDisplayMedia() stream.
(function () {
  function isAndroidNative() {
    return !!(
      window.Capacitor &&
      window.Capacitor.isNativePlatform &&
      window.Capacitor.isNativePlatform() &&
      window.Capacitor.getPlatform &&
      window.Capacitor.getPlatform() === 'android' &&
      window.Capacitor.Plugins &&
      window.Capacitor.Plugins.ScreenCapture
    );
  }

  async function startAndroidScreenShare() {
    const { ScreenCapture } = window.Capacitor.Plugins;

    const canvas = document.createElement('canvas');
    canvas.width = 640;
    canvas.height = 360;
    const ctx = canvas.getContext('2d');
    let sized = false;

    const frameListener = await ScreenCapture.addListener('frame', (evt) => {
      const img = new Image();
      img.onload = () => {
        if (!sized || canvas.width !== img.width || canvas.height !== img.height) {
          canvas.width = img.width;
          canvas.height = img.height;
          sized = true;
        }
        ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
      };
      img.src = 'data:image/jpeg;base64,' + evt.data;
    });

    // Modest, fixed sample rate — the source frames themselves arrive as
    // fast as the phone can produce them, but there's no point sampling the
    // canvas faster than that over what is, underneath, a base64-JPEG bridge.
    const stream = canvas.captureStream(8);

    const stoppedListener = await ScreenCapture.addListener('stopped', () => {
      stream.getTracks().forEach((t) => t.stop());
    });

    // Launches Android's screen-capture consent dialog. Throws/rejects if
    // the person declines it.
    await ScreenCapture.start();

    const track = stream.getVideoTracks()[0];
    const originalStop = track.stop.bind(track);
    track.stop = () => {
      originalStop();
      ScreenCapture.stop().catch(() => {});
      frameListener.remove();
      stoppedListener.remove();
    };

    return stream;
  }

  window.__talkpeerAndroidScreenShare = {
    supported: isAndroidNative,
    start: startAndroidScreenShare,
  };
})();
