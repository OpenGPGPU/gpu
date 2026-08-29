/* SPDX-License-Identifier: GPL-2.0 */
/* Static init used by the OpenGPU ARTI userspace DRM test. */
#include <sys/mount.h>
#include <sys/reboot.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <linux/reboot.h>
#include <fcntl.h>
#include <stddef.h>
#include <string.h>
#include <unistd.h>

#ifndef __NR_finit_module
#define __NR_finit_module 413
#endif

#ifndef ARTI_TEST_HOLD_SECONDS
#define ARTI_TEST_HOLD_SECONDS 1
#endif

static void putstr(const char *text)
{
    write(STDOUT_FILENO, text, strlen(text));
}

static int load_module(const char *path, const char *name)
{
    int fd = open(path, O_RDONLY);
    int ret;

    if (fd < 0)
        return -1;
    ret = syscall(__NR_finit_module, fd, "", 0);
    close(fd);
    if (!ret) {
        putstr("ARTI Linux init: loaded ");
        putstr(name);
        putstr("\r\n");
    }
    return ret;
}

static int load_dependencies(void)
{
    char manifest[4096];
    int fd = open("/arti_driver_deps", O_RDONLY);
    ssize_t count;
    size_t start = 0;

    if (fd < 0)
        return 0;
    count = read(fd, manifest, sizeof(manifest) - 1);
    close(fd);
    if (count <= 0)
        return -1;
    manifest[count] = 0;

    for (size_t i = 0; i <= (size_t)count; i++) {
        if (manifest[i] != '\n' && manifest[i] != 0)
            continue;
        manifest[i] = 0;
        if (i > start && load_module(manifest + start,
                                     manifest + start) < 0)
            return -1;
        start = i + 1;
    }
    return 0;
}

static int run_drm_test(void)
{
    pid_t child = fork();
    int status;

    if (child < 0)
        return -1;
    if (!child) {
        char *const argv[] = { "/opengpu_drm_test", NULL };
        char *const envp[] = { NULL };

        execve(argv[0], argv, envp);
        _exit(127);
    }
    if (waitpid(child, &status, 0) < 0)
        return -1;
    return WIFEXITED(status) && WEXITSTATUS(status) == 0 ? 0 : -1;
}

int main(void)
{
    int console;

    mount("proc", "/proc", "proc", 0, NULL);
    mount("sysfs", "/sys", "sysfs", 0, NULL);
    mount("devtmpfs", "/dev", "devtmpfs", 0, NULL);
    console = open("/dev/console", O_WRONLY);
    if (console >= 0) {
        dup2(console, STDOUT_FILENO);
        dup2(console, STDERR_FILENO);
    }

    putstr("ARTI Linux init: loading OpenGPU DRM stack...\r\n");
    if (load_dependencies() < 0 ||
        load_module("/arti_driver.ko", "arti_driver") < 0) {
        putstr("OPENGPU USERSPACE DRM FAIL: module load\r\n");
    } else if (run_drm_test() < 0) {
        putstr("OPENGPU USERSPACE DRM FAIL: test process\r\n");
    }

    sync();
    sleep(ARTI_TEST_HOLD_SECONDS);
    putstr("ARTI Linux init: done, powering off\r\n");
    reboot(LINUX_REBOOT_CMD_POWER_OFF);
    for (;;)
        pause();
}
