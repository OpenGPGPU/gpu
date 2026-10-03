/* GLES2 clear and one triangle. The vertex and fragment shaders are copies,
 * so the Gallium driver submits vertex_passthrough and fragment_color to the
 * GPU. GeometryStage turns the clip coordinates into pixels. This program
 * only packs the vertex buffer, issues the GL calls, and reads the GEM back.
 */
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <drm/drm_fourcc.h>
#include <fcntl.h>
#include <gbm.h>
#include <xf86drm.h>
#include <xf86drmMode.h>

#include <errno.h>
#include <execinfo.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define COLOR_CLEAR 0x0000ffffu    /* glClearColor(0, 0, 1, 1) */
#define COLOR_TRIANGLE 0xffff00ffu /* vertex colour (1, 1, 0, 1) */

static const char *vs_src =
    "attribute vec4 pos;\n"
    "attribute vec4 color;\n"
    "varying vec4 v_color;\n"
    "void main() {\n"
    "  gl_Position = pos;\n"
    "  v_color = color;\n"
    "}\n";

static const char *fs_src =
    "precision mediump float;\n"
    "varying vec4 v_color;\n"
    "void main() {\n"
    "  gl_FragColor = v_color;\n"
    "}\n";

/* Clip triangle covering the GPU's top-left half: NDC y=-1 is scanout row 0. */
static const float verts[] = {
    -1.f, -1.f, 0.f, 1.f, 1.f, 1.f, 0.f, 1.f,
     1.f, -1.f, 0.f, 1.f, 1.f, 1.f, 0.f, 1.f,
    -1.f,  1.f, 0.f, 1.f, 1.f, 1.f, 0.f, 1.f,
};

/* The guest has no debugger. Print library+offset frames so a fault can be
 * resolved with addr2line against the unstripped build. */
static void crash(int sig)
{
    void *frames[32];
    int n = backtrace(frames, 32);
    static const char msg[] = "gl_triangle: fatal signal, backtrace:\n";

    write(2, msg, sizeof(msg) - 1);
    backtrace_symbols_fd(frames, n, 2);
    signal(sig, SIG_DFL);
    raise(sig);
}

static void step(const char *what)
{
    fprintf(stderr, "gl_triangle: %s\n", what);
}

static void die(const char *what)
{
    fprintf(stderr, "gl_triangle: %s\n", what);
    exit(1);
}

static void die_errno(const char *what)
{
    fprintf(stderr, "gl_triangle: %s: %s\n", what, strerror(errno));
    exit(1);
}

static void die_gl(const char *what)
{
    fprintf(stderr, "gl_triangle: %s (egl 0x%x)\n", what, eglGetError());
    exit(1);
}

static GLuint compile_shader(GLenum type, const char *src)
{
    GLuint shader = glCreateShader(type);
    GLint ok = 0;
    char log[512];

    glShaderSource(shader, 1, &src, NULL);
    glCompileShader(shader);
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        glGetShaderInfoLog(shader, sizeof(log), NULL, log);
        fprintf(stderr, "gl_triangle: shader compile failed: %s\n", log);
        exit(1);
    }
    return shader;
}

static uint32_t pixel_word(int x, int y)
{
    uint8_t px[4];

    glReadPixels(x, y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, px);
    /* R8G8B8A8 memory is the little-endian bytes of the GPU colour word. */
    return (uint32_t)px[0] | ((uint32_t)px[1] << 8) |
           ((uint32_t)px[2] << 16) | ((uint32_t)px[3] << 24);
}

