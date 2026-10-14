package com.retrovision.player;

import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeWebChromeClient;

/**
 * Capacitor's chrome client cancels every fullscreen request. This one shows the
 * fullscreen element in a full-window view, so element.requestFullscreen() works
 * the same as in Chrome. File pickers and permissions still come from the parent.
 */
public class FullscreenChromeClient extends BridgeWebChromeClient {

    private final Bridge bridge;
    private View customView;
    private CustomViewCallback customViewCallback;

    public FullscreenChromeClient(Bridge bridge) {
        super(bridge);
        this.bridge = bridge;
    }

    @Override
    public void onShowCustomView(View view, CustomViewCallback callback) {
        if (customView != null) {
            callback.onCustomViewHidden();
            return;
        }
        customView = view;
        customViewCallback = callback;
        view.setBackgroundColor(Color.BLACK);
        ViewGroup decor = (ViewGroup) bridge.getActivity().getWindow().getDecorView();
        decor.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        bridge.getWebView().setVisibility(View.INVISIBLE);
    }

    @Override
    public void onHideCustomView() {
        if (customView == null) {
            return;
        }
        ViewGroup decor = (ViewGroup) bridge.getActivity().getWindow().getDecorView();
        decor.removeView(customView);
        customView = null;
        bridge.getWebView().setVisibility(View.VISIBLE);
        bridge.getWebView().requestFocus();
        if (customViewCallback != null) {
            customViewCallback.onCustomViewHidden();
            customViewCallback = null;
        }
    }
}
