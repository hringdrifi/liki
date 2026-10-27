import test from 'node:test';
import assert from 'node:assert/strict';
import { field as f, frame, protobuf, StudioFrames, physicalLayout, StudioClient, studioBinding, studioKeyActive } from '../src/zmk.ts';
import { LikiModel, Geometry, rotate, KEY_PITCH_MIN_MM, KEY_PITCH_MAX_MM } from '../src/model.ts';
import { StudioKeyboardGesture } from '../src/studio-gesture.ts';
import { bindingUsage, expandUsage, parseRelayRemaps } from '../src/remap.ts';

const msg = (values: number[]) => protobuf(Uint8Array.from(values));
const text = (s: string) => Array.from(new TextEncoder().encode(s));
const attrs = [...f(1, 200), ...f(2, 200), ...f(3, 299), ...f(4, 100), ...f(5, 3000), ...f(6, 200), ...f(7, 100)];
const physical = [...f(2, [...f(1, text('Split')), ...f(2, attrs)])];
const binding = [...f(1, 2), ...f(2, 0x70004)];
const details = [...f(1, 1), ...f(2, text('Key Press')), ...f(3, f(1, f(5, [])))];
const keymap = f(1, [...f(1, 0), ...f(2, text('Base')), ...f(3, binding)]);

function studioGesture() {
  let reports = 0;
  const model = new LikiModel({ invoke: async () => { reports++; }, subscribe: () => ({ remove() {} }) });
  model.settings = { pinch_zoom: true, drag_move_keyboard: true };
  const geometry = new Geometry(physicalLayout(msg(physical)).keys);
  geometry.width = 400; geometry.height = 240; geometry.base = 40;
  const selected: number[] = [], gesture = new StudioKeyboardGesture(model, geometry, index => selected.push(index));
  const center = () => {
    const key = geometry.keys[0], point = rotate(key, { x: key.x + key.w / 2, y: key.y + key.h / 2 });
    return { x: geometry.origin.x + point.x * geometry.scale, y: geometry.origin.y + point.y * geometry.scale };
  };
  return { model, geometry, gesture, selected, center, reports: () => reports };
}

test('Studio tap selection follows the transformed and rotated key without sending host input', () => {
  const { geometry, gesture, selected, center, reports } = studioGesture();
  geometry.pan = { x: 28, y: -17 }; geometry.zoom = 1.5;
  const position = center();
  gesture.touch({ kind: 'down', id: 1, position, time: 0 });
  gesture.touch({ kind: 'up', id: 1, position, time: 100 });
  assert.deepEqual(selected, [0]); assert.equal(reports(), 0);
});

test('Studio dragging pans the layout and suppresses key selection', () => {
  const { geometry, gesture, selected, center, reports } = studioGesture(), start = center();
  const end = { x: start.x + 25, y: start.y - 10 };
  gesture.touch({ kind: 'down', id: 1, position: start, time: 0 });
  gesture.touch({ kind: 'move', id: 1, position: end, time: 50 });
  gesture.touch({ kind: 'up', id: 1, position: end, time: 100 });
  assert.deepEqual(geometry.pan, { x: 25, y: -10 }); assert.deepEqual(selected, []); assert.equal(reports(), 0);
  geometry.zoom = 2; gesture.setViewport(500, 300);
  assert.deepEqual(geometry.pan, { x: 25, y: -10 }); assert.equal(geometry.zoom, 2);
});

