#include <stdint.h>
void shader(uint32_t *kernarg)
{
    kernarg[48] = kernarg[24] ^ 0x00ff00ffu;
}
