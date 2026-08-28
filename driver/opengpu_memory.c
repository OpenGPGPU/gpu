// SPDX-License-Identifier: GPL-2.0
/* Shared buffer allocation; GEM DMA objects will build on this layer. */
#include "opengpu_device.h"

int opengpu_buffer_alloc(struct opengpu_device *gpu,
                         struct opengpu_buffer *buffer, size_t size)
{
    buffer->size = size;
    buffer->cpu = dma_alloc_coherent(gpu->dev, size, &buffer->dma,
                                     GFP_KERNEL);
    return buffer->cpu ? 0 : -ENOMEM;
}

void opengpu_buffer_free(struct opengpu_device *gpu,
                         struct opengpu_buffer *buffer)
{
    if (!buffer->cpu)
        return;
    dma_free_coherent(gpu->dev, buffer->size, buffer->cpu, buffer->dma);
    buffer->cpu = NULL;
}