test('Studio pinch keeps its focus, respects pitch limits, and never selects when a finger lifts', () => {
  const { model, geometry, gesture, selected, center, reports } = studioGesture(), focus = center(), world = geometry.world(focus);
  gesture.touch({ kind: 'down', id: 1, position: { x: focus.x - 30, y: focus.y }, time: 0 });
  gesture.touch({ kind: 'down', id: 2, position: { x: focus.x + 30, y: focus.y }, time: 1 });
  gesture.points.set(1, { x: focus.x - 60, y: focus.y });
  gesture.touch({ kind: 'move', id: 2, position: { x: focus.x + 60, y: focus.y }, time: 20 });
  assert.equal(geometry.zoom, 2);
  assert.ok(Math.abs(geometry.world(focus).x - world.x) < 1e-9); assert.ok(Math.abs(geometry.world(focus).y - world.y) < 1e-9);
  gesture.points.set(1, { x: focus.x - 1e6, y: focus.y });
  gesture.touch({ kind: 'move', id: 2, position: { x: focus.x + 1e6, y: focus.y }, time: 30 });
  const pitch = geometry.base * model.density * 25.4 / model.xdpi;
  assert.equal(geometry.zoom, KEY_PITCH_MAX_MM / pitch);
  gesture.points.set(1, { x: focus.x - .01, y: focus.y });
  gesture.touch({ kind: 'move', id: 2, position: { x: focus.x + .01, y: focus.y }, time: 40 });
  assert.equal(geometry.zoom, KEY_PITCH_MIN_MM / pitch);
  gesture.touch({ kind: 'up', id: 2, position: { x: focus.x + .01, y: focus.y }, time: 50 });
  const pan = { ...geometry.pan };
  gesture.touch({ kind: 'move', id: 1, position: { x: focus.x + 40, y: focus.y }, time: 60 });
  gesture.touch({ kind: 'up', id: 1, position: { x: focus.x + 40, y: focus.y }, time: 70 });
  assert.deepEqual(geometry.pan, pan); assert.deepEqual(selected, []); assert.equal(reports(), 0);
  gesture.reset(); assert.equal(geometry.zoom, 1); assert.deepEqual(geometry.pan, { x: 0, y: 0 });
});

test('Studio display gestures respect disabled settings and cancellation', () => {
  const { model, geometry, gesture, selected, center } = studioGesture(), position = center();
  model.settings = { pinch_zoom: false, drag_move_keyboard: false };
  gesture.touch({ kind: 'down', id: 1, position, time: 0 });
  gesture.touch({ kind: 'move', id: 1, position: { x: position.x + 30, y: position.y }, time: 10 });
  gesture.touch({ kind: 'down', id: 2, position: { x: position.x + 60, y: position.y }, time: 20 });
  gesture.touch({ kind: 'move', id: 2, position: { x: position.x + 90, y: position.y }, time: 30 });
  gesture.touch({ kind: 'cancel', id: 1, position, time: 40 });
  assert.equal(geometry.zoom, 1); assert.deepEqual(geometry.pan, { x: 0, y: 0 });
  assert.deepEqual(selected, []); assert.equal(gesture.points.size, 0);
});

test('relay mappings validate HID pages, unique sources and target chords', () => {
  const rules = [{ source: 0x70004, target: [0x700e1, 0x70005, 0x70005] }, { source: 0xc00e9, target: [] }];
  assert.deepEqual(parseRelayRemaps(JSON.stringify(rules)), [{ source: 0x70004, target: [0x700e1, 0x70005] }, rules[1]]);
  for (const bad of [[rules[0], rules[0]], [{ source: 0x70004, target: [0x80001] }], [{ source: 458756.5, target: [] }]]) {
    assert.throws(() => parseRelayRemaps(JSON.stringify(bad)));
  }
  assert.deepEqual(expandUsage(0x03070004), [0x700e0, 0x700e1, 0x70004]);
  assert.equal(bindingUsage({ name: 'Ctrl', code: 0, modifier: 1, consumer: 0, action: 'NONE', layer: 0 }), 0x700e0);
  assert.deepEqual(expandUsage(0), []);
});

test('Studio framing handles special bytes and arbitrary BLE packet splits', () => {
  const frames = new StudioFrames();
  assert.deepEqual(frame([0xab, 0xac, 0xad, 42]), [0xab, 0xac, 0xab, 0xac, 0xac, 0xac, 0xad, 42, 0xad]);
  const result = frame([0xab, 0xac, 0xad, 42]).flatMap(byte => frames.push([byte]));
  assert.deepEqual(result, [Uint8Array.from([0xab, 0xac, 0xad, 42])]);
  assert.deepEqual(frames.push([9, 0xab, 1, 0xab, 2, 0xad]), [Uint8Array.from([2])]);
  assert.throws(() => msg([0x0a, 100, 1]));
  assert.throws(() => msg([0x80]));
});

test('Studio physical attributes decode signed hundredths and rotation', () => {
  const layout = physicalLayout(msg(physical)), key = layout.keys[0];
  assert.equal(layout.name, 'Split');
  assert.deepEqual([key.w, key.h, key.x, key.y, key.rotation, key.rx, key.ry], [1, 1, -1.5, .5, 15, 1, .5]);
  assert.throws(() => physicalLayout(msg([])));
  assert.throws(() => physicalLayout(msg(f(2, f(2, f(2, 200))))));
});

