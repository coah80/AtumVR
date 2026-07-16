package me.phoenixra.atumvr.core;

import lombok.Getter;
import lombok.Setter;
import me.phoenixra.atumvr.api.AtumVRLogger;
import me.phoenixra.atumvr.api.AtumVRProvider;
import me.phoenixra.atumvr.api.input.profile.tracker.ViveTrackerRole;
import me.phoenixra.atumvr.api.rendering.AtumVRRenderer;
import me.phoenixra.atumvr.core.enums.XREvent;
import me.phoenixra.atumvr.api.exceptions.AtumVRException;
import me.phoenixra.atumvr.api.rendering.AtumVRRenderContext;
import me.phoenixra.atumvr.core.enums.XRActionResult;
import me.phoenixra.atumvr.core.enums.XRSessionState;
import me.phoenixra.atumvr.core.rendering.XRRenderer;
import me.phoenixra.atumvr.core.session.XRSession;
import me.phoenixra.atumvr.core.input.XRInputHandler;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.openxr.*;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.util.List;

import static org.lwjgl.system.MemoryUtil.memAddress;
import static org.lwjgl.system.MemoryUtil.memUTF8;

/**
 * The entry point and manager of XR
 *
 * <h2>Thread Safety</h2>
 * <p>
 *     All VR operations should be performed on the
 *     same thread (typically the render thread).
 * </p>
 */
@Getter
public abstract class XRProvider implements AtumVRProvider {

    private final String appName;


    private final AtumVRLogger logger;

    /**The XR session handler*/
    protected XRSession session;
    protected XRState state;

    protected XRInputHandler inputHandler;
    protected AtumVRRenderer renderer;



    /**
     * The predicted display time for the current frame from the OpenXR runtime.
     */
    @Setter
    protected long xrDisplayTime;

    @Getter
    private volatile boolean shuttingDown = false;

    public XRProvider(@NotNull String appName,
                      @NotNull AtumVRLogger logger){
        this.appName = appName;
        this.logger = logger;
        this.state = createStateHandler();
        this.renderer = createRenderer();
        this.inputHandler = createInputHandler();
    }


    // -------- SETTING UP --------

    /**
     * Create renderer
     * <p>
     *     This is a special case.<br>
     *     For your rendering class, you have an option to extend {@link XRRenderer}
     *     or implement interface {@link AtumVRRenderer}.
     * </p>
     * <p>
     *     It is made this way, to help big projects make less mess while integrating AtumVR.<br>
     *     The other parts like input and session are too different
     *     from what you would have on PC game code (no purpose collision, won't be ugly)
     *     and not so potentially huge and important as rendering
     * </p>
     *
     * @return new VRRenderer instance
     */
    protected abstract @NotNull AtumVRRenderer createRenderer();

    /**
     * Create input handler
     *
     * @return new VRInputHandler instance
     */
    protected abstract @NotNull XRInputHandler createInputHandler();


    /**
     * Create state handler
     * <p>
     *     Override if you need custom state handler
     * </p>
     *
     * @return new VRState instance
     */
    protected @NotNull XRState createStateHandler(){
        return new XRState(this);
    }

    /**
     * Create session handler
     * <p>
     *     Override if you need custom session handler
     * </p>
     *
     * @return new VRSession instance
     */
    protected @NotNull XRSession createSessionHandler(){
        return new XRSession(this);
    }


    /**
     * Get XR extensions to apply for app during initialization
     * <br>
     * By default, the following OpenXR extensions are applied:
     * <ul>
     *     <li>{@link EXTHPMixedRealityController#XR_EXT_HP_MIXED_REALITY_CONTROLLER_EXTENSION_NAME}</li>
     *     <li>{@link HTCViveCosmosControllerInteraction#XR_HTC_VIVE_COSMOS_CONTROLLER_INTERACTION_EXTENSION_NAME}</li>
     *     <li>{@link BDControllerInteraction#XR_BD_CONTROLLER_INTERACTION_EXTENSION_NAME}</li>
     *     <li>{@link HTCXViveTrackerInteraction#XR_HTCX_VIVE_TRACKER_INTERACTION_EXTENSION_NAME}</li>
     * </ul>
     *
     * <p>
     *     Each extension is only enabled if the active runtime supports it
     * </p>
     * <p>
     *     Override if you need different OpenXR extensions.
     * </p>
     *
     * @return list of XR extensions
     */
    public @NotNull List<String> getXRAppExtensions(){
        return List.of(
                EXTHPMixedRealityController.XR_EXT_HP_MIXED_REALITY_CONTROLLER_EXTENSION_NAME,
                HTCViveCosmosControllerInteraction.XR_HTC_VIVE_COSMOS_CONTROLLER_INTERACTION_EXTENSION_NAME,
                BDControllerInteraction.XR_BD_CONTROLLER_INTERACTION_EXTENSION_NAME,
                HTCXViveTrackerInteraction.XR_HTCX_VIVE_TRACKER_INTERACTION_EXTENSION_NAME

        );
    }


