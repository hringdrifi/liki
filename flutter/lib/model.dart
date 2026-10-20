import 'dart:convert';
import 'dart:async';
import 'dart:math' as math;
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';

typedef Json = Map<String, dynamic>;
Json object(dynamic value) => Map<String, dynamic>.from(value as Map);
double number(dynamic value) => (value as num).toDouble();

class Binding {
  Binding(Json json)
    : name = json['name'],
      code = json['code'],
      modifier = json['modifier'],
      layer = json['layer'],
      action = json['action'],
      consumer = json['consumer'];
  final String name, action;
  final int code, modifier, layer, consumer;
}

class KeyShape {
  KeyShape(Json j)
    : index = j['index'],
      x = number(j['x']),
      y = number(j['y']),
      w = number(j['w']),
      h = number(j['h']),
      x2 = number(j['x2']),
      y2 = number(j['y2']),
      w2 = number(j['w2']),
      h2 = number(j['h2']),
      rx = number(j['rx']),
      ry = number(j['ry']),
      rotation = number(j['rotation']),
      color = Color(j['color']),
      labels = List<String>.from(j['labels']),
      ghost = j['ghost'],
      decal = j['decal'];
  final int index;
  final double x, y, w, h, x2, y2, w2, h2, rx, ry, rotation;
  final Color color;
  final List<String> labels;
  final bool ghost, decal;
  Offset rotate(Offset point, {bool inverse = false}) {
    final a = rotation * math.pi / 180 * (inverse ? -1 : 1);
    final dx = point.dx - rx, dy = point.dy - ry;
    return Offset(
      rx + dx * math.cos(a) - dy * math.sin(a),
      ry + dx * math.sin(a) + dy * math.cos(a),
    );
  }

  Rect get primary => Rect.fromLTWH(x, y, w, h);
  Rect get secondary => Rect.fromLTWH(x + x2, y + y2, w2, h2);
  bool contains(Offset point) {
    final p = rotate(point, inverse: true);
    return !decal && (primary.contains(p) || secondary.contains(p));
  }
}

class KeyboardGeometry {
  KeyboardGeometry(this.keys) {
    final points = <Offset>[];
    for (final k in keys) {
      for (final r in [k.primary, k.secondary]) {
        points.addAll(
          [r.topLeft, r.topRight, r.bottomLeft, r.bottomRight].map(k.rotate),
        );
      }
    }
    bounds = Rect.fromLTRB(
      points.map((p) => p.dx).reduce(math.min),
      points.map((p) => p.dy).reduce(math.min),
      points.map((p) => p.dx).reduce(math.max),
      points.map((p) => p.dy).reduce(math.max),
    );
  }
  final List<KeyShape> keys;
  late final Rect bounds;
  double zoom = 1, base = 1;
  Offset pan = Offset.zero;
  Size size = Size.zero;
  double get scale => base * zoom;
  Offset get origin =>
      Offset(
        (size.width - bounds.width * scale) / 2 - bounds.left * scale,
        (size.height - bounds.height * scale) / 2 - bounds.top * scale,
      ) +
      pan;
  Offset world(Offset point) => (point - origin) / scale;
  KeyShape? hit(Offset point) {
    for (final k in keys.reversed) {
      if (k.contains(world(point))) return k;
    }
    return null;
  }

  void zoomAt(double factor, Offset focus, bool manualPitch) {
    final before = world(focus);
    zoom = (zoom * factor).clamp(manualPitch ? .25 : 1, 5);
    pan += focus - (origin + before * scale);
  }

  Json save(double density) => {
    'zoom': zoom,
    'pan_x': pan.dx * density,
    'pan_y': pan.dy * density,
  };
  void restore(Json j, double density) {
    final z = number(j['zoom'] ?? 1),
        px = number(j['pan_x'] ?? 0),
        py = number(j['pan_y'] ?? 0);
    if (!z.isFinite || z <= 0 || !px.isFinite || !py.isFinite)
      throw const FormatException('表示状態が不正です');
    zoom = z;
    pan = Offset(px, py) / density;
  }
}

