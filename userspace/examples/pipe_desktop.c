/* SPDX-License-Identifier: MIT */
/* Debian desktop frame drawn by the GPU.
 *
 * Background, panel, window and pointer are GPU fill and strided blit.
 * The window content is a GPU triangle. A vertex-core build has to submit
 * that triangle through the vertex shader; the legacy draw record is
 * rejected there. The fragment build loads fragment_tint, which paints
 * every covered pixel. Scanout is that colour GEM.
 * Pass --hold to keep the session up and move the pointer from evdev.
 * The proof is the pixel check below, not how fast the model redraws.
 */
#include "../pipe_opengpu.h"

#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#ifdef __linux__
#include <dirent.h>
#include <linux/input.h>
#include <poll.h>
#include <sys/ioctl.h>
#endif

enum {
    COLOR_BACKGROUND = 0x1b2838ffu,
    COLOR_PANEL = 0x243044ffu,
    COLOR_TITLE = 0x3d6b99ffu,
    COLOR_WINDOW = 0xf2efe8ffu,
    COLOR_CURSOR = 0xffe14affu,
    CURSOR = 16
};

struct desktop {
    uint32_t width;
    uint32_t height;
    uint32_t pitch;
    uint32_t panel_h;
    uint32_t win_x;
    uint32_t win_y;
    uint32_t win_w;
    uint32_t win_h;
    uint32_t title_h;
    uint32_t cursor_x;
    uint32_t cursor_y;
};

static int want_hold(int argc, char **argv)
{
    const char *env = getenv("OPENGPU_PRESENT_HOLD");

    if (env && env[0] && strcmp(env, "0") != 0)
        return 1;
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--hold") == 0)
            return 1;
    }
    return 0;
}

static const char *card_path(int argc, char **argv)
{
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--hold") == 0)
            continue;
        if (argv[i][0] == '-')
            continue;
        return argv[i];
    }
    return NULL;
}

static const char *shader_path(int argc, char **argv)
{
    int seen_card = 0;

    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--hold") == 0)
            continue;
        if (argv[i][0] == '-')
            continue;
        if (!seen_card) {
            seen_card = 1;
            continue;
        }
        return argv[i];
    }
    return "/opengpu_fragment_tint.bin";
}

static int load_shader(const char *path, uint8_t *buf, size_t cap, size_t *out)
{
    int fd = open(path, O_RDONLY);
    ssize_t n;

    if (fd < 0)
        fd = open("/root/opengpu_fragment_tint.bin", O_RDONLY);
    if (fd < 0)
        return -1;
    n = read(fd, buf, cap);
    close(fd);
    if (n <= 0 || (size_t)n > cap || (n & 3)) {
        errno = EINVAL;
        return -1;
    }
    *out = (size_t)n;
    return 0;
}

static int32_t ndc(uint32_t pixel, uint32_t extent)
{
    int64_t num = ((int64_t)pixel * 2 - (int64_t)extent) << 16;

    return (int32_t)(num / (int64_t)extent);
}

static int layout(struct desktop *desk, uint32_t width, uint32_t height,
                  uint32_t pitch)
{
    uint32_t win_w, win_h;

    if (width < 64 || height < 64 || (width & 15u) || (pitch & 63u) ||
        pitch < width * 4u) {
        fprintf(stderr,
                "pipe_desktop: mode %ux%u pitch %u is below 64x64 or not "
                "64-byte aligned\n",
                width, height, pitch);
        errno = EINVAL;
        return -1;
    }
    memset(desk, 0, sizeof(*desk));
    desk->width = width;
    desk->height = height;
    desk->pitch = pitch;
    desk->panel_h = height >= 128 ? 32u : 16u;
    desk->title_h = 16;
    desk->win_x = 16;
    desk->win_y = desk->panel_h;
    win_w = (width / 2u) & ~15u;
    if (win_w < 32 || desk->win_x + win_w > width)
        win_w = 32;
    win_h = height - desk->win_y;
    if (win_h > 16)
        win_h -= 16;
    win_h &= ~15u;
    if (win_h < 32) {
        fprintf(stderr, "pipe_desktop: mode %ux%u leaves no window\n",
                width, height);
        errno = EINVAL;
        return -1;
    }
    desk->win_w = win_w;
    desk->win_h = win_h;
    desk->cursor_x = desk->win_x + desk->win_w - CURSOR;
    desk->cursor_y = desk->win_y;
    return 0;
}

static uint32_t pixel_at(struct pipe_opengpu_resource *color, uint32_t x,
                         uint32_t y)
{
    uint8_t *base = pipe_opengpu_resource_map(color);
    uint32_t pitch = pipe_opengpu_resource_pitch(color);

    return *(uint32_t *)(base + (uint64_t)y * pitch + (uint64_t)x * 4u);
}

