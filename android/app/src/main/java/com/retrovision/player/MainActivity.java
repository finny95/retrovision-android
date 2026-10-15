package com.retrovision.player;

import android.app.PictureInPictureParams;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.util.Rational;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(MediaFolderPlugin.class);
        super.onCreate(savedInstanceState);

        // Streams linked media files with proper HTTP range support (seeking, files over 2 GB).
        bridge.setWebViewClient(new MediaWebViewClient(bridge));
        // Lets the player's fullscreen button work inside the WebView.
        bridge.getWebView().setWebChromeClient(new FullscreenChromeClient(bridge));

        enterImmersiveMode();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enterImmersiveMode();
        }
    }

    /** Leaving the app (Home button) while a channel plays: keep watching in a small window. */
    @Override
    public void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (MediaFolderPlugin.pipEnabled && MediaFolderPlugin.playing && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                enterPictureInPictureMode(new PictureInPictureParams.Builder().setAspectRatio(new Rational(16, 9)).build());
            } catch (Exception ignored) {
                // Not allowed on this device right now.
            }
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        if (bridge != null) {
            bridge.triggerWindowJSEvent("rv-pip", "{ \"active\": " + isInPictureInPictureMode + " }");
        }
    }

    /** Hides the status and navigation bars; a swipe from the edge shows them briefly. */
    private void enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.hide(WindowInsetsCompat.Type.systemBars());
        controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }
}
