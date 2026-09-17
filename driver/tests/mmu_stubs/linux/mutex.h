/* SPDX-License-Identifier: GPL-2.0 */
/* Single-threaded dependency stub; checks the production MMU's lock scope. */
#ifndef OPENGPU_TEST_MUTEX_H
#define OPENGPU_TEST_MUTEX_H
#include <assert.h>
struct mutex { int held; };
static inline void mutex_init(struct mutex *m) { m->held = 0; }
static inline void mutex_lock(struct mutex *m)
{
    assert(!m->held);
    m->held = 1;
}
static inline void mutex_unlock(struct mutex *m)
{
    assert(m->held);
    m->held = 0;
}
#endif
