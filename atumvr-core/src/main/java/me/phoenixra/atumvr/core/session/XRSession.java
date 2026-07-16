package me.phoenixra.atumvr.core.session;

import lombok.Getter;
import me.phoenixra.atumvr.api.AtumVRSession;
import me.phoenixra.atumvr.core.utils.XRUtils;
import me.phoenixra.atumvr.core.XRProvider;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.PointerBuffer;
import org.lwjgl.openxr.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Struct;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * XR session handler (low-level OpenXR stuff)
 */
public class XRSession implements AtumVRSession {
    private final XRProvider vrProvider;

    @Getter
    protected XrSession handle;

    @Getter
    protected XrSpace xrAppSpace;

    @Getter
    protected int xrAppSpaceType;

    @Getter
    protected XrSpace xrViewSpace;



    @Getter
    protected XRInstance instance;

    @Getter
    protected XRSwapChain swapChain;

    @Getter
    protected XRSystem system;

    @Getter
    protected float requestedDisplayRefreshRate = Float.NaN;

    @Getter
    protected float currentDisplayRefreshRate = Float.NaN;

    private long nextDisplayRefreshRateCheckNanos = Long.MAX_VALUE;
    private int displayRefreshRateRequestCount;

    public XRSession(@NotNull XRProvider vrProvider){
        this.vrProvider = vrProvider;
        instance = new XRInstance(vrProvider);
        system = new XRSystem(vrProvider);
        swapChain = new XRSwapChain(vrProvider);
    }


