package me.phoenixra.atumvr.core;

import lombok.Getter;
import me.phoenixra.atumvr.api.AtumVRState;
import me.phoenixra.atumvr.core.enums.XREvent;
import me.phoenixra.atumvr.core.enums.XRSessionState;
import me.phoenixra.atumvr.core.session.XRInstance;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.openxr.*;
import org.lwjgl.system.MemoryStack;


import static org.lwjgl.system.MemoryUtil.NULL;


public class XRState implements AtumVRState {

    @Getter
    private final XRProvider vrProvider;


    /**
     * If VR session is ready (initialized) on VR runtime side
     */
    protected boolean ready = false;


    @Getter
    protected boolean initialized = false;


    @Getter
    protected boolean active = false;


    @Getter
    protected boolean focused = false;


    public XRState(@NotNull XRProvider vrProvider){
        this.vrProvider = vrProvider;

    }

    public void init() throws Throwable{

        while (!ready){
            vrProvider.getLogger().logInfo("Waiting for OpenXR session to start...");
            pollVREvents();
        }
        vrProvider.inputHandler.init();
        vrProvider.renderer.init();

        initialized = true;
    }



    protected void pollVREvents() {
        XRInstance vrInstance = vrProvider.getSession().getInstance();
        XrEventDataBuffer eventBuffer = vrInstance.getXrEventBuffer();
        while (true) {
            eventBuffer.clear();
            eventBuffer.type(XR10.XR_TYPE_EVENT_DATA_BUFFER);
            int error = XR10.xrPollEvent(vrInstance.getHandle(), eventBuffer);
            vrProvider.checkXRError(error, "xrPollEvent", "");
            if (error != XR10.XR_SUCCESS) {
                //no more events available
                break;
            }
            var event = XrEventDataBaseHeader.create(eventBuffer.address());
            var vrEvent = XREvent.fromId(event.type());

            if (vrEvent == XREvent.SESSION_STATE_CHANGED) {
                xrStateChanged(
                        XrEventDataSessionStateChanged.create(event.address())
                );
                continue;
            }

            if (vrEvent == XREvent.VIVE_TRACKER_CONNECTED_HTCX) {
                vrProvider.onViveTrackerConnected(
                        XrEventDataViveTrackerConnectedHTCX.create(event.address())
                );
                continue;
            }

            if (vrEvent == XREvent.REFERENCE_SPACE_CHANGE_PENDING) {
                XrEventDataReferenceSpaceChangePending change =
                        XrEventDataReferenceSpaceChangePending.create(event.address());
                vrProvider.getLogger().logInfo(
                        "Reference space change pending: type="
                                + change.referenceSpaceType()
                                + ", appType="
                                + vrProvider.getSession().getXrAppSpaceType()
                                + ", changeTime="
                                + change.changeTime()
                                + ", poseValid="
                                + change.poseValid()
                );
                continue;
            }

            if (vrEvent == XREvent.DISPLAY_REFRESH_RATE_CHANGED_FB) {
                XrEventDataDisplayRefreshRateChangedFB change =
                        XrEventDataDisplayRefreshRateChangedFB.create(event.address());
                vrProvider.getSession().onDisplayRefreshRateChanged(
                        change.fromDisplayRefreshRate(),
                        change.toDisplayRefreshRate()
                );
                continue;
            }

            vrProvider.onXREventReceived(vrEvent);
        }
        vrProvider.getSession().maintainPreferredDisplayRefreshRate(focused);
    }

    private void xrStateChanged(XrEventDataSessionStateChanged event) {
        var stateChange = XRSessionState.fromId(event.state());
        vrProvider.getLogger().logDebug("VR Session State changed to: "+stateChange);


        switch (stateChange) {
            case READY -> {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    XrSessionBeginInfo sessionBeginInfo = XrSessionBeginInfo.calloc(stack)
                            .type(XR10.XR_TYPE_SESSION_BEGIN_INFO)
                            .next(NULL)
                            .primaryViewConfigurationType(XR10.XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO);

                    vrProvider.checkXRError(
                            XR10.xrBeginSession(vrProvider.getSession().getHandle(), sessionBeginInfo),
                            "xrBeginSession", "XRStateChangeREADY"
                    );
                }
                ready = true;
                active = true; //for convenience
                vrProvider.getSession().requestPreferredDisplayRefreshRate();
                vrProvider.getLogger().logInfo("OpenXR session is READY");

            }

            case STOPPING -> {
                ready = false;
                initialized = false;
                active = false;

            }

            case VISIBLE -> active = true;

            case FOCUSED -> active = true;

            case EXITING, IDLE, SYNCHRONIZED -> active = false;
        }

        focused = stateChange == XRSessionState.FOCUSED;

        vrProvider.onStateChanged(stateChange);
    }

    // -------- DESTROY --------

    /**
     * Destroy VR state by setting its fields to initial values
     */
    public void destroy(){
        ready = false;
        active = false;
        focused = false;
        initialized = false;
    }
}