static int wait_fence(struct pipe_opengpu_context *ctx,
                      struct pipe_opengpu_fence **fence)
{
    /* A 640x480 FlashSim clear can run past five minutes. */
    if (pipe_opengpu_fence_finish(ctx, *fence, 900000))
        return -1;
    pipe_opengpu_fence_reference(fence, NULL);
    return 0;
}

static int fill_solid(struct pipe_opengpu_context *ctx,
                      struct pipe_opengpu_resource *dst, uint64_t bytes,
                      uint32_t pattern)
{
    struct pipe_opengpu_fence *fence = NULL;

    if (pipe_opengpu_fill(ctx, dst, 0, bytes, pattern, &fence) ||
        wait_fence(ctx, &fence))
        return -1;
    return 0;
}

static int blit_box(struct pipe_opengpu_context *ctx,
                    struct pipe_opengpu_resource *dst,
                    struct pipe_opengpu_resource *src, uint64_t dst_offset,
                    uint64_t src_offset, uint32_t width_bytes, uint32_t height,
                    uint32_t dst_stride, uint32_t src_stride)
{
    struct pipe_opengpu_fence *fence = NULL;

    if (pipe_opengpu_strided_blit(ctx, dst, src, dst_offset, src_offset,
                                  width_bytes, height, dst_stride, src_stride,
                                  &fence) ||
        wait_fence(ctx, &fence))
        return -1;
    return 0;
}

static uint64_t cursor_offset(const struct desktop *desk)
{
    return (uint64_t)desk->cursor_y * desk->pitch + desk->cursor_x * 4u;
}

static int place_cursor(struct pipe_opengpu_context *ctx,
                        struct pipe_opengpu_resource *color,
                        struct pipe_opengpu_resource *backup,
                        struct pipe_opengpu_resource *sprite,
                        const struct desktop *desk)
{
    uint64_t at = cursor_offset(desk);

    if (blit_box(ctx, backup, color, 0, at, CURSOR * 4u, CURSOR, CURSOR * 4u,
                 desk->pitch))
        return -1;
    return blit_box(ctx, color, sprite, at, 0, CURSOR * 4u, CURSOR, desk->pitch,
                    CURSOR * 4u);
}

static int restore_cursor(struct pipe_opengpu_context *ctx,
                          struct pipe_opengpu_resource *color,
                          struct pipe_opengpu_resource *backup,
                          const struct desktop *desk)
{
    return blit_box(ctx, color, backup, cursor_offset(desk), 0, CURSOR * 4u,
                    CURSOR, desk->pitch, CURSOR * 4u);
}

/* Format 0: pos.xyzw Q16.16, packed colour, depth, uv. */
struct desktop_vertex {
    int32_t x, y, z, w;
    uint32_t color;
    int32_t depth;
    uint32_t u, v;
};

/* Copy the eight vertex words into the fragment varyings. */
static size_t write_passthrough_vs(uint32_t *program)
{
    uint32_t pc = 0, field;

    program[pc++] = 0x00241293u;
    program[pc++] = 0x005082b3u;
    program[pc++] = 0xc1027057u;
    for (field = 0; field < 8; field++) {
        uint32_t input_offset = field * 32;
        uint32_t output_offset = (8 + field) * 32;

        program[pc++] = (input_offset << 20) | 0x00028313u;
        program[pc++] = 0x02036087u;
        program[pc++] = (output_offset << 20) | 0x00028313u;
        program[pc++] = 0x020360a7u;
    }
    program[pc++] = 0x30500073u;
    return (size_t)pc * 4u;
}

static void window_vertices(struct desktop_vertex verts[3],
                            const struct desktop *desk)
{
    uint32_t x0 = desk->win_x;
    uint32_t y0 = desk->win_y + desk->title_h;
    uint32_t x1 = desk->win_x + desk->win_w;
    uint32_t y1 = desk->win_y + desk->win_h;
    const uint32_t color = 0xff4040ffu;

    verts[0] = (struct desktop_vertex){
        ndc(x0, desk->width), ndc(y0, desk->height), 0, 0x10000, color, 0x10,
        0, 0};
    verts[1] = (struct desktop_vertex){
        ndc(x1, desk->width), ndc(y0, desk->height), 0, 0x10000, color, 0x10,
        0, 0};
    verts[2] = (struct desktop_vertex){
        ndc(x0, desk->width), ndc(y1, desk->height), 0, 0x10000, color, 0x10,
        0, 0};
}