    public void init() {
        instance.init();
        system.init();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            initSession(stack);
            initSpaces(stack);
            initDisplayRefreshRate(stack);
            initPerformanceLevels();
            initColorSpace();
        }
        swapChain.init();
    }


    private void initSession(MemoryStack stack){

        long systemId = system.getSystemId();

        Struct<?> graphicsBind = system.createGraphicsBinding(
                stack,
                instance.getHandle(),
                systemId,
                vrProvider.getRenderer().getWindowHandle()
        );
        var sessionInfo = XrSessionCreateInfo.calloc(stack)
                .type(XR10.XR_TYPE_SESSION_CREATE_INFO)
                .next(graphicsBind.address())
                .systemId(systemId)
                .createFlags(0);

        PointerBuffer sessionBuf = stack.callocPointer(1);
        vrProvider.checkXRError(
                XR10.xrCreateSession(instance.getHandle(), sessionInfo, sessionBuf),
                "xrCreateSession", "Failed to create session"
        );
        handle = new XrSession(sessionBuf.get(0), instance.getHandle());

    }

    private void initSpaces(MemoryStack stack) {
        // Identity pose for all reference spaces
        XrPosef identity = XrPosef.calloc(stack)
                .set(
                        XrQuaternionf.calloc(stack).set(0, 0, 0, 1),
                        XrVector3f.calloc(stack).set(0f, 0f, 0f)
                );

        xrAppSpaceType = handle.getCapabilities().XR_EXT_local_floor
                ? EXTLocalFloor.XR_REFERENCE_SPACE_TYPE_LOCAL_FLOOR_EXT
                : XR10.XR_REFERENCE_SPACE_TYPE_STAGE;

        xrAppSpace  = XRUtils.createReferenceSpace(
                vrProvider,
                xrAppSpaceType,
                identity,
                stack
        );
        vrProvider.getLogger().logInfo(
                "Application reference space: "
                        + (xrAppSpaceType == EXTLocalFloor.XR_REFERENCE_SPACE_TYPE_LOCAL_FLOOR_EXT
                        ? "LOCAL_FLOOR_EXT" : "STAGE")
        );

        identity = XrPosef.calloc(stack)
                .set(
                        XrQuaternionf.calloc(stack).set(0, 0, 0, 1),
                        XrVector3f.calloc(stack).set(0f, 0f, 0f)
                );
        xrViewSpace = XRUtils.createReferenceSpace(
                vrProvider,
                XR10.XR_REFERENCE_SPACE_TYPE_VIEW,
                identity,
                stack
        );
    }



    private void initDisplayRefreshRate(MemoryStack stack) {
        float preferred = vrProvider.getPreferredRefreshRate();
        if (preferred <= 0f || !handle.getCapabilities().XR_FB_display_refresh_rate) {
            return;
        }
        IntBuffer refreshRateCount = stack.callocInt(1);
        vrProvider.checkXRError(
                FBDisplayRefreshRate.xrEnumerateDisplayRefreshRatesFB(
                        handle, refreshRateCount, null
                ),
                "xrEnumerateDisplayRefreshRatesFB",
                "first call"
        );
        FloatBuffer refreshRateBuffer = stack.callocFloat(refreshRateCount.get(0));
        vrProvider.checkXRError(
                FBDisplayRefreshRate.xrEnumerateDisplayRefreshRatesFB(
                        handle, refreshRateCount, refreshRateBuffer
                ),
                "xrEnumerateDisplayRefreshRatesFB",
                "second call"
        );

        float chosen = Float.NaN;
        for (int i = 0; i < refreshRateCount.get(0); i++) {
            float rate = refreshRateBuffer.get(i);
            if (Float.isNaN(chosen)
                    || Math.abs(rate - preferred) < Math.abs(chosen - preferred)
                    || (Math.abs(rate - preferred) == Math.abs(chosen - preferred) && rate < chosen)) {
                chosen = rate;
            }
        }
        if (Float.isNaN(chosen)) {
            return;
        }
        requestedDisplayRefreshRate = chosen;
        displayRefreshRateRequestCount = 0;
        nextDisplayRefreshRateCheckNanos = 0L;
        StringBuilder supported = new StringBuilder();
        for (int i = 0; i < refreshRateCount.get(0); i++) {
            if (i > 0) {
                supported.append(", ");
            }
            supported.append(refreshRateBuffer.get(i));
        }
        vrProvider.getLogger().logInfo(
                "Supported display refresh rates: [" + supported + "] Hz; selected "
                        + chosen + " Hz (preferred " + preferred + " Hz)"
        );
        updateCurrentDisplayRefreshRate(stack);
    }

    public void requestPreferredDisplayRefreshRate() {
        if (Float.isNaN(requestedDisplayRefreshRate)
                || handle == null
                || !handle.getCapabilities().XR_FB_display_refresh_rate) {
            return;
        }
        vrProvider.checkXRError(
                FBDisplayRefreshRate.xrRequestDisplayRefreshRateFB(handle, requestedDisplayRefreshRate),
                "xrRequestDisplayRefreshRateFB"
        );
        try (MemoryStack stack = MemoryStack.stackPush()) {
            updateCurrentDisplayRefreshRate(stack);
        }
        displayRefreshRateRequestCount++;
        nextDisplayRefreshRateCheckNanos = System.nanoTime()
                + (displayRefreshRateRequestCount < 8 ? 1_000_000_000L : 30_000_000_000L);
        vrProvider.getLogger().logInfo(
                "Display refresh request submitted: attempt=" + displayRefreshRateRequestCount
                        + ", requested=" + requestedDisplayRefreshRate
                        + " Hz, current=" + currentDisplayRefreshRate + " Hz"
        );
    }

    public void maintainPreferredDisplayRefreshRate(boolean focused) {
        if (!focused
                || Float.isNaN(requestedDisplayRefreshRate)
                || System.nanoTime() < nextDisplayRefreshRateCheckNanos) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            updateCurrentDisplayRefreshRate(stack);
        }
        if (Math.abs(currentDisplayRefreshRate - requestedDisplayRefreshRate) <= 0.1f) {
            nextDisplayRefreshRateCheckNanos = Long.MAX_VALUE;
            vrProvider.getLogger().logInfo(
                    "Display refresh rate confirmed by readback: "
                            + currentDisplayRefreshRate + " Hz"
            );
            return;
        }
        requestPreferredDisplayRefreshRate();
    }

    public void onDisplayRefreshRateChanged(float from, float to) {
        currentDisplayRefreshRate = to;
        nextDisplayRefreshRateCheckNanos = Math.abs(to - requestedDisplayRefreshRate) <= 0.1f
                ? Long.MAX_VALUE
                : System.nanoTime() + 1_000_000_000L;
        vrProvider.getLogger().logInfo(
                "Display refresh rate changed: " + from + " Hz -> " + to + " Hz"
        );
    }

    private void updateCurrentDisplayRefreshRate(MemoryStack stack) {
        FloatBuffer current = stack.callocFloat(1);
        vrProvider.checkXRError(
                FBDisplayRefreshRate.xrGetDisplayRefreshRateFB(handle, current),
                "xrGetDisplayRefreshRateFB"
        );
        currentDisplayRefreshRate = current.get(0);
    }

    private void initPerformanceLevels() {
        if (!handle.getCapabilities().XR_EXT_performance_settings) {
            return;
        }
        vrProvider.checkXRError(
                EXTPerformanceSettings.xrPerfSettingsSetPerformanceLevelEXT(
                        handle,
                        EXTPerformanceSettings.XR_PERF_SETTINGS_DOMAIN_CPU_EXT,
                        EXTPerformanceSettings.XR_PERF_SETTINGS_LEVEL_SUSTAINED_HIGH_EXT
                ),
                "xrPerfSettingsSetPerformanceLevelEXT", "CPU"
        );
        vrProvider.checkXRError(
                EXTPerformanceSettings.xrPerfSettingsSetPerformanceLevelEXT(
                        handle,
                        EXTPerformanceSettings.XR_PERF_SETTINGS_DOMAIN_GPU_EXT,
                        EXTPerformanceSettings.XR_PERF_SETTINGS_LEVEL_SUSTAINED_HIGH_EXT
                ),
                "xrPerfSettingsSetPerformanceLevelEXT", "GPU"
        );
        vrProvider.getLogger().logInfo("CPU/GPU performance levels set to SUSTAINED_HIGH");
    }

    private void initColorSpace() {
        if (!handle.getCapabilities().XR_FB_color_space) {
            return;
        }
        vrProvider.checkXRError(
                FBColorSpace.xrSetColorSpaceFB(handle, FBColorSpace.XR_COLOR_SPACE_REC709_FB),
                "xrSetColorSpaceFB", "REC709"
        );
        vrProvider.getLogger().logInfo("Color space set to REC709");
    }

    public void destroy(){

        requestExitAndDrain();

        try {
            swapChain.destroy();
        } catch (Throwable ignored) {}

        destroySpaceQuietly(this.xrAppSpace, "xrAppSpace");
        this.xrAppSpace = null;

        destroySpaceQuietly(this.xrViewSpace, "xrViewSpace");
        this.xrViewSpace = null;

        if (this.handle != null) {
            try {
                vrProvider.checkXRError(
                        false,
                        XR10.xrDestroySession(this.handle),
                        "xrDestroySession",
                        "session"
                );
            } catch (Throwable ignored) {}
            this.handle = null;
        }

        try { system.destroy(); }   catch (Throwable ignored) {}
        try { instance.destroy(); } catch (Throwable ignored) {}
    }

    private void destroySpaceQuietly(XrSpace space, String label) {
        if (space == null) return;
        try {
            vrProvider.checkXRError(
                    false,
                    XR10.xrDestroySpace(space),
                    "xrDestroySpace",
                    label
            );
        } catch (Throwable ignored) {}
    }


    private void requestExitAndDrain() {
        if (handle == null || instance == null || instance.getHandle() == null) {
            return;
        }
        try {
            XR10.xrRequestExitSession(handle);
        } catch (Throwable ignored) {
            // Session may already be in IDLE / EXITING — that's fine.
        }

        XrEventDataBuffer eventBuffer = instance.getXrEventBuffer();
        boolean ended = false;
        long deadline = System.currentTimeMillis() + 500L;

        while (System.currentTimeMillis() < deadline) {
            eventBuffer.clear();
            eventBuffer.type(XR10.XR_TYPE_EVENT_DATA_BUFFER);

            int err;
            try {
                err = XR10.xrPollEvent(instance.getHandle(), eventBuffer);
            } catch (Throwable t) {
                break;
            }
            if (err != XR10.XR_SUCCESS) {
                try {
                    Thread.sleep(2);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }

            var header = XrEventDataBaseHeader.create(eventBuffer.address());
            if (header.type() != XR10.XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
                continue;
            }

            var ev = XrEventDataSessionStateChanged.create(header.address());
            int state = ev.state();

            if (!ended && state == XR10.XR_SESSION_STATE_STOPPING) {
                try {
                    XR10.xrEndSession(handle);
                } catch (Throwable ignored) {}
                ended = true;
            }
            if (state == XR10.XR_SESSION_STATE_EXITING
                    || state == XR10.XR_SESSION_STATE_LOSS_PENDING
                    || state == XR10.XR_SESSION_STATE_IDLE) {
                // Safe to destroy from here.
                if (!ended) {
                    // STOPPING may have been missed (already in IDLE/EXITING).
                    try { XR10.xrEndSession(handle); } catch (Throwable ignored) {}
                    ended = true;
                }
                break;
            }
        }
    }
}
