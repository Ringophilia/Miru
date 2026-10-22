// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
// The controller is browser-independent so cancellation/restore behavior can also be reused on iOS.
globalThis.MiruWebHold = (() => {
  function createController({ later, cancelLater, show, hide }) {
    let active = null, enabled = true, speed = 2;
    const playing = video => video?.isConnected && !video.paused && !video.ended;
    function cancel() {
      const previous = active;
      active = null;
      if (!previous) return;
      cancelLater(previous.timer);
      // A website that changed the speed while held owns that new value.
      if (previous.applied && previous.video.playbackRate === previous.appliedRate) {
        try { previous.video.playbackRate = previous.originalRate; } catch (_) {}
      }
      hide();
    }
    function down(video, pointer) {
      cancel();
      if (!enabled || !playing(video) || pointer.primary === false || pointer.button > 0 || pointer.prevented) return;
      const state = { video, id: pointer.id, x: pointer.x, y: pointer.y, applied: false, timer: null };
      active = state;
      state.timer = later(() => {
        if (active !== state || !playing(video)) { cancel(); return; }
        const originalRate = video.playbackRate;
        if (!Number.isFinite(originalRate) || originalRate <= 0) { cancel(); return; }
        state.originalRate = originalRate;
        state.appliedRate = speed;
        try {
          video.playbackRate = speed;
          if (video.playbackRate !== speed) { cancel(); return; }
          state.applied = true;
          show(video, speed);
        } catch (_) { cancel(); }
      }, 500);
    }
    function move(pointer) {
      if (!active || pointer.id !== active.id) return;
      if (pointer.prevented || pointer.outside || Math.hypot(pointer.x - active.x, pointer.y - active.y) > 10) cancel();
    }
    function up(id) { if (active && (id == null || id === active.id)) cancel(); }
    function check() {
      if (active && (!playing(active.video) || (active.applied && active.video.playbackRate !== active.appliedRate))) cancel();
    }
    function configure(next = {}) {
      const nextEnabled = next.enabled !== false;
      const nextSpeed = Number.isFinite(next.speed) ? Math.min(3, Math.max(0.5, next.speed)) : 2;
      if (nextEnabled !== enabled || nextSpeed !== speed) cancel();
      enabled = nextEnabled; speed = nextSpeed;
    }
    return { down, move, up, check, cancel, configure, current: () => active?.video || null, holding: () => !!active?.applied };
  }

  function install(document, window) {
    let host = null, pill = null, listening = false, enabled = true, speed = 2;
    const videoFrom = event => event.composedPath().find(node => node instanceof window.HTMLVideoElement);
    function hide() {
      if (host) {
        try { host.hidePopover?.(); } catch (_) {}
        host.remove();
      }
      host = null; pill = null;
    }
    function position(video) {
      if (!host || !video?.isConnected) return;
      const rect = video.getBoundingClientRect();
      const center = Math.min(window.innerWidth - 12, Math.max(12, rect.left + rect.width / 2));
      host.style.setProperty('left', `${center}px`, 'important');
      host.style.setProperty('top', `${Math.max(8, rect.top + 12)}px`, 'important');
    }
    function show(video, value) {
      hide();
      host = document.createElement('span');
      host.setAttribute('aria-hidden', 'true');
      host.setAttribute('popover', 'manual');
      host.style.cssText = 'all:initial!important;position:fixed!important;inset:auto!important;display:block!important;width:max-content!important;margin:0!important;padding:0!important;border:0!important;background:transparent!important;pointer-events:none!important;user-select:none!important;z-index:2147483647!important;transform:translateX(-50%)!important;';
      const shadow = host.attachShadow({ mode: 'closed' });
      pill = document.createElement('span');
      pill.style.cssText = 'display:block;padding:8px 14px;border-radius:999px;background:rgba(15,15,18,.8);color:#fff;font:700 15px/1.4 system-ui,sans-serif;white-space:nowrap;pointer-events:none;user-select:none;';
      pill.textContent = `${value.toFixed(1)}x ▶▶`;
      shadow.append(pill);
      // A manual popover enters the top layer above a fullscreen video without taking focus.
      // Older implementations still get a fixed overlay in ordinary page playback.
      (document.fullscreenElement && !(document.fullscreenElement instanceof window.HTMLMediaElement)
        ? document.fullscreenElement : document.documentElement).append(host);
      position(video);
      try { host.showPopover?.(); } catch (_) {}
    }
    const controller = createController({ later: (fn, ms) => window.setTimeout(fn, ms), cancelLater: id => window.clearTimeout(id), show, hide });
    const defer = fn => window.queueMicrotask(fn);
    function pointerDown(event) {
      if (!event.isTrusted) return;
      const video = videoFrom(event);
      if (!video || event.isPrimary === false) { controller.cancel(); return; }
      const rect = video.getBoundingClientRect();
      if (rect.width <= 0 || rect.height <= 0) return;
      // Inspect after dispatch so a site's later document handler can claim its own gesture.
      defer(() => { if (!listening) return; controller.down(video, { id: event.pointerId, x: event.clientX, y: event.clientY, button: event.button, primary: event.isPrimary, prevented: event.defaultPrevented }); });
    }
    function pointerMove(event) {
      const video = controller.current();
      if (!video) return;
      const rect = video.getBoundingClientRect();
      const pointer = { id: event.pointerId, x: event.clientX, y: event.clientY, outside: event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom };
      controller.move(pointer);
      defer(() => { if (event.defaultPrevented) controller.cancel(); });
    }
    function pointerUp(event) { controller.up(event.pointerId); }
    function pointerLeave(event) { if (event.target === controller.current()) controller.cancel(); }
    function siteTouch(event) {
      if (event.touches?.length > 1) controller.cancel();
      defer(() => { if (event.defaultPrevented) controller.cancel(); });
    }
    function contextMenu(event) {
      if (event.defaultPrevented) { controller.cancel(); return; }
      // Keep a successfully recognized hold from opening the browser's own menu. A site's
      // earlier prevented contextmenu/touchstart still wins; propagation is never stopped.
      // Gecko may dispatch its long-tap contextmenu just before our 500 ms timer gets a turn.
      // Claim that eligible pending hold too, otherwise the native menu cancels the pointer first.
      if (controller.current() && videoFrom(event) === controller.current()) event.preventDefault();
    }
    function mediaChange(event) {
      if (event.target !== controller.current()) return;
      if (event.type === 'emptied') controller.cancel(); else controller.check();
    }
    function hidden() { if (document.visibilityState === 'hidden') controller.cancel(); }
    function reposition() {
      const video = controller.current();
      if (video) {
        position(video);
        if (host) { try { host.hidePopover?.(); host.showPopover?.(); } catch (_) {} }
      }
    }
    const events = [
      ['pointerdown', pointerDown, false], ['pointermove', pointerMove, true], ['pointerup', pointerUp, true],
      ['pointercancel', pointerUp, true], ['pointerleave', pointerLeave, true], ['touchstart', siteTouch, false],
      ['contextmenu', contextMenu, false, false], ['pause', mediaChange, true], ['ended', mediaChange, true],
      ['emptied', mediaChange, true], ['ratechange', mediaChange, true], ['visibilitychange', hidden, true],
      ['fullscreenchange', reposition, true],
    ];
    const observer = new window.MutationObserver(() => controller.check());
    function start() {
      if (listening) return;
      listening = true;
      for (const [name, callback, capture, passive = true] of events) document.addEventListener(name, callback, { capture, passive });
      window.addEventListener('resize', reposition, { passive: true });
      window.addEventListener('scroll', reposition, { capture: true, passive: true });
      observer.observe(document.documentElement, { subtree: true, childList: true });
    }
    function stop() {
      controller.cancel();
      if (!listening) return;
      listening = false;
      for (const [name, callback, capture] of events) document.removeEventListener(name, callback, capture);
      window.removeEventListener('resize', reposition);
      window.removeEventListener('scroll', reposition, true);
      observer.disconnect();
    }
    return {
      configure(settings = {}) {
        enabled = settings.holdEnabled !== false;
        speed = Number.isFinite(settings.holdSpeed) ? settings.holdSpeed : 2;
        controller.configure({ enabled, speed });
        if (enabled) start(); else stop();
      },
      cancel: controller.cancel,
      check: controller.check,
      stop,
    };
  }
  return { createController, install };
})();
