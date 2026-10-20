import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:liki_flutter/model.dart';
import 'package:liki_flutter/keyboard.dart';
import 'package:liki_flutter/main.dart';
import 'package:liki_flutter/trackpad.dart';

Json binding(
  String name, {
  int code = 0,
  int mod = 0,
  int layer = 0,
  String action = 'NONE',
  int consumer = 0,
}) => {
  'name': name,
  'code': code,
  'modifier': mod,
  'layer': layer,
  'action': action,
  'consumer': consumer,
};
Json shape(
  int index, {
  bool ghost = false,
  double rotation = 0,
  bool decal = false,
}) => {
  'index': index,
  'x': index.toDouble(),
  'y': 0.0,
  'w': 1.0,
  'h': 1.0,
  'x2': 0.0,
  'y2': 0.0,
  'w2': 1.0,
  'h2': 1.0,
  'rx': 0.0,
  'ry': 0.0,
  'rotation': rotation,
  'color': 0xffd9e0e9,
  'labels': List.generate(12, (i) => i == 0 ? 'A' : ''),
  'ghost': ghost,
  'decal': decal,
};

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('jp.liki/test');
  late LikiModel model;
  late List<MethodCall> calls;
  late Json preferences, settings;
  final options = [
    binding('A', code: 4),
    binding('B', code: 5),
    binding('Ctrl', mod: 1),
    binding('Shift', mod: 2),
    binding('Ctrl+A', code: 4, mod: 1),
    binding('Mute', consumer: 0xe2),
    binding('base', action: 'BASE'),
    binding('cycle', action: 'CYCLE'),
    for (var l = 1; l <= 3; l++) ...[
      binding('toggle$l', layer: l, action: 'TOGGLE'),
      binding('once$l', layer: l, action: 'ONE_SHOT'),
      binding('hold$l', layer: l, action: 'MOMENTARY'),
    ],
  ];
  setUp(() async {
    calls = [];
    preferences = {};
    settings = {
      'sticky_modifiers': true,
      'kle_json_key_labels': true,
      'pinch_zoom': true,
      'drag_move_keyboard': true,
      'key_pitch_mm': 0.0,
      'auto_key_scale_px': 0.0,
      'pointer_speed': 1.0,
      'scroll_speed': 1.0,
      'screen_orientation': 0,
      'pointer_send_mode': 2,
      'tap_to_click': true,
      'hold_to_drag': true,
      'tap_drag': true,
      'two_finger_scroll': true,
      'trackpad_pinch_zoom': true,
      'two_finger_right_click': true,
      'three_finger_middle_click': true,
      'border_colors': [0xff657789, 0xff80cbc4, 0xffedb369, 0xffbe9ce7],
    };
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call);
          final args = call.arguments as Map? ?? {};
          switch (call.method) {
            case 'preferences':
              preferences.addAll(object(args['values']));
              return null;
            case 'session':
              return null;
            case 'setting':
              settings[args['key']] = args['value'];
              return settings;
            case 'parse':
              if (args['json'] == 'invalid')
                throw PlatformException(code: 'liki', message: '不正なKLE');
              return {
                'name': 'fixture',
                'keys': [shape(0), shape(1), shape(2, ghost: true)],
                'inferred': '{"0":"A","1":"B"}',
              };
            case 'binding':
              return options
                  .where((b) => b['name'] == args['name'])
                  .firstOrNull;
            default:
              return null;
          }
        });
    model = LikiModel(channel: channel)
      ..settings = Map.of(settings)
      ..options = options.map(Binding.new).toList();
    model.bindings.addEntries(model.options.map((b) => MapEntry(b.name, b)));
    await model.readLayout('fixture');
    model.overrides = {'0': 'A', '1': 'B'};
    model.initialized = true;
    calls.clear();
  });
  tearDown(() {
    model.dispose();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });
  List<MethodCall> sent(String method) =>
      calls.where((c) => c.method == method).toList();

  test(
    'sticky modifiers combine and clear after the next normal key',
    () async {
      model.overrides['0'] = 'Ctrl';
      await model.tap(model.keys[0]);
      expect(model.modifiers, 1);
      expect(sent('key'), isEmpty);
      await model.tap(model.keys[1]);
      expect(sent('key').single.arguments, {'modifiers': 1, 'code': 5});
      expect(model.modifiers, 0);
    },
  );
  test('nonsticky modifiers send a standalone report', () async {
    model.settings['sticky_modifiers'] = false;
    model.overrides['0'] = 'Shift';
    await model.tap(model.keys[0]);
    await model.tap(model.keys[1]);
    expect(sent('key').map((c) => c.arguments), [
      {'modifiers': 2, 'code': 0},
      {'modifiers': 0, 'code': 5},
    ]);
  });
  test('layer 1 through 3 toggle and inherit base mappings', () async {
    for (var l = 1; l <= 3; l++) {
      model.overrides['0'] = 'toggle$l';
      await model.tap(model.keys[0]);
      expect(model.layer, l);
      expect(model.layerKey, 0);
      expect(model.bindingFor(model.keys[1])!.name, 'B');
      model.layers['$l'] = {'1': 'A'};
      await model.tap(model.keys[1]);
      expect(sent('key').last.arguments['code'], 4);
      await model.tap(model.keys[0]);
      expect(model.layer, 0);
    }
  });
  test('cycle visits all four layers; base resets to zero', () async {
    model.overrides['0'] = 'cycle';
    for (final l in [1, 2, 3, 0]) {
      await model.tap(model.keys[0]);
      expect(model.layer, l);
    }
    model.setLayer(3);
    model.overrides['0'] = 'base';
    await model.tap(model.keys[0]);
    expect(model.layer, 0);
  });
  test(
    'one-shot survives modifier keys, then restores previous layer and marker',
    () async {
      model.setLayer(2);
      model.layerKey = 7;
      model.overrides = {'0': 'once3', '1': 'Ctrl'};
      await model.tap(model.keys[0]);
      expect(model.layer, 3);
      await model.tap(model.keys[1]);
      expect(model.layer, 3);
      model.layers['3'] = {'1': 'B'};
      await model.tap(model.keys[1]);
      expect(model.layer, 2);
      expect(model.layerKey, 7);
      expect(model.oneShotReturn, isNull);
    },
  );
  test(
    'momentary chord uses captured layer after holding pointer releases',
    () async {
      model.overrides['0'] = 'hold3';
      model.layers['3'] = {'1': 'A'};
      model.momentaryDown(model.keys[0]);
      expect(model.layer, 3);
      model.momentaryUp();
      expect(model.layer, 0);
      await model.tap(model.keys[1], 3);
      expect(sent('key').single.arguments['code'], 4);
    },
  );
  test('media keys use consumer reports and consume one-shot', () async {
    model.overrides = {'0': 'once1', '1': 'Mute'};
    await model.tap(model.keys[0]);
    await model.tap(model.keys[1]);
    expect(sent('consumer').single.arguments, {'usage': 0xe2});
    expect(sent('key'), isEmpty);
    expect(model.layer, 0);
  });
  test(
    'edit cancel restores all layers, save retains draft in legacy preferences',
    () async {
      model.beginEdit();
      model.editLayer = 3;
      await model.assign(model.keys[1], 'A');
      await model.endEdit(false);
      expect(model.layers, isEmpty);
      expect(preferences, isEmpty);
      model.beginEdit();
      model.editLayer = 3;
      await model.assign(model.keys[1], 'A');
      await model.endEdit(true);
      expect(jsonDecode(preferences['layer_overrides']), {
        '3': {'1': 'A'},
      });
    },
  );
  test(
    'named layout roundtrip retains physical pan, zoom and all layer bindings',
    () async {
      model.density = 2;
      model.geometry!.pan = const Offset(12, -3);
      model.geometry!.zoom = 2;
      model.layers = {
        '3': {'1': 'Ctrl+A'},
      };
      await model.saveLayout('Test');
      final saved = model.saved.single;
      expect(saved['pan_x'], 24);
      expect(saved['pan_y'], -6);
      await model.applyLayout('other');
      await model.restoreLayout(saved);
      expect(model.geometry!.pan, const Offset(12, -3));
      expect(model.geometry!.zoom, 2);
      expect(model.layers, {
        '3': {'1': 'Ctrl+A'},
      });
      expect(model.selectedName, 'Test');
      await model.saveLayout('test');
      expect(model.saved.length, 1);
      await model.deleteLayout('TEST');
      expect(model.saved, isEmpty);
      expect(model.selectedName, isNull);
    },
  );
  test('invalid import leaves active layout and bindings untouched', () async {
    final geometry = model.geometry;
    await expectLater(
      model.applyLayout('invalid'),
      throwsA(isA<PlatformException>()),
    );
    expect(model.json, 'fixture');
    expect(model.geometry, same(geometry));
    expect(model.overrides, {'0': 'A', '1': 'B'});
  });
  test('rotated secondary key hit and zoom focus match drawn geometry', () {
    final key = KeyShape({...shape(0, rotation: 90), 'x2': -1.0, 'w2': 2.0});
    expect(key.contains(const Offset(-.5, -.5)), isTrue);
    final g = KeyboardGeometry([key])
      ..size = const Size(500, 300)
      ..base = 40;
    const focus = Offset(123, 87);
    final before = g.world(focus);
    g.zoomAt(2, focus, false);
    expect((g.world(focus) - before).distance, lessThan(1e-8));
    g.zoomAt(.01, focus, false);
    expect(g.zoom, 1);
  });

  void touch(
    TrackpadGesture gesture,
    Map<int, Offset> points,
    String kind,
    int id,
    double x,
    double y,
    int time,
  ) {
    final position = Offset(x, y);
    points[id] = position;
    final stamp = Duration(milliseconds: time);
    final PointerEvent event = switch (kind) {
      'down' => PointerDownEvent(
        pointer: id,
        position: position,
        timeStamp: stamp,
      ),
      'move' => PointerMoveEvent(
        pointer: id,
        position: position,
        timeStamp: stamp,
      ),
      'up' => PointerUpEvent(pointer: id, position: position, timeStamp: stamp),
      _ => PointerCancelEvent(
        pointer: id,
        position: position,
        timeStamp: stamp,
      ),
    };
    gesture.touch(event, points);
    if (kind == 'up' || kind == 'cancel') points.remove(id);
  }

  testWidgets('tap, two finger right click and three finger middle click', (
    tester,
  ) async {
    final gesture = TrackpadGesture(model), points = <int, Offset>{};
    for (var count = 1; count <= 3; count++) {
      for (var p = 1; p <= count; p++)
        touch(gesture, points, 'down', p, p * 30, 10, 1000 * count);
      for (var p = count; p >= 1; p--)
        touch(gesture, points, 'up', p, p * 30, 10, 1000 * count + 100);
    }
    await tester.pump();
    expect(sent('click').map((c) => c.arguments['button']), [1, 2, 4]);
    gesture.cancel();
  });
  testWidgets(
    'long press and cancellation release mouse; double tap drag starts on movement',
    (tester) async {
      final gesture = TrackpadGesture(model), points = <int, Offset>{};
      touch(gesture, points, 'down', 1, 10, 10, 1000);
      await tester.pump(const Duration(milliseconds: 451));
      gesture.cancel();
      await tester.pump();
      expect(sent('mouseDown').length, 1);
      expect(sent('mouseUp').length, 1);
      points.clear();
      calls.clear();
      touch(gesture, points, 'down', 1, 10, 10, 2000);
      touch(gesture, points, 'up', 1, 10, 10, 2100);
      touch(gesture, points, 'down', 1, 10, 10, 2200);
      touch(gesture, points, 'move', 1, 30, 10, 2250);
      touch(gesture, points, 'up', 1, 30, 10, 2300);
      await tester.pump();
      expect(sent('mouseDown').length, 1);
      expect(sent('mouseUp').length, 1);
      expect(sent('click').length, 1);
      gesture.cancel();
    },
  );
  testWidgets('two finger scroll and pinch choose distinct HID actions', (
    tester,
  ) async {
    final gesture = TrackpadGesture(model), points = <int, Offset>{};
    touch(gesture, points, 'down', 1, 0, 100, 1000);
    touch(gesture, points, 'down', 2, 40, 100, 1000);
    points[2] = const Offset(40, 50);
    touch(gesture, points, 'move', 1, 0, 50, 1100);
    touch(gesture, points, 'up', 2, 40, 50, 1200);
    touch(gesture, points, 'up', 1, 0, 50, 1200);
    await tester.pump();
    expect(sent('scroll').single.arguments['amount'], 2);
    expect(sent('zoom'), isEmpty);
    calls.clear();
    touch(gesture, points, 'down', 1, 0, 100, 2000);
    touch(gesture, points, 'down', 2, 40, 100, 2000);
    points[2] = const Offset(80, 100);
    touch(gesture, points, 'move', 1, -40, 100, 2100);
    await tester.pump();
    expect(sent('zoom').single.arguments['amount'], 3);
    expect(sent('scroll'), isEmpty);
    gesture.cancel();
  });
  testWidgets(
    'connection, edit mode, settings render on small portrait and landscape screens',
    (tester) async {
      await tester.pumpWidget(LikiApp(model: model));
      await tester.pumpAndSettle();
      expect(find.text('選択したPCに接続'), findsOneWidget);
      model.showingControls = true;
      model.changed();
      await tester.pumpAndSettle();
      expect(find.byType(Keyboard), findsOneWidget);
      model.beginEdit();
      await tester.pumpAndSettle();
      expect(find.text('レイヤー 3'), findsOneWidget);
      await model.endEdit(false);
      model.settingsVisible = true;
      model.changed();
      await tester.pumpAndSettle();
      expect(find.text('設定'), findsOneWidget);
      for (final size in [const Size(360, 800), const Size(800, 360)]) {
        tester.view.physicalSize = size;
        tester.view.devicePixelRatio = 1;
        await tester.pumpAndSettle();
        expect(tester.takeException(), isNull);
      }
      tester.view.resetPhysicalSize();
      tester.view.resetDevicePixelRatio();
      await tester.pumpWidget(const SizedBox.shrink());
    },
  );
}