class LikiModel extends ChangeNotifier {
  LikiModel({MethodChannel? channel})
    : channel = channel ?? const MethodChannel('jp.liki/hid');
  final MethodChannel channel;
  Json settings = {}, status = {}, preferences = {};
  final Map<String, Binding> bindings = {};
  List<Binding> options = [];
  List<KeyShape> keys = [];
  KeyboardGeometry? geometry;
  String json = '', layoutName = '', privacy = '';
  String? selectedName, selectedAddress, error;
  Json overrides = {}, layers = {}, inferred = {};
  double density = 1, xdpi = 160;
  bool initialized = false, editing = false, settingsVisible = false;
  bool showingControls = false;
  int layer = 0, editLayer = 0, modifiers = 0;
  int? layerKey,
      oneShotReturn,
      oneShotKey,
      momentaryPrevious,
      momentaryKey,
      momentaryTarget;
  String? baseSnapshot, layersSnapshot;
  int get displayedLayer => editing ? editLayer : layer;
  bool flag(String key) => settings[key] == true;
  double settingNumber(String key) => number(settings[key] ?? 0);
  bool get connected => status['connected'] == true;
  List<Json> get devices =>
      (status['devices'] as List? ?? []).map(object).toList();
  List<Json> get saved {
    try {
      return (jsonDecode(preferences['saved_layouts'] ?? '[]') as List)
          .whereType<Map>()
          .map(object)
          .where((j) {
            final z = j['zoom'] ?? 1, x = j['pan_x'] ?? 0, y = j['pan_y'] ?? 0;
            return j['name'] is String &&
                (j['name'] as String).trim().isNotEmpty &&
                j['kle_json'] is String &&
                (j['kle_json'] as String).isNotEmpty &&
                z is num &&
                z.isFinite &&
                z > 0 &&
                x is num &&
                x.isFinite &&
                y is num &&
                y.isFinite;
          })
          .toList();
    } catch (_) {
      return [];
    }
  }

  Future<T?> invoke<T>(String method, [Json? args]) =>
      channel.invokeMethod<T>(method, args);
  void changed() {
    saveSession();
    notifyListeners();
  }

  void saveSession() {
    if (!initialized) return;
    unawaited(
      invoke('session', {
        'value': jsonEncode({
          'layer': momentaryPrevious ?? layer,
          'layerKey': momentaryPrevious != null ? momentaryKey : layerKey,
          'oneShotReturn': oneShotReturn,
          'oneShotKey': oneShotKey,
          'editing': editing,
          'editLayer': editLayer,
          'baseSnapshot': baseSnapshot,
          'layersSnapshot': layersSnapshot,
          if (editing) 'overrides': overrides,
          if (editing) 'layers': layers,
          if (geometry != null) 'view': geometry!.save(density),
        }),
      }),
    );
  }

  Future<void> initialize() async {
    try {
      channel.setMethodCallHandler((call) async {
        if (call.method == 'statusChanged')
          acceptStatus(object(call.arguments));
      });
      final data = object(await invoke('initialize'));
      settings = object(data['settings']);
      preferences = object(data['preferences']);
      density = number(data['density']);
      xdpi = number(data['xdpi']);
      if (xdpi <= 0) xdpi = density * 160;
      privacy = data['privacy'];
      options = (data['bindings'] as List)
          .map((v) => Binding(object(v)))
          .toList();
      bindings.addEntries(options.map((b) => MapEntry(b.name, b)));
      final initial = object(data['layout']);
      await readLayout(initial['json']);
      overrides = object(jsonDecode(initial['overrides']));
      layers = object(jsonDecode(initial['layers']));
      selectedName = initial['selected'];
      if (data['session'] != null) {
        final session = object(jsonDecode(data['session']));
        layer = (session['layer'] as int? ?? 0).clamp(0, 3);
        layerKey = session['layerKey'];
        oneShotReturn = session['oneShotReturn'];
        oneShotKey = session['oneShotKey'];
        if (session['view'] != null)
          geometry!.restore(object(session['view']), density);
        editing = session['editing'] == true;
        if (editing) {
          editLayer = (session['editLayer'] as int? ?? 0).clamp(0, 3);
          baseSnapshot = session['baseSnapshot'];
          layersSnapshot = session['layersSnapshot'];
          overrides = object(session['overrides']);
          layers = object(session['layers']);
        }
      }
      await resolveBindings();
      initialized = true;
      acceptStatus(object(data['status']));
      await invoke('ready');
    } catch (e) {
      error = '$e';
      changed();
    }
  }

  void acceptStatus(Json next) {
    status = next;
    if (connected != showingControls && next['restoring'] != true) {
      showingControls = connected;
      settingsVisible = false;
      syncPage();
    }
    final available = devices.map((d) => d['address']).toList();
    if (!available.contains(selectedAddress)) {
      selectedAddress = available.contains(next['lastAddress'])
          ? next['lastAddress']
          : available.firstOrNull;
    }
    changed();
  }

