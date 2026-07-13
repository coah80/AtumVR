package me.phoenixra.atumvr.core.session.platform;

public interface AndroidXRBridge {
    long getEGLDisplay();
    long getEGLContext();
    long getEGLConfig();
    long getDalvikVM();
    long getDalvikActivity();
    void setupAndroid();
}
