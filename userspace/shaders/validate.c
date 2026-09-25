/* SPDX-License-Identifier: MIT */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include "../../driver/opengpu_shader_validator.h"

static int validate(const char *path, int profile)
{
    uint32_t words[OPENGPU_SHADER_MAX_INSTRUCTIONS] = { 0 };
    FILE *file = fopen(path, "rb");
    long bytes;
    size_t count;
    int valid;
    if (!file) { perror(path); return 1; }
    if (fseek(file, 0, SEEK_END) || (bytes = ftell(file)) <= 0 ||
        bytes % 4 || bytes > (long)sizeof(words) || fseek(file, 0, SEEK_SET)) {
        fprintf(stderr, "%s: invalid shader size\n", path);
        fclose(file);
        return 1;
    }
    count = (size_t)bytes / 4;
    if (fread(words, sizeof(words[0]), count, file) != count) {
        fprintf(stderr, "%s: short read\n", path);
        fclose(file);
        return 1;
    }
    fclose(file);
    if (words[count - 1] != OPENGPU_SHADER_CEASE) {
        fprintf(stderr, "%s: missing cease\n", path);
        return 1;
    }
    switch (profile) {
    case 0: valid = opengpu_compute_shader_validate_words(words, count, 64, 1); break;
    case 1: valid = opengpu_shader_validate_words(words, count, 288, 8); break;
    case 2: valid = opengpu_vertex_shader_validate_words(words, count, 512, 8); break;
    case 3: valid = opengpu_shader_validate_words_with_texture(
                words, count, 288, 8, true); break;
    case 4: valid = opengpu_compute_shader_validate_words(words, count, 64, 4); break;
    default: return 1;
    }
    if (!valid) {
        fprintf(stderr, "%s: rejected by profile %d\n", path, profile);
        return 1;
    }
    printf("%s: PASS (%zu instructions, profile %d)\n", path, count, profile);
    return 0;
}

int main(int argc, char **argv)
{
    int i;

    if (argc < 3 || ((argc - 1) & 1)) {
        fprintf(stderr,
                "usage: %s <bin profile>...\n"
                "profiles: 0=compute 1=fragment 2=vertex 3=fragment+texture "
                "4=compute/local4\n",
                argv[0]);
        return 2;
    }
    for (i = 1; i + 1 < argc; i += 2) {
        if (validate(argv[i], atoi(argv[i + 1])))
            return 1;
    }
    return 0;
}
