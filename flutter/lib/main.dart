import 'dart:async';
import 'package:flutter/material.dart';
import 'keyboard.dart';
import 'model.dart';
import 'settings.dart';

void main() => runApp(const LikiApp());

class LikiApp extends StatelessWidget {
  const LikiApp({super.key, this.model});
  final LikiModel? model;
  @override
  Widget build(BuildContext context) => MaterialApp(
    title: 'Liki',
    debugShowCheckedModeBanner: false,
    theme: ThemeData(
      brightness: Brightness.dark,
      scaffoldBackgroundColor: background,
      colorScheme: const ColorScheme.dark(
        primary: accent,
        surface: surface,
        onSurface: foreground,
      ),
      useMaterial3: true,
    ),
    home: LikiHome(model: model),
  );
}

class LikiHome extends StatefulWidget {
  const LikiHome({super.key, this.model});
  final LikiModel? model;
  @override
  State<LikiHome> createState() => _LikiHomeState();
}

class _LikiHomeState extends State<LikiHome> with WidgetsBindingObserver {
  late final LikiModel model = widget.model ?? LikiModel();
  final scaffold = GlobalKey<ScaffoldState>();
  bool menuOpen = false;
  Offset? menuStart;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    model.addListener(update);
    if (!model.initialized) unawaited(model.initialize());
  }

  void update() {
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    model.removeListener(update);
    if (widget.model == null) model.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed && model.initialized)
      run(model.refresh);
  }

  void error(Object error) {
    if (!mounted) return;
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text('$error')));
  }

  Future<void> run(Future<dynamic> Function() work) async {
    try {
      await work();
    } catch (e) {
      error(e);
    }
  }

  Future<void> privacy() => showDialog<void>(
    context: context,
    builder: (c) => AlertDialog(
      title: const Text('プライバシーポリシー'),
      content: SingleChildScrollView(child: Text(model.privacy)),
      actions: [
        TextButton(onPressed: () => Navigator.pop(c), child: const Text('閉じる')),
      ],
    ),
  );

  @override
  Widget build(BuildContext context) {
    if (model.error != null)
      return Scaffold(
        body: SafeArea(
          child: Padding(
            padding: const EdgeInsets.all(20),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Text('初期化できません: ${model.error}'),
                TextButton(
                  onPressed: () {
                    model.error = null;
                    run(model.initialize);
                  },
                  child: const Text('再試行'),
                ),
              ],
            ),
          ),
        ),
      );
    if (!model.initialized)
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    return PopScope(
      canPop: !menuOpen && !model.editing && !model.settingsVisible,
      onPopInvokedWithResult: (didPop, result) {
        if (didPop) return;
        if (menuOpen) {
          scaffold.currentState?.closeEndDrawer();
        } else if (model.editing) {
          run(() => model.endEdit(false));
        } else if (model.settingsVisible) {
          model.settingsVisible = false;
          run(model.syncPage);
          model.changed();
        }
      },
      child: Scaffold(
        key: scaffold,
        endDrawerEnableOpenDragGesture: false,
        onEndDrawerChanged: (open) => setState(() => menuOpen = open),
        endDrawer: Drawer(
          child: SafeArea(
            child: ListView(
              padding: const EdgeInsets.all(12),
              children: [
                const ListTile(
                  title: Text(
                    'Liki',
                    style: TextStyle(fontSize: 24, fontWeight: FontWeight.bold),
                  ),
                ),
                if (model.selectedName != null)
                  ListTile(title: Text('保存: ${model.selectedName}')),
                menu('レイアウトを選択', chooseLayout),
                menu('キー割り当てを編集', () async {
                  model.beginEdit();
                }),
                menu('表示をリセット', model.resetView),
                menu('設定', () async {
                  model.settingsVisible = true;
                  await model.syncPage();
                  model.changed();
                }),
                menu('切断', () => model.invoke('disconnect')),
              ],
            ),
          ),
        ),
        body: SafeArea(
          child: model.settingsVisible
              ? SettingsPage(
                  model: model,
                  onError: error,
                  onPrivacy: privacy,
                  onBack: () {
                    model.settingsVisible = false;
                    run(model.syncPage);
                    model.changed();
                  },
                )
              : model.showingControls
              ? controls()
              : connection(),
        ),
      ),
    );
  }

  Widget menu(String title, Future<dynamic> Function() work) => ListTile(
    title: Text(title),
    onTap: () {
      scaffold.currentState?.closeEndDrawer();
      run(work);
    },
  );
  Widget connection() {
    final cancelling = model.status['cancelling'] == true,
        connecting = model.status['connecting'] == true;
    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 24, 20, 24),
      children: [
        const Text(
          'Liki',
          style: TextStyle(fontSize: 36, fontWeight: FontWeight.bold),
        ),
        const Text(
          'Pocket HID',
          style: TextStyle(
            color: accent,
            fontSize: 15,
            fontWeight: FontWeight.bold,
          ),
        ),
        const Text('スマホをPCのキーボード・トラックパッドに'),
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 26),
          child: Text(
            '${model.connected ? '●' : '○'} ${model.status['message'] ?? ''}',
            style: TextStyle(
              color: model.connected ? accent : muted,
              fontWeight: FontWeight.bold,
            ),
          ),
        ),
        section('1. PCとペアリング'),
        const Text('下のボタンを押した後、PCのBluetooth設定からこのスマホを追加します。'),
        SizedBox(
          height: 60,
          child: FilledButton.tonal(
            onPressed: () => run(() => model.invoke('discoverable')),
            child: const Text('ペアリング待機（5分間公開）'),
          ),
        ),
        section('2. 接続先を選択'),
        Row(
          children: [
            Expanded(
              child: model.devices.isEmpty
                  ? const Text('ペアリング済みのPCがありません')
                  : DropdownButton<String>(
                      isExpanded: true,
                      value: model.selectedAddress,
                      items: model.devices
                          .map(
                            (d) => DropdownMenuItem<String>(
                              value: d['address'],
                              child: Text(
                                d['name'],
                                overflow: TextOverflow.ellipsis,
                              ),
                            ),
                          )
                          .toList(),
                      onChanged: cancelling || connecting
                          ? null
                          : (v) => setState(() => model.selectedAddress = v),
                    ),
            ),
            TextButton(
              onPressed: () => run(model.refresh),
              child: const Text('更新'),
            ),
          ],
        ),
        SizedBox(
          height: 60,
          child: FilledButton.tonal(
            onPressed: cancelling
                ? null
                : () => run(
                    () => connecting
                        ? model.invoke('cancelConnection')
                        : model.invoke('connect', {
                            'address': model.selectedAddress ?? '',
                          }),
                  ),
            child: Text(
              cancelling
                  ? 'キャンセル中…'
                  : connecting
                  ? '接続をキャンセル'
                  : '選択したPCに接続',
            ),
          ),
        ),
        const Padding(
          padding: EdgeInsets.only(top: 12),
          child: Text('接続すると操作画面へ切り替わります。PCから自動接続される場合もあります。'),
        ),
        TextButton(onPressed: privacy, child: const Text('プライバシーポリシー')),
      ],
    );
  }

  Widget controls() => Stack(
    children: [
      Padding(
        padding: const EdgeInsets.all(8),
        child: Column(
          children: [
            if (model.editing) ...[
              Row(
                children: List.generate(
                  4,
                  (i) => Expanded(
                    child: TextButton(
                      onPressed: () {
                        model.editLayer = i;
                        model.changed();
                      },
                      style: TextButton.styleFrom(
                        foregroundColor: i == model.editLayer ? accent : muted,
                      ),
                      child: Text('レイヤー $i'),
                    ),
                  ),
                ),
              ),
              Row(
                children: [
                  Expanded(
                    child: TextButton(
                      onPressed: () => run(() => model.endEdit(false)),
                      child: const Text('キャンセル'),
                    ),
                  ),
                  Expanded(
                    child: TextButton(
                      onPressed: () => run(() => model.endEdit(true)),
                      child: const Text('保存'),
                    ),
                  ),
                ],
              ),
            ],
            Expanded(
              child: Keyboard(
                key: ValueKey(model.geometry),
                model: model,
                onEdit: (key) => run(() => editBinding(key)),
                onError: error,
              ),
            ),
          ],
        ),
      ),
      if (!model.editing) ...[
        Positioned(
          left: 12,
          top: 12,
          child: DecoratedBox(
            decoration: BoxDecoration(
              color: surface,
              borderRadius: BorderRadius.circular(8),
            ),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
              child: Text(
                'L${model.layer}${model.oneShotReturn != null ? '・1回' : ''}',
                semanticsLabel: '現在のレイヤー ${model.layer}',
                style: const TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.bold,
                ),
              ),
            ),
          ),
        ),
        Align(
          alignment: Alignment.centerRight,
          child: Semantics(
            label: '右端中央から左へスワイプしてメニューを開く',
            child: GestureDetector(
              behavior: HitTestBehavior.opaque,
              onPanDown: (details) => menuStart = details.globalPosition,
              onPanUpdate: (details) {
                final delta =
                    details.globalPosition -
                    (menuStart ?? details.globalPosition);
                if (delta.dx < -42 && delta.dx.abs() > delta.dy.abs() * 1.25)
                  scaffold.currentState?.openEndDrawer();
              },
              child: SizedBox(
                width: 24,
                height: 108,
                child: Align(
                  alignment: Alignment.centerRight,
                  child: Container(
                    width: 3,
                    height: 48,
                    margin: const EdgeInsets.only(right: 2),
                    decoration: BoxDecoration(
                      color: accent,
                      borderRadius: BorderRadius.circular(2),
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      ],
    ],
  );
  Widget section(String title) => Padding(
    padding: const EdgeInsets.only(top: 24, bottom: 8),
    child: Text(
      title,
      style: const TextStyle(fontSize: 20, fontWeight: FontWeight.bold),
    ),
  );

  Future<int?> choose(String title, List<String> names) => showDialog<int>(
    context: context,
    builder: (c) => SimpleDialog(
      title: Text(title),
      children: [
        for (var i = 0; i < names.length; i++)
          SimpleDialogOption(
            onPressed: () => Navigator.pop(c, i),
            child: Text(names[i]),
          ),
        TextButton(onPressed: () => Navigator.pop(c), child: const Text('閉じる')),
      ],
    ),
  );
  Future<bool> confirm(String title) async =>
      await showDialog<bool>(
        context: context,
        builder: (c) => AlertDialog(
          title: Text(title),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(c, false),
              child: const Text('キャンセル'),
            ),
            TextButton(
              onPressed: () => Navigator.pop(c, true),
              child: const Text('確定'),
            ),
          ],
        ),
      ) ??
      false;
  Future<void> chooseLayout() async {
    final entries = model.saved;
    final i = await choose('レイアウトを選択', [
      '標準トラックパッド',
      '標準キーボード（TKL）',
      ...entries.map((e) => '保存: ${e['name']}'),
      'KLE JSONファイルを開く',
      '現在のレイアウトに名前を付けて保存',
      '保存したレイアウトを削除',
    ]);
    if (i == null) return;
    if (i < 2) {
      await model.applyLayout(
        (await model.invoke<String>('asset', {
          'name': i == 0 ? 'ghosted-trackpad.json' : 'default-kle.json',
        }))!,
      );
    } else if (i < entries.length + 2) {
      await model.restoreLayout(entries[i - 2]);
    } else if (i == entries.length + 2) {
      final value = await model.invoke<String>('pickFile');
      if (value != null) await model.applyLayout(value);
    } else if (i == entries.length + 3) {
      await saveLayout();
    } else {
      if (entries.isEmpty) {
        error('保存したレイアウトはありません');
        return;
      }
      final at = await choose(
        '保存したレイアウトを削除',
        entries.map((e) => e['name'] as String).toList(),
      );
      if (at != null && await confirm('「${entries[at]['name']}」を削除しますか？'))
        await model.deleteLayout(entries[at]['name']);
    }
  }

  Future<void> saveLayout() async {
    var value = model.selectedName ?? '';
    String? name;
    name = await showDialog<String>(
      context: context,
      builder: (c) => StatefulBuilder(
        builder: (c, update) => AlertDialog(
          title: const Text('レイアウトに名前を付けて保存'),
          content: TextFormField(
            initialValue: value,
            autofocus: true,
            maxLength: 40,
            decoration: const InputDecoration(hintText: '1〜40文字'),
            onChanged: (text) => update(() => value = text),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(c),
              child: const Text('キャンセル'),
            ),
            TextButton(
              onPressed: value.trim().isEmpty || value.trim().length > 40
                  ? null
                  : () => Navigator.pop(c, value.trim()),
              child: const Text('保存'),
            ),
          ],
        ),
      ),
    );
    if (name == null) return;
    final exists = model.saved.any(
      (e) => (e['name'] as String).toLowerCase() == name!.toLowerCase(),
    );
    if (!exists || await confirm('「$name」を上書きしますか？'))
      await model.saveLayout(name);
  }

  Future<void> editBinding(KeyShape key) async {
    final categories = [
      '文字・数字',
      '記号',
      '基本キー',
      '修飾キー付き',
      'メディアキー',
      'ファンクションキー',
      '移動・編集',
      'テンキー',
      '修飾キー単体',
      'ショートカット',
      'レイヤー',
      model.editLayer == 0 ? '自動割り当て' : 'ベースを継承',
    ];
    final i = await choose(
      'レイヤー${model.editLayer}・「${key.labels.firstWhere((s) => s.isNotEmpty, orElse: () => '')}」の割り当て',
      categories,
    );
    if (i == null) return;
    if (i == 11) {
      await model.assign(
        key,
        model.editLayer == 0 ? model.inferred['${key.index}'] : null,
      );
      return;
    }
    if (i == 3) {
      await modifierChoice(key);
      return;
    }
    final choices = model.options
        .where(
          (b) => switch (i) {
            0 => b.code >= 4 && b.code <= 39 && b.modifier == 0,
            1 => b.code >= 45 && b.code <= 56,
            2 => b.code >= 40 && b.code <= 44 || b.code == 57,
            4 => b.consumer != 0,
            5 => b.code >= 58 && b.code <= 72,
            6 => b.code >= 73 && b.code <= 82 || b.code == 101,
            7 => b.code >= 83 && b.code <= 99,
            8 => b.code == 0 && b.modifier != 0,
            9 => b.code != 0 && b.modifier != 0,
            10 => b.action != 'NONE',
            _ => false,
          },
        )
        .toList();
    final at = await choose(categories[i], choices.map((b) => b.name).toList());
    if (at != null) await model.assign(key, choices[at].name);
  }

  Future<void> modifierChoice(KeyShape key) async {
    final selected = <String>[];
    const mods = [
      'LCTL',
      'LSFT',
      'LALT',
      'LGUI',
      'RCTL',
      'RSFT',
      'RALT',
      'RGUI',
    ];
    final bases = model.options
        .where(
          (b) =>
              b.code != 0 &&
              b.modifier == 0 &&
              b.consumer == 0 &&
              b.action == 'NONE',
        )
        .toList();
    while (true) {
      final available = mods.where((s) => !selected.contains(s)).toList();
      final at = await choose('修飾キー付き → ${selected.join('+')}', available);
      if (at == null) return;
      selected.add(available[at]);
      final addMore = selected.length < 8;
      final base = await choose('修飾キー付き → ${selected.join('+')} → キー', [
        if (addMore) '修飾キーを追加…',
        ...bases.map((b) => b.name),
      ]);
      if (base == null) return;
      if (addMore && base == 0) continue;
      final expression = selected.reversed.fold(
        bases[base - (addMore ? 1 : 0)].name,
        (inner, mod) => '$mod($inner)',
      );
      await model.assign(key, expression);
      return;
    }
  }
}
