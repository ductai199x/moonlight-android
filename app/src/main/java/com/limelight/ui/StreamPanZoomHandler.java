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
    private static final float IMPLICIT_DECISION_SLOP_DP = 24;

    // Results of filterImplicitGesture()
    public static final int IMPLICIT_PASS = 0;
    public static final int IMPLICIT_CONSUMED = 1;
    public static final int IMPLICIT_CLAIM_STARTED = 2;
    public static final int IMPLICIT_SMART_ZOOM = 3;

    private static final float SMART_ZOOM_SCALE = 2.5f;
    private static final int TWO_FINGER_TAP_TIME_MS = 250;
    private static final int SMART_ZOOM_DOUBLE_TAP_TIME_MS = 400;

    private static final int GESTURE_IDLE = 0;
    private static final int GESTURE_UNDECIDED = 1;
    private static final int GESTURE_CLAIMED = 2;
    private static final int GESTURE_REJECTED = 3;

    private final View streamView;
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector panDetector;
    private final float followCursorMargin;
    private final float implicitDecisionSlop;

    private float scaleFactor = 1.0f;

    private int implicitGestureState = GESTURE_IDLE;
    private long implicitStartTime;
    private float implicitStartSpan, implicitStartFocusX, implicitStartFocusY;
    private float implicitPrevSpan, implicitPrevFocusX, implicitPrevFocusY;
    private long lastTwoFingerTapTime;
    private float lastTwoFingerTapFocusX, lastTwoFingerTapFocusY;

    public StreamPanZoomHandler(Context context, View streamView) {
        this.streamView = streamView;
        this.followCursorMargin = FOLLOW_CURSOR_MARGIN_DP * context.getResources().getDisplayMetrics().density;

        scaleDetector = new ScaleGestureDetector(context, new ScaleListener());
        panDetector = new GestureDetector(context, new PanListener());
        this.implicitDecisionSlop = IMPLICIT_DECISION_SLOP_DP * context.getResources().getDisplayMetrics().density;

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

    // Watches multi-touch gestures outside of navigation mode and claims those
    // that look like a pinch, so zooming works without switching modes. Returns
    // IMPLICIT_CLAIM_STARTED when a pinch is first recognized (the caller
    // should cancel any in-progress touch handling), IMPLICIT_CONSUMED while a
    // pinch (or an undecided two-finger movement) is in progress, and
    // IMPLICIT_PASS for everything else.
    public int filterImplicitGesture(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                implicitGestureState = GESTURE_IDLE;
                return IMPLICIT_PASS;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (implicitGestureState == GESTURE_CLAIMED) {
                    if (event.getPointerCount() == 2) {
                        primeImplicitPinch(event);
                    }
                    return IMPLICIT_CONSUMED;
                }
                if (implicitGestureState == GESTURE_IDLE && event.getPointerCount() == 2) {
                    implicitGestureState = GESTURE_UNDECIDED;
                    implicitStartTime = event.getEventTime();
                    implicitStartSpan = pointerSpan(event);
                    implicitStartFocusX = pointerFocusX(event);
                    implicitStartFocusY = pointerFocusY(event);
                }
                else if (implicitGestureState == GESTURE_UNDECIDED) {
                    // A third finger is not a pinch (e.g. the keyboard gesture)
                    implicitGestureState = GESTURE_REJECTED;
                }
                return IMPLICIT_PASS;

            case MotionEvent.ACTION_MOVE:
                if (implicitGestureState == GESTURE_UNDECIDED) {
                    if (event.getPointerCount() < 2) {
                        return IMPLICIT_PASS;
                    }

                    float spanDelta = Math.abs(pointerSpan(event) - implicitStartSpan);
                    float focusDelta = (float) Math.hypot(
                            pointerFocusX(event) - implicitStartFocusX,
                            pointerFocusY(event) - implicitStartFocusY);

                    if (spanDelta > implicitDecisionSlop && spanDelta > focusDelta) {
                        implicitGestureState = GESTURE_CLAIMED;
                        primeImplicitPinch(event);
                        return IMPLICIT_CLAIM_STARTED;
                    }
                    else if (focusDelta > implicitDecisionSlop * 1.5f && focusDelta > spanDelta * 2) {
                        // Fingers moving together: this is a scroll
                        implicitGestureState = GESTURE_REJECTED;
                        return IMPLICIT_PASS;
                    }

                    // Hold the gesture back until we know what it is, so a
                    // pinch doesn't cause a scroll before it's recognized
                    return IMPLICIT_CONSUMED;
                }
                if (implicitGestureState == GESTURE_CLAIMED) {
                    if (event.getPointerCount() >= 2) {
                        continueImplicitPinch(event);
                    }
                    return IMPLICIT_CONSUMED;
                }
                return IMPLICIT_PASS;

            case MotionEvent.ACTION_POINTER_UP:
                if (implicitGestureState == GESTURE_CLAIMED) {
                    return IMPLICIT_CONSUMED;
                }
                if (implicitGestureState == GESTURE_UNDECIDED) {
                    // Released before moving enough to decide: it was a tap
                    implicitGestureState = GESTURE_REJECTED;

                    if (event.getEventTime() - implicitStartTime < TWO_FINGER_TAP_TIME_MS) {
                        // Two quick two-finger taps in the same place toggle smart zoom
                        if (event.getEventTime() - lastTwoFingerTapTime < SMART_ZOOM_DOUBLE_TAP_TIME_MS &&
                                Math.hypot(implicitStartFocusX - lastTwoFingerTapFocusX,
                                        implicitStartFocusY - lastTwoFingerTapFocusY) < implicitDecisionSlop * 3) {
                            lastTwoFingerTapTime = 0;
                            smartZoomToggle(implicitStartFocusX, implicitStartFocusY);
                            return IMPLICIT_SMART_ZOOM;
                        }

                        lastTwoFingerTapTime = event.getEventTime();
                        lastTwoFingerTapFocusX = implicitStartFocusX;
                        lastTwoFingerTapFocusY = implicitStartFocusY;
                    }
                }
                return IMPLICIT_PASS;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                boolean wasClaimed = implicitGestureState == GESTURE_CLAIMED;
                implicitGestureState = GESTURE_IDLE;
                return wasClaimed ? IMPLICIT_CONSUMED : IMPLICIT_PASS;

            default:
                return IMPLICIT_PASS;
        }
    }

    public void resetImplicitGesture() {
        implicitGestureState = GESTURE_IDLE;
    }

    // Toggles between fit-to-screen and a fixed zoom level anchored at the
    // given point in parent coordinates
    public void smartZoomToggle(float focusX, float focusY) {
        if (scaleFactor > MIN_SCALE + 0.01f) {
            reset();
            return;
        }

        float x = focusX + (streamView.getX() - focusX) / scaleFactor * SMART_ZOOM_SCALE;
        float y = focusY + (streamView.getY() - focusY) / scaleFactor * SMART_ZOOM_SCALE;

        scaleFactor = SMART_ZOOM_SCALE;
        streamView.setScaleX(scaleFactor);
        streamView.setScaleY(scaleFactor);
        streamView.setX(x);
        streamView.setY(y);
        constrainToBounds();
    }

    // Returns the center of the visible viewport in content fractions (0..1),
    // for persisting the view across sessions
    public float[] getViewportCenter() {
        View parent = (View) streamView.getParent();
        if (parent == null || streamView.getWidth() == 0) {
            return new float[] { 0.5f, 0.5f };
        }

        float viewportCenterX = (parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight()) / 2.f;
        float viewportCenterY = (parent.getHeight() - parent.getPaddingTop() - parent.getPaddingBottom()) / 2.f;
        float cx = (viewportCenterX - streamView.getX()) / scaleFactor / streamView.getWidth();
        float cy = (viewportCenterY - streamView.getY()) / scaleFactor / streamView.getHeight();
        return new float[] { cx, cy };
    }

    public void applyViewState(float scale, float centerXFraction, float centerYFraction) {
        View parent = (View) streamView.getParent();
        if (parent == null || streamView.getWidth() == 0) {
            return;
        }

        scaleFactor = Math.max(MIN_SCALE, Math.min(scale, MAX_SCALE));
        streamView.setScaleX(scaleFactor);
        streamView.setScaleY(scaleFactor);

        float viewportCenterX = (parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight()) / 2.f;
        float viewportCenterY = (parent.getHeight() - parent.getPaddingTop() - parent.getPaddingBottom()) / 2.f;
        streamView.setX(viewportCenterX - centerXFraction * streamView.getWidth() * scaleFactor);
        streamView.setY(viewportCenterY - centerYFraction * streamView.getHeight() * scaleFactor);

        constrainToBounds();
    }

    private void primeImplicitPinch(MotionEvent event) {
        implicitPrevSpan = pointerSpan(event);
        implicitPrevFocusX = pointerFocusX(event);
        implicitPrevFocusY = pointerFocusY(event);
    }

    private void continueImplicitPinch(MotionEvent event) {
        float span = pointerSpan(event);
        float focusX = pointerFocusX(event);
        float focusY = pointerFocusY(event);

        if (implicitPrevSpan > 0) {
            float newScaleFactor = Math.max(MIN_SCALE,
                    Math.min(scaleFactor * (span / implicitPrevSpan), MAX_SCALE));

            // Anchor the zoom at the gesture's focal point, then pan with it
            float x = focusX + (streamView.getX() - focusX) / scaleFactor * newScaleFactor;
            float y = focusY + (streamView.getY() - focusY) / scaleFactor * newScaleFactor;

            scaleFactor = newScaleFactor;

            streamView.setScaleX(scaleFactor);
            streamView.setScaleY(scaleFactor);
            streamView.setX(x + (focusX - implicitPrevFocusX));
            streamView.setY(y + (focusY - implicitPrevFocusY));

            constrainToBounds();
        }

        implicitPrevSpan = span;
        implicitPrevFocusX = focusX;
        implicitPrevFocusY = focusY;
    }

    private static float pointerSpan(MotionEvent event) {
        return (float) Math.hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0));
    }

    private static float pointerFocusX(MotionEvent event) {
        return (event.getX(0) + event.getX(1)) / 2;
    }

    private static float pointerFocusY(MotionEvent event) {
        return (event.getY(0) + event.getY(1)) / 2;
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

        float viewportWidth = parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight();
        float viewportHeight = parent.getHeight() - parent.getPaddingTop() - parent.getPaddingBottom();

        float screenX = streamView.getX() + streamX * scaleFactor;
        float screenY = streamView.getY() + streamY * scaleFactor;

        float dx = 0, dy = 0;
        if (screenX < followCursorMargin) {
            dx = followCursorMargin - screenX;
        }
        else if (screenX > viewportWidth - followCursorMargin) {
            dx = viewportWidth - followCursorMargin - screenX;
        }
        if (screenY < followCursorMargin) {
            dy = followCursorMargin - screenY;
        }
        else if (screenY > viewportHeight - followCursorMargin) {
            dy = viewportHeight - followCursorMargin - screenY;
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

        // Padding on the parent shrinks the usable viewport (e.g. to keep the
        // stream clear of the soft keyboard)
        float parentWidth = parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight();
        float parentHeight = parent.getHeight() - parent.getPaddingTop() - parent.getPaddingBottom();

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
