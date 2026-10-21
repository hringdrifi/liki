import test from 'node:test';
import assert from 'node:assert/strict';
import { field as f, frame, protobuf, StudioFrames, physicalLayout, StudioClient, studioBinding, studioKeyActive } from '../src/zmk.ts';
import { LikiModel } from '../src/model.ts';

const msg = (values: number[]) => protobuf(Uint8Array.from(values));
const text = (s: string) => Array.from(new TextEncoder().encode(s));
const attrs = [...f(1, 200), ...f(2, 200), ...f(3, 299), ...f(4, 100), ...f(5, 3000), ...f(6, 200), ...f(7, 100)];
const physical = [...f(2, [...f(1, text('Split')), ...f(2, attrs)])];
const binding = [...f(1, 2), ...f(2, 0x70004)];
const details = [...f(1, 1), ...f(2, text('Key Press')), ...f(3, f(1, f(5, [])))];
const keymap = f(1, [...f(1, 0), ...f(2, text('Base')), ...f(3, binding)]);

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
