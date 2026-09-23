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
    default: return 1;
    }
    if (!valid) {
        fprintf(stderr, "%s: rejected by profile %d\n", path, profile);
        return 1;
    }
    printf("%s: PASS (%zu instructions)\n", path, count);
    return 0;
}

int main(int argc, char **argv)
{
    if (argc != 9) {
        fprintf(stderr,
                "usage: %s copy round masked fixed widen compute fragment vertex\n",
                argv[0]);
        return 2;
    }
    return validate(argv[1], 0) || validate(argv[2], 0) ||
           validate(argv[3], 0) || validate(argv[4], 0) ||
           validate(argv[5], 0) || validate(argv[6], 0) ||
           validate(argv[7], 1) || validate(argv[8], 2);
}