static void fill_triangle(struct drm_opengpu_draw *draw, const struct desktop *desk)
{
    uint32_t x0 = desk->win_x;
    uint32_t y0 = desk->win_y + desk->title_h;
    uint32_t x1 = desk->win_x + desk->win_w;
    uint32_t y1 = desk->win_y + desk->win_h;

    memset(draw, 0, sizeof(*draw));
    draw->v0[0] = ndc(x0, desk->width);
    draw->v0[1] = ndc(y0, desk->height);
    draw->v1[0] = ndc(x1, desk->width);
    draw->v1[1] = ndc(y0, desk->height);
    draw->v2[0] = ndc(x0, desk->width);
    draw->v2[1] = ndc(y1, desk->height);
    draw->v0[3] = draw->v1[3] = draw->v2[3] = 0x10000;
    draw->c0[0] = draw->c1[0] = draw->c2[0] = 255;
    draw->d0 = draw->d1 = draw->d2 = 0x10;
}

#ifdef __linux__
static int has_axis(int fd)
{
    unsigned long ev[(EV_MAX / (sizeof(long) * 8)) + 1];
    unsigned long absb[(ABS_MAX / (sizeof(long) * 8)) + 1];
    unsigned long rel[(REL_MAX / (sizeof(long) * 8)) + 1];

    memset(ev, 0, sizeof(ev));
    if (ioctl(fd, EVIOCGBIT(0, sizeof(ev)), ev) < 0)
        return 0;
    if ((ev[EV_ABS / (sizeof(long) * 8)] >> (EV_ABS % (sizeof(long) * 8))) & 1) {
        memset(absb, 0, sizeof(absb));
        if (ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(absb)), absb) < 0)
            return 0;
        return (absb[ABS_X / (sizeof(long) * 8)] >>
                (ABS_X % (sizeof(long) * 8))) &
               1;
    }
    if ((ev[EV_REL / (sizeof(long) * 8)] >> (EV_REL % (sizeof(long) * 8))) & 1) {
        memset(rel, 0, sizeof(rel));
        if (ioctl(fd, EVIOCGBIT(EV_REL, sizeof(rel)), rel) < 0)
            return 0;
        return (rel[REL_X / (sizeof(long) * 8)] >>
                (REL_X % (sizeof(long) * 8))) &
               1;
    }
    return 0;
}

static int open_pointer(void)
{
    DIR *dir = opendir("/dev/input");
    struct dirent *ent;
    int found = -1;

    if (!dir)
        return -1;
    while ((ent = readdir(dir)) != NULL) {
        char path[288];
        int fd;

        if (strncmp(ent->d_name, "event", 5) != 0)
            continue;
        snprintf(path, sizeof(path), "/dev/input/%s", ent->d_name);
        fd = open(path, O_RDONLY | O_NONBLOCK);
        if (fd < 0)
            continue;
        if (has_axis(fd)) {
            found = fd;
            break;
        }
        close(fd);
    }
    closedir(dir);
    return found;
}

static uint32_t snap_cursor(int value, int minimum, int maximum, uint32_t limit)
{
    int span = maximum - minimum;
    int pixel;

    if (span <= 0)
        pixel = value;
    else
        pixel = (int)((int64_t)(value - minimum) * (int64_t)(limit - CURSOR) /
                      span);
    if (pixel < 0)
        pixel = 0;
    pixel &= ~15;
    if ((uint32_t)pixel + CURSOR > limit)
        pixel = (int)((limit - CURSOR) & ~15u);
    return (uint32_t)pixel;
}

static int move_pointer(struct pipe_opengpu_context *ctx,
                        struct pipe_opengpu_screen *screen,
                        struct pipe_opengpu_resource *color,
                        struct pipe_opengpu_resource *backup,
                        struct pipe_opengpu_resource *sprite,
                        struct desktop *desk, int pointer)
{
    struct input_absinfo abs_x = { 0 }, abs_y = { 0 };
    int rel = 0;

    if (ioctl(pointer, EVIOCGABS(ABS_X), &abs_x) < 0 ||
        ioctl(pointer, EVIOCGABS(ABS_Y), &abs_y) < 0)
        rel = 1;