test('Studio labels and highlighting use behavior metadata, modifiers and consumer page', () => {
  const key = studioBinding(msg(binding), msg(details));
  assert.deepEqual(key, { label: 'A', usages: [0x70004] });
  assert.equal(studioKeyActive(key, [4], 0), true);
  assert.equal(studioKeyActive(key, [], 0), false);
  assert.equal(studioKeyActive({ label: '', usages: [0x02070004] }, [4], 0), false);
  assert.equal(studioKeyActive({ label: '', usages: [0x02070004] }, [4, 0xe1], 0), true);
  assert.equal(studioKeyActive({ label: '', usages: [0xc00e9] }, [], 0xe9), true);
  assert.deepEqual(studioBinding(msg(binding)).usages, []);
});

test('Bifrost custom symbol keys highlight both mod-morph outputs without guessing other boards', () => {
  const noParameters = msg(f(1, 2));
  const minusDetails = msg([...f(1, 1), ...f(2, text('us_minus'))]);
  const semicolonDetails = msg([...f(1, 1), ...f(2, text('us_semicolon'))]);
  const backslashDetails = msg([...f(1, 1), ...f(2, text('us_backslash'))]);
  const minus = studioBinding(noParameters, minusDetails, 'Bifrost');
  const semicolon = studioBinding(noParameters, semicolonDetails, 'Bifrost');
  const backslash = studioBinding(noParameters, backslashDetails, 'Bifrost');
  assert.equal(minus.label, '-'); assert.equal(semicolon.label, ';');
  assert.equal(studioKeyActive(minus, [0x2d], 0), true);
  assert.equal(studioKeyActive(minus, [0xe1, 0x87], 0), true);
  assert.equal(studioKeyActive(minus, [0x87], 0), false);
  assert.equal(studioKeyActive(semicolon, [0x33], 0), true);
  assert.equal(studioKeyActive(semicolon, [0x34], 0), true);
  assert.equal(studioKeyActive(semicolon, [], 0), false);
  assert.equal(backslash.label, '\\');
  assert.equal(studioKeyActive(backslash, [0x87], 0), true);
  assert.equal(studioKeyActive(backslash, [0xe1, 0x89], 0), true);
  assert.equal(studioKeyActive(backslash, [0x89], 0), false);
  assert.equal(studioKeyActive(backslash, [0x31], 0), false);
  assert.equal(studioKeyActive(backslash, [], 0), false);
  assert.deepEqual(studioBinding(noParameters, minusDetails, 'Other').usages, []);
  assert.deepEqual(studioBinding(noParameters, semicolonDetails).usages, []);
  assert.deepEqual(studioBinding(noParameters, backslashDetails, 'Other').usages, []);
});

test('Studio client reads layout, keymap and metadata without sending mutations', async () => {
  let client: StudioClient;
  const requests: number[] = [];
  client = new StudioClient(async data => {
    const decoded = new StudioFrames().push(data)[0], request = protobuf(decoded), id = request.get(1)![0] as number;
    const subsystem = request.has(3) ? 3 : request.has(5) ? 5 : 4;
    const payload = protobuf(request.get(subsystem)![0] as Uint8Array), operation = [...payload.keys()][0];
    requests.push(subsystem * 10 + operation);
    const result = subsystem === 3 ? 1 : subsystem === 4 ? details : operation === 6 ? physical : keymap;
    const reply = frame(f(1, [...f(1, id), ...f(subsystem, f(operation, result))]));
    // Notifications may arrive before the response, fragmented at any boundary.
    client.receive(frame(f(2, f(2, f(1, 1)))));
    for (let i = 0; i < reply.length; i += 3) client.receive(reply.slice(i, i + 3));
  }, () => {}, async () => {});
  await client.load();
  assert.deepEqual(requests, [32, 56, 51, 42]);
  assert.equal(client.ready, true); assert.equal(client.layout?.layers[0].keys[0].label, 'A');
  client.reset(); assert.equal(client.layout, undefined);
});

test('Studio client reports lock errors and cancels an in-flight read on disconnect', async () => {
  let client: StudioClient;
  client = new StudioClient(async data => {
    const request = protobuf(new StudioFrames().push(data)[0]), id = request.get(1)![0] as number;
    client.receive(frame(f(1, [...f(1, id), ...f(2, f(2, 1))])));
  }, () => {}, async () => {});
  await client.load(); assert.match(client.message, /ロックを解除/); assert.equal(client.layout, undefined);
  let writes = 0;
  const cancelled = new StudioClient(async () => { writes++; }, () => {}, async () => {});
  const loading = cancelled.load(); cancelled.reset(); await loading;
  assert.equal(writes, 1); assert.equal(cancelled.message, ''); assert.equal(cancelled.layout, undefined);
});

