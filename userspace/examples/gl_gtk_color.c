/* Two matrix-vector products, the position math in GTK 4.14 color.glsl
 * and in Qt 5.15 textmask.vert (without that shader's floor).
 *   vec4 xformed = u_modelview * aPosition;
 *   gl_Position = u_projection * xformed;
 * The vertex core applies modelview, then projection. The fragment core
 * copies the uniform colour. The CPU only packs the vertex buffer and
 * the two matrices.
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

#define COLOR_CLEAR 0x0000ffffu /* glClearColor(0, 0, 1, 1) */
#define COLOR_FILL 0xff0000ffu  /* uniform color (1, 0, 0, 1) */

static const char *vs_src =
    "attribute highp vec4 aPosition;\n"
    "uniform highp mat4 u_projection;\n"
    "uniform highp mat4 u_modelview;\n"
    "void main()\n"
    "{\n"
    "    vec4 xformed = u_modelview * aPosition;\n"
    "    gl_Position = u_projection * xformed;\n"
    "}\n";

static const char *fs_src =
    "uniform lowp vec4 color;\n"
    "void main()\n"
    "{\n"
    "    gl_FragColor = color;\n"
    "}\n";

/* Column-major. Modelview scales x by 1/2. Projection then adds 1/2
 * and scales y by -1, the sign a window projection uses to flip Y.
 * The products do not commute, so a reversed multiply misses x=420
 * and paints x=280. A dropped negative scale leaves the triangle on
 * the top row instead of the bottom. */
static const float projection[16] = {
    1.f, 0.f, 0.f, 0.f,
    0.f, -1.f, 0.f, 0.f,
    0.f, 0.f, 1.f, 0.f,
    0.5f, 0.f, 0.f, 1.f,
};
static const float modelview[16] = {
    0.5f, 0.f, 0.f, 0.f,
    0.f, 1.f, 0.f, 0.f,
    0.f, 0.f, 1.f, 0.f,
    0.f, 0.f, 0.f, 1.f,
};

/* Qt textmask writes the same two products, without its floor().
 * At 640x480 the triangle covers x=320..480. The Y flip puts it on
 * scanout rows 420..479, wide at the bottom and narrow at row 420. */
static const float verts[] = {
    -1.f, -1.f,   0.f, 1.f,
     0.f, -1.f,   0.f, 1.f,
    -1.f, -0.75f, 0.f, 1.f,
};

static void crash(int sig)
{
    void *frames[32];
    int n = backtrace(frames, 32);
    static const char msg[] = "gl_gtk_color: fatal signal, backtrace:\n";

    write(2, msg, sizeof(msg) - 1);
    backtrace_symbols_fd(frames, n, 2);
    signal(sig, SIG_DFL);
    raise(sig);
}

static void step(const char *what)
{
    fprintf(stderr, "gl_gtk_color: %s\n", what);
}

static void die(const char *what)
{
    fprintf(stderr, "gl_gtk_color: %s\n", what);
    exit(1);
}

static void die_errno(const char *what)
{
    fprintf(stderr, "gl_gtk_color: %s: %s\n", what, strerror(errno));
    exit(1);
}

static void die_gl(const char *what)
{
    fprintf(stderr, "gl_gtk_color: %s (egl 0x%x)\n", what, eglGetError());
    exit(1);
}

static GLuint compile_shader(GLenum type, const char *src)
{
    GLuint shader = glCreateShader(type);
    GLint ok = 0;
    char log[1024];

    glShaderSource(shader, 1, &src, NULL);
    glCompileShader(shader);
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        glGetShaderInfoLog(shader, sizeof(log), NULL, log);
        fprintf(stderr, "gl_gtk_color: shader compile failed: %s\n", log);
        exit(1);
    }
    return shader;
}

static uint32_t pixel_word(int x, int y)
{
    uint8_t px[4];

    glReadPixels(x, y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, px);
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
    GLint linked = 0, matrix_loc, color_loc;
    uint32_t crtc_id, fb = 0;
    uint32_t handles[4] = { 0 }, pitches[4] = { 0 }, offsets[4] = { 0 };
    uint32_t inside, top, reversed, outside, clear;
    int i;
    const EGLint config_attribs[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_NONE
    };
    const EGLint context_attribs[] = {
        EGL_CONTEXT_CLIENT_VERSION, 2,
        EGL_NONE
    };
    char log[1024];

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
        fprintf(stderr, "gl_gtk_color: eglChooseConfig matched %d configs\n",
                nconfig);
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
    glBindAttribLocation(program, 0, "aPosition");
    glLinkProgram(program);
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (!linked) {
        glGetProgramInfoLog(program, sizeof(log), NULL, log);
        fprintf(stderr, "gl_gtk_color: link failed: %s\n", log);
        return 1;
    }
    glUseProgram(program);
    matrix_loc = glGetUniformLocation(program, "u_projection");
    if (matrix_loc < 0)
        die("projection uniform missing");
    glUniformMatrix4fv(matrix_loc, 1, GL_FALSE, projection);
    {
        GLint model_loc = glGetUniformLocation(program, "u_modelview");
        if (model_loc < 0)
            die("modelview uniform missing");
        glUniformMatrix4fv(model_loc, 1, GL_FALSE, modelview);
    }
    color_loc = glGetUniformLocation(program, "color");
    if (color_loc < 0)
        die("color uniform missing");
    glUniform4f(color_loc, 1.f, 0.f, 0.f, 1.f);

    glGenBuffers(1, &vbo);
    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(verts), verts, GL_STATIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 4, GL_FLOAT, GL_FALSE, 4 * sizeof(float),
                          (void *)0);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);
    glDisable(GL_BLEND);
    glViewport(0, 0, mode.hdisplay, mode.vdisplay);
    glClearColor(0.f, 0.f, 1.f, 1.f);
    glClear(GL_COLOR_BUFFER_BIT);
    step("clear submitted");
    glDrawArrays(GL_TRIANGLES, 0, 3);
    glFinish();
    step("draw finished");

    /* NDC y=-1 is scanout row 0. After the Y flip the triangle sits on
     * the bottom. (360, row 450) is inside it. (420, row 8) is where the
     * same triangle sits when the negative scale is dropped. */
    if (mode.hdisplay != 640 || mode.vdisplay != 480)
        die("gtk color proof expects 640x480");
    outside = pixel_word(8, mode.vdisplay - 1 - 8);
    top = pixel_word(420, mode.vdisplay - 1 - 8);
    inside = pixel_word(360, mode.vdisplay - 1 - (mode.vdisplay - 30));
    reversed = pixel_word(280, mode.vdisplay - 1 - 8);
    clear = pixel_word(mode.hdisplay - 8, 8);
    printf("gl_gtk_color: mode %ux%u outside=0x%08x top=0x%08x inside=0x%08x reversed=0x%08x clear=0x%08x\n",
           mode.hdisplay, mode.vdisplay, outside, top, inside, reversed, clear);
    fflush(stdout);
    if (outside != COLOR_CLEAR || top != COLOR_CLEAR || inside != COLOR_FILL ||
        reversed != COLOR_CLEAR || clear != COLOR_CLEAR) {
        fprintf(stderr, "gl_gtk_color: pixel mismatch\n");
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
    printf("OPENGPU GL GTK COLOR PASS\n");
    fflush(stdout);
    if (getenv("OPENGPU_GL_EXIT"))
        return 0;
    fprintf(stderr, "gl_gtk_color: holding the frame; Ctrl-C to exit\n");
    for (;;)
        pause();
    return 0;
}