    for (;;) {
        struct pollfd pfd = { .fd = pointer, .events = POLLIN };
        struct input_event ev;
        int nx = (int)desk->cursor_x;
        int ny = (int)desk->cursor_y;
        int moved = 0;

        if (poll(&pfd, 1, -1) < 0) {
            if (errno == EINTR)
                continue;
            return -1;
        }
        while (read(pointer, &ev, sizeof(ev)) == (ssize_t)sizeof(ev)) {
            if (ev.type == EV_ABS && ev.code == ABS_X) {
                nx = (int)snap_cursor(ev.value, abs_x.minimum, abs_x.maximum,
                                      desk->width);
                moved = 1;
            } else if (ev.type == EV_ABS && ev.code == ABS_Y) {
                ny = (int)snap_cursor(ev.value, abs_y.minimum, abs_y.maximum,
                                      desk->height);
                moved = 1;
            } else if (rel && ev.type == EV_REL && ev.code == REL_X) {
                nx = (int)snap_cursor((int)desk->cursor_x + ev.value, 0, 0,
                                      desk->width);
                moved = 1;
            } else if (rel && ev.type == EV_REL && ev.code == REL_Y) {
                ny = (int)snap_cursor((int)desk->cursor_y + ev.value, 0, 0,
                                      desk->height);
                moved = 1;
            }
        }
        if (!moved || ((uint32_t)nx == desk->cursor_x &&
                       (uint32_t)ny == desk->cursor_y))
            continue;
        if (restore_cursor(ctx, color, backup, desk))
            return -1;
        desk->cursor_x = (uint32_t)nx;
        desk->cursor_y = (uint32_t)ny;
        if (place_cursor(ctx, color, backup, sprite, desk) ||
            pipe_opengpu_present(screen, color))
            return -1;
    }
}
#endif

