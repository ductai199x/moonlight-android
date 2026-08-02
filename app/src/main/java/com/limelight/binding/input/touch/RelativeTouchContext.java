package com.limelight.binding.input.touch;

import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.View;

import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.MouseButtonPacket;
import com.limelight.preferences.PreferenceConfiguration;

public class RelativeTouchContext implements TouchContext {
    public interface CursorMotionListener {
        // Reports cursor motion in StreamView-local pixels. This mirrors the
        // deltas sent to the host, so it is exact in absolute mouse mode and a
        // best-effort estimate otherwise (the host may apply pointer
        // acceleration to relative motion).
        void onRelativeCursorMoved(float deltaX, float deltaY);
    }

    private int lastTouchX = 0;
    private int lastTouchY = 0;
    private int originalTouchX = 0;
    private int originalTouchY = 0;
    private long originalTouchTime = 0;
    private boolean cancelled;
    private boolean confirmedMove;
    private boolean confirmedDrag;
    private boolean confirmedScroll;
    private double distanceMoved;
    private double xFactor, yFactor;
    private int pointerCount;
    private int maxPointerCountInGesture;

    private final NvConnection conn;
    private final int actionIndex;
    private final int referenceWidth;
    private final int referenceHeight;
    private final View targetView;
    private final PreferenceConfiguration prefConfig;
    private final Handler handler;
    private final CursorMotionListener cursorMotionListener;

    private final Runnable dragTimerRunnable = new Runnable() {
        @Override
        public void run() {
            // Check if someone already set move
            if (confirmedMove) {
                return;
            }

            // The drag should only be processed for the primary finger
            if (actionIndex != maxPointerCountInGesture - 1) {
                return;
            }

            if (prefConfig.enablePanZoom) {
                // In desktop mode, pressing and holding in place means a right
                // click, sent on release so the context menu appears where the
                // finger lifts
                confirmedHoldRightClick = true;
                targetView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                return;
            }

            // We haven't been cancelled before the timer expired so begin dragging
            confirmedDrag = true;
            conn.sendMouseButtonDown(getMouseButtonIndex());
        }
    };

