package me.phoenixra.atumvr.core.rendering;

import lombok.Getter;
import me.phoenixra.atumvr.api.rendering.AtumVRRenderContext;
import me.phoenixra.atumvr.api.rendering.AtumVRRenderer;
import me.phoenixra.atumvr.api.rendering.AtumVRTexture;
import me.phoenixra.atumvr.core.XRProvider;
import me.phoenixra.atumvr.api.enums.EyeType;
import me.phoenixra.atumvr.api.exceptions.AtumVRException;
import me.phoenixra.atumvr.core.input.device.XRDeviceHMD;
import me.phoenixra.atumvr.api.utils.GLUtils;
import me.phoenixra.atumvr.core.utils.XRUtils;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL30;
import org.lwjgl.openxr.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.Platform;

import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Locale;

import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import static org.lwjgl.glfw.GLFW.*;


/**
 * Abstract base class for XR rendering
 */
public abstract class XRRenderer implements AtumVRRenderer {

    @Getter
    protected XRProvider vrProvider;


    @Getter
    protected int resolutionWidth;

    @Getter
    protected int resolutionHeight;


    @Getter
    protected long windowHandle;



    /** Current swapChain image index. */
    protected int swapIndex;

    /** FrameBuffers for left eye. */
    protected AtumVRTexture[] leftFramebuffers;

    /** FrameBuffers for right eye. */
    protected AtumVRTexture[] rightFramebuffers;

    /** Projection layer views for frame submission. */
    protected XrCompositionLayerProjectionView.Buffer projectionLayerViews;

    /**Hidden area mesh for stencil mask*/
    protected final HashMap<EyeType, float[]> hiddenArea = new HashMap<>();

    /**If created gl context with {@link #setupGLContext()}*/
    private boolean glContextCreated;


    /** SteamVR + Linux workaround on GL issue */
    private boolean steamVRLinuxWorkaround;
    private int lastSceneGLError;

    public XRRenderer(@NotNull XRProvider vrProvider) {
        this.vrProvider = vrProvider;

    }

    // -------- SETTING UP --------

    @Override
    public abstract @NotNull XRScene getCurrentScene();

    /**
     * On renderer initialized
     *
     * @throws Throwable exception or error
     */
    protected abstract void onInit() throws Throwable;

    /**
     * Create VR texture for eye display
     *
     * @param width the requested width
     * @param height the requested height
     * @param textureId the requested texture id
     * @param index the requested texture index
     *
     * @return the VR texture instance
     */
    protected @NotNull XRTexture createTexture(int width, int height,
                                               int textureId,
                                               int index){
        return new XRTexture(width, height, textureId, index);
    }


    // -------- LIFECYCLE --------

    @Override
    public void init() throws Throwable{
        steamVRLinuxWorkaround = XRUtils.detectSteamVRLinux(vrProvider);

        restoreGLContext();
        setupResolution();
        setupEyes();
        setupHiddenArea();
        onInit();
    }

    @Override
    public void prepareFrame() {
        prepareXrFrame();
    }

    @Override
    public void renderFrame(@NotNull AtumVRRenderContext context) {


        if(glContextCreated) {
            GL30.glViewport(0, 0, resolutionWidth, resolutionHeight);
            GL30.glEnable(GL30.GL_DEPTH_TEST);
        }

        getCurrentScene().render(context);

        finishXrFrame();

        if(glContextCreated) {
            GL30.glFlush();
            GL30.glFinish();
        }
    }


