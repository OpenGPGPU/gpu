#include <stdint.h>
void shader(uint32_t *kernarg)
{
    kernarg[64] = kernarg[0] + 0x100u;
}