int main(void)
{
    int fd;
    drmModeRes *res = NULL;
    drmModeConnector *conn = NULL;
    drmModeEncoder *enc = NULL;
    drmModeModeInfo mode;
    struct gbm_device *gbm = NULL;
    struct gbm_surface *gsurf = NULL;
    struct gbm_bo *bo = NULL;
    EGLDisplay dpy = EGL_NO_DISPLAY;
    EGLConfig config;
    EGLContext ctx = EGL_NO_CONTEXT;
    EGLSurface surface = EGL_NO_SURFACE;
    EGLint major = 0, minor = 0, nconfig = 0;
    GLuint program, vbo, vs, fs;
    GLint linked = 0;
    uint32_t crtc_id, fb = 0;
    uint32_t handles[4] = { 0 }, pitches[4] = { 0 }, offsets[4] = { 0 };
    uint32_t tri, clear;
    int i;
    const EGLint config_attribs[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_NONE
    };
    const EGLint context_attribs[] = {
        EGL_CONTEXT_CLIENT_VERSION, 2,
        EGL_NONE
    };
    char log[512];

    signal(SIGSEGV, crash);
    signal(SIGBUS, crash);
    signal(SIGILL, crash);
    signal(SIGABRT, crash);
    fd = open("/dev/dri/card0", O_RDWR);
    if (fd < 0)
        die("open /dev/dri/card0");
    res = drmModeGetResources(fd);
    if (!res)
        die("drmModeGetResources");
    for (i = 0; i < res->count_connectors; i++) {
        conn = drmModeGetConnector(fd, res->connectors[i]);
        if (conn && conn->connection == DRM_MODE_CONNECTED &&
            conn->count_modes > 0)
            break;
        drmModeFreeConnector(conn);
        conn = NULL;
    }
    if (!conn)
        die("no connected KMS mode");
    mode = conn->modes[0];
    if (conn->encoder_id)
        enc = drmModeGetEncoder(fd, conn->encoder_id);
    if (!enc && conn->count_encoders > 0)
        enc = drmModeGetEncoder(fd, conn->encoders[0]);
    if (!enc)
        die("no KMS encoder");
    crtc_id = enc->crtc_id;
    if (!crtc_id) {
        for (i = 0; i < res->count_crtcs; i++) {
            if (enc->possible_crtcs & (1u << i)) {
                crtc_id = res->crtcs[i];
                break;
            }
        }
    }
    if (!crtc_id)
        die("no KMS crtc");

    gbm = gbm_create_device(fd);
    if (!gbm)
        die("gbm_create_device");
    /* Mesa's R8G8B8A8 config matches the ABGR8888 visual (red in the low
     * byte). Scanout still uses DRM_FORMAT_RGBA8888: the GPU word stores R
     * in the high byte. */
    gsurf = gbm_surface_create(gbm, mode.hdisplay, mode.vdisplay,
                               GBM_FORMAT_ABGR8888,
                               GBM_BO_USE_SCANOUT | GBM_BO_USE_RENDERING);
    if (!gsurf)
        die("gbm_surface_create");
    dpy = eglGetPlatformDisplay(EGL_PLATFORM_GBM_KHR, gbm, NULL);
    if (dpy == EGL_NO_DISPLAY)
        die_gl("eglGetPlatformDisplay");
    if (!eglInitialize(dpy, &major, &minor))
        die_gl("eglInitialize");
    if (!eglBindAPI(EGL_OPENGL_ES_API))
        die_gl("eglBindAPI");
    if (!eglChooseConfig(dpy, config_attribs, &config, 1, &nconfig) ||
        nconfig < 1) {
        fprintf(stderr,
                "gl_triangle: eglChooseConfig matched %d configs (egl 0x%x)\n",
                nconfig, eglGetError());
        return 1;
    }
    surface = eglCreateWindowSurface(dpy, config, (EGLNativeWindowType)gsurf,
                                     NULL);
    if (surface == EGL_NO_SURFACE)
        die_gl("eglCreateWindowSurface");
    ctx = eglCreateContext(dpy, config, EGL_NO_CONTEXT, context_attribs);
    if (ctx == EGL_NO_CONTEXT)
        die_gl("eglCreateContext");
    if (!eglMakeCurrent(dpy, surface, surface, ctx))
        die_gl("eglMakeCurrent");

    step("context current");
    vs = compile_shader(GL_VERTEX_SHADER, vs_src);
    fs = compile_shader(GL_FRAGMENT_SHADER, fs_src);
    program = glCreateProgram();
    glAttachShader(program, vs);
    glAttachShader(program, fs);
    glBindAttribLocation(program, 0, "pos");
    glBindAttribLocation(program, 1, "color");
    glLinkProgram(program);
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (!linked) {
        glGetProgramInfoLog(program, sizeof(log), NULL, log);
        fprintf(stderr, "gl_triangle: link failed: %s\n", log);
        return 1;
    }
    glUseProgram(program);
    step("program linked");
    glGenBuffers(1, &vbo);
    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(verts), verts, GL_STATIC_DRAW);
    glEnableVertexAttribArray(0);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(0, 4, GL_FLOAT, GL_FALSE, 8 * sizeof(float),
                          (void *)0);
    glVertexAttribPointer(1, 4, GL_FLOAT, GL_FALSE, 8 * sizeof(float),
                          (void *)(4 * sizeof(float)));
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);
    glDisable(GL_BLEND);
    glViewport(0, 0, mode.hdisplay, mode.vdisplay);
    glClearColor(0.f, 0.f, 1.f, 1.f);
    step("vertex buffer uploaded");
    glClear(GL_COLOR_BUFFER_BIT);
    step("clear submitted");
    glDrawArrays(GL_TRIANGLES, 0, 3);
    glFinish();
    step("draw finished");

    /* Window framebuffers are FlipY: GL y=0 is the bottom scanout row. */
    tri = pixel_word(8, mode.vdisplay - 1 - 8);
    clear = pixel_word(mode.hdisplay - 8, mode.vdisplay - 1 - 8);
    printf("gl_triangle: mode %ux%u triangle=0x%08x clear=0x%08x\n",
           mode.hdisplay, mode.vdisplay, tri, clear);
    if (tri != COLOR_TRIANGLE || clear != COLOR_CLEAR) {
        fprintf(stderr, "gl_triangle: pixel mismatch\n");
        return 1;
    }

    step("swap");
    if (!eglSwapBuffers(dpy, surface))
        die_gl("eglSwapBuffers");
    bo = gbm_surface_lock_front_buffer(gsurf);
    if (!bo)
        die("gbm_surface_lock_front_buffer");
    handles[0] = gbm_bo_get_handle(bo).u32;
    pitches[0] = gbm_bo_get_stride(bo);
    if (drmModeAddFB2(fd, mode.hdisplay, mode.vdisplay, DRM_FORMAT_RGBA8888,
                      handles, pitches, offsets, &fb, 0))
        die_errno("drmModeAddFB2");
    if (drmModeSetCrtc(fd, crtc_id, fb, 0, 0, &conn->connector_id, 1, &mode))
        die_errno("drmModeSetCrtc (another DRM master holds the display?)");
    printf("OPENGPU GL PASS\n");
    fflush(stdout);
    /* Closing the fd removes the framebuffer and turns the CRTC off. */
    fprintf(stderr, "gl_triangle: holding the frame; Ctrl-C to exit\n");
    for (;;)
        pause();
    return 0;
}
