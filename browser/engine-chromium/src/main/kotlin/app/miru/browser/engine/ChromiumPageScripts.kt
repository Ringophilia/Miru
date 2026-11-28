// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import org.json.JSONObject

/**
 * Self-contained isolated-world scripts executed in `ISOLATED_WORLD_ID_CONTENT_END` via C++ `MiruTab`.
 * Events travel back to C++ over `DidAddMessageToConsole` authenticated with a per-tab random
 * 128-bit `__MIRU_NONCE__` token substituted strictly inside native memory.
 */
internal object ChromiumPageScripts {
    fun pageAgentScript(holdEnabled: Boolean, holdSpeed: Float): String {
        val enabledLit = if (holdEnabled) "true" else "false"
        val speedLit = "%.2f".format(holdSpeed.coerceIn(0.5f, 3.0f))
        return """
            (() => {
              if (globalThis.__miruAgentInstalled) {
                globalThis.__miruConfigureHold?.({ holdEnabled: $enabledLit, holdSpeed: $speedLit });
                globalThis.__miruReportMedia?.('');
                return;
              }
              globalThis.__miruAgentInstalled = true;
              const emit = (payload) => {
                try { console.info('__MIRU_EVT__:__MIRU_NONCE__:' + JSON.stringify(payload)); } catch (_) {}
              };

              // 1. Long-press video speed controller (respects site preventDefault & fullscreens).
              let holdActive = null, holdTimer = null, holdHost = null;
              let holdCfg = { holdEnabled: $enabledLit, holdSpeed: $speedLit };
              const isPlaying = (v) => v && v.isConnected && !v.paused && !v.ended;
              const hideHoldPill = () => {
                if (holdHost) { try { holdHost.hidePopover?.(); } catch (_) {} holdHost.remove(); holdHost = null; }
              };
              const showHoldPill = (v, rate) => {
                hideHoldPill();
                const el = document.createElement('span');
                el.setAttribute('aria-hidden', 'true');
                el.setAttribute('popover', 'manual');
                el.style.cssText = 'all:initial!important;position:fixed!important;inset:auto!important;display:block!important;width:max-content!important;z-index:2147483647!important;transform:translateX(-50%)!important;pointer-events:none!important;';
                const shadow = el.attachShadow({ mode: 'closed' });
                const pill = document.createElement('span');
                pill.style.cssText = 'display:block;padding:8px 14px;border-radius:999px;background:rgba(15,15,18,.82);color:#fff;font:700 15px/1.4 system-ui,sans-serif;white-space:nowrap;';
                pill.textContent = rate.toFixed(1) + 'x ▶▶';
                shadow.append(pill);
                const parent = (document.fullscreenElement && !(document.fullscreenElement instanceof HTMLMediaElement))
                  ? document.fullscreenElement : document.documentElement;
                parent?.append(el);
                const r = v.getBoundingClientRect();
                el.style.setProperty('left', Math.min(window.innerWidth - 12, Math.max(12, r.left + r.width / 2)) + 'px', 'important');
                el.style.setProperty('top', Math.max(8, r.top + 12) + 'px', 'important');
                holdHost = el;
                try { el.showPopover?.(); } catch (_) {}
              };
              const cancelHold = () => {
                if (holdTimer) { clearTimeout(holdTimer); holdTimer = null; }
                const prev = holdActive;
                holdActive = null;
                if (prev && prev.applied && prev.video.playbackRate === prev.appliedRate) {
                  try { prev.video.playbackRate = prev.originalRate; } catch (_) {}
                }
                hideHoldPill();
              };
              globalThis.__miruConfigureHold = (next) => {
                holdCfg = {
                  holdEnabled: next?.holdEnabled !== false,
                  holdSpeed: Number.isFinite(next?.holdSpeed) ? Math.min(3, Math.max(0.5, next.holdSpeed)) : 2,
                };
                if (!holdCfg.holdEnabled) cancelHold();
              };
              const videoFromEvent = (e) => e.composedPath().find((n) => n instanceof HTMLVideoElement);
              document.addEventListener('pointerdown', (e) => {
                if (!e.isTrusted || !holdCfg.holdEnabled) return;
                const video = videoFromEvent(e);
                if (!video || e.isPrimary === false) { cancelHold(); return; }
                queueMicrotask(() => {
                  if (e.defaultPrevented || !isPlaying(video)) return;
                  cancelHold();
                  const state = { video, id: e.pointerId, x: e.clientX, y: e.clientY, applied: false };
                  holdActive = state;
                  holdTimer = setTimeout(() => {
                    if (holdActive !== state || !isPlaying(video)) { cancelHold(); return; }
                    state.originalRate = video.playbackRate;
                    state.appliedRate = holdCfg.holdSpeed;
                    try {
                      video.playbackRate = holdCfg.holdSpeed;
                      if (video.playbackRate !== holdCfg.holdSpeed) { cancelHold(); return; }
                      state.applied = true;
                      showHoldPill(video, holdCfg.holdSpeed);
                    } catch (_) { cancelHold(); }
                  }, 500);
                });
              }, { passive: true });
              document.addEventListener('pointermove', (e) => {
                if (!holdActive || e.pointerId !== holdActive.id) return;
                if (Math.hypot(e.clientX - holdActive.x, e.clientY - holdActive.y) > 10) cancelHold();
              }, { capture: true, passive: true });
              document.addEventListener('pointerup', (e) => { if (holdActive?.id === e.pointerId) cancelHold(); }, { capture: true, passive: true });
              document.addEventListener('pointercancel', cancelHold, { capture: true, passive: true });
              document.addEventListener('contextmenu', (e) => {
                const img = e.composedPath?.().find((n) => n instanceof HTMLImageElement) || (e.target instanceof HTMLImageElement ? e.target : null);
                if (img) globalThis.__miruLastContextImg = new WeakRef(img);
                if (holdActive && videoFromEvent(e) === holdActive.video) e.preventDefault();
              }, { capture: true, passive: false });
              document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'hidden') cancelHold(); }, { capture: true, passive: true });

              // 1b. Fail-closed <meta name="referrer"> tracker for context image policy.
              const validReferrerTokens = new Set([
                'no-referrer', 'no-referrer-when-downgrade', 'same-origin', 'origin',
                'strict-origin', 'origin-when-cross-origin', 'strict-origin-when-cross-origin',
                'unsafe-url', 'never', 'always', 'default', 'origin-when-crossorigin',
              ]);
              const checkMetaElement = (el) => {
                if (!(el instanceof HTMLMetaElement)) return;
                if ((el.getAttribute('name') || '').trim().toLowerCase() !== 'referrer') return;
                const val = (el.getAttribute('content') || '').trim().toLowerCase();
                if (!validReferrerTokens.has(val)) {
                  globalThis.__miruMetaReferrerUnknown = true;
                }
              };
              const checkAllReferrerMetas = () => {
                for (const m of document.querySelectorAll('meta')) checkMetaElement(m);
              };
              checkAllReferrerMetas();
              const metaObserver = new MutationObserver((records) => {
                for (const r of records) {
                  if (r.type === 'attributes') {
                    checkMetaElement(r.target);
                  } else if (r.type === 'childList') {
                    for (const n of r.addedNodes) {
                      if (n instanceof HTMLMetaElement) checkMetaElement(n);
                      else if (n instanceof Element) n.querySelectorAll('meta').forEach(checkMetaElement);
                    }
                  }
                }
              });
              if (document.documentElement) {
                metaObserver.observe(document.documentElement, {
                  childList: true,
                  subtree: true,
                  attributes: true,
                  attributeFilter: ['name', 'content'],
                });
              }
              globalThis.__miruInspectContextImage = (target, hitX, hitY) => {
                try {
                  checkAllReferrerMetas();
                  if (globalThis.__miruMetaReferrerUnknown) {
                    return { metaUnknown: true, matched: false, docUrl: document.URL || '' };
                  }
                  const sameSrc = (el) => el instanceof HTMLImageElement && (el.currentSrc === target || el.src === target);
                  let chosen = null;
                  const lastRef = globalThis.__miruLastContextImg?.deref?.();
                  if (lastRef?.isConnected && sameSrc(lastRef)) {
                    chosen = lastRef;
                  }
                  if (!chosen && Number.isFinite(hitX) && Number.isFinite(hitY) && hitX >= 0 && hitY >= 0) {
                    const hit = document.elementFromPoint(hitX, hitY);
                    if (sameSrc(hit)) chosen = hit;
                    else if (hit instanceof Element) {
                      const inside = [...hit.querySelectorAll('img')].find(sameSrc);
                      if (inside) chosen = inside;
                    }
                  }
                  if (!chosen) {
                    const matches = [...document.getElementsByTagName('img')].filter(sameSrc);
                    if (!matches.length) return { metaUnknown: false, matched: false, docUrl: document.URL || '' };
                    const policies = new Set(matches.map((el) => (el.referrerPolicy || '').toLowerCase()));
                    if (policies.size > 1) return { metaUnknown: true, matched: false, docUrl: document.URL || '' };
                    chosen = matches[0];
                  }
                  return {
                    metaUnknown: false,
                    matched: true,
                    imgPolicy: (chosen.referrerPolicy || '').toLowerCase(),
                    docPolicy: (document.referrerPolicy || '').toLowerCase(),
                    docUrl: document.URL || '',
                  };
                } catch (_) {
                  return null;
                }
              };

              // 2. DOM video/audio metadata + prepare/restore bridge.
              const finite = (v) => (Number.isFinite(v) && v >= 0 ? v : 0);
              const srcOf = (el) => el.currentSrc || el.src || '';
              const elements = () => [...document.querySelectorAll('video,audio')];
              let lastPaused = null;

              const collectItems = () => elements().map((el) => ({
                url: srcOf(el),
                kind: el.tagName === 'AUDIO' ? 'AUDIO' : 'VIDEO',
                duration: finite(el.duration),
                position: finite(el.currentTime),
                width: el.videoWidth || 0,
                height: el.videoHeight || 0,
                playing: !el.paused && !el.ended,
                drm: !!el.mediaKeys || el.dataset.miruEncrypted === 'true',
              }));

              const reportMedia = (evt = '') => {
                const items = collectItems();
                if (!items.length && !evt) return;
                emit({ kind: 'dom_media', event: evt, isTop: window === window.top, pageUrl: location.href, title: document.title || '', items });
              };
              globalThis.__miruReportMedia = reportMedia;

              for (const evt of ['loadedmetadata', 'durationchange', 'play', 'pause', 'emptied', 'encrypted', 'seeking', 'seeked']) {
                document.addEventListener(evt, (e) => {
                  if (!(e.target instanceof HTMLMediaElement)) return;
                  if (evt === 'encrypted') e.target.dataset.miruEncrypted = 'true';
                  reportMedia(evt);
                }, true);
              }
              document.addEventListener('fullscreenchange', () => reportMedia('fullscreen'));

              globalThis.__miruPrepareMedia = (targetUrl, targetKey) => {
                cancelHold();
                const list = elements();
                const normKey = (u) => { try { const parsed = new URL(u); return parsed.origin + parsed.pathname; } catch (_) { return u; } };
                const el = list.find((e) => srcOf(e) === targetUrl)
                  || list.find((e) => normKey(srcOf(e)) === targetKey)
                  || list.find((e) => !e.paused && !e.ended && srcOf(e).startsWith('blob:'))
                  || (list.length === 1 && srcOf(list[0]).startsWith('blob:') ? list[0] : null);
                if (!el) return { found: false };
                const drm = !!el.mediaKeys || el.dataset.miruEncrypted === 'true';
                const wasPlaying = !el.paused && !el.ended;
                const position = finite(el.currentTime);
                if (!drm) {
                  lastPaused = { element: el, wasPlaying };
                  try { el.pause(); } catch (_) {}
                }
                return {
                  found: true,
                  drm,
                  wasPlaying,
                  position,
                  duration: finite(el.duration),
                  width: el.videoWidth || 0,
                  height: el.videoHeight || 0,
                };
              };

              globalThis.__miruRestoreMedia = () => {
                if (lastPaused?.wasPlaying && lastPaused.element?.isConnected) {
                  lastPaused.element.play().catch(() => {});
                }
                lastPaused = null;
                return true;
              };

              reportMedia('');
            })();
        """.trimIndent()
    }