test('Studio checks the lock before reading and does not treat missing or unknown state as unlocked', async () => {
  for (const state of [0, undefined, 2]) {
    const requests: number[] = [];
    let client: StudioClient;
    client = new StudioClient(async data => {
      const request = protobuf(new StudioFrames().push(data)[0]), id = request.get(1)![0] as number;
      assert.equal(request.has(3), true);
      assert.equal(protobuf(request.get(3)![0] as Uint8Array).get(2)![0], 1);
      requests.push(id);
      client.receive(frame(f(1, [...f(1, id), ...f(3, state === undefined ? [] : f(2, state))])));
    }, () => {}, async () => {});
    await client.load();
    assert.equal(requests.length, 1);
    assert.equal(client.ready, false);
    assert.equal(client.layout, undefined);
    assert.match(client.message, state === 0 ? /ロックを解除/ : /ロック状態を確認できません/);
  }
});

test('Studio stops reading immediately when the keyboard reports a lock during loading', async () => {
  const requests: number[] = [];
  let client: StudioClient;
  client = new StudioClient(async data => {
    const request = protobuf(new StudioFrames().push(data)[0]), id = request.get(1)![0] as number;
    const subsystem = request.has(3) ? 3 : request.has(5) ? 5 : 4;
    requests.push(subsystem);
    if (subsystem === 3) client.receive(frame(f(1, [...f(1, id), ...f(3, f(2, 1))])));
    else {
      client.receive(frame(f(2, f(2, f(1, 0)))));
      client.receive(frame(f(1, [...f(1, id), ...f(5, f(6, physical))])));
    }
  }, () => {}, async () => {});
  await client.load();
  assert.deepEqual(requests, [3, 5]); assert.equal(client.ready, false);
  assert.match(client.message, /ロックを解除/);
});

test('Studio lock notifications disable editing and retain unsaved changes without automatic requests', () => {
  let writes = 0;
  const client = new StudioClient(async () => { writes++; }, () => {}, async () => {});
  client.ready = true; client.dirty = true;
  client.receive(frame(f(2, f(2, f(1, 0)))));
  assert.equal(client.ready, false); assert.equal(client.dirty, true); assert.match(client.message, /ロックを解除/);
  client.receive(frame(f(2, f(2, f(1, 1)))));
  assert.equal(client.ready, false); assert.equal(client.dirty, true); assert.equal(writes, 0);
});

test('relay input and Studio events do not replace connection status or persist keystrokes', () => {
  const calls: string[] = [];
  const model = new LikiModel({ invoke: async method => { calls.push(method); }, subscribe: () => ({ remove() {} }) });
  model.status = { connected: true }; model.initialized = true;
  model.acceptStatus({ relayInput: { pressed: [4, 0xe1], consumer: 0 } });
  assert.equal(model.connected, true); assert.deepEqual(model.relayPressed, [4, 0xe1]); assert.deepEqual(calls, []);
  model.acceptStatus({ studioState: { ready: false, message: 'Disconnected' } });
  assert.equal(model.connected, true); assert.equal(model.studio.message, 'Disconnected');
  model.syncPage(); assert.deepEqual(model.relayPressed, []); model.dispose();
});

test('Studio propagates native request timeout and ignores late responses', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const writes: { id: number; label: string }[] = [], completed: number[] = [];
  const model = new LikiModel({
    invoke: async (method, args) => {
      if (method === 'studioWrite') writes.push({ id: args!.id, label: args!.label });
      if (method === 'studioComplete') completed.push(args!.id);
    }, subscribe: () => ({ remove() {} }),
  });
  const loading = model.studio.load();
  assert.deepEqual(writes, [{ id: 1, label: 'ロック状態確認' }]);
  t.mock.timers.tick(30000);
  assert.equal(model.studio.message, 'ZMKレイアウトを取得中…');
  model.acceptStatus({ studioBytes: [0xab, 0x0a, 0x20] });
  const message = 'Studioの応答がありません（ロック状態確認、受信3バイト）。再取得してください';
  model.acceptStatus({ studioState: { ready: false, message } });
  await loading;
  assert.equal(model.studio.message, message);
  assert.equal(model.studio.ready, false);
  model.acceptStatus({ studioBytes: frame(f(1, [...f(1, 1), ...f(3, f(2, 1))])) });
  assert.equal(writes.length, 1); assert.deepEqual(completed, []);
  assert.equal(model.studio.message, message);
});