    // Indexed by MouseButtonPacket.BUTTON_XXX - 1
    private final Runnable[] buttonUpRunnables = new Runnable[] {
            new Runnable() {
                @Override
                public void run() {
                    conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
                }
            },
            new Runnable() {
                @Override
                public void run() {
                    conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_MIDDLE);
                }
            },
            new Runnable() {
                @Override
                public void run() {
                    conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
                }
            },
            new Runnable() {
                @Override
                public void run() {
                    conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_X1);
                }
            },
            new Runnable() {
                @Override
                public void run() {
                    conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_X2);
                }
            }
    };

    private static final int TAP_MOVEMENT_THRESHOLD = 20;
    private static final int TAP_DISTANCE_THRESHOLD = 25;
    private static final int TAP_TIME_THRESHOLD = 250;
    private static final int DRAG_TIME_THRESHOLD = 650;

    private static final int SCROLL_SPEED_FACTOR = 5;

    // Cursor and scroll speed multipliers while the desktop zoom/pan
    // preference is active
    private static final float DESKTOP_CURSOR_SPEED_FACTOR = 0.4f;
    private static final float DESKTOP_SCROLL_SPEED_FACTOR = 0.57f;

    private float cursorSpeedCarryX, cursorSpeedCarryY;
    private Runnable pendingClickRunnable;
    private boolean confirmedHoldRightClick;
    private long lastLeftTapTime;

    private static final int DOUBLE_TAP_DRAG_TIME_THRESHOLD = 300;

    public RelativeTouchContext(NvConnection conn, int actionIndex,
                                int referenceWidth, int referenceHeight,
                                View view, PreferenceConfiguration prefConfig,
                                CursorMotionListener cursorMotionListener)
    {
        this.conn = conn;
        this.actionIndex = actionIndex;
        this.referenceWidth = referenceWidth;
        this.referenceHeight = referenceHeight;
        this.targetView = view;
        this.prefConfig = prefConfig;
        this.handler = new Handler(Looper.getMainLooper());
        this.cursorMotionListener = cursorMotionListener;
    }

    @Override
    public int getActionIndex()
    {
        return actionIndex;
    }

    private boolean isWithinTapBounds(int touchX, int touchY)
    {
        int xDelta = Math.abs(touchX - originalTouchX);
        int yDelta = Math.abs(touchY - originalTouchY);
        return xDelta <= TAP_MOVEMENT_THRESHOLD &&
                yDelta <= TAP_MOVEMENT_THRESHOLD;
    }

    private boolean isTap(long eventTime)
    {
        if (confirmedDrag || confirmedMove || confirmedScroll) {
            return false;
        }

        // If this input wasn't the last finger down, do not report
        // a tap. This ensures we don't report duplicate taps for each
        // finger on a multi-finger tap gesture
        if (actionIndex + 1 != maxPointerCountInGesture) {
            return false;
        }

        long timeDelta = eventTime - originalTouchTime;
        return isWithinTapBounds(lastTouchX, lastTouchY) && timeDelta <= TAP_TIME_THRESHOLD;
    }

    private byte getMouseButtonIndex()
    {
        if (actionIndex == 1) {
            return MouseButtonPacket.BUTTON_RIGHT;
        }
        else {
            return MouseButtonPacket.BUTTON_LEFT;
        }
    }

    @Override
    public boolean touchDownEvent(int eventX, int eventY, long eventTime, boolean isNewFinger)
    {
        // Get the view dimensions to scale inputs on this touch
        xFactor = referenceWidth / (double)targetView.getWidth();
        yFactor = referenceHeight / (double)targetView.getHeight();

        originalTouchX = lastTouchX = eventX;
        originalTouchY = lastTouchY = eventY;

        if (isNewFinger) {
            maxPointerCountInGesture = pointerCount;
            originalTouchTime = eventTime;
            cancelled = confirmedDrag = confirmedMove = confirmedScroll = false;
            confirmedHoldRightClick = false;
            distanceMoved = 0;
            cursorSpeedCarryX = cursorSpeedCarryY = 0;

            if (actionIndex == 0) {
                if (prefConfig.enablePanZoom && pointerCount == 1 &&
                        eventTime - lastLeftTapTime < DOUBLE_TAP_DRAG_TIME_THRESHOLD) {
                    // Tap then press again quickly: hold the left button down so
                    // moving the cursor drags or selects. A quick release without
                    // movement still registers as the second click of a double
                    // click.
                    handler.removeCallbacks(buttonUpRunnables[MouseButtonPacket.BUTTON_LEFT - 1]);
                    conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
                    confirmedDrag = true;
                }
                else {
                    // Start the timer for engaging a drag
                    startDragTimer();
                }
            }
        }

        return true;
    }

    @Override
    public void touchUpEvent(int eventX, int eventY, long eventTime)
    {
        if (cancelled) {
            return;
        }

        // Cancel the drag timer
        cancelDragTimer();

        byte buttonIndex = getMouseButtonIndex();

        if (confirmedDrag) {
            // Raise the button after a drag
            conn.sendMouseButtonUp(buttonIndex);
        }
        else if (confirmedHoldRightClick) {
            if (!confirmedMove) {
                // Send the right click now that the finger has lifted, so the
                // context menu opens under the cursor
                sendTapClick(MouseButtonPacket.BUTTON_RIGHT);
            }
        }
        else if (isTap(eventTime))
        {
            if (buttonIndex == MouseButtonPacket.BUTTON_RIGHT && prefConfig.enablePanZoom) {
                // Delay two-finger right clicks briefly so a second two-finger
                // tap can become a smart zoom instead (cancelled in cancelTouch)
                pendingClickRunnable = new Runnable() {
                    @Override
                    public void run() {
                        pendingClickRunnable = null;
                        sendTapClick(MouseButtonPacket.BUTTON_RIGHT);
                    }
                };
                handler.postDelayed(pendingClickRunnable, 350);
            }
            else {
                sendTapClick(buttonIndex);

                if (buttonIndex == MouseButtonPacket.BUTTON_LEFT) {
                    // Remembered so a quick second press can become a drag
                    lastLeftTapTime = eventTime;
                }
            }
        }
    }

    private void sendTapClick(byte buttonIndex) {
        // Lower the mouse button
        conn.sendMouseButtonDown(buttonIndex);

        // Release the mouse button in 100ms to allow for apps that use polling
        // to detect mouse button presses.
        Runnable buttonUpRunnable = buttonUpRunnables[buttonIndex - 1];
        handler.removeCallbacks(buttonUpRunnable);
        handler.postDelayed(buttonUpRunnable, 100);
    }

    private void startDragTimer() {
        cancelDragTimer();
        handler.postDelayed(dragTimerRunnable, DRAG_TIME_THRESHOLD);
    }

    private void cancelDragTimer() {
        handler.removeCallbacks(dragTimerRunnable);
    }

    private void checkForConfirmedMove(int eventX, int eventY) {
        // If we've already confirmed something, get out now
        if (confirmedMove || confirmedDrag) {
            return;
        }

        // If it leaves the tap bounds before the drag time expires, it's a move.
        if (!isWithinTapBounds(eventX, eventY)) {
            confirmedMove = true;
            cancelDragTimer();
            return;
        }

        // Check if we've exceeded the maximum distance moved
        distanceMoved += Math.sqrt(Math.pow(eventX - lastTouchX, 2) + Math.pow(eventY - lastTouchY, 2));
        if (distanceMoved >= TAP_DISTANCE_THRESHOLD) {
            confirmedMove = true;
            cancelDragTimer();
            return;
        }
    }

    private void checkForConfirmedScroll() {
        // Enter scrolling mode if we've already left the tap zone
        // and we have 2 fingers on screen. Leave scroll mode if
        // we no longer have 2 fingers on screen
        confirmedScroll = (actionIndex == 0 && pointerCount == 2 && confirmedMove);
    }

    @Override
    public boolean touchMoveEvent(int eventX, int eventY, long eventTime)
    {
        if (cancelled) {
            return true;
        }

        if (eventX != lastTouchX || eventY != lastTouchY)
        {
            checkForConfirmedMove(eventX, eventY);
            checkForConfirmedScroll();

            // We only send moves and drags for the primary touch point
            if (actionIndex == 0) {
                int deltaX = eventX - lastTouchX;
                int deltaY = eventY - lastTouchY;

                // Scale the deltas based on the factors passed to our constructor
                deltaX = (int) Math.round((double) Math.abs(deltaX) * xFactor);
                deltaY = (int) Math.round((double) Math.abs(deltaY) * yFactor);

                // Fix up the signs
                if (eventX < lastTouchX) {
                    deltaX = -deltaX;
                }
                if (eventY < lastTouchY) {
                    deltaY = -deltaY;
                }

                // The pre-scaling deltas decide whether lastTouch advances, so
                // the sub-pixel carry below never double-counts movement
                int rawDeltaX = deltaX;
                int rawDeltaY = deltaY;

                if (pointerCount == 2) {
                    if (confirmedScroll) {
                        int scrollDelta = deltaY * SCROLL_SPEED_FACTOR;
                        if (prefConfig.enablePanZoom) {
                            // Slow scrolling down for finer control in desktop mode
                            scrollDelta = (int) (scrollDelta * DESKTOP_SCROLL_SPEED_FACTOR);
                        }
                        if (prefConfig.invertScrollDirection) {
                            scrollDelta = -scrollDelta;
                        }
                        conn.sendMouseHighResScroll((short) scrollDelta);
                    }
                } else {
                    // Slow the cursor down for finer control in desktop mode,
                    // carrying sub-pixel remainders so slow motion isn't lost
                    if (prefConfig.enablePanZoom) {
                        float scaledX = deltaX * DESKTOP_CURSOR_SPEED_FACTOR + cursorSpeedCarryX;
                        float scaledY = deltaY * DESKTOP_CURSOR_SPEED_FACTOR + cursorSpeedCarryY;
                        deltaX = (int) scaledX;
                        deltaY = (int) scaledY;
                        cursorSpeedCarryX = scaledX - deltaX;
                        cursorSpeedCarryY = scaledY - deltaY;
                    }

                    if (prefConfig.absoluteMouseMode) {
                        conn.sendMouseMoveAsMousePosition(
                                (short) deltaX,
                                (short) deltaY,
                                (short) targetView.getWidth(),
                                (short) targetView.getHeight());

                        if (cursorMotionListener != null) {
                            // Deltas are accumulated by the host as-is in this mode
                            cursorMotionListener.onRelativeCursorMoved(deltaX, deltaY);
                        }
                    }
                    else {
                        conn.sendMouseMove((short) deltaX, (short) deltaY);

                        if (cursorMotionListener != null) {
                            // Convert the reference-resolution deltas back to view pixels
                            cursorMotionListener.onRelativeCursorMoved(
                                    (float) (deltaX / xFactor),
                                    (float) (deltaY / yFactor));
                        }
                    }
                }

                // If the scaling factor ended up rounding deltas to zero, wait until they are
                // non-zero to update lastTouch that way devices that report small touch events often
                // will work correctly
                if (rawDeltaX != 0) {
                    lastTouchX = eventX;
                }
                if (rawDeltaY != 0) {
                    lastTouchY = eventY;
                }
            }
            else {
                lastTouchX = eventX;
                lastTouchY = eventY;
            }
        }

        return true;
    }

    @Override
    public void cancelTouch() {
        cancelled = true;

        // Cancel the drag timer
        cancelDragTimer();

        // Cancel any delayed right click that hasn't fired yet
        if (pendingClickRunnable != null) {
            handler.removeCallbacks(pendingClickRunnable);
            pendingClickRunnable = null;
        }

        // If it was a confirmed drag, we'll need to raise the button now
        if (confirmedDrag) {
            conn.sendMouseButtonUp(getMouseButtonIndex());
        }
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setPointerCount(int pointerCount) {
        this.pointerCount = pointerCount;

        if (pointerCount > maxPointerCountInGesture) {
            maxPointerCountInGesture = pointerCount;
        }
    }
}
