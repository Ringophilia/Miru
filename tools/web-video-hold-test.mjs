// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const sandbox = vm.createContext({});
vm.runInContext(fs.readFileSync(new URL('../browser/engine/src/main/assets/extensions/miru-bridge/web-video-hold.js', import.meta.url), 'utf8'), sandbox);

function fixture() {
  let now = 0, sequence = 0, shown = 0, hidden = 0;
  const timers = new Map();
  const controller = sandbox.MiruWebHold.createController({
    later(fn, delay) { const id = ++sequence; timers.set(id, { at: now + delay, fn }); return id; },
    cancelLater(id) { timers.delete(id); },
    show() { shown++; },
    hide() { hidden++; },
  });
  const video = { isConnected: true, paused: false, ended: false, playbackRate: 1.25 };
  const point = { id: 7, x: 100, y: 100, button: 0, primary: true, prevented: false };
  return {
    controller, video, point,
    advance(duration) {
      now += duration;
      for (const [id, timer] of [...timers]) if (timer.at <= now) { timers.delete(id); timer.fn(); }
    },
    get shown() { return shown; }, get hidden() { return hidden; },
  };
}

{
  const f = fixture();
  f.controller.configure({ enabled: true, speed: 2.5 });
  f.controller.down(f.video, f.point);
  f.advance(499); assert.equal(f.video.playbackRate, 1.25);
  f.advance(1); assert.equal(f.video.playbackRate, 2.5); assert.equal(f.shown, 1);
  f.controller.up(99); assert.equal(f.video.playbackRate, 2.5);
  f.controller.up(7); assert.equal(f.video.playbackRate, 1.25); assert.equal(f.hidden, 1);
}
for (const field of ['paused', 'ended']) {
  const f = fixture(); f.video[field] = true;
  f.controller.down(f.video, f.point); f.advance(600);
  assert.equal(f.video.playbackRate, 1.25); assert.equal(f.shown, 0);
}
for (const point of [{ prevented: true }, { primary: false }, { button: 2 }]) {
  const f = fixture(); f.controller.down(f.video, { ...f.point, ...point }); f.advance(600);
  assert.equal(f.video.playbackRate, 1.25); assert.equal(f.shown, 0);
}
{
  const f = fixture(); f.controller.down(f.video, f.point);
  f.controller.move({ id: 7, x: 111, y: 100 }); f.advance(600);
  assert.equal(f.shown, 0);
  f.controller.down(f.video, f.point); f.advance(500);
  f.controller.move({ id: 7, x: 100, y: 100, outside: true });
  assert.equal(f.video.playbackRate, 1.25);
}
for (const change of [v => { v.paused = true; }, v => { v.ended = true; }, v => { v.isConnected = false; }]) {
  const f = fixture(); f.controller.down(f.video, f.point); f.advance(500);
  change(f.video); f.controller.check();
  assert.equal(f.video.playbackRate, 1.25); assert.equal(f.controller.current(), null);
}
{
  const f = fixture(); f.controller.down(f.video, f.point); f.advance(500);
  f.video.playbackRate = 1.5; f.controller.check();
  assert.equal(f.video.playbackRate, 1.5, 'A site-owned speed change must not be overwritten');
  assert.equal(f.controller.current(), null);
}
{
  const f = fixture(); f.controller.down(f.video, f.point); f.advance(500);
  f.controller.configure({ enabled: false, speed: 3 });
  assert.equal(f.video.playbackRate, 1.25);
  f.controller.down(f.video, f.point); f.advance(600); assert.equal(f.video.playbackRate, 1.25);
  f.controller.configure({ enabled: true, speed: 3 });
  f.controller.down(f.video, f.point); f.advance(500); assert.equal(f.video.playbackRate, 3);
  f.controller.cancel(); assert.equal(f.video.playbackRate, 1.25);
}
{
  const f = fixture();
  Object.defineProperty(f.video, 'playbackRate', { get: () => 1.25, set() { throw new Error('Unsupported speed'); } });
  f.controller.down(f.video, f.point); f.advance(500);
  assert.equal(f.controller.current(), null); assert.equal(f.shown, 0);
}
console.log('Web video hold passed: 500 ms activation, configured speed, restore, ownership, motion, site claims, pause/removal and disable.');