    protected void prepareXrFrame(){
        try (MemoryStack stack = MemoryStack.stackPush()) {
            XrFrameState frameState = XrFrameState.calloc(stack).type(XR10.XR_TYPE_FRAME_STATE);

            vrProvider.checkXRError(
                    XR10.xrWaitFrame(
                            vrProvider.getSession().getHandle(),
                            XrFrameWaitInfo.calloc(stack)
                                    .type(XR10.XR_TYPE_FRAME_WAIT_INFO),
                            frameState
                    ),
                    "xrWaitFrame", ""
            );

            vrProvider.setXrDisplayTime(frameState.predictedDisplayTime());

            vrProvider.checkXRError(
                    XR10.xrBeginFrame(
                            vrProvider.getSession().getHandle(),
                            XrFrameBeginInfo.calloc(stack)
                                    .type(XR10.XR_TYPE_FRAME_BEGIN_INFO)
                    ),
                    "xrBeginFrame", ""
            );


            XrViewState viewState = XrViewState.calloc(stack).type(XR10.XR_TYPE_VIEW_STATE);
            IntBuffer intBuf = stack.callocInt(1);

            XrViewLocateInfo viewLocateInfo = XrViewLocateInfo.calloc(stack);
            viewLocateInfo.set(
                    XR10.XR_TYPE_VIEW_LOCATE_INFO,
                    0,
                    XR10.XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,
                    vrProvider.getXrDisplayTime(),
                    vrProvider.getSession().getXrAppSpace()
            );

            vrProvider.checkXRError(
                    XR10.xrLocateViews(
                            vrProvider.getSession().getHandle(),
                            viewLocateInfo, viewState,
                            intBuf, vrProvider.getSession().getSwapChain().getXrViewBuffer()
                    ),
                    "xrLocateViews", ""
            );


        }


        XrSwapchain xrSwapchain = vrProvider.getSession().getSwapChain().getHandle();
        if (this.projectionLayerViews == null) {
            this.projectionLayerViews = XrCompositionLayerProjectionView.calloc(2);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {

            IntBuffer intBuf2 = stack.callocInt(1);

            vrProvider.checkXRError(
                    XR10.xrAcquireSwapchainImage(
                            xrSwapchain,
                            XrSwapchainImageAcquireInfo
                                    .calloc(stack)
                                    .type(XR10.XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO),
                            intBuf2
                    ),
                    "xrAcquireSwapchainImage", ""
            );

            vrProvider.checkXRError(
                    XR10.xrWaitSwapchainImage(xrSwapchain,
                            XrSwapchainImageWaitInfo.calloc(stack)
                                    .type(XR10.XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO)
                                    .timeout(XR10.XR_INFINITE_DURATION)
                    ),
                    "xrWaitSwapchainImage", ""
            );

            this.swapIndex = intBuf2.get(0);

            // Render view to the appropriate part of the swapchain image.
            for (EyeType eyeType : EyeType.values()) {
                int index = eyeType.getIndex();
                XrView xrView = vrProvider.getInputHandler()
                        .getDevice(XRDeviceHMD.ID, XRDeviceHMD.class)
                        .getXrView(eyeType);
                XrSwapchainSubImage subImage = this.projectionLayerViews.get(index)
                        .type(XR10.XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW)
                        .pose(xrView.pose())
                        .fov(xrView.fov())
                        .subImage();
                subImage.swapchain(xrSwapchain);
                subImage.imageRect().offset().set(0, 0);
                subImage.imageRect().extent().set(resolutionWidth, resolutionHeight);
                subImage.imageArrayIndex(index);
            }

        }

        if (steamVRLinuxWorkaround) {
            restoreGLContext();
            GLUtils.drainGLErrors();
        }
    }

    protected void finishXrFrame(){
        XrSwapchain xrSwapchain = vrProvider.getSession().getSwapChain().getHandle();

        if (steamVRLinuxWorkaround) {
            int sceneErr = GLUtils.drainGLErrors();
            if (sceneErr != lastSceneGLError) {
                lastSceneGLError = sceneErr;
                if (sceneErr != 0) {
                    vrProvider.getLogger().logError(
                            "OpenGL error generated by application/scene rendering: "
                                    + sceneErr + " (0x" + Integer.toHexString(sceneErr)
                                    + ") - this is an app-side bug, not the"
                                    + " SteamVR/Linux interop artifact"
                    );
                }
            }
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer layers = stack.callocPointer(1);
            int error;

            error = XR10.xrReleaseSwapchainImage(
                    xrSwapchain,
                    XrSwapchainImageReleaseInfo.calloc(stack)
                            .type(XR10.XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO));
            vrProvider.checkXRError(error, "xrReleaseSwapchainImage", "");

            XrCompositionLayerProjection compositionLayerProjection = XrCompositionLayerProjection.calloc(stack)
                    .type(XR10.XR_TYPE_COMPOSITION_LAYER_PROJECTION)
                    .space(vrProvider.getSession().getXrAppSpace())
                    .views(this.projectionLayerViews);

            layers.put(compositionLayerProjection);

            layers.flip();

            error = XR10.xrEndFrame(
                    vrProvider.getSession().getHandle(),
                    XrFrameEndInfo.calloc(stack)
                            .type(XR10.XR_TYPE_FRAME_END_INFO)
                            .displayTime(vrProvider.getXrDisplayTime())
                            .environmentBlendMode(XR10.XR_ENVIRONMENT_BLEND_MODE_OPAQUE)
                            .layers(layers));
            vrProvider.checkXRError(error, "xrEndFrame", "");

        }

        if (steamVRLinuxWorkaround) {
            restoreGLContext();
            GLUtils.drainGLErrors();
        }
    }



    protected void restoreGLContext() {
        if (steamVRLinuxWorkaround && windowHandle != 0L) {
            glfwMakeContextCurrent(windowHandle);
        }
    }


    /**
     * Setup OpenGL context for VR.
     * <p>
     *     Optional to use.<br>
     *     If you already have OpenGL context for your app,
     *     set its {@link #windowHandle} for the field instead
     * </p>
     */
    public void setupGLContext() {
        glContextCreated = true;
        GLFWErrorCallback.createPrint(System.out).set();

        if (!glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }


        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_DEPTH_BITS, 24);
        glfwWindowHint(GLFW_STENCIL_BITS, 8);

        windowHandle = glfwCreateWindow(640, 480, vrProvider.getAppName(), 0L, 0L);
        if (windowHandle == 0L) {
            throw new RuntimeException("Failed to create the GLFW window");
        }

        glfwMakeContextCurrent(windowHandle);
        glfwSwapInterval(1);

        GL.createCapabilities();
        GL30.glEnable(GL30.GL_DEPTH_TEST);

        //texture staff, better be moved to a renderers of objects
        GL30.glEnable(GL30.GL_CULL_FACE);
        GL30.glCullFace(GL30.GL_BACK);

    }

