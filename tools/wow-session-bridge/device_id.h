#ifndef DEVICE_ID_H
#define DEVICE_ID_H

#include <stddef.h>

/* Resets the bounded device-JSON sink owned by bridge.c. */
void bridge_device_reset(void);
/* Appends raw bytes to the device-JSON sink; sets an overflow flag on excess. */
void bridge_device_append(const char *text, size_t length);

/* Initialize a missing identity and fill the prefix's 23-field Device-Id JSON. */
int pipe_device(void);

#endif