  Future<void> refresh() async => acceptStatus(object(await invoke('status')));
  Future<void> syncPage() async => invoke('page', {
    'controls': showingControls,
    'settings': settingsVisible,
  });
  Future<void> setSetting(String key, dynamic value) async {
    settings = object(await invoke('setting', {'key': key, 'value': value}));
    if (key == 'sticky_modifiers' && value == false) modifiers = 0;
    changed();
  }

  Future<void> persist(Json values) async {
    await invoke('preferences', {'values': values});
    values.forEach((key, value) {
      if (value == null) {
        preferences.remove(key);
      } else {
        preferences[key] = value;
      }
    });
  }

  Future<void> readLayout(String value) async {
    final parsed = object(await invoke('parse', {'json': value}));
    final shapes = (parsed['keys'] as List)
        .map((v) => KeyShape(object(v)))
        .toList();
    final defaults = object(jsonDecode(parsed['inferred']));
    for (final name in defaults.values) {
      await resolve(name);
    }
    json = value;
    layoutName = parsed['name'];
    keys = shapes;
    geometry = KeyboardGeometry(keys);
    inferred = defaults;
  }

  Future<Binding?> resolve(String name) async {
    if (!bindings.containsKey(name)) {
      final data = await invoke('binding', {'name': name});
      if (data != null) bindings[name] = Binding(object(data));
    }
    return bindings[name];
  }

  Future<void> resolveBindings() async {
    for (final name in overrides.values) {
      if (name is String) await resolve(name);
    }
    for (final map in layers.values) {
      for (final name in (map as Map).values) {
        if (name is String) await resolve(name);
      }
    }
  }

  Binding? bindingFor(KeyShape key, [int? atLayer]) {
    final l = atLayer ?? displayedLayer, index = '${key.index}';
    final name = l == 0 ? null : (layers['$l'] as Map?)?[index];
    return bindings[name] ?? bindings[overrides[index]];
  }

  bool overridden(KeyShape key) => displayedLayer == 0
      ? overrides.containsKey('${key.index}')
      : (layers['$displayedLayer'] as Map?)?.containsKey('${key.index}') ==
            true;
  void setLayer(int value) {
    layer = value.clamp(0, 3);
    layerKey = null;
    oneShotReturn = null;
    oneShotKey = null;
    momentaryPrevious = null;
    momentaryKey = null;
    momentaryTarget = null;
    modifiers = 0;
  }

  void momentaryDown(KeyShape key) {
    final b = bindingFor(key);
    if (b?.action != 'MOMENTARY') return;
    final previous = layer, previousKey = layerKey;
    setLayer(b!.layer);
    momentaryPrevious = previous;
    momentaryKey = previousKey;
    momentaryTarget = b.layer;
    layerKey = key.index;
    changed();
  }

  void momentaryUp({bool notify = true}) {
    final previous = momentaryPrevious,
        key = momentaryKey,
        target = momentaryTarget;
    momentaryPrevious = null;
    momentaryKey = null;
    momentaryTarget = null;
    if (previous != null && layer == target) {
      setLayer(previous);
      layerKey = key;
      if (notify) changed();
    }
  }

  Future<void> tap(KeyShape key, [int? atLayer]) async {
    final b = bindingFor(key, atLayer);
    if (b == null) throw StateError('メニューのキー割り当て編集で設定してください');
    switch (b.action) {
      case 'BASE':
        setLayer(0);
      case 'TOGGLE':
        setLayer(layer == b.layer ? 0 : b.layer);
        if (layer != 0) layerKey = key.index;
      case 'CYCLE':
        setLayer((layer + 1) % 4);
        if (layer != 0) layerKey = key.index;
      case 'ONE_SHOT':
        final previous = layer, previousKey = layerKey;
        setLayer(b.layer);
        oneShotReturn = previous == b.layer ? 0 : previous;
        oneShotKey = previous == b.layer ? null : previousKey;
        layerKey = key.index;
      case 'MOMENTARY':
        return;
      default:
        if (b.modifier != 0 && b.code == 0) {
          if (flag('sticky_modifiers')) {
            modifiers ^= b.modifier;
          } else {
            await invoke('key', {'modifiers': b.modifier, 'code': 0});
          }
        } else {
          if (b.consumer != 0) {
            await invoke('consumer', {'usage': b.consumer});
          } else {
            await invoke('key', {
              'modifiers':
                  (flag('sticky_modifiers') ? modifiers : 0) | b.modifier,
              'code': b.code,
            });
          }
          modifiers = 0;
          if (oneShotReturn != null) {
            final previous = oneShotReturn!, k = oneShotKey;
            setLayer(previous);
            layerKey = k;
          }
        }
    }
    changed();
  }