test('Studio waits for native request completion before sending the next request', async () => {
  const requests: number[] = [], completed: number[] = [];
  let acknowledge!: () => void, nextWrite!: () => void, client: StudioClient;
  const nextRequest = new Promise<void>(resolve => { nextWrite = resolve; });
  client = new StudioClient(async (data, id) => {
    assert.equal(protobuf(new StudioFrames().push(data)[0]).get(1)![0], id);
    requests.push(id);
    if (id === 2) nextWrite();
  }, () => {}, async id => {
    completed.push(id);
    await new Promise<void>(resolve => { acknowledge = resolve; });
  });
  const loading = client.load();
  const reply = frame(f(1, [...f(1, 1), ...f(3, f(2, 1))]));
  client.receive(frame(f(1, [...f(1, 99), ...f(3, f(2, 1))])));
  client.receive(frame(f(2, f(2, f(1, 1)))));
  assert.deepEqual(completed, []);
  client.receive(reply.slice(0, -1));
  assert.deepEqual(completed, []);
  client.receive(reply.slice(-1)); client.receive(reply);
  await Promise.resolve();
  assert.deepEqual(completed, [1]); assert.deepEqual(requests, [1]);
  acknowledge();
  await nextRequest;
  assert.deepEqual(requests, [1, 2]);
  client.reset(); await loading;
});

test('Studio does not continue when native request completion fails', async () => {
  let writes = 0, client: StudioClient;
  client = new StudioClient(async (_, id) => {
    writes++;
    client.receive(frame(f(1, [...f(1, id), ...f(3, f(2, 1))])));
  }, () => {}, async () => { throw Error('Studio接続が変更されました'); });
  await client.load();
  assert.equal(writes, 1); assert.equal(client.ready, false);
  assert.match(client.message, /Studio接続が変更されました/);
});

test('Studio cancels a request once if a lock arrives while native completion is pending', async () => {
  let acknowledge!: () => void, completions = 0;
  const client = new StudioClient(async () => {}, () => {}, async () => {
    completions++;
    await new Promise<void>(resolve => { acknowledge = resolve; });
  });
  const loading = client.load();
  client.receive(frame(f(1, [...f(1, 1), ...f(3, f(2, 1))])));
  client.receive(frame(f(2, f(2, f(1, 0)))));
  acknowledge(); await loading;
  assert.equal(completions, 1); assert.equal(client.ready, false);
  assert.match(client.message, /ロックを解除/);
});

async function editableStudio(respond?: (operation: number, payload: ReturnType<typeof protobuf>) => number[] | undefined, lockState: () => number = () => 1) {
  const writes: { operation: number; payload: ReturnType<typeof protobuf> }[] = [];
  const metadata = [...f(1, 1), ...f(2, text('Key Press')), ...f(3, f(1, f(5, [...f(1, 0xe7), ...f(2, 0x2ff)])))];
  let client: StudioClient;
  client = new StudioClient(async data => {
    const request = protobuf(new StudioFrames().push(data)[0]), id = request.get(1)![0] as number;
    const subsystem = request.has(3) ? 3 : request.has(5) ? 5 : 4, payload = protobuf(request.get(subsystem)![0] as Uint8Array), operation = [...payload.keys()][0];
    if (subsystem === 5 && [2, 3, 4, 5].includes(operation)) writes.push({ operation, payload });
    const custom = subsystem === 5 ? respond?.(operation, payload) : undefined;
    const response = custom ?? (subsystem === 3 ? f(2, lockState()) : subsystem === 4 ? f(operation, operation === 1 ? f(1, [1]) : metadata) :
      operation === 6 ? f(6, physical) : operation === 1 ? f(1, f(1, [...f(1, 9), ...f(2, text('Base')), ...f(3, binding)])) :
      operation === 2 || operation === 3 ? f(operation, 0) : operation === 4 ? f(4, f(1, 1)) : f(5, 1));
    client.receive(frame(f(1, [...f(1, id), ...f(subsystem, response)])));
  }, () => {}, async () => {});
  await client.load(); await client.loadBehaviors(); return { client, writes };
}