    /**
     * Retrieves the list of supported swap chain formats.
     * <p>
     *     The first format in the list that is supported
     *     by the user's hardware will be applied during initialization,
     *     starting from index 0.
     * </p>
     *
     * By default, the following swap chain formats are included, in order of priority:
     * <ul>
     *     <li>{@code GL21.GL_SRGB8_ALPHA8}</li>
     *     <li>{@code GL21.GL_SRGB8}</li>
     *     <li>{@code GL11.GL_RGB10_A2}</li>
     *     <li>{@code GL30.GL_RGBA16F}</li>
     *     <li>{@code GL30.GL_RGB16F}</li>
     *     <li>Fallback formats:
     *         <ul>
     *             <li>{@code GL11.GL_RGBA8}</li>
     *             <li>{@code GL31.GL_RGBA8_SNORM}</li>
     *         </ul>
     *     </li>
     * </ul>
     *
     * <p>
     *     Override if you need to specify
     *     a different list of formats or modify their apply priority.
     * </p>
     *
     * @return list of integers representing swap chain formats.
     */
    public @NotNull List<Integer> getSwapChainFormats(){
        return List.of(
                GL30.GL_SRGB8_ALPHA8,
                GL30.GL_SRGB8,
                // others
                GL30.GL_RGB10_A2,
                GL30.GL_RGBA16F,
                GL30.GL_RGB16F,

                // Fallback
                GL30.GL_RGBA8,
                GL31.GL_RGBA8_SNORM
        );
    }

    public float getPreferredRefreshRate(){
        return 120f;
    }

    public int getFoveationLevel(){
        return FBFoveationConfiguration.XR_FOVEATION_LEVEL_MEDIUM_FB;
    }

    // -------- LIFECYCLE --------

    @Override
    public void initializeVR() throws Throwable{
        if (state.isInitialized()) {
            throw new AtumVRException("Already initialized!");
        }
        session = createSessionHandler();
        session.init();
        state.init();
    }

    @Override
    public void syncState(){
        if(!state.isInitialized()){
            return;
        }
        state.pollVREvents();
    }

    @Override
    public void startFrame() {
        if(!state.isInitialized()){
            return;
        }
        renderer.prepareFrame();
        inputHandler.update();
    }

    @Override
    public void render(@NotNull AtumVRRenderContext context) {
        if(!state.isInitialized()){
            return;
        }
        renderer.renderFrame(context);
    }

    @Override
    public void postRender() {

    }

    /**
     * On session state changed.
     * <p>
     *     Called from VRState only.<br>
     *     Do nothing by default, intended to be overwritten when needed
     * </p>
     *
     * @param state the new session state
     */
    protected void onStateChanged(@NotNull XRSessionState state){

    }

    /**
     * On XR event received
     * <p>
     *     Called from VRState only.<br>
     *     Do nothing by default, intended to be overwritten when needed
     * </p>
     * <p>
     *     This method won't be called for state change,
     *     use {@link #onStateChanged(XRSessionState)} instead
     * </p>
     * @param state the new session state
     */
    protected void onXREventReceived(@NotNull XREvent state){

    }


    protected void onViveTrackerConnected(@NotNull XrEventDataViveTrackerConnectedHTCX event){

    }



    // -------- HELPER METHODS --------

    public void checkXRError(int xrResult,
                             @NotNull String caller,
                             String... args) throws AtumVRException {
        checkXRError(true,xrResult,caller,args);
    }

    public void checkXRError(boolean throwError,
                             int xrResult,
                             @NotNull String caller,
                             String... args) throws AtumVRException {
        if (xrResult < 0) {
            String msg = String.format(
                    "%s for %s error: %s",
                    caller,
                    String.join(" ", args),
                    getXRActionResult(xrResult)
            );
            if(!throwError){
                logger.logError(
                        msg
                );
                return;
            }
            throw new AtumVRException(msg);
        }
    }

    public String getXRActionResult(int resultId) {
        var result = XRActionResult.fromId(resultId);
        String resultString = result != null
                ? result.toString()
                : null;
        if (resultString == null) {
            if(session.getInstance() == null){
                return  "Unknown XR Action Result: " + resultId;
            }
            // ask the runtime for the xrResult name
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer str = stack.calloc(XR10.XR_MAX_RESULT_STRING_SIZE);

                if (XR10.xrResultToString(session.getInstance().getHandle(), resultId, str) == XR10.XR_SUCCESS) {
                    resultString = (memUTF8(memAddress(str)));
                } else {
                    resultString = "Unknown XR Action Result: " + resultId;
                }
            }
        }
        return resultString;
    }


    // -------- DESTROY --------


    @Override
    public void prepareDestroy(){
        shuttingDown = true;
        if (inputHandler != null) {
            try { inputHandler.stopActiveHaptics(); } catch (Throwable t) {
                logger.logError("inputHandler.stopActiveHaptics() failed: " + t.getMessage());
            }
        }
    }

    @Override
    public void destroy() {
        shuttingDown = true;
        if (inputHandler != null) {
            inputHandler.stopActiveHaptics();
        }
        if (renderer != null) {
            try { renderer.destroy(); } catch (Throwable t) {
                logger.logError("renderer.destroy() failed: " + t.getMessage());
            }
        }
        if (inputHandler != null) {
            try { inputHandler.destroy(); } catch (Throwable t) {
                logger.logError("inputHandler.destroy() failed: " + t.getMessage());
            }
        }
        if (session != null) {
            try { session.destroy(); } catch (Throwable t) {
                logger.logError("session.destroy() failed: " + t.getMessage());
            }
        }
        if (state != null) {
            try { state.destroy(); } catch (Throwable t) {
                logger.logError("state.destroy() failed: " + t.getMessage());
            }
        }
        shuttingDown = false;
    }
}