  void beginEdit() {
    baseSnapshot = jsonEncode(overrides);
    layersSnapshot = jsonEncode(layers);
    editLayer = layer;
    editing = true;
    changed();
  }

  Future<void> endEdit(bool save) async {
    if (save) {
      await persistBindings();
    } else {
      overrides = object(jsonDecode(baseSnapshot!));
      layers = object(jsonDecode(layersSnapshot!));
    }
    editing = false;
    baseSnapshot = null;
    layersSnapshot = null;
    changed();
  }

  Future<void> assign(KeyShape key, String? name) async {
    final mapping = editLayer == 0
        ? overrides
        : layers.putIfAbsent('$editLayer', () => <String, dynamic>{}) as Map;
    if (name == null) {
      mapping.remove('${key.index}');
    } else {
      final b = await resolve(name);
      if (b == null) throw const FormatException('未対応のキー割り当てです');
      mapping['${key.index}'] = name;
    }
    changed();
  }

  Future<void> persistBindings() => persist({
    'overrides': jsonEncode(overrides),
    'layer_overrides': jsonEncode(layers),
  });
  Future<void> applyLayout(String value) async {
    await readLayout(value);
    overrides = Map.of(inferred);
    layers = {};
    selectedName = null;
    setLayer(0);
    editing = false;
    await setSetting('auto_key_scale_px', 0.0);
    await persist({
      'kle_json': json,
      'overrides': jsonEncode(overrides),
      'layer_overrides': '{}',
      'selected_saved_layout_name': null,
    });
    changed();
  }

  Future<void> resetView() async {
    geometry?.zoom = 1;
    geometry?.pan = Offset.zero;
    await setSetting('key_pitch_mm', 0.0);
    await setSetting('auto_key_scale_px', 0.0);
    changed();
  }

  Future<void> saveLayout(String name) async {
    final entries = saved;
    final entry = <String, dynamic>{
      'name': name,
      'kle_json': json,
      'overrides': jsonEncode(overrides),
      'layer_overrides': jsonEncode(layers),
      ...geometry!.save(density),
    };
    final i = entries.indexWhere(
      (e) => (e['name'] as String).toLowerCase() == name.toLowerCase(),
    );
    if (i >= 0) {
      entries[i] = entry;
    } else {
      entries.add(entry);
    }
    await persist({
      'saved_layouts': jsonEncode(entries),
      'selected_saved_layout_name': name,
    });
    selectedName = name;
    changed();
  }

  Future<void> restoreLayout(Json entry) async {
    // Validate everything before replacing the active state.
    final base = object(jsonDecode(entry['overrides'] ?? '{}'));
    final extra = object(jsonDecode(entry['layer_overrides'] ?? '{}'));
    final stateCheck = KeyboardGeometry(keys)..restore(entry, density);
    await readLayout(entry['kle_json']);
    overrides = base;
    layers = extra;
    geometry!.zoom = stateCheck.zoom;
    geometry!.pan = stateCheck.pan;
    selectedName = entry['name'];
    setLayer(0);
    await resolveBindings();
    await setSetting('auto_key_scale_px', 0.0);
    await persist({
      'kle_json': json,
      'overrides': jsonEncode(overrides),
      'layer_overrides': jsonEncode(layers),
      'selected_saved_layout_name': selectedName,
    });
    changed();
  }

  Future<void> deleteLayout(String name) async {
    await persist({
      'saved_layouts': jsonEncode(
        saved
            .where(
              (e) => (e['name'] as String).toLowerCase() != name.toLowerCase(),
            )
            .toList(),
      ),
      if (selectedName?.toLowerCase() == name.toLowerCase())
        'selected_saved_layout_name': null,
    });
    if (selectedName?.toLowerCase() == name.toLowerCase()) selectedName = null;
    changed();
  }

  @override
  void dispose() {
    channel.setMethodCallHandler(null);
    super.dispose();
  }
}
