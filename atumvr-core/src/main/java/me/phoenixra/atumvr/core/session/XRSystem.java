package me.phoenixra.atumvr.core.session;

import lombok.Getter;
import me.phoenixra.atumvr.api.exceptions.AtumVRException;
import me.phoenixra.atumvr.core.XRProvider;
import me.phoenixra.atumvr.core.session.platform.XRPlatform;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.openxr.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Struct;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.system.MemoryUtil.memAddress;
import static org.lwjgl.system.MemoryUtil.memUTF8;

/**
 * XR session system (low-level OpenXR stuff)
 */
public class XRSystem {
    private final XRProvider vrProvider;

    @Getter
    private long systemId;

    public XRSystem(@NotNull XRProvider vrProvider){
        this.vrProvider = vrProvider;

    }

    public void init(){
        try (MemoryStack stack = MemoryStack.stackPush()) {


            // 1) Acquire system ID for HMD
            var sysGetInfo = XrSystemGetInfo.calloc(stack)
                    .type(XR10.XR_TYPE_SYSTEM_GET_INFO)
                    .next(NULL)
                    .formFactor(XR10.XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY);

            LongBuffer sysIdBuf = stack.callocLong(1);
            vrProvider.checkXRError(
                    XR10.xrGetSystem(
                            vrProvider.getSession().getInstance().getHandle(),
                            sysGetInfo,
                            sysIdBuf
                    ),
                    "xrGetSystem", "fetch HMD system ID"
            );

            systemId = sysIdBuf.get(0);
            if (systemId == XR10.XR_NULL_SYSTEM_ID) {
                throw new AtumVRException("No compatible HMD detected (system ID == 0)");
            }

            // 2) Query system properties
            var sysProps = XrSystemProperties.calloc(stack)
                    .type(XR10.XR_TYPE_SYSTEM_PROPERTIES);
            vrProvider.checkXRError(
                    XR10.xrGetSystemProperties(
                            vrProvider.getSession().getInstance().getHandle(),
                            systemId,
                            sysProps
                    ),
                    "xrGetSystemProperties", "id=" + systemId
            );

            String name = memUTF8(memAddress(sysProps.systemName()));
            var track = sysProps.trackingProperties();
            var gfx = sysProps.graphicsProperties();

            vrProvider.getLogger().logInfo(
                    String.format(
                            "Found HMD [%s] (vendor=%d): orientTrack=%b, posTrack=%b, maxRes=%dx%d, maxLayers=%d",
                            name,
                            sysProps.vendorId(),
                            track.orientationTracking(),
                            track.positionTracking(),
                            gfx.maxSwapchainImageWidth(),
                            gfx.maxSwapchainImageHeight(),
                            gfx.maxLayerCount()
                    )
            );
        }
    }

    public Struct<?> createGraphicsBinding(MemoryStack stack,
                                            XrInstance instance,
                                            long systemID,
                                            long windowHandle) {
        return XRPlatform.get().createGraphicsBinding(stack, instance, systemID, windowHandle);
    }
    public void destroy(){

    }
}
