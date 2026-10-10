/* Minimal GTK3 window exercising the OpenGPU GtkGLArea path.
 * The panel is drawn by OpenGL ES; GTK owns the window, label and button.
 */
#include <gtk/gtk.h>
#include <epoxy/gl.h>
#include <math.h>

struct AppState {
    GtkWidget *area;
    gboolean active;
    GLuint program;
    GLuint vbo;
};

static const char *vs_src =
    "attribute vec2 position;\n"
    "void main() { gl_Position = vec4(position, 0.0, 1.0); }\n";
static const char *fs_src =
    "precision mediump float;\n"
    "uniform vec4 color;\n"
    "void main() { gl_FragColor = color; }\n";

static GLuint compile_shader(GLenum type, const char *source)
{
    GLuint shader = glCreateShader(type);
    GLint ok = 0;
    glShaderSource(shader, 1, &source, NULL);
    glCompileShader(shader);
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[512];
        glGetShaderInfoLog(shader, sizeof log, NULL, log);
        g_printerr("gtk_opengpu_smoke: shader compile failed: %s\n", log);
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

static void realize(GtkGLArea *area, gpointer data)
{
    struct AppState *state = data;
    GLuint vs, fs;
    GLint ok = 0;
    const GLfloat vertices[] = {
        -0.82f, -0.60f,  0.82f, -0.60f,  0.82f, 0.60f,
        -0.82f, -0.60f,  0.82f,  0.60f, -0.82f, 0.60f,
    };
    g_print("OPENGPU GTK REALIZE ENTER\n");
    fflush(stdout);
    gtk_gl_area_make_current(area);
    {
        GError *error = gtk_gl_area_get_error(area);
        if (error) {
            g_printerr("OPENGPU GTK REALIZE ERROR: %s\n", error->message);
            return;
        }
    }
    {
        const char *vendor = (const char *)glGetString(GL_VENDOR);
        const char *renderer = (const char *)glGetString(GL_RENDERER);
        g_print("OPENGPU GTK GL VENDOR: %s\n", vendor ? vendor : "<null>");
        g_print("OPENGPU GTK GL RENDERER: %s\n", renderer ? renderer : "<null>");
        if (!renderer || !g_strrstr(renderer, "opengpu")) {
            g_printerr("OPENGPU GTK REALIZE ERROR: renderer is not OpenGPU\n");
            return;
        }
    }
    vs = compile_shader(GL_VERTEX_SHADER, vs_src);
    fs = compile_shader(GL_FRAGMENT_SHADER, fs_src);
    if (!vs || !fs)
        return;
    state->program = glCreateProgram();
    glAttachShader(state->program, vs);
    glAttachShader(state->program, fs);
    glBindAttribLocation(state->program, 0, "position");
    glLinkProgram(state->program);
    glGetProgramiv(state->program, GL_LINK_STATUS, &ok);
    glDeleteShader(vs);
    glDeleteShader(fs);
    if (!ok) {
        g_printerr("gtk_opengpu_smoke: program link failed\n");
        return;
    }
    glGenBuffers(1, &state->vbo);
    glBindBuffer(GL_ARRAY_BUFFER, state->vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof vertices, vertices, GL_STATIC_DRAW);
    g_print("OPENGPU GTK REALIZE PASS\n");
    fflush(stdout);
}

static gboolean render(GtkGLArea *area, GdkGLContext *context, gpointer data)
{
    struct AppState *state = data;
    GLint color;
    (void)context;
    g_print("OPENGPU GTK RENDER ENTER\n");
    fflush(stdout);
    gtk_gl_area_make_current(area);
    if (gtk_gl_area_get_error(area)) {
        g_printerr("OPENGPU GTK RENDER ERROR\n");
        return FALSE;
    }
    glViewport(0, 0, gtk_widget_get_allocated_width(GTK_WIDGET(area)),
               gtk_widget_get_allocated_height(GTK_WIDGET(area)));
    glClearColor(0.04f, 0.06f, 0.10f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    glUseProgram(state->program);
    color = glGetUniformLocation(state->program, "color");
    glUniform4f(color, state->active ? 0.95f : 0.20f,
                state->active ? 0.35f : 0.55f, 0.18f, 1.0f);
    glBindBuffer(GL_ARRAY_BUFFER, state->vbo);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, NULL);
    glDrawArrays(GL_TRIANGLES, 0, 6);
    glDisableVertexAttribArray(0);
    static gboolean reported;
    if (!reported) {
        reported = TRUE;
        g_print("OPENGPU GTK FRAME PASS\n");
        fflush(stdout);
    }
    return TRUE;
}

static void clicked(GtkButton *button, gpointer data)
{
    struct AppState *state = data;
    (void)button;
    state->active = !state->active;
    gtk_widget_queue_draw(state->area);
    g_print("OPENGPU GTK REDRAW: %s\n", state->active ? "active" : "idle");
}

static gboolean automatic_redraw(gpointer data)
{
    clicked(NULL, data);
    return G_SOURCE_REMOVE;
}

static void activate(GtkApplication *application, gpointer data)
{
    struct AppState *state = data;
    GtkWidget *window, *box, *label, *button;
    g_print("OPENGPU GTK ACTIVATE PASS\n");
    fflush(stdout);
    window = gtk_application_window_new(application);
    gtk_window_set_title(GTK_WINDOW(window), "OpenGPU GTK smoke");
    gtk_window_set_default_size(GTK_WINDOW(window), 320, 240);
    box = gtk_box_new(GTK_ORIENTATION_VERTICAL, 12);
    gtk_container_set_border_width(GTK_CONTAINER(box), 20);
    label = gtk_label_new("OpenGPU GtkGLArea");
    gtk_box_pack_start(GTK_BOX(box), label, FALSE, FALSE, 0);
    state->area = gtk_gl_area_new();
    gtk_gl_area_set_use_es(GTK_GL_AREA(state->area), TRUE);
    gtk_gl_area_set_required_version(GTK_GL_AREA(state->area), 2, 0);
    gtk_gl_area_set_has_depth_buffer(GTK_GL_AREA(state->area), FALSE);
    g_signal_connect(state->area, "realize", G_CALLBACK(realize), state);
    g_signal_connect(state->area, "render", G_CALLBACK(render), state);
    gtk_box_pack_start(GTK_BOX(box), state->area, TRUE, TRUE, 0);
    button = gtk_button_new_with_label("Redraw GPU panel");
    g_signal_connect(button, "clicked", G_CALLBACK(clicked), state);
    gtk_box_pack_start(GTK_BOX(box), button, FALSE, FALSE, 0);
    gtk_container_add(GTK_CONTAINER(window), box);
    g_print("OPENGPU GTK WINDOW BUILT\n");
    fflush(stdout);
    gtk_widget_show(window);
    gtk_widget_show(box);
    gtk_widget_show(label);
    gtk_widget_show(state->area);
    gtk_widget_show(button);
    g_print("OPENGPU GTK SHOW PASS\n");
    fflush(stdout);
    gtk_window_present(GTK_WINDOW(window));
    g_print("OPENGPU GTK PRESENT PASS\n");
    fflush(stdout);
    /* Keep the unattended guest proof deterministic while retaining the same
     * callback used by the real button. */
    g_timeout_add_seconds(3, automatic_redraw, state);
}

int main(int argc, char **argv)
{
    struct AppState state = {0};
    GtkApplication *application;
    int status;
    application = gtk_application_new("org.opengpu.GtkSmoke", G_APPLICATION_DEFAULT_FLAGS);
    g_signal_connect(application, "activate", G_CALLBACK(activate), &state);
    status = g_application_run(G_APPLICATION(application), argc, argv);
    g_object_unref(application);
    return status;
}
