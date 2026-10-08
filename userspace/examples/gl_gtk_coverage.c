/* GTK 4.14 color.glsl fragment: gskSetOutputColor(final_color).
 * The color program is not NO_CLIP, so this is gsk_rounded_rect_coverage
 * from preamble.fs.glsl, written in ESSL 100. The vertex program is the
 * plain position copy. The CPU only packs the vertex buffer and the
 * clip-rect uniforms.
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
    "void main()\n"
    "{\n"
    "    gl_Position = aPosition;\n"
    "}\n";

static const char *fs_src =
    "precision highp float;\n"
    "uniform lowp vec4 color;\n"
    "uniform highp vec4 bounds;\n"
    "uniform highp vec4 corner1;\n"
    "uniform highp vec4 corner2;\n"
    "float ellipsis_coverage(vec2 point, vec2 center, vec2 radius)\n"
    "{\n"
    "    vec2 p = point - center;\n"
    "    if (radius.x == 0.0 && radius.y == 0.0)\n"
    "        return 0.0;\n"
    "    vec2 p0 = p / radius;\n"
    "    vec2 p1 = 2.0 * p0 / radius;\n"
    "    return clamp(0.5 - (dot(p0, p0) - 1.0) / length(p1), 0.0, 1.0);\n"
    "}\n"
    "void main()\n"
    "{\n"
    "    vec2 p = gl_FragCoord.xy;\n"
    "    float coverage;\n"
    "    if (p.x < bounds.x || p.y < bounds.y ||\n"
    "        p.x >= bounds.z || p.y >= bounds.w)\n"
    "        coverage = 0.0;\n"
    "    else if (p.x >= corner1.x && p.x >= corner2.z &&\n"
    "             p.x <= corner1.z && p.x <= corner2.x)\n"
    "        coverage = 1.0;\n"
    "    else if (p.y >= corner1.y && p.y >= corner1.w &&\n"
    "             p.y <= corner2.w && p.y <= corner2.y)\n"
    "        coverage = 1.0;\n"
    "    else {\n"
    "        vec2 rad_tl = corner1.xy - bounds.xy;\n"
    "        vec2 rad_tr = vec2(corner1.z, corner1.w) - vec2(bounds.z, bounds.y);\n"
    "        vec2 rad_br = corner2.xy - bounds.zw;\n"
    "        vec2 rad_bl = vec2(corner2.z, corner2.w) - vec2(bounds.x, bounds.w);\n"
    "        float d_tl = ellipsis_coverage(p, corner1.xy, rad_tl);\n"
    "        float d_tr = ellipsis_coverage(p, vec2(corner1.z, corner1.w), rad_tr);\n"
    "        float d_br = ellipsis_coverage(p, corner2.xy, rad_br);\n"
    "        float d_bl = ellipsis_coverage(p, vec2(corner2.z, corner2.w), rad_bl);\n"
    "        vec4 cover = 1.0 - vec4(d_tl, d_tr, d_br, d_bl);\n"
    "        vec4 outside = vec4(\n"
    "            float(p.x < corner1.x && p.y < corner1.y),\n"
    "            float(p.x > corner1.z && p.y < corner1.w),\n"
    "            float(p.x > corner2.x && p.y > corner2.y),\n"
    "            float(p.x < corner2.z && p.y > corner2.w));\n"
    "        coverage = 1.0 - dot(outside, cover);\n"
    "    }\n"
    "    gl_FragColor = color * coverage;\n"
    "}\n";

/* Unshifted corner. At 640x480 the triangle is x=0..160, scanout y=0..60. */
static const float verts[] = {
    -1.f, -1.f,   0.f, 1.f,
    -0.5f, -1.f,  0.f, 1.f,
    -1.f, -0.75f, 0.f, 1.f,
};

static void crash(int sig)
{
    void *frames[32];
    int n = backtrace(frames, 32);
    static const char msg[] = "gl_gtk_coverage: fatal signal, backtrace:\n";

    write(2, msg, sizeof(msg) - 1);
    backtrace_symbols_fd(frames, n, 2);
    signal(sig, SIG_DFL);
    raise(sig);
}

static void step(const char *what)
{
    fprintf(stderr, "gl_gtk_coverage: %s\n", what);
}

static void die(const char *what)
{
    fprintf(stderr, "gl_gtk_coverage: %s\n", what);
    exit(1);
}

static void die_errno(const char *what)
{
    fprintf(stderr, "gl_gtk_coverage: %s: %s\n", what, strerror(errno));
    exit(1);
}

static void die_gl(const char *what)
{
    fprintf(stderr, "gl_gtk_coverage: %s (egl 0x%x)\n", what, eglGetError());
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
        fprintf(stderr, "gl_gtk_coverage: shader compile failed: %s\n", log);
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
    GLint linked = 0, color_loc;
    uint32_t crtc_id, fb = 0;
    uint32_t handles[4] = { 0 }, pitches[4] = { 0 }, offsets[4] = { 0 };
    uint32_t inside, outside;
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
        fprintf(stderr, "gl_gtk_coverage: eglChooseConfig matched %d configs\n",
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
        fprintf(stderr, "gl_gtk_coverage: link failed: %s\n", log);
        return 1;
    }
    glUseProgram(program);
    color_loc = glGetUniformLocation(program, "color");
    if (color_loc < 0)
        die("color uniform missing");
    glUniform4f(color_loc, 1.f, 0.f, 0.f, 1.f);
    {
        GLint bounds_loc = glGetUniformLocation(program, "bounds");
        GLint c1_loc = glGetUniformLocation(program, "corner1");
        GLint c2_loc = glGetUniformLocation(program, "corner2");
        if (bounds_loc < 0 || c1_loc < 0 || c2_loc < 0)
            die("coverage uniform missing");
        /* Axis-aligned clip of the whole framebuffer. Corner references
         * sit on the bounds, so a lowered shader covers the interior. */
        glUniform4f(bounds_loc, 0.f, 0.f, (float)mode.hdisplay, (float)mode.vdisplay);
        glUniform4f(c1_loc, 0.f, (float)mode.vdisplay, (float)mode.hdisplay, (float)mode.vdisplay);
        glUniform4f(c2_loc, (float)mode.hdisplay, 0.f, 0.f, 0.f);
    }

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

    /* NDC y=-1 is scanout row 0. The triangle covers x=0..160, rows 0..60.
     * (8, row 8) is inside. The far corner stays the clear color. */
    if (mode.hdisplay != 640 || mode.vdisplay != 480)
        die("gtk coverage proof expects 640x480");
    inside = pixel_word(8, mode.vdisplay - 1 - 8);
    outside = pixel_word(mode.hdisplay - 8, 8);
    printf("gl_gtk_coverage: mode %ux%u inside=0x%08x outside=0x%08x\n",
           mode.hdisplay, mode.vdisplay, inside, outside);
    fflush(stdout);
    if (inside != COLOR_FILL || outside != COLOR_CLEAR) {
        fprintf(stderr, "gl_gtk_coverage: pixel mismatch\n");
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
    printf("OPENGPU GL GTK COVERAGE PASS\n");
    fflush(stdout);
    if (getenv("OPENGPU_GL_EXIT"))
        return 0;
    fprintf(stderr, "gl_gtk_coverage: holding the frame; Ctrl-C to exit\n");
    for (;;)
        pause();
    return 0;
}