    /**
     * Setup resolution for eye display
     */
    protected void setupResolution() {

        resolutionWidth = vrProvider.getSession().getSwapChain().getEyeWidth();
        resolutionHeight = vrProvider.getSession().getSwapChain().getEyeHeight();
    }


    /**
     * Setup Eye textures and swapChains
     */
    protected void setupEyes() {

        try (MemoryStack stack = MemoryStack.stackPush()) {

            // Get amount of views in the swapchain
            IntBuffer intBuffer = stack.ints(0); //Set value to 0
            int error = XR10.xrEnumerateSwapchainImages(vrProvider.getSession().getSwapChain().getHandle(), intBuffer, null);
            vrProvider.checkXRError(error, "xrEnumerateSwapchainImages", "get count");

            // Now we know the amount, create the image buffer
            int imageCount = intBuffer.get(0);
            XrSwapchainImageOpenGLKHR.Buffer swapchainImageBuffer = vrProvider
                    .getSession().getSwapChain().createImageBuffers(imageCount,
                            stack);

            error = XR10.xrEnumerateSwapchainImages(vrProvider.getSession().getSwapChain().getHandle(), intBuffer,
                    XrSwapchainImageBaseHeader.create(swapchainImageBuffer.address(), swapchainImageBuffer.capacity()));
            vrProvider.checkXRError(error, "xrEnumerateSwapchainImages", "get images");

            this.leftFramebuffers = new AtumVRTexture[imageCount];
            this.rightFramebuffers = new AtumVRTexture[imageCount];

            for (int i = 0; i < imageCount; i++) {
                XrSwapchainImageOpenGLKHR openxrImage = swapchainImageBuffer.get(i);
                this.leftFramebuffers[i] = createTexture(
                        resolutionWidth, resolutionHeight,
                        openxrImage.image(),
                        0
                ).init();
                GLUtils.checkGLError("Left Eye " + i + " framebuffer setup");
                this.rightFramebuffers[i] = createTexture(
                        resolutionWidth, resolutionHeight,
                        openxrImage.image(),
                        1
                ).init();
                GLUtils.checkGLError("Right Eye " + i + " framebuffer setup");

            }
        }

    }


