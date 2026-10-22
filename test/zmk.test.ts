import test from 'node:test';
import assert from 'node:assert/strict';
import { field as f, frame, protobuf, StudioFrames, physicalLayout, StudioClient, studioBinding, studioKeyActive } from '../src/zmk.ts';
import { LikiModel } from '../src/model.ts';
import { bindingUsage, expandUsage, parseRelayRemaps } from '../src/remap.ts';

const msg = (values: number[]) => protobuf(Uint8Array.from(values));
const text = (s: string) => Array.from(new TextEncoder().encode(s));
const attrs = [...f(1, 200), ...f(2, 200), ...f(3, 299), ...f(4, 100), ...f(5, 3000), ...f(6, 200), ...f(7, 100)];
const physical = [...f(2, [...f(1, text('Split')), ...f(2, attrs)])];
const binding = [...f(1, 2), ...f(2, 0x70004)];
const details = [...f(1, 1), ...f(2, text('Key Press')), ...f(3, f(1, f(5, [])))];
const keymap = f(1, [...f(1, 0), ...f(2, text('Base')), ...f(3, binding)]);

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
  const minus = studioBinding(noParameters, minusDetails, 'Bifrost');
  const semicolon = studioBinding(noParameters, semicolonDetails, 'Bifrost');
  assert.equal(minus.label, '-'); assert.equal(semicolon.label, ';');
  assert.equal(studioKeyActive(minus, [0x2d], 0), true);
  assert.equal(studioKeyActive(minus, [0xe1, 0x87], 0), true);
  assert.equal(studioKeyActive(minus, [0x87], 0), false);
  assert.equal(studioKeyActive(semicolon, [0x33], 0), true);
  assert.equal(studioKeyActive(semicolon, [0x34], 0), true);
  assert.equal(studioKeyActive(semicolon, [], 0), false);
  assert.deepEqual(studioBinding(noParameters, minusDetails, 'Other').usages, []);
  assert.deepEqual(studioBinding(noParameters, semicolonDetails).usages, []);
});

test('Studio client reads layout, keymap and metadata without sending mutations', async () => {
  let client: StudioClient;
  const requests: number[] = [];
  client = new StudioClient(async data => {
    const decoded = new StudioFrames().push(data)[0], request = protobuf(decoded), id = request.get(1)![0] as number;
    const subsystem = request.has(5) ? 5 : 4;
    const payload = protobuf(request.get(subsystem)![0] as Uint8Array), operation = [...payload.keys()][0];
    requests.push(subsystem * 10 + operation);
    const result = subsystem === 4 ? details : operation === 6 ? physical : keymap;
    const reply = frame(f(1, [...f(1, id), ...f(subsystem, f(operation, result))]));
    // Notifications may arrive before the response, fragmented at any boundary.
    client.receive(frame(f(2, f(2, f(1, 1)))));
    for (let i = 0; i < reply.length; i += 3) client.receive(reply.slice(i, i + 3));
  }, () => {});
  await client.load();
  assert.deepEqual(requests, [56, 51, 42]);
  assert.equal(client.ready, true); assert.equal(client.layout?.layers[0].keys[0].label, 'A');
  client.reset(); assert.equal(client.layout, undefined);
});

test('Studio client reports lock errors and cancels an in-flight read on disconnect', async () => {
  let client: StudioClient;
  client = new StudioClient(async data => {
    const request = protobuf(new StudioFrames().push(data)[0]), id = request.get(1)![0] as number;
    client.receive(frame(f(1, [...f(1, id), ...f(2, f(2, 1))])));
  }, () => {});
  await client.load(); assert.match(client.message, /ロックを解除/); assert.equal(client.layout, undefined);
  let writes = 0;
  const cancelled = new StudioClient(async () => { writes++; }, () => {});
  const loading = cancelled.load(); cancelled.reset(); await loading;
  assert.equal(writes, 1); assert.equal(cancelled.message, ''); assert.equal(cancelled.layout, undefined);
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

test('Studio timeout distinguishes missing and incomplete responses without assuming a lock', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const client = new StudioClient(async () => {}, () => {});
  const loading = client.load();
  client.receive([0xab, 0x0a, 0x20]);
  t.mock.timers.tick(8000);
  await loading;
  assert.match(client.message, /物理レイアウト、受信3バイト/);
  assert.doesNotMatch(client.message, /ロック/);
  client.reset();
  const second = client.load();
  t.mock.timers.tick(8000);
  await second;
  assert.match(client.message, /受信0バイト/);
});

async function editableStudio(respond?: (operation: number, payload: ReturnType<typeof protobuf>) => number[] | undefined) {
  const writes: { operation: number; payload: ReturnType<typeof protobuf> }[] = [];
  const metadata = [...f(1, 1), ...f(2, text('Key Press')), ...f(3, f(1, f(5, [...f(1, 0xe7), ...f(2, 0x2ff)])))];
  let client: StudioClient;
  client = new StudioClient(async data => {
    const request = protobuf(new StudioFrames().push(data)[0]), id = request.get(1)![0] as number;
    const subsystem = request.has(5) ? 5 : 4, payload = protobuf(request.get(subsystem)![0] as Uint8Array), operation = [...payload.keys()][0];
    if (subsystem === 5 && [2, 3, 4, 5].includes(operation)) writes.push({ operation, payload });
    const custom = subsystem === 5 ? respond?.(operation, payload) : undefined;
    const response = custom ?? (subsystem === 4 ? f(operation, operation === 1 ? f(1, [1]) : metadata) :
      operation === 6 ? f(6, physical) : operation === 1 ? f(1, f(1, [...f(1, 9), ...f(2, text('Base')), ...f(3, binding)])) :
      operation === 2 || operation === 3 ? f(operation, 0) : operation === 4 ? f(4, f(1, 1)) : f(5, 1));
    client.receive(frame(f(1, [...f(1, id), ...f(subsystem, response)])));
  }, () => {});
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
