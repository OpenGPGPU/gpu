#include <stdint.h>
void shader(uint32_t *kernarg)
{
    kernarg[1] = kernarg[0] + 1u;
}