    /**
     * Loads hidden area mesh from VR session
     */
    protected void setupHiddenArea(){
        try(MemoryStack stack = MemoryStack.stackPush()) {
            XrSession xrSession = getVrProvider().getSession().getHandle();
            for (int eye = 0; eye < 2; ++eye) {
                // 1) Allocate the mask struct
                XrVisibilityMaskKHR mask = XrVisibilityMaskKHR
                        .calloc(stack)
                        .type(KHRVisibilityMask.XR_TYPE_VISIBILITY_MASK_KHR)
                        .next(0);

                // 2) First call: get counts
                getVrProvider().checkXRError(
                        KHRVisibilityMask.xrGetVisibilityMaskKHR(
                                xrSession,
                                XR10.XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,
                                eye,
                                KHRVisibilityMask.XR_VISIBILITY_MASK_TYPE_HIDDEN_TRIANGLE_MESH_KHR,
                                mask
                        ),
                        "xrGetVisibilityMaskKHR",
                        "query counts"
                );
                int vertCount  = mask.vertexCountOutput();
                int indexCount = mask.indexCountOutput();

                if (indexCount <= 0) {
                    getVrProvider().getLogger().logInfo("No hidden-area mesh found for eye " + eye);
                    continue;
                }

                // 3) Allocate buffers for the data
                XrVector2f.Buffer verts  = XrVector2f.calloc(vertCount, stack);
                IntBuffer          idxBuf = stack.mallocInt(indexCount);

                mask
                        .vertexCapacityInput(vertCount)
                        .indexCapacityInput(indexCount)
                        .vertices(verts)
                        .indices(idxBuf);

                // 4) Second call: actually fill verts & indices
                getVrProvider().checkXRError(
                        KHRVisibilityMask.xrGetVisibilityMaskKHR(
                                xrSession,
                                XR10.XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,
                                eye,
                                KHRVisibilityMask.XR_VISIBILITY_MASK_TYPE_HIDDEN_TRIANGLE_MESH_KHR,
                                mask
                        ),
                        "xrGetVisibilityMaskKHR",
                        "retrieve mesh"
                );

                // 5) Flatten into your float[] format (tri-list: x,y,x,y,…)
                float[] area = new float[indexCount * 2];
                for (int i = 0; i < indexCount; i++) {
                    XrVector2f v = verts.get(idxBuf.get(i));
                    // If your runtime gives coords in [-1..1], map them to [0..1]:
                    float ux = (v.x() * 0.5f) + 0.5f;
                    float uy = (v.y() * 0.5f) + 0.5f;
                    // then to pixels:
                    area[i*2    ] = ux * getResolutionWidth();
                    area[i*2 + 1] = uy * getResolutionHeight();
                }

                hiddenArea.put(EyeType.fromIndex(eye), area);
                getVrProvider().getLogger().logInfo("Hidden-area mesh loaded for eye " + eye);
            }
        }
    }


    // -------- API --------

    @Override
    public @NotNull AtumVRTexture getTextureLeftEye() {
        if(leftFramebuffers==null){
            throw new AtumVRException("Tried to get left eye texture before textures initialized");
        }
        return leftFramebuffers[swapIndex];
    }


    @Override
    public @NotNull AtumVRTexture getTextureRightEye() {
        if(rightFramebuffers==null){
            throw new AtumVRException("Tried to get right eye texture before textures initialized");
        }
        return rightFramebuffers[swapIndex];
    }

    @Override
    public float[] getHiddenAreaVertices(@NotNull EyeType eyeType) {
        return hiddenArea.get(eyeType);
    }



    // -------- DESTROY --------

    public void destroy() {
        getCurrentScene().destroy();
        if (projectionLayerViews != null) {
            projectionLayerViews.close();
            projectionLayerViews = null;
        }
        if(glContextCreated) {
            glfwFreeCallbacks(windowHandle);
            glfwDestroyWindow(windowHandle);

            glfwTerminate();
        }
    }

}
