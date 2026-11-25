// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

#ifndef MIRU_CORE_H_
#define MIRU_CORE_H_

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct MiruAdblockHandle MiruAdblockHandle;

typedef struct MiruBytes {
  uint8_t* data;
  size_t len;
  size_t cap;
} MiruBytes;

typedef struct MiruResolution {
  uint32_t width;
  uint32_t height;
  int valid;
} MiruResolution;

/* Adblock (URLLoaderThrottle / NavigationThrottle) */
MiruAdblockHandle* miru_adblock_create_from_rules(const char* rules_utf8);
MiruAdblockHandle* miru_adblock_create_from_snapshot(const uint8_t* data, size_t len);
MiruBytes miru_adblock_serialize_snapshot(const MiruAdblockHandle* handle);
int miru_adblock_should_block(const MiruAdblockHandle* handle,
                              const char* url_utf8,
                              const char* source_url_utf8,
                              const char* request_type_utf8);
char* miru_adblock_cosmetic_json(const MiruAdblockHandle* handle, const char* url_utf8);
void miru_adblock_set_enabled(const MiruAdblockHandle* handle, int enabled);
void miru_adblock_set_site_allowed(const MiruAdblockHandle* handle,
                                   const char* host_utf8,
                                   int allowed);
void miru_adblock_destroy(MiruAdblockHandle* handle);

/* Media sniffing & header-only resolution probe */
char* miru_media_classify(const char* url_utf8,
                          const char* content_type_utf8,
                          int64_t content_length);
MiruResolution miru_media_probe_bytes(const uint8_t* data, size_t len);

/* URL & cache-key normalization */
char* miru_url_strip_signature_params(const char* url_utf8);

/* Memory disposal */
void miru_bytes_free(MiruBytes bytes);
void miru_string_free(char* ptr);

#ifdef __cplusplus
}
#endif

#endif  // MIRU_CORE_H_