test('Studio edits use the real layer ID and persist only after an explicit save', async () => {
  const { client, writes } = await editableStudio();
  assert.equal(writes.length, 0);
  await client.saveBinding(0, 0, { behaviorId: 1, param1: 0x02070005, param2: 0 });
  assert.deepEqual(writes.map(w => w.operation), [3, 2, 4]);
  const request = protobuf(writes[1].payload.get(2)![0] as Uint8Array);
  assert.equal(request.get(1)![0], 9); assert.equal(request.get(2)![0], 0);
  const assigned = protobuf(request.get(3)![0] as Uint8Array);
  assert.equal(assigned.get(1)![0], 2); assert.equal(assigned.get(2)![0], 0x02070005);
  assert.equal(client.layout!.layers[0].bindings[0].param1, 0x02070005);
  assert.equal(client.dirty, false); assert.equal(client.busy, false);
});

test('Studio checks the current lock before assigning, saving or discarding changes', async () => {
  let lock = 1;
  const { client, writes } = await editableStudio(undefined, () => lock);
  lock = 0;
  await assert.rejects(client.saveBinding(0, 0, { behaviorId: 1, param1: 0x70005, param2: 0 }), /ロックを解除/);
  assert.equal(client.ready, false); assert.equal(client.dirty, false); assert.deepEqual(writes, []);
  client.dirty = true;
  await assert.rejects(client.savePending(), /ロックを解除/);
  await assert.rejects(client.discardPending(), /ロックを解除/);
  assert.equal(client.dirty, true); assert.deepEqual(writes, []);
});

test('Studio refuses invalid parameters and existing unsaved changes without mutating the keyboard', async () => {
  const { client, writes } = await editableStudio(op => op === 3 ? f(3, 1) : undefined);
  await assert.rejects(client.saveBinding(0, 0, { behaviorId: 1, param1: 0x80001, param2: 0 }), /パラメーター/);
  assert.equal(writes.length, 0);
  await assert.rejects(client.saveBinding(0, 0, { behaviorId: 1, param1: 0x70005, param2: 0 }), /未保存/);
  assert.deepEqual(writes.map(w => w.operation), [3]); assert.equal(client.dirty, false);
});

test('Studio preserves an unsaved change on persistence failure and retries saving without another assignment', async () => {
  let fail = true;
  const { client, writes } = await editableStudio(op => op === 4 && fail ? f(4, f(2, 3)) : undefined);
  await assert.rejects(client.saveBinding(0, 0, { behaviorId: 1, param1: 0x70005, param2: 0 }), /保存に失敗/);
  assert.equal(client.dirty, true); assert.equal(client.busy, false);
  await assert.rejects(client.saveBinding(0, 0, { behaviorId: 1, param1: 0x70006, param2: 0 }), /未保存/);
  fail = false; await client.savePending();
  assert.deepEqual(writes.map(w => w.operation), [3, 2, 4, 4]); assert.equal(client.dirty, false);
});

test('Studio rejects refused assignments and requires explicit discard for an unconfirmed assignment', async () => {
  const rejected = await editableStudio(op => op === 2 ? f(2, 1) : undefined);
  await assert.rejects(rejected.client.saveBinding(0, 0, { behaviorId: 1, param1: 0x70005, param2: 0 }), /拒否/);
  assert.equal(rejected.client.dirty, false); assert.equal(rejected.client.layout!.layers[0].bindings[0].param1, 0x70004);
  const unknown = await editableStudio(op => op === 2 ? [] : undefined);
  await assert.rejects(unknown.client.saveBinding(0, 0, { behaviorId: 1, param1: 0x70005, param2: 0 }), /確認できません/);
  assert.equal(unknown.client.dirty, true);
  await unknown.client.discardPending();
  assert.equal(unknown.client.dirty, false); assert.equal(unknown.client.ready, true);
  assert.deepEqual(unknown.writes.map(w => w.operation), [3, 2, 5]);
});

test('Studio decodes negative int32 parameter ranges encoded as ten-byte varints', () => {
  const decoded = msg([8, 255, 255, 255, 255, 255, 255, 255, 255, 255, 1]);
  assert.equal(decoded.get(1)![0], -1);
  assert.throws(() => msg([8, 255, 255, 255, 255, 255, 255, 255, 255, 255, 2]));
});
