package com.limelight.binding.input.touch;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;

import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.MouseButtonPacket;
import com.limelight.preferences.PreferenceConfiguration;

public class RelativeTouchContext implements TouchContext {
    static private int INVALID_POINTER_ID = 100;
    static private int touchPointerIdLockedForMouse = INVALID_POINTER_ID;
    static private boolean touchPointerLockedForMouseMoved = false;
    static private boolean quickTapDetected = false;
    static private long touchDownTimestamp = 0;
    static private boolean isScrolling;

    private int pointerId;
    private int latestTouchX = 0;
    private int latestTouchY = 0;
    private int firstTouchX = 0;
    private int firstTouchY = 0;
    private boolean cancelled;
    private boolean confirmedMove;
    private boolean confirmedDrag;
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

            // We haven't been cancelled before the timer expired so begin dragging
            confirmedDrag = true;
            conn.sendMouseButtonDown(getMouseButtonIndex());
        }
    };

    private final Runnable sendMouseLeftButtonClick = new Runnable() {
        @Override
        public void run() {
            conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
            Runnable sendMouseLeftButtonUp = new Runnable() {
                @Override
                public void run() {
                    conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
                }
            };
            handler.postDelayed(sendMouseLeftButtonUp, 50);
        }
    };

    private final Runnable sendMouseRightButtonClick = new Runnable() {
        @Override
        public void run() {
            conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_RIGHT);
            Runnable sendMouseLeftButtonUp = new Runnable() {
                @Override
                public void run() {
                    conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_RIGHT);
                }
            };
            handler.postDelayed(sendMouseLeftButtonUp, 50);
        }
    };


    private final Runnable sendMouseLeftButtonUpOrKeepDragging = new Runnable() {
        @Override
        public void run() {
            Log.d("double quick tap", "quickTapDetected: " + quickTapDetected);
            if(!quickTapDetected) conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
        }
    };

    private static final int TAP_MOVEMENT_THRESHOLD = 100;
    private static final int TAP_DISTANCE_THRESHOLD = 25;
    private static final int QUICK_TAP_TIME_INTERVAL = 200;
    private static final int SCROLL_SPEED_FACTOR = 5;

    public RelativeTouchContext(NvConnection conn, int actionIndex,
                                int referenceWidth, int referenceHeight,
                                View view, PreferenceConfiguration prefConfig)
    {
        this.conn = conn;
        this.actionIndex = actionIndex;
        this.referenceWidth = referenceWidth;
        this.referenceHeight = referenceHeight;
        this.targetView = view;
        this.prefConfig = prefConfig;
        this.handler = new Handler(Looper.getMainLooper());
    }

    @Override
    public int getActionIndex()
    {
        return actionIndex;
    }

    private boolean isWithinTapBounds(int touchX, int touchY)
    {
        int xDelta = Math.abs(touchX - latestTouchX);
        int yDelta = Math.abs(touchY - latestTouchY);
        return xDelta <= TAP_MOVEMENT_THRESHOLD &&
                yDelta <= TAP_MOVEMENT_THRESHOLD;
    }

    private boolean isQuickTap(long eventTime, int eventX, int eventY){
        // Log.d("double tap test", "tap interval: " + (eventTime - touchDownTimestamp));
        return eventTime - touchDownTimestamp < QUICK_TAP_TIME_INTERVAL && isWithinTapBounds(eventX,eventY);
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
    public boolean touchDownEvent(int pointerId, int eventX, int eventY, long eventTime, boolean isNewFinger, boolean isFirstFinger)
    {
        // Get the view dimensions to scale inputs on this touch
        xFactor = referenceWidth / (double)targetView.getWidth();
        yFactor = referenceHeight / (double)targetView.getHeight();


        this.pointerId = pointerId;

        if(isFirstFinger){
            isScrolling = false;
            touchPointerIdLockedForMouse = this.pointerId;
        }
        quickTapDetected = isQuickTap(eventTime, eventX, eventY);
        touchDownTimestamp = eventTime;
        latestTouchX = eventX;
        latestTouchY = eventY;

        return true;
    }

    @Override
    public void touchUpEvent(int eventX, int eventY, long eventTime)
    {
        // handling a single tap: whether the left button will be released, will be decided in sendMouseLeftButtonUpOrKeepDragging
        if(!touchPointerLockedForMouseMoved && !quickTapDetected && !isScrolling) {
            conn.sendMouseButtonDown(MouseButtonPacket.BUTTON_LEFT);
            handler.postDelayed(sendMouseLeftButtonUpOrKeepDragging, QUICK_TAP_TIME_INTERVAL);
        }

        // handling the second tap following the first tap after a very short interval:
        // in this case we must release the left button anyway, then consider whether to compensate another click.
        if(quickTapDetected){
            conn.sendMouseButtonUp(MouseButtonPacket.BUTTON_LEFT);
            if(!touchPointerLockedForMouseMoved) handler.postDelayed(sendMouseLeftButtonClick, 50);
            quickTapDetected = false;
        }

        if(pointerCount == 2 && !isScrolling){
            handler.postDelayed(sendMouseRightButtonClick,0);
        }

        if(this.pointerId == touchPointerIdLockedForMouse){
            touchPointerIdLockedForMouse = INVALID_POINTER_ID;
            touchPointerLockedForMouseMoved = false;
        }
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
        distanceMoved += Math.sqrt(Math.pow(eventX - latestTouchX, 2) + Math.pow(eventY - latestTouchY, 2));
        if (distanceMoved >= TAP_DISTANCE_THRESHOLD) {
            confirmedMove = true;
            cancelDragTimer();
        }
    }

    @Override
    public boolean touchMoveEvent(int pointerId, int eventX, int eventY, long eventTime)
    {
        if(pointerCount >= 3) return true;

        if(pointerCount == 2) isScrolling = true;

        if (eventX != latestTouchX || eventY != latestTouchY)
        {
            checkForConfirmedMove(eventX, eventY);

            // We only send moves and drags for the primary touch point
            if (touchPointerIdLockedForMouse == this.pointerId) {

                touchPointerLockedForMouseMoved = true;
                Log.d("mouse moved", "mouse moved");

                int deltaX = eventX - latestTouchX;
                int deltaY = eventY - latestTouchY;

                // Scale the deltas based on the factors passed to our constructor
                deltaX = (int) Math.round((double) Math.abs(deltaX) * xFactor);
                deltaY = (int) Math.round((double) Math.abs(deltaY) * yFactor);

                // Fix up the signs
                if (eventX < latestTouchX) {
                    deltaX = -deltaX;
                }
                if (eventY < latestTouchY) {
                    deltaY = -deltaY;
                }

                if (pointerCount == 2) {
                    if (isScrolling) {
                        conn.sendMouseHighResScroll((short)(deltaY * SCROLL_SPEED_FACTOR));
                    }
                } else {
                    if (prefConfig.absoluteMouseMode) {
                        conn.sendMouseMoveAsMousePosition(
                                (short) deltaX,
                                (short) deltaY,
                                (short) targetView.getWidth(),
                                (short) targetView.getHeight());
                    }
                    else {
                        conn.sendMouseMove((short) deltaX, (short) deltaY);
                    }
                }

                // If the scaling factor ended up rounding deltas to zero, wait until they are
                // non-zero to update lastTouch that way devices that report small touch events often
                // will work correctly
                if (deltaX != 0) {
                    latestTouchX = eventX;
                }
                if (deltaY != 0) {
                    latestTouchY = eventY;
                }
            }
            else {
                latestTouchX = eventX;
                latestTouchY = eventY;
            }
        }

        return true;
    }

    @Override
    public void cancelTouch() {
        // If it was a confirmed drag, we'll need to raise the button now
        if (quickTapDetected) {
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
