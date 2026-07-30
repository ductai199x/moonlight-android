package com.limelight.ui;

import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/**
 * Handles pinch-to-zoom and pan gestures by transforming the StreamView.
 *
 * The transform is applied entirely on the client side using the standard
 * view scale and position properties, so it has no effect on the video
 * pipeline. All math uses a top-left pivot to keep the scaled bounds simple:
 * the rendered top-left corner is (getX(), getY()) and the rendered size is
 * (getWidth() * scale, getHeight() * scale).
 */
public class StreamPanZoomHandler {
    private static final float MIN_SCALE = 1.0f;
    private static final float MAX_SCALE = 5.0f;
    private static final float FOLLOW_CURSOR_MARGIN_DP = 48;

    private final View streamView;
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector panDetector;
    private final float followCursorMargin;

    private float scaleFactor = 1.0f;

    public StreamPanZoomHandler(Context context, View streamView) {
        this.streamView = streamView;
        this.followCursorMargin = FOLLOW_CURSOR_MARGIN_DP * context.getResources().getDisplayMetrics().density;

        scaleDetector = new ScaleGestureDetector(context, new ScaleListener());
        panDetector = new GestureDetector(context, new PanListener());

        streamView.setPivotX(0);
        streamView.setPivotY(0);

        // Re-clamp if the view's layout changes (rotation, resolution change)
        streamView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if ((right - left) != (oldRight - oldLeft) || (bottom - top) != (oldBottom - oldTop)) {
                    constrainToBounds();
                }
            }
        });
    }

    public void handleTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        panDetector.onTouchEvent(event);
    }

    public float getScaleFactor() {
        return scaleFactor;
    }

    // Pans the view (if zoomed) to keep the given StreamView-local point inside
    // the visible viewport with a small margin. Used to make the viewport follow
    // the mouse cursor to the edges of the desktop in trackpad mode.
    public void followCursor(float streamX, float streamY) {
        if (scaleFactor <= MIN_SCALE) {
            return;
        }

        View parent = (View) streamView.getParent();
        if (parent == null) {
            return;
        }

        float screenX = streamView.getX() + streamX * scaleFactor;
        float screenY = streamView.getY() + streamY * scaleFactor;

        float dx = 0, dy = 0;
        if (screenX < followCursorMargin) {
            dx = followCursorMargin - screenX;
        }
        else if (screenX > parent.getWidth() - followCursorMargin) {
            dx = parent.getWidth() - followCursorMargin - screenX;
        }
        if (screenY < followCursorMargin) {
            dy = followCursorMargin - screenY;
        }
        else if (screenY > parent.getHeight() - followCursorMargin) {
            dy = parent.getHeight() - followCursorMargin - screenY;
        }

        if (dx != 0 || dy != 0) {
            streamView.setX(streamView.getX() + dx);
            streamView.setY(streamView.getY() + dy);
            constrainToBounds();
        }
    }

    public void reset() {
        scaleFactor = 1.0f;
        streamView.setScaleX(1.0f);
        streamView.setScaleY(1.0f);
        constrainToBounds();
    }

    private void constrainToBounds() {
        View parent = (View) streamView.getParent();
        if (parent == null) {
            return;
        }

        float scaledWidth = streamView.getWidth() * scaleFactor;
        float scaledHeight = streamView.getHeight() * scaleFactor;
        float parentWidth = parent.getWidth();
        float parentHeight = parent.getHeight();

        float x = streamView.getX();
        float y = streamView.getY();

        if (scaledWidth <= parentWidth) {
            // Content narrower than the parent gets centered
            x = (parentWidth - scaledWidth) / 2;
        }
        else {
            // Content wider than the parent must cover it entirely
            x = Math.max(parentWidth - scaledWidth, Math.min(x, 0));
        }

        if (scaledHeight <= parentHeight) {
            y = (parentHeight - scaledHeight) / 2;
        }
        else {
            y = Math.max(parentHeight - scaledHeight, Math.min(y, 0));
        }

        streamView.setX(x);
        streamView.setY(y);
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float newScaleFactor = Math.max(MIN_SCALE, Math.min(scaleFactor * detector.getScaleFactor(), MAX_SCALE));

            // Anchor the zoom at the gesture's focal point by scaling the
            // vector from the focal point to the view's origin
            float focusX = detector.getFocusX();
            float focusY = detector.getFocusY();

            float x = focusX + (streamView.getX() - focusX) / scaleFactor * newScaleFactor;
            float y = focusY + (streamView.getY() - focusY) / scaleFactor * newScaleFactor;

            scaleFactor = newScaleFactor;

            streamView.setScaleX(scaleFactor);
            streamView.setScaleY(scaleFactor);
            streamView.setX(x);
            streamView.setY(y);

            constrainToBounds();
            return true;
        }
    }

    private class PanListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            streamView.setX(streamView.getX() - distanceX);
            streamView.setY(streamView.getY() - distanceY);

            constrainToBounds();
            return true;
        }
    }
}
