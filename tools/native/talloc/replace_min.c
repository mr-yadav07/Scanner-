/* Android bionic implementation for the one libreplace symbol used by talloc. */
#include <string.h>
void *rep_memset_explicit(void *block, int c, size_t size) {
    void *ptr = memset(block, c, size);
    __asm__ volatile("" : : "r"(block) : "memory");
    return ptr;
}