int main(int argc, char **argv)
{
    struct pipe_opengpu_screen *screen = NULL;
    struct pipe_opengpu_context *ctx = NULL;
    struct pipe_opengpu_resource *color = NULL;
    struct pipe_opengpu_resource *vertices = NULL;
    struct pipe_opengpu_resource *tiles = NULL;
    struct pipe_opengpu_resource *sprite = NULL;
    struct pipe_opengpu_resource *backup = NULL;
    struct pipe_opengpu_fence *fence = NULL;
    struct drm_opengpu_draw draw;
    struct drm_opengpu_vertex_draw vdraw;
    struct desktop desk;
    struct desktop_vertex verts[3];
    uint32_t vs[64];
    uint8_t shader[256];
    size_t shader_bytes = 0, vs_bytes = 0;
    uint32_t width = 0, height = 0, pitch, win_bytes;
    uint32_t panel, window, triangle, cursor;
    uint64_t caps;
    int status = 1;
    int fragment, vertex, hold;

    hold = want_hold(argc, argv);
    screen = pipe_opengpu_screen_create(card_path(argc, argv));
    if (!screen)
        goto done;
    caps = pipe_opengpu_screen_capabilities(screen);
    fragment = !!(caps & OPENGPU_CAP_FRAGMENT_CORE);
    vertex = !!(caps & OPENGPU_CAP_VERTEX_CORE);
    if ((caps & OPENGPU_CAP_VERTEX_CORE) && !fragment) {
        fprintf(stderr, "pipe_desktop: unexpected vertex-only config\n");
        errno = EINVAL;
        goto done;
    }
    if (!(caps & OPENGPU_CAP_STRIDED_ENGINE)) {
        fprintf(stderr, "pipe_desktop: strided blit is required\n");
        errno = ENODEV;
        goto done;
    }
    if (pipe_opengpu_screen_display_size(screen, &width, &height))
        goto done;

    ctx = pipe_opengpu_context_create(screen);
    color = pipe_opengpu_resource_create_2d(screen, width, height);
    if (!ctx || !color)
        goto done;
    pitch = pipe_opengpu_resource_pitch(color);
    if (layout(&desk, width, height, pitch))
        goto done;
    pipe_opengpu_set_framebuffer(ctx, color, width, height);

    if (fragment) {
        if (load_shader(shader_path(argc, argv), shader, sizeof(shader),
                        &shader_bytes) ||
            pipe_opengpu_bind_fs(ctx, shader, shader_bytes))
            goto done;
    } else if (pipe_opengpu_bind_fs(ctx, NULL, 0)) {
        goto done;
    }
    if (vertex) {
        window_vertices(verts, &desk);
        vs_bytes = write_passthrough_vs(vs);
        vertices = pipe_opengpu_resource_create(screen, sizeof(verts));
        if (!vertices)
            goto done;
        memcpy(pipe_opengpu_resource_map(vertices), verts, sizeof(verts));
        if (pipe_opengpu_bind_vs(ctx, vs, vs_bytes) ||
            pipe_opengpu_set_vertex_buffer(ctx, vertices, sizeof(verts[0]),
                                           sizeof(verts)))
            goto done;
    }

    win_bytes = desk.win_w * 4u * desk.win_h;
    tiles = pipe_opengpu_resource_create(screen, win_bytes);
    sprite = pipe_opengpu_resource_create(screen, CURSOR * 4u * CURSOR);
    backup = pipe_opengpu_resource_create(screen, CURSOR * 4u * CURSOR);
    if (!tiles || !sprite || !backup)
        goto done;

    if (pipe_opengpu_clear(ctx, COLOR_BACKGROUND, &fence) ||
        wait_fence(ctx, &fence))
        goto done;
    if (pipe_opengpu_fill(ctx, color, 0, (uint64_t)desk.panel_h * pitch,
                          COLOR_PANEL, &fence) ||
        wait_fence(ctx, &fence))
        goto done;

    if (fill_solid(ctx, tiles, (uint64_t)desk.win_w * 4u * desk.title_h,
                   COLOR_TITLE) ||
        blit_box(ctx, color, tiles,
                 (uint64_t)desk.win_y * pitch + desk.win_x * 4u, 0,
                 desk.win_w * 4u, desk.title_h, pitch, desk.win_w * 4u))
        goto done;
    if (fill_solid(ctx, tiles,
                   (uint64_t)desk.win_w * 4u * (desk.win_h - desk.title_h),
                   COLOR_WINDOW) ||
        blit_box(ctx, color, tiles,
                 (uint64_t)(desk.win_y + desk.title_h) * pitch +
                     desk.win_x * 4u,
                 0, desk.win_w * 4u, desk.win_h - desk.title_h, pitch,
                 desk.win_w * 4u))
        goto done;

    if (vertex) {
        memset(&vdraw, 0, sizeof(vdraw));
        vdraw.vertex_count = 3;
        vdraw.vertex_stride = sizeof(verts[0]);
        vdraw.fragment_kernarg_bank_stride = 320;
        vdraw.state = OPENGPU_DRAW_STATE_OVERRIDE;
        if (pipe_opengpu_draw_vertex(ctx, &vdraw, &fence) ||
            wait_fence(ctx, &fence))
            goto done;
    } else {
        fill_triangle(&draw, &desk);
        if (pipe_opengpu_draw_vbo(ctx, &draw, &fence) || wait_fence(ctx, &fence))
            goto done;
    }
    if (fill_solid(ctx, sprite, CURSOR * 4u * CURSOR, COLOR_CURSOR) ||
        place_cursor(ctx, color, backup, sprite, &desk))
        goto done;
    if (pipe_opengpu_present(screen, color))
        goto done;

    panel = pixel_at(color, 4, 4);
    window = pixel_at(color, desk.win_x + desk.win_w - 4,
                      desk.win_y + desk.win_h - 4);
    triangle = pixel_at(color, desk.win_x + 4, desk.win_y + desk.title_h + 4);
    cursor = pixel_at(color, desk.cursor_x + 4, desk.cursor_y + 4);
    if (panel != COLOR_PANEL || window != COLOR_WINDOW ||
        cursor != COLOR_CURSOR || triangle == COLOR_WINDOW ||
        triangle == COLOR_BACKGROUND || triangle == 0) {
        fprintf(stderr,
                "pipe_desktop: panel=0x%08x window=0x%08x triangle=0x%08x "
                "cursor=0x%08x\n",
                panel, window, triangle, cursor);
        errno = EIO;
        goto done;
    }

    printf("OPENGPU DESKTOP PASS: %ux%u panel=0x%08x window=0x%08x "
           "triangle=0x%08x cursor=0x%08x%s\n",
           width, height, panel, window, triangle, cursor,
           fragment ? " tint" : "");
    fflush(stdout);
    status = 0;
    if (hold) {
#ifdef __linux__
        int pointer = open_pointer();

        if (pointer >= 0) {
            if (move_pointer(ctx, screen, color, backup, sprite, &desk,
                             pointer))
                status = 1;
            close(pointer);
        } else {
            for (;;)
                pause();
        }
#else
        for (;;)
            pause();
#endif
    }

done:
    if (status)
        perror("pipe_desktop");
    pipe_opengpu_fence_reference(&fence, NULL);
    if (backup)
        pipe_opengpu_resource_destroy(screen, backup);
    if (sprite)
        pipe_opengpu_resource_destroy(screen, sprite);
    if (tiles)
        pipe_opengpu_resource_destroy(screen, tiles);
    if (vertices)
        pipe_opengpu_resource_destroy(screen, vertices);
    if (color)
        pipe_opengpu_resource_destroy(screen, color);
    if (ctx)
        pipe_opengpu_context_destroy(ctx);
    if (screen)
        pipe_opengpu_screen_destroy(screen);
    return status;
}
