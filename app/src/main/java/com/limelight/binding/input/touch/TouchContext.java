package com.limelight.binding.input.touch;

public interface TouchContext {
    int getActionIndex();
    void setPointerCount(int pointerCount);
    boolean touchDownEvent(int pointerId, int eventX, int eventY, long eventTime, boolean isNewFinger, boolean isFirstFinger);
    boolean touchMoveEvent(int pointerId, int eventX, int eventY, long eventTime);
    void touchUpEvent(int eventX, int eventY, long eventTime);
    void cancelTouch();
    boolean isCancelled();
}