    fun webHoldConfigScript(holdEnabled: Boolean, holdSpeed: Float): String {
        val enabledLit = if (holdEnabled) "true" else "false"
        val speedLit = "%.2f".format(holdSpeed.coerceIn(0.5f, 3.0f))
        return "globalThis.__miruConfigureHold?.({ holdEnabled: $enabledLit, holdSpeed: $speedLit });"
    }

    fun prepareMediaScript(mediaUrl: String, mediaKey: String): String {
        val qUrl = JSONObject.quote(mediaUrl)
        val qKey = JSONObject.quote(mediaKey)
        return "globalThis.__miruPrepareMedia ? globalThis.__miruPrepareMedia($qUrl, $qKey) : { found: false };"
    }

    fun restoreMediaScript(): String = "globalThis.__miruRestoreMedia ? globalThis.__miruRestoreMedia() : false;"

    fun elementPickerScript(): String = """
        (() => {
          if (globalThis.__miruPickerCleanup) globalThis.__miruPickerCleanup();
          const emit = (payload) => {
            try { console.info('__MIRU_EVT__:__MIRU_NONCE__:' + JSON.stringify(payload)); } catch (_) {}
          };
          let selectedEl = null, prevOutline = '', currentSelector = '';
          const host = document.createElement('div');
          host.setAttribute('aria-hidden', 'true');
          host.style.cssText = 'all:initial!important;position:fixed!important;left:0!important;right:0!important;bottom:16px!important;z-index:2147483647!important;display:flex!important;justify-content:center!important;pointer-events:none!important;';
          const shadow = host.attachShadow({ mode: 'closed' });
          const bar = document.createElement('div');
          bar.style.cssText = 'pointer-events:auto;display:flex;align-items:center;gap:10px;padding:10px 14px;border-radius:16px;background:rgba(20,20,24,.94);color:#fff;font:500 13px/1.3 system-ui,sans-serif;box-shadow:0 8px 24px rgba(0,0,0,.35);max-width:92vw;';
          const label = document.createElement('span');
          label.style.cssText = 'max-width:48vw;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;';
          label.textContent = '点按页面元素以选中屏蔽';
          const btnCancel = document.createElement('button');
          btnCancel.textContent = '取消';
          btnCancel.style.cssText = 'border:0;border-radius:999px;padding:6px 12px;background:rgba(255,255,255,.16);color:#fff;font:600 13px system-ui,sans-serif;';
          const btnConfirm = document.createElement('button');
          btnConfirm.textContent = '屏蔽';
          btnConfirm.disabled = true;
          btnConfirm.style.cssText = 'border:0;border-radius:999px;padding:6px 14px;background:#e53935;color:#fff;font:600 13px system-ui,sans-serif;opacity:.45;';
          bar.append(label, btnCancel, btnConfirm);
          shadow.append(bar);
          document.documentElement.append(host);

          const buildSelector = (el) => {
            if (!el || el === document.body || el === document.documentElement) return '';
            if (el.id) return '#' + CSS.escape(el.id);
            const tag = el.tagName.toLowerCase();
            const classes = [...el.classList].filter((c) => c && !c.startsWith('__miru')).slice(0, 2);
            if (classes.length) return tag + '.' + classes.map((c) => CSS.escape(c)).join('.');
            const parent = el.parentElement;
            if (!parent) return tag;
            const sameTag = [...parent.children].filter((c) => c.tagName === el.tagName);
            const idx = sameTag.indexOf(el) + 1;
            const pTag = parent === document.body ? 'body' : (parent.id ? '#' + CSS.escape(parent.id) : parent.tagName.toLowerCase());
            return pTag + ' > ' + tag + (sameTag.length > 1 ? ':nth-of-type(' + idx + ')' : '');
          };

          const cleanup = () => {
            document.removeEventListener('click', onClick, true);
            if (selectedEl) { try { selectedEl.style.outline = prevOutline; } catch (_) {} }
            host.remove();
            delete globalThis.__miruPickerCleanup;
          };
          globalThis.__miruPickerCleanup = cleanup;

          const onClick = (e) => {
            if (e.composedPath().includes(host)) return;
            e.preventDefault();
            e.stopPropagation();
            const target = e.target instanceof Element ? e.target : null;
            if (!target) return;
            if (selectedEl) { try { selectedEl.style.outline = prevOutline; } catch (_) {} }
            selectedEl = target;
            prevOutline = target.style.outline || '';
            target.style.outline = '2px solid #e53935';
            currentSelector = buildSelector(target);
            label.textContent = currentSelector || target.tagName.toLowerCase();
            btnConfirm.disabled = !currentSelector;
            btnConfirm.style.opacity = currentSelector ? '1' : '.45';
          };
          document.addEventListener('click', onClick, true);

          btnCancel.addEventListener('click', () => {
            cleanup();
            emit({ kind: 'picker_cancel' });
          });
          btnConfirm.addEventListener('click', () => {
            if (!currentSelector) return;
            if (selectedEl) selectedEl.style.setProperty('display', 'none', 'important');
            const selector = currentSelector;
            const hostName = location.hostname || '';
            cleanup();
            emit({ kind: 'picker_confirm', host: hostName, selector });
          });
        })();
    """.trimIndent()
}
